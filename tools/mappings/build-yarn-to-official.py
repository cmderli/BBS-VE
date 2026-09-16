#!/usr/bin/env python3
"""
Build a Yarn -> Mojang (official) identifier mapping table for Minecraft 1.21.11.

Why this exists
---------------
The mod sources are written against Yarn names for Minecraft 1.21.11.  Minecraft
26.2 ships unobfuscated (official Mojang names only) and Yarn stopped publishing
after 1.21.11, so the sources have to be renamed from Yarn names to official
names.  This script produces the machine readable table that enables that rename.

How it works
------------
Two mapping sets that describe the *same* Minecraft 1.21.11 jar are joined on the
obfuscated (ProGuard) names of that version:

  * Yarn `net.fabricmc:yarn:1.21.11+build.6`, tiny v2 with the namespaces
    `official intermediary named` (the `-mergedv2` artifact -- only that one
    carries the obfuscated `official` column; the plain `-v2` artifact has just
    `intermediary named` and therefore cannot be joined against Mojang's files).
  * Mojang `client_mappings` (`client.txt`) from the 1.21.11 version json: the
    ProGuard style `obf -> official` mapping for classes, methods and fields.

Classes are joined on `obf class name` (`tiny official column == client.txt
class key`).  Members are joined on `(obf owner, obf member name, obf
descriptor)`; because `client.txt` stores Java source types rather than JVM
descriptors, a client.txt member's descriptor is first reconstructed and
rewritten into the obf namespace through the class join, which makes the member
join exact.  Where a member name is reused (overloads, bridges), the descriptor
decides.

The script is dependency free (Python 3 standard library only), re-runnable and
deterministic: downloads are cached under `tools/mappings/.cache/`, all outputs
are written with a stable sort order.

Usage
-----
    python3 tools/mappings/build-yarn-to-official.py            # build tables
    python3 tools/mappings/build-yarn-to-official.py --verify    # build + prove
    python3 tools/mappings/build-yarn-to-official.py --refresh   # re-download

`--verify` re-derives everything, asserts the hand-checked spot checks below and
checks that the files on disk are exactly what a fresh run produces.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.request
import zipfile
from collections import defaultdict

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE_DIR = os.path.join(HERE, ".cache")

MC_VERSION = "1.21.11"
YARN_VERSION = MC_VERSION + "+build.6"

YARN_BASE_URL = "https://maven.fabricmc.net/net/fabricmc/yarn/" + YARN_VERSION
YARN_MERGEDV2_URL = "%s/yarn-%s-mergedv2.jar" % (YARN_BASE_URL, YARN_VERSION)
YARN_V2_URL = "%s/yarn-%s-v2.jar" % (YARN_BASE_URL, YARN_VERSION)

VERSION_MANIFEST_URL = (
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
)

YARN_MERGEDV2_JAR = os.path.join(
    CACHE_DIR, "yarn-%s-mergedv2.jar" % YARN_VERSION
)
YARN_V2_JAR = os.path.join(CACHE_DIR, "yarn-%s-v2.jar" % YARN_VERSION)
VERSION_MANIFEST_JSON = os.path.join(CACHE_DIR, "version_manifest_v2.json")
VERSION_JSON = os.path.join(CACHE_DIR, "%s.json" % MC_VERSION)
CLIENT_TXT = os.path.join(CACHE_DIR, "client-%s.txt" % MC_VERSION)

CLASSES_TSV = os.path.join(
    HERE, "yarn-%s-to-official-%s.tsv" % (MC_VERSION, MC_VERSION)
)
MEMBERS_TSV = os.path.join(
    HERE, "yarn-%s-to-official-%s-members.tsv" % (MC_VERSION, MC_VERSION)
)

CLASSES_HEADER = "#yarn\tofficial\n"
MEMBERS_HEADER = (
    "#yarnOwner\tyarnName\tdescriptor"
    "\tofficialOwner\tofficialName\tofficialDescriptor\n"
)

# Yarn mergedv2 occasionally stores an intermediary name in its `official`
# column instead of a real obf name (synthetic / bridge members that the merge
# could not resolve).  Such a name can never be found in client.txt.
INTERMEDIARY_NAME_RE = re.compile(r"^(?:method|field|class|comp)_\d+$")
INTERMEDIARY_NAME_PREFIX_RE = re.compile(r"^(?:method|field|class|comp)_\d+")

PRIMITIVE_DESCRIPTORS = {
    "void": "V",
    "boolean": "Z",
    "byte": "B",
    "char": "C",
    "short": "S",
    "int": "I",
    "long": "J",
    "float": "F",
    "double": "D",
}

# --------------------------------------------------------------------------- #
# Hand-checked spot checks (see README.md).  Each entry was verified by hand
# against the obfuscated 1.21.11 client jar and Mojang's client.txt.
# --------------------------------------------------------------------------- #

CLASS_SPOT_CHECKS = [
    # (yarn class, expected official class)
    ("net/minecraft/client/MinecraftClient", "net/minecraft/client/Minecraft"),
    ("net/minecraft/util/Identifier", "net/minecraft/resources/Identifier"),
    (
        "net/minecraft/client/render/WorldRenderer",
        "net/minecraft/client/renderer/LevelRenderer",
    ),
    (
        "net/minecraft/client/render/RenderLayer",
        "net/minecraft/client/renderer/rendertype/RenderType",
    ),
    (
        "net/minecraft/client/gl/Framebuffer",
        "com/mojang/blaze3d/pipeline/RenderTarget",
    ),
    ("net/minecraft/text/Text", "net/minecraft/network/chat/Component"),
    ("net/minecraft/world/World", "net/minecraft/world/level/Level"),
    (
        "net/minecraft/entity/player/PlayerEntity",
        "net/minecraft/world/entity/player/Player",
    ),
    ("net/minecraft/item/ItemStack", "net/minecraft/world/item/ItemStack"),
    ("net/minecraft/block/Block", "net/minecraft/world/level/block/Block"),
    ("net/minecraft/util/math/BlockPos", "net/minecraft/core/BlockPos"),
    ("net/minecraft/client/gui/screen/Screen", "net/minecraft/client/gui/screens/Screen"),
    ("net/minecraft/client/option/GameOptions", "net/minecraft/client/Options"),
    ("net/minecraft/sound/SoundEvent", "net/minecraft/sounds/SoundEvent"),
    ("net/minecraft/entity/Entity", "net/minecraft/world/entity/Entity"),
    ("net/minecraft/server/world/ServerWorld", "net/minecraft/server/level/ServerLevel"),
    (
        "net/minecraft/client/network/ClientPlayerEntity",
        "net/minecraft/client/player/LocalPlayer",
    ),
    ("net/minecraft/block/BlockState", "net/minecraft/world/level/block/state/BlockState"),
    ("net/minecraft/util/math/Vec3d", "net/minecraft/world/phys/Vec3"),
    (
        "net/minecraft/client/render/VertexConsumer",
        "com/mojang/blaze3d/vertex/VertexConsumer",
    ),
    ("net/minecraft/entity/LivingEntity", "net/minecraft/world/entity/LivingEntity"),
]

# The `descriptor` column of the member table is the obfuscated descriptor that
# was joined through (as required for the join); the expectations below are
# given for the obf descriptor, the official owner/name and the official
# descriptor that the row must contain.
MEMBER_SPOT_CHECKS = [
    # (yarn owner, yarn name, obf descriptor, official owner, official name,
    #  official descriptor)
    (
        "net/minecraft/client/MinecraftClient",
        "getInstance",
        "()Lgfj;",
        "net/minecraft/client/Minecraft",
        "getInstance",
        "()Lnet/minecraft/client/Minecraft;",
    ),
    (
        "net/minecraft/client/MinecraftClient",
        "getWindow",
        "()Lfyk;",
        "net/minecraft/client/Minecraft",
        "getWindow",
        "()Lcom/mojang/blaze3d/platform/Window;",
    ),
    (
        "net/minecraft/client/MinecraftClient",
        "player",
        "Lhnh;",
        "net/minecraft/client/Minecraft",
        "player",
        "Lnet/minecraft/client/player/LocalPlayer;",
    ),
    (
        "net/minecraft/client/MinecraftClient",
        "getSession",
        "()Lgfx;",
        "net/minecraft/client/Minecraft",
        "getUser",
        "()Lnet/minecraft/client/User;",
    ),
    (
        "net/minecraft/util/Identifier",
        "of",
        "(Ljava/lang/String;)Lamo;",
        "net/minecraft/resources/Identifier",
        "parse",
        "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;",
    ),
    (
        "net/minecraft/text/Text",
        "literal",
        "(Ljava/lang/String;)Lyw;",
        "net/minecraft/network/chat/Component",
        "literal",
        "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;",
    ),
    (
        "net/minecraft/item/ItemStack",
        "getCount",
        "()I",
        "net/minecraft/world/item/ItemStack",
        "getCount",
        "()I",
    ),
    (
        "net/minecraft/block/Blocks",
        "STONE",
        "Ldzq;",
        "net/minecraft/world/level/block/Blocks",
        "STONE",
        "Lnet/minecraft/world/level/block/Block;",
    ),
    (
        "net/minecraft/entity/player/PlayerEntity",
        "getInventory",
        "()Lddl;",
        "net/minecraft/world/entity/player/Player",
        "getInventory",
        "()Lnet/minecraft/world/entity/player/Inventory;",
    ),
    (
        "net/minecraft/world/World",
        "setBlockState",
        "(Lis;Leoh;)Z",
        "net/minecraft/world/level/Level",
        "setBlockAndUpdate",
        "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
    ),
]

# --------------------------------------------------------------------------- #
# Download / cache helpers
# --------------------------------------------------------------------------- #


def sha1_of(path):
    digest = hashlib.sha1()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def published_sha1(url):
    """Best-effort fetch of a `<url>.sha1` sidecar (Fabric publishes one)."""
    try:
        with urllib.request.urlopen(url + ".sha1", timeout=30) as response:
            return response.read().decode("utf-8").strip().split()[0].lower()
    except Exception:  # offline or sidecar missing: not fatal, the sha1 is printed
        return None


def download(url, dest, expected_sha1=None, refresh=False, sha1_sidecar=False):
    """Download `url` to `dest` unless it is already cached (and correct)."""
    if os.path.exists(dest) and not refresh:
        if expected_sha1 is None or sha1_of(dest) == expected_sha1:
            return dest
        print("[cache] %s has wrong sha1, re-downloading" % os.path.basename(dest))
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    print("[get]   %s" % url)
    with urllib.request.urlopen(url, timeout=120) as response:
        with open(tmp, "wb") as handle:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                handle.write(chunk)
    if expected_sha1 is None and sha1_sidecar:
        expected_sha1 = published_sha1(url)
    if expected_sha1 is not None:
        actual = sha1_of(tmp)
        if actual != expected_sha1:
            os.unlink(tmp)
            raise SystemExit(
                "sha1 mismatch for %s: expected %s, got %s"
                % (url, expected_sha1, actual)
            )
    os.replace(tmp, dest)
    return dest


def fetch_client_mappings(refresh=False):
    """Resolve `downloads.client_mappings` for MC_VERSION and cache client.txt."""
    manifest_path = download(VERSION_MANIFEST_URL, VERSION_MANIFEST_JSON, refresh=refresh)
    with open(manifest_path, "r", encoding="utf-8") as handle:
        manifest = json.load(handle)

    version_url = None
    for entry in manifest["versions"]:
        if entry["id"] == MC_VERSION:
            version_url = entry["url"]
            break
    if version_url is None:
        raise SystemExit("version %s not found in the Mojang version manifest" % MC_VERSION)

    download(version_url, VERSION_JSON, refresh=refresh)
    with open(VERSION_JSON, "r", encoding="utf-8") as handle:
        version = json.load(handle)

    client_mappings = version["downloads"]["client_mappings"]
    download(
        client_mappings["url"],
        CLIENT_TXT,
        expected_sha1=client_mappings["sha1"],
        refresh=refresh,
    )
    return client_mappings["url"], client_mappings["sha1"]


# --------------------------------------------------------------------------- #
# Parsing
# --------------------------------------------------------------------------- #


def read_tiny(path):
    """Read the tiny v2 `official intermediary named` mapping out of a yarn jar."""
    with zipfile.ZipFile(path) as archive:
        raw = archive.read("mappings/mappings.tiny").decode("utf-8")

    lines = raw.split("\n")
    header = lines[0].split("\t")
    if header[:3] != ["tiny", "2", "0"]:
        raise SystemExit("unexpected tiny header: %r" % lines[0])
    namespaces = header[3:]
    if namespaces != ["official", "intermediary", "named"]:
        raise SystemExit(
            "expected tiny namespaces ['official', 'intermediary', 'named'], got %r"
            "(the -v2 artifact only has intermediary+named and cannot be joined "
            "against Mojang's client.txt)" % (namespaces,)
        )

    classes = []  # (obf, intermediary, named)
    members = []  # (kind, obf_owner, obf_name, descriptor, intermediary_name, named)
    skipped = defaultdict(int)

    current = None
    for line in lines:
        if not line:
            continue
        if line.startswith("c\t"):
            parts = line.split("\t")
            current = (parts[1], parts[2], parts[3])
            classes.append(current)
            continue
        if line.startswith("\t"):
            depth = len(line) - len(line.lstrip("\t"))
            fields = line.split("\t")
            kind = fields[depth] if len(fields) > depth else "?"
            if kind in ("f", "m") and depth == 1:
                # ['', 'f'|'m', descriptor, official, intermediary, named]
                members.append(
                    (kind, current[0], fields[3], fields[2], fields[4], fields[5])
                )
            else:
                # p = parameter, v = local variable, c = comment: no members
                skipped[kind] += 1

    return classes, members, dict(skipped)


class ClientMember:
    __slots__ = ("kind", "owner", "name", "official_name", "official_desc", "obf_desc")

    def __init__(self, kind, owner, name, official_name, official_desc, obf_desc):
        self.kind = kind
        self.owner = owner
        self.name = name
        self.official_name = official_name
        self.official_desc = official_desc
        self.obf_desc = obf_desc


def java_type_to_descriptor(java_type):
    """`java.util.List[]` -> `[Ljava/util/List;`, `int` -> `I`."""
    java_type = java_type.strip()
    dimensions = 0
    while java_type.endswith("[]"):
        java_type = java_type[:-2]
        dimensions += 1
    if java_type in PRIMITIVE_DESCRIPTORS:
        base = PRIMITIVE_DESCRIPTORS[java_type]
    else:
        base = "L" + java_type.replace(".", "/") + ";"
    return "[" * dimensions + base


def translate_descriptor(descriptor, class_map):
    """Rewrite every `L<name>;` in a JVM descriptor through class_map.

    Names that are unknown to class_map are left untouched (JDK / library
    classes are not obfuscated, so their names are identical in both
    namespaces).  Returns (translated, unknown_names).
    """
    out = []
    unknown = []
    index = 0
    while index < len(descriptor):
        char = descriptor[index]
        if char == "L":
            end = descriptor.find(";", index)
            if end == -1:
                raise SystemExit("malformed descriptor: %r" % descriptor)
            name = descriptor[index + 1 : end]
            mapped = class_map.get(name)
            if mapped is None:
                unknown.append(name)
                mapped = name
            out.append("L" + mapped + ";")
            index = end + 1
        else:
            out.append(char)
            index += 1
    return "".join(out), unknown


def parse_client_txt(path):
    """Parse Mojang's ProGuard style client.txt.

    Returns (obf_to_official, official_to_obf, members_by_name, members_by_desc).
    """
    class_re = re.compile(r"^(\S+) -> (\S+):$")
    member_re = re.compile(r"^    (?:(\d+):(\d+):)?(.+?) -> (\S+)$")
    method_re = re.compile(r"^(.*?)\s+(\S+)\((.*)\)$")

    obf_to_official = {}
    official_to_obf = {}
    parsed = []

    current = None
    seen = set()
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            match = class_re.match(line)
            if match:
                official = match.group(1).replace(".", "/")
                obf = match.group(2).replace(".", "/")
                obf_to_official[obf] = official
                official_to_obf[official] = obf
                current = obf
                continue
            if line.startswith("#") or not line.startswith("    "):
                continue
            match = member_re.match(line)
            if not match:
                raise SystemExit("unparsed client.txt line: %r" % line)
            body = match.group(3)
            obf_name = match.group(4)
            if "(" in body:
                method = method_re.match(body)
                if not method:
                    raise SystemExit("unparsed client.txt method: %r" % line)
                returns, name, arguments = method.groups()
                descriptor = (
                    "("
                    + "".join(
                        java_type_to_descriptor(argument)
                        for argument in (arguments.split(",") if arguments else [])
                    )
                    + ")"
                    + java_type_to_descriptor(returns)
                )
                kind = "m"
            else:
                java_type, name = body.rsplit(" ", 1)
                descriptor = java_type_to_descriptor(java_type)
                kind = "f"

            key = (kind, current, obf_name, descriptor)
            if key in seen:  # client.txt may repeat identical rows
                continue
            seen.add(key)
            parsed.append((kind, current, obf_name, name, descriptor))

    # Second pass: the class map is complete now, so descriptors that reference
    # classes declared further down the file translate correctly.
    by_name = defaultdict(list)
    by_desc = defaultdict(list)
    for kind, owner, obf_name, name, descriptor in parsed:
        obf_descriptor, _ = translate_descriptor(descriptor, official_to_obf)
        member = ClientMember(kind, owner, obf_name, name, descriptor, obf_descriptor)
        by_name[(kind, owner, obf_name)].append(member)
        by_desc[(kind, owner, obf_descriptor)].append(member)

    return obf_to_official, official_to_obf, by_name, by_desc


def parse_tiny_v2_names(path):
    """intermediary -> named for the plain `-v2` yarn artifact (integrity check)."""
    with zipfile.ZipFile(path) as archive:
        raw = archive.read("mappings/mappings.tiny").decode("utf-8")
    classes = {}
    members = {}
    current = None
    for line in raw.split("\n"):
        if line.startswith("c\t"):
            parts = line.split("\t")
            classes[parts[1]] = parts[2]
            current = parts[1]
        elif line.startswith("\t"):
            fields = line.split("\t")
            if fields[1] in ("f", "m") and current is not None:
                members[(current, fields[2], fields[3])] = fields[4]
    return classes, members


# --------------------------------------------------------------------------- #
# Join
# --------------------------------------------------------------------------- #


def build(refresh=False):
    download(
        YARN_MERGEDV2_URL, YARN_MERGEDV2_JAR, refresh=refresh, sha1_sidecar=True
    )
    download(YARN_V2_URL, YARN_V2_JAR, refresh=refresh, sha1_sidecar=True)
    print("[info]  yarn mergedv2: sha1 %s" % sha1_of(YARN_MERGEDV2_JAR))
    print("[info]  yarn v2      : sha1 %s" % sha1_of(YARN_V2_JAR))

    yarn_classes, yarn_members, skipped_lines = read_tiny(YARN_MERGEDV2_JAR)
    (
        obf_to_official,
        official_to_obf,
        client_by_name,
        client_by_desc,
    ) = parse_client_txt(CLIENT_TXT)

    named_by_obf = {obf: named for obf, _intermediary, named in yarn_classes}

    # ---- classes ---------------------------------------------------------- #
    class_rows = []
    unjoined_classes = []
    unnamed_yarn_classes = 0
    for obf, intermediary, named in yarn_classes:
        official = obf_to_official.get(obf)
        if official is None:
            unjoined_classes.append((named, obf))
        if named == intermediary or INTERMEDIARY_NAME_PREFIX_RE.match(
            named.rsplit("/", 1)[-1]
        ):
            unnamed_yarn_classes += 1
        class_rows.append((named, official or ""))

    class_rows.sort(key=lambda row: (row[0], row[1]))

    # ---- members ---------------------------------------------------------- #
    member_rows = []
    stats = defaultdict(int)
    ambiguous_keys = set()
    unresolved_examples = []
    ambiguous_examples = []

    for kind, obf_owner, obf_name, descriptor, _intermediary, named in yarn_members:
        stats["yarn_members"] += 1
        stats["yarn_%s" % ("methods" if kind == "m" else "fields")] += 1

        official_owner = obf_to_official.get(obf_owner, "")
        official_name = ""
        official_descriptor = ""

        candidates = client_by_name.get((kind, obf_owner, obf_name))
        join_kind = None
        chosen = None
        if candidates:
            if len(candidates) == 1:
                chosen = candidates[0]
                join_kind = "unique_name"
            else:
                ambiguous_keys.add((kind, obf_owner, obf_name))
                exact = [c for c in candidates if c.obf_desc == descriptor]
                if len(exact) == 1:
                    chosen = exact[0]
                    join_kind = "descriptor"
                elif len(exact) == 0:
                    stats["unresolved_ambiguous_no_descriptor_match"] += 1
                    unresolved_examples.append(
                        ("ambiguous", kind, named, descriptor, len(candidates))
                    )
                else:
                    stats["unresolved_ambiguous_duplicate_descriptor"] += 1
                    unresolved_examples.append(
                        ("ambiguous-dup", kind, named, descriptor, len(exact))
                    )
        else:
            # mergedv2 stores an intermediary name in its `official` column
            # instead of an obf name for members whose obf name does not agree
            # between the client and the server jar it merges (49 rows for
            # 1.21.11).  No name lookup is possible for those; accept a unique
            # (owner, descriptor) match instead.
            if INTERMEDIARY_NAME_RE.match(obf_name):
                stats["intermediary_named_members"] += 1
                fallback = client_by_desc.get((kind, obf_owner, descriptor))
                if fallback is not None and len(fallback) == 1:
                    chosen = fallback[0]
                    join_kind = "descriptor_fallback"
                else:
                    stats["unresolved_no_official_counterpart"] += 1
                    if fallback:
                        stats["unresolved_descriptor_ambiguous"] += 1
                    unresolved_examples.append(
                        ("intermediary name, %d descriptor candidates"
                         % (0 if not fallback else len(fallback)),
                         kind, named, descriptor, 0 if not fallback else len(fallback))
                    )
            else:
                stats["unresolved_no_official_counterpart"] += 1
                unresolved_examples.append(("no-counterpart", kind, named, descriptor, 0))

        if chosen is not None:
            stats["joined_" + join_kind] += 1
            if chosen.obf_desc != descriptor:
                stats["desc_mismatch_joined_by_name"] += 1
            official_name = chosen.official_name
            translated, unknown = translate_descriptor(descriptor, obf_to_official)
            if any("/" not in name for name in unknown):
                stats["official_descriptor_unresolved"] += 1
                official_descriptor = ""
            else:
                official_descriptor = translated
            if join_kind == "descriptor" and len(ambiguous_examples) < 8:
                ambiguous_examples.append(
                    (named, descriptor, [(c.official_name, c.obf_desc) for c in candidates])
                )
        else:
            stats["unresolved_members"] += 1

        member_rows.append(
            (
                named_by_obf.get(obf_owner, obf_owner),
                named,
                descriptor,
                official_owner,
                official_name,
                official_descriptor,
            )
        )

    member_rows.sort(key=lambda row: (row[0], row[1], row[2], row[3], row[4]))

    stats["yarn_classes"] = len(yarn_classes)
    stats["joined_classes"] = len(yarn_classes) - len(unjoined_classes)
    stats["unresolved_classes"] = len(unjoined_classes)
    stats["unnamed_yarn_classes"] = unnamed_yarn_classes
    stats["client_txt_classes"] = len(obf_to_official)
    stats["client_classes_not_in_yarn"] = len(
        set(obf_to_official) - {obf for obf, _, _ in yarn_classes}
    )
    stats["ambiguous_name_keys"] = len(ambiguous_keys)
    stats["joined_members"] = stats["yarn_members"] - stats["unresolved_members"]
    stats["skipped_parameter_lines"] = skipped_lines.get("p", 0)
    stats["skipped_local_variable_lines"] = skipped_lines.get("v", 0)
    stats["skipped_comment_lines"] = skipped_lines.get("c", 0)
    stats["skipped_other_lines"] = sum(
        count
        for kind, count in skipped_lines.items()
        if kind not in ("p", "v", "c")
    )

    return class_rows, member_rows, stats, unresolved_examples, ambiguous_examples


# --------------------------------------------------------------------------- #
# Output
# --------------------------------------------------------------------------- #


def write_tsv(path, header, rows):
    body = "".join("\t".join(row) + "\n" for row in rows)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(header)
        handle.write(body)


def read_tsv(path):
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


# --------------------------------------------------------------------------- #
# Reporting / verification
# --------------------------------------------------------------------------- #


def print_stats(stats, class_rows, member_rows):
    print()
    print("=== join statistics (Minecraft %s) ===" % MC_VERSION)
    print(
        "classes : %d yarn classes, %d joined (%.4f%%), %d without official counterpart"
        % (
            stats["yarn_classes"],
            stats["joined_classes"],
            100.0 * stats["joined_classes"] / stats["yarn_classes"],
            stats["unresolved_classes"],
        )
    )
    print(
        "          %d classes in client.txt, %d of them are not in yarn (out of scope)"
        % (stats["client_txt_classes"], stats["client_classes_not_in_yarn"])
    )
    print(
        "          %d yarn classes have no yarn name of their own (named == intermediary)"
        % stats["unnamed_yarn_classes"]
    )
    print(
        "members : %d yarn members (%d methods, %d fields), %d joined (%.4f%%), "
        "%d without official counterpart"
        % (
            stats["yarn_members"],
            stats["yarn_methods"],
            stats["yarn_fields"],
            stats["joined_members"],
            100.0 * stats["joined_members"] / stats["yarn_members"],
            stats["unresolved_members"],
        )
    )
    print(
        "          joined by unique (owner, name): %d; disambiguated by descriptor: %d; "
        "descriptor-only fallback: %d"
        % (
            stats["joined_unique_name"],
            stats["joined_descriptor"],
            stats["joined_descriptor_fallback"],
        )
    )
    print(
        "          ambiguous (owner, name) keys: %d; rows resolved through them: %d; "
        "rows left unresolved there: %d"
        % (
            stats["ambiguous_name_keys"],
            stats["joined_descriptor"],
            stats["unresolved_ambiguous_no_descriptor_match"]
            + stats["unresolved_ambiguous_duplicate_descriptor"],
        )
    )
    print(
        "          members with an intermediary name in mergedv2: %d; of those "
        "recovered by descriptor: %d; unresolved: %d (%d with an ambiguous "
        "descriptor in client.txt)"
        % (
            stats["intermediary_named_members"],
            stats["joined_descriptor_fallback"],
            stats["intermediary_named_members"] - stats["joined_descriptor_fallback"],
            stats["unresolved_descriptor_ambiguous"],
        )
    )
    print(
        "          rows joined by name whose descriptor disagrees: %d; rows with an "
        "unresolvable official descriptor: %d"
        % (stats["desc_mismatch_joined_by_name"], stats["official_descriptor_unresolved"])
    )
    print(
        "          skipped non-member tiny lines: %d parameters, %d local variables, "
        "%d comments, %d other"
        % (
            stats["skipped_parameter_lines"],
            stats["skipped_local_variable_lines"],
            stats["skipped_comment_lines"],
            stats["skipped_other_lines"],
        )
    )
    print("          rows written: %d classes, %d members" % (len(class_rows), len(member_rows)))


def run_spot_checks(class_rows, member_rows):
    class_map = {}
    for yarn_name, official_name in class_rows:
        class_map.setdefault(yarn_name, official_name)
    member_map = {}
    for row in member_rows:
        member_map[(row[0], row[1], row[2])] = row

    failures = []
    print()
    print("=== spot checks ===")
    print("%-58s %s" % ("yarn class", "official class"))
    for yarn_name, expected in CLASS_SPOT_CHECKS:
        actual = class_map.get(yarn_name)
        status = "ok" if actual == expected else "FAIL"
        if actual != expected:
            failures.append("class %s: expected %s, got %s" % (yarn_name, expected, actual))
        print("%-58s %-58s [%s]" % (yarn_name, actual, status))

    print()
    print(
        "%-58s %-24s %-20s %s"
        % ("yarn member (obf descriptor)", "official owner#name", "official name", "official descriptor")
    )
    for y_owner, y_name, y_desc, exp_owner, exp_name, exp_desc in MEMBER_SPOT_CHECKS:
        row = member_map.get((y_owner, y_name, y_desc))
        if row is None:
            actual_owner = actual_name = actual_desc = "<missing row>"
        else:
            actual_owner, actual_name, actual_desc = row[3], row[4], row[5]
        status = (
            "ok"
            if (actual_owner, actual_name, actual_desc) == (exp_owner, exp_name, exp_desc)
            else "FAIL"
        )
        if status == "FAIL":
            failures.append(
                "member %s#%s %s: expected %s#%s %s, got %s#%s %s"
                % (
                    y_owner,
                    y_name,
                    y_desc,
                    exp_owner,
                    exp_name,
                    exp_desc,
                    actual_owner,
                    actual_name,
                    actual_desc,
                )
            )
        print(
            "%-58s %-24s %-20s %s [%s]"
            % (
                y_owner + "#" + y_name + " " + y_desc,
                actual_owner + "#" + (actual_name or "?"),
                actual_name,
                actual_desc,
                status,
            )
        )
    return failures


def structural_checks(class_rows, member_rows, stats):
    """Cheap invariants that must hold for every run."""
    problems = []

    if list(class_rows) != sorted(class_rows, key=lambda row: (row[0], row[1])):
        problems.append("class rows are not sorted by yarn name")
    if list(member_rows) != sorted(
        member_rows, key=lambda row: (row[0], row[1], row[2], row[3], row[4])
    ):
        problems.append("member rows are not sorted by owner, name, descriptor")

    seen = set()
    for yarn_name, official_name in class_rows:
        if yarn_name in seen:
            problems.append("duplicate yarn class %s" % yarn_name)
        seen.add(yarn_name)
        if not yarn_name or not official_name:
            problems.append("incomplete class row %r" % ((yarn_name, official_name),))

    seen = set()
    for row in member_rows:
        key = (row[0], row[1], row[2])
        if key in seen:
            problems.append("duplicate yarn member %r" % (key,))
        seen.add(key)
        if not row[3]:
            problems.append("member row without official owner: %r" % (row,))
        if not row[4] and row[5]:
            problems.append("official descriptor without official name: %r" % (row,))
        if row[5]:
            if row[5].count("(") != row[5].count(")") or (
                row[5].startswith("(") != row[2].startswith("(")
            ):
                problems.append("malformed official descriptor: %r" % (row,))

    if stats["desc_mismatch_joined_by_name"] != 0:
        problems.append(
            "%d rows were joined by name with a disagreeing descriptor"
            % stats["desc_mismatch_joined_by_name"]
        )
    return problems


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument(
        "--verify",
        action="store_true",
        help="also run the hand-checked spot checks and validate the files on disk",
    )
    parser.add_argument(
        "--refresh",
        action="store_true",
        help="re-download the cached upstream mapping files",
    )
    args = parser.parse_args(argv)

    client_txt_url, client_txt_sha1 = fetch_client_mappings(refresh=args.refresh)
    print("[info]  client mappings: %s (sha1 %s)" % (client_txt_url, client_txt_sha1))
    print("[info]  yarn mappings  : %s" % YARN_MERGEDV2_URL)

    class_rows, member_rows, stats, unresolved_examples, ambiguous_examples = build(
        refresh=args.refresh
    )

    # Integrity cross-check: yarn's plain -v2 artifact must agree with mergedv2
    # on the named namespace.  This proves the obf column we joined on belongs
    # to the same Yarn build that the mod's sources were compiled against.
    v2_classes, _v2_members = parse_tiny_v2_names(YARN_V2_JAR)
    mergedv2_classes = {inter: named for _obf, inter, named in
                        read_tiny(YARN_MERGEDV2_JAR)[0]}
    class_agreement = sum(
        1 for inter, named in v2_classes.items() if mergedv2_classes.get(inter) == named
    )
    print(
        "[check] yarn -v2 vs -mergedv2 named namespace: %d/%d classes agree"
        % (class_agreement, len(v2_classes))
    )

    write_tsv(CLASSES_TSV, CLASSES_HEADER, class_rows)
    write_tsv(MEMBERS_TSV, MEMBERS_HEADER, member_rows)
    print("[write] %s" % os.path.relpath(CLASSES_TSV))
    print("[write] %s" % os.path.relpath(MEMBERS_TSV))

    print_stats(stats, class_rows, member_rows)

    problems = structural_checks(class_rows, member_rows, stats)
    if problems:
        print()
        print("STRUCTURAL CHECK FAILURES:")
        for problem in problems[:20]:
            print("  - %s" % problem)

    if unresolved_examples:
        print()
        print("examples of unjoined yarn members:")
        for reason, kind, name, descriptor, count in unresolved_examples[:8]:
            print(
                "  [%s/%s] %s %s (%d candidate rows)"
                % (reason, kind, name, descriptor, count)
            )

    if ambiguous_examples:
        print()
        print("examples of members disambiguated by descriptor:")
        for name, descriptor, candidates in ambiguous_examples[:3]:
            print("  %s %s ->" % (name, descriptor))
            for official_name, obf_desc in candidates:
                marker = "==>" if obf_desc == descriptor else "   "
                print("    %s %s %s" % (marker, official_name, obf_desc))

    failures = list(problems)
    if args.verify:
        failures += run_spot_checks(class_rows, member_rows)
        for path, expected, label in (
            (CLASSES_TSV, CLASSES_HEADER + "".join(
                "\t".join(row) + "\n" for row in class_rows), "class table"),
            (MEMBERS_TSV, MEMBERS_HEADER + "".join(
                "\t".join(row) + "\n" for row in member_rows), "member table"),
        ):
            if read_tsv(path) != expected:
                failures.append("%s on disk does not match a fresh build (%s)" % (label, path))
        if class_agreement != len(v2_classes):
            failures.append("yarn -v2 and -mergedv2 disagree on the named namespace")

    print()
    if failures:
        print("FAILED: %d problem(s)" % len(failures))
        for failure in failures[:20]:
            print("  - %s" % failure)
        return 1
    print("OK" + (" (spot checks + on-disk validation passed)" if args.verify else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
