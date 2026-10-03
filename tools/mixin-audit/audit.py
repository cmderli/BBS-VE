#!/usr/bin/env python3
"""Audit this mod's mixin selectors against the resolved Minecraft jar, offline.

Why this exists
---------------
Mixin target strings (`@Inject(method = "...")`, `@At(target = "...")`, `@Accessor`/`@Invoker`
members) are plain strings. The Yarn -> official rename could not touch them, and 26.2 ships
unobfuscated so the jar carries no refmap for Mixin to remap them through. A stale string therefore
fails at APPLY, and because `bbs.mixins.json` is `required: true` with `defaultRequire: 1`, one dead
selector aborts class transformation and the game dies before the main menu.

This script compiles `MixinAudit.java` (ASM) and runs it over the compiled mixin classes. It checks:

  * every `@Mixin` target class exists,
  * every injection `method` selector is **declared by** the target class - note: Mixin does not
    resolve selectors through supertypes, so an inherited-only method is a failure, not a match,
  * selectors written as a named `static final String` constant are resolved first,
  * every nested `@At(target = "Lowner;name(desc)ret")` owner and member exists,
  * `@Accessor` / `@Invoker` members exist (these may be inherited, unlike selectors),
  * `@Shadow` members exist.

Usage
-----
    python3 tools/mixin-audit/audit.py                 # audit build/classes (default)
    python3 tools/mixin-audit/audit.py --json          # machine-readable summary

Exit code is 1 when a main-config mixin has a problem, 2 on infrastructure failure. Problems in the
Iris config are reported but do not fail the run: those targets are absent by design whenever Iris is
not installed (bbs.iris.mixins.json ships as required: false for exactly that reason).
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))

# Mixin config files and the compiled tree each one's classes live in, relative to the repo root.
CONFIGS = [
    # (label, mixin classes directory, exclude subdirectory, is_optional)
    ("main", os.path.join("build", "classes", "java", "main", "mchorse", "bbs_mod", "mixin"), None, False),
    ("client", os.path.join("build", "classes", "java", "client", "mchorse", "bbs_mod", "mixin"), os.path.join("client", "iris"), False),
    # bbs.iris.mixins.json ships as required: false - absent targets are the intended state
    # whenever Iris is not installed, so these never fail the run.
    ("iris", os.path.join("build", "classes", "java", "client", "mchorse", "bbs_mod", "mixin", "client", "iris"), None, True),
]


def first_existing(paths):
    for p in paths:
        if p and os.path.exists(p):
            return p
    return None


def find_minecraft_jar():
    """Locate the Minecraft 26.2 jar Loom resolved. Merged first: it is the one the mod sees."""
    candidates = []
    loom = os.path.expanduser("~/.gradle/caches/fabric-loom")
    if os.path.isdir(loom):
        for version in sorted(os.listdir(loom), reverse=True):
            vdir = os.path.join(loom, version)
            for name in ("minecraft-merged.jar", "minecraft-client-only.jar",
                         "minecraft-common.jar", "minecraft-client.jar"):
                candidates.append(os.path.join(vdir, name))
            maven = os.path.join(vdir, "minecraftMaven")
            if os.path.isdir(maven):
                for dirpath, _dirs, files in os.walk(maven):
                    for f in files:
                        if f.endswith(".jar") and "sources" not in f:
                            candidates.append(os.path.join(dirpath, f))
    jar = first_existing(candidates)
    if jar:
        return jar
    env = os.environ.get("MC_JAR")
    if env and os.path.exists(env):
        return env
    return None


def find_asm():
    """ASM ships with the HMCL launcher's library set and with Gradle's caches; either will do."""
    candidates = []
    for base in (os.path.expanduser("~/.hmcl/.minecraft/libraries/org/ow2/asm"),
                 os.path.expanduser("~/.gradle/caches/modules-2/files-2.1/org.ow2.asm")):
        if os.path.isdir(base):
            for dirpath, _dirs, files in os.walk(base):
                for f in files:
                    if re.match(r"asm-\d[\d.]*\.jar$", f) and "tree" not in f and "analysis" not in f:
                        candidates.append(os.path.join(dirpath, f))
    candidates.sort()
    return first_existing(candidates)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--json", action="store_true", help="emit a JSON summary instead of prose")
    ap.add_argument("--mc-jar", help="override the Minecraft jar to audit against")
    ap.add_argument("--classes", help="override the compiled mixin classes directory (single tree)")
    args = ap.parse_args()

    mc_jar = args.mc_jar or find_minecraft_jar()
    if not mc_jar:
        print("ERROR: could not find the Minecraft jar. Pass --mc-jar.", file=sys.stderr)
        return 2
    asm = find_asm()
    if not asm:
        print("ERROR: could not find an ASM jar. Set one up or pass nothing and install ASM.", file=sys.stderr)
        return 2

    trees = [("all", args.classes, None, False)] if args.classes else \
            [(label, os.path.join(ROOT, rel), excl, opt) for label, rel, excl, opt in CONFIGS]

    workdir = tempfile.mkdtemp(prefix="mixin-audit-")
    try:
        shutil.copy(os.path.join(HERE, "MixinAudit.java"), workdir)
        build = subprocess.run(["javac", "-cp", asm, "-d", workdir, os.path.join(workdir, "MixinAudit.java")],
                               capture_output=True, text=True)
        if build.returncode != 0:
            print("ERROR: could not compile the auditor:\n" + build.stderr, file=sys.stderr)
            return 2

        results = []
        staged = []
        for label, tree, exclude, optional in trees:
            if not os.path.isdir(tree):
                results.append({"config": label, "classes": 0, "problems": [],
                                "note": "not built: " + tree, "optional": optional})
                continue
            scan = tree
            if exclude:
                # The auditor walks one directory, so isolate the optional sub-config's classes.
                scan = os.path.join(workdir, "tree-" + label)
                shutil.copytree(tree, scan, dirs_exist_ok=True)
                shutil.rmtree(os.path.join(scan, exclude), ignore_errors=True)
                staged.append(scan)
            run = subprocess.run(["java", "-cp", workdir + os.pathsep + asm, "MixinAudit", mc_jar, scan],
                                 capture_output=True, text=True)
            problems = [l[len("PROBLEM "):] for l in run.stdout.splitlines() if l.startswith("PROBLEM ")]
            m = re.search(r"===== (\d+) problem\(s\) across (\d+) classes", run.stdout)
            results.append({
                "config": label,
                "classes": int(m.group(2)) if m else 0,
                "problems": problems,
                "tree": os.path.relpath(tree, ROOT),
                "optional": optional,
            })
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    blocking = [r for r in results if r["problems"] and not r["optional"]]

    if args.json:
        print(json.dumps({"minecraft_jar": mc_jar, "results": results,
                          "blocking": len(blocking)}, indent=2))
    else:
        print("Minecraft jar: " + mc_jar)
        print()
        for r in results:
            if "note" in r:
                print("%-8s SKIP  %s" % (r["config"], r["note"]))
                continue
            status = "OK" if not r["problems"] else ("skipped/absent" if r["optional"] else "PROBLEMS")
            print("%-8s %-15s %d mixin class(es)" % (r["config"], status, r["classes"]))
            for p in r["problems"]:
                print("           " + p)
        print()
        if blocking:
            print("FAIL: %d mixin config(s) would fail at APPLY." % len(blocking))
        else:
            print("PASS: no blocking mixin problems.")
        print("Note: bbs.iris.mixins.json targets report as missing when Iris is absent - that is "
              "expected and does not fail the run.")

    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main())
