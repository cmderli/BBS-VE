#!/usr/bin/env bash
#
# Compiles the BBS graphics core against the real Minecraft 26.2 artifacts.
#
# The rest of the mod is still written against 1.21.11 Yarn names, so a normal `gradlew
# compileClientJava` cannot say anything useful yet — it fails on every Minecraft reference at
# once. This probe exists to keep the part of the port that *is* done honest: it compiles the
# 26.2-native graphics classes against the unobfuscated 26.2 jar and Fabric API for 26.2, so a
# signature that drifted (a `CommandEncoder` overload, a `RenderPassDescriptor` builder method, a
# `GpuFormat` constant) fails here instead of at the next full build.
#
# Usage:  tools/26.2-api-probe.sh
#
# Exit code 0 means the graphics core still matches the 26.2 API. It does not run the game and it
# cannot catch a call that compiles but is semantically wrong (an int parameter order, a missing
# submit) — those need runClient.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/bbs-26.2-probe"
STUBS="$OUT/stubs"
CLASSES="$OUT/classes"
CP_FILE="$OUT/client-classpath.txt"

mkdir -p "$STUBS/mchorse/bbs_mod/graphics/texture" "$STUBS/mchorse/bbs_mod/utils" "$CLASSES"

# ---------------------------------------------------------------------------------------------
# The compile classpath comes from Loom itself so that the probe can never disagree with the
# build about which Minecraft jar is in play. An init script adds the task, which keeps the
# project's own build.gradle free of probe scaffolding.
# ---------------------------------------------------------------------------------------------
cat > "$OUT/dump-classpath.init.gradle" <<'GRADLE'
allprojects {
    tasks.register('dumpClientCp') {
        doLast {
            def ss = project.extensions.getByName('sourceSets')
            def out = new StringBuilder()
            ['main', 'client'].each { n ->
                def s = ss.findByName(n)
                if (s != null) { out.append(s.compileClasspath.asPath).append(File.pathSeparator) }
            }
            new File(System.getProperty('bbs.probe.cp')).text = out.toString()
        }
    }
}
GRADLE

echo "==> resolving the 26.2 compile classpath through Loom"
(cd "$ROOT" && sh ./gradlew --console=plain -q \
    -I "$OUT/dump-classpath.init.gradle" \
    -Dbbs.probe.cp="$CP_FILE" \
    dumpClientCp)

# ---------------------------------------------------------------------------------------------
# Three mod-local types the graphics core touches are stubbed rather than compiled: BBSMod pulls
# in the entire yarn-named mod, AnimatedTexture exists only for Texture's parent pointer, and the
# real StringUtils imports a yarn Minecraft text type. Everything else — including Pixels — is the
# repository's own source, so the probe still exercises the real call signatures.
# ---------------------------------------------------------------------------------------------
cat > "$STUBS/mchorse/bbs_mod/BBSMod.java" <<'JAVA'
package mchorse.bbs_mod;

public class BBSMod
{
    public static final String MOD_ID = "bbs";
}
JAVA

cat > "$STUBS/mchorse/bbs_mod/graphics/texture/AnimatedTexture.java" <<'JAVA'
package mchorse.bbs_mod.graphics.texture;

public class AnimatedTexture
{}
JAVA

cat > "$STUBS/mchorse/bbs_mod/utils/StringUtils.java" <<'JAVA'
package mchorse.bbs_mod.utils;

public class StringUtils
{
    public static String leftPad(String string, int length, String pad)
    {
        return string;
    }

    public static int parseHex(String color)
    {
        return 0;
    }
}
JAVA

SOURCES=(
    "$STUBS/mchorse/bbs_mod/BBSMod.java"
    "$STUBS/mchorse/bbs_mod/graphics/texture/AnimatedTexture.java"
    "$ROOT/src/main/java/mchorse/bbs_mod/utils/resources/Pixels.java"
    "$ROOT/src/main/java/mchorse/bbs_mod/utils/IOUtils.java"
    "$ROOT/src/main/java/mchorse/bbs_mod/utils/MathUtils.java"
    "$ROOT/src/main/java/mchorse/bbs_mod/utils/colors/Color.java"
    "$ROOT/src/main/java/mchorse/bbs_mod/utils/colors/Colors.java"
    "$ROOT/src/main/java/mchorse/bbs_mod/utils/interps/Lerps.java"
    "$ROOT/src/client/java/mchorse/bbs_mod/graphics/gpu/BBSGpu.java"
    "$ROOT/src/client/java/mchorse/bbs_mod/graphics/gpu/BBSRenderPipelines.java"
    "$ROOT/src/client/java/mchorse/bbs_mod/graphics/gpu/BBSGeometryQueue.java"
    "$ROOT/src/client/java/mchorse/bbs_mod/graphics/texture/TextureFilter.java"
    "$ROOT/src/client/java/mchorse/bbs_mod/graphics/texture/TextureFormat.java"
    "$ROOT/src/client/java/mchorse/bbs_mod/graphics/texture/Texture.java"
    "$ROOT/src/client/java/mchorse/bbs_mod/graphics/Renderbuffer.java"
    "$ROOT/src/client/java/mchorse/bbs_mod/graphics/Framebuffer.java"
)

echo "==> compiling the graphics core against $(tr ':' '\n' < "$CP_FILE" | grep -c . ) classpath entries"
javac -nowarn -proc:none \
    -d "$CLASSES" \
    -cp "$(cat "$CP_FILE")" \
    -sourcepath "$STUBS:$ROOT/src/main/java" \
    "${SOURCES[@]}"

echo "==> OK: the BBS graphics core compiles against Minecraft 26.2"
