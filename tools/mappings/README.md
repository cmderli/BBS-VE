# Yarn → Mojang (official) identifier table for Minecraft 1.21.11

This directory contains a machine-readable **Yarn → Mojang official** identifier
table for Minecraft **1.21.11**, produced by joining the two mapping sets that
describe the very same 1.21.11 jar on that jar's obfuscated (ProGuard) names.

It exists because the mod sources (1566 Java files) are written against **Yarn**
names for 1.21.11, while Minecraft **26.2** ships unobfuscated (official Mojang
names only) and Yarn stopped publishing after 1.21.11. The table is the enabler
for a mechanical rename of the sources from Yarn names to official names.

> **Scope / caveat — read this first.**
> 26.2 is unobfuscated, so a *1.21.11-official* name is **not automatically a
> 26.2 name**. Mojang renamed a lot between 1.21.11 and 26.2 (and dropped the
> obfuscation entirely). This table only does one job: get the sources **off
> Yarn names and onto 1.21.11 official names**. Any residual 1.21.11 → 26.2
> drift is a separate problem and is *not* covered here.

## Files

| File | Contents |
| --- | --- |
| `build-yarn-to-official.py` | Self-contained Python 3 (stdlib only) builder. Downloads both mapping sets, joins them, writes the two TSVs, prints statistics; `--verify` additionally runs the hand-checked spot checks and proves that the files on disk match a fresh build. |
| `yarn-1.21.11-to-official-1.21.11.tsv` | CLASS table. 10 275 rows + header. `yarn/class/Name<TAB>official/class/Name`, sorted by the Yarn name. |
| `yarn-1.21.11-to-official-1.21.11-members.tsv` | MEMBER table. 102 202 rows + header. `yarn/owner/Class<TAB>yarnName<TAB>descriptor<TAB>official/owner/Class<TAB>officialName<TAB>officialDescriptor`, sorted by owner, then name, then descriptor. |
| `.cache/` | Downloaded upstream files (git-ignored). |

### Exact formats

```
#yarn	official
net/minecraft/client/MinecraftClient	net/minecraft/client/Minecraft
```

```
#yarnOwner	yarnName	descriptor	officialOwner	officialName	officialDescriptor
net/minecraft/client/MinecraftClient	getInstance	()Lgfj;	net/minecraft/client/Minecraft	getInstance	()Lnet/minecraft/client/Minecraft;
```

* Both files are UTF-8 with `\n` line endings and start with a `#` header line.
* `descriptor` is the **obfuscated JVM descriptor** that was joined through
  (`(Lgfj;)V`-style), exactly as it appears in Yarn's tiny file.
* `officialDescriptor` is that same descriptor with every Minecraft class name
  rewritten to its official name. JDK/library classes (`java/…`, `org/lwjgl/…`,
  `com/google/gson/…`) are not obfuscated and therefore appear unchanged.
* **Rows with an empty `officialName` could not be joined** (see
  [Missing counterparts](#missing-counterparts)); `officialOwner` is still
  filled in for them. 14 rows are in that state.
* Inner classes use the JVM `$` convention on both sides
  (`net/minecraft/client/gui/widget/EntryListWidget$Entry` →
  `net/minecraft/client/gui/components/AbstractSelectionList$Entry`), including
  the obf side (`giy$a`).

## Upstream sources

| Source | Artifact | sha1 |
| --- | --- | --- |
| Yarn (tiny v2, namespaces `official intermediary named`) | `https://maven.fabricmc.net/net/fabricmc/yarn/1.21.11+build.6/yarn-1.21.11+build.6-mergedv2.jar` | `06e326952b4061490230419e3ff035ea8c0117a2` |
| Yarn (tiny v2, namespaces `intermediary named`) — cross-check only | `https://maven.fabricmc.net/net/fabricmc/yarn/1.21.11+build.6/yarn-1.21.11+build.6-v2.jar` | `623a9304295dc0128e0543b558bb46c01ac81959` |
| Mojang official mappings | `downloads.client_mappings.url` of the 1.21.11 version json (`https://piston-data.mojang.com/v1/objects/031a68bebf55d824f66d6573d8c752f0e1bf232a/client.txt`) | `031a68bebf55d824f66d6573d8c752f0e1bf232a` |

The version json itself is resolved from
`https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`
(1.21.11 → `https://piston-meta.mojang.com/v1/packages/f8d822c54003334930a71b7b7b5e16b3d98ea9fd/1.21.11.json`),
and the sha1 published in the manifest is verified after download.

**Why `-mergedv2` and not the `-v2` jar?** The `-v2` artifact only carries the
namespaces `intermediary named`; it has **no obfuscated column**, so it cannot be
joined against Mojang's `obf → official` file. The `-mergedv2` artifact carries
`official intermediary named`, i.e. exactly the three namespaces this join needs.
The plain `-v2` jar is still downloaded and used as an integrity cross-check:
for every class present in both artifacts the `named` name must be identical
(9 720/9 720 agree for 1.21.11+build.6), which proves that the obf column we
joined on belongs to the same Yarn build the sources were written against.

## How the join works

Both mapping sets describe the same jar, so the obfuscated names are the join key.

**Classes** — `tiny.official` (obf) == `client.txt` class key (obf):

* `net/minecraft/client/MinecraftClient` (yarn) → obf `gfj` (tiny) and
  `net.minecraft.client.Minecraft -> gfj:` (client.txt) → official
  `net/minecraft/client/Minecraft`.

**Members** — `client.txt` lists members as Java source types, not JVM
descriptors (`com.mojang.blaze3d.systems.GpuDevice` instead of
`Lcom/mojang/blaze3d/systems/GpuDevice;`). The builder therefore reconstructs a
JVM descriptor for every `client.txt` member and rewrites its class names into
the obf namespace through the (already complete) class join. That yields an exact
obf descriptor, and the member join becomes a lookup on

```
(kind: field|method, obf owner, obf member name, obf descriptor)
```

The resolution order for each Yarn member row is:

1. **Unique name** — exactly one `client.txt` member has this
   `(kind, obf owner, obf name)` → join it. *(66 234 rows.)*
2. **Descriptor disambiguation** — several `client.txt` members share the
   `(kind, obf owner, obf name)` (obfuscated names are heavily reused for
   overloads and bridges). Translate each candidate's official descriptor into
   the obf namespace and keep the one that equals the Yarn descriptor; exactly
   one candidate matched in every one of these cases. *(35 919 rows,
   10 173 distinct ambiguous `(owner, name)` keys, 0 left unresolved.)*
3. **Descriptor-only fallback** — Yarn's `-mergedv2` file stores an
   *intermediary* name (`method_12345`/`field_12345`) instead of an obf name in
   its `official` column for 49 members, so no name lookup is possible at all.
   For those, the builder requires a **unique** `(kind, obf owner, obf
   descriptor)` match in `client.txt`. *(35 rows recovered; 14 left unresolved,
   see below.)*

`officialDescriptor` is produced by translating the obf descriptor back through
the class map. Classes that are *not* in `client.txt` are JDK/library classes;
they are not obfuscated, so their names pass through unchanged. There are
therefore **0 rows with an unresolvable official descriptor**, and **0 rows**
where a member was joined by name while its descriptor disagreed with `client.txt`.

## Statistics (Minecraft 1.21.11, yarn `1.21.11+build.6`)

```
classes : 10275 yarn classes, 10275 joined (100.0000%), 0 without official counterpart
          10291 classes in client.txt, 16 of them are not in yarn (out of scope)
          48 yarn classes have no yarn name of their own (named == intermediary)
members : 102202 yarn members (56954 methods, 45248 fields), 102188 joined (99.9863%),
          14 without official counterpart
          joined by unique (owner, name): 66234; disambiguated by descriptor: 35919;
          descriptor-only fallback: 35
          ambiguous (owner, name) keys: 10173; rows resolved through them: 35919;
          rows left unresolved there: 0
          members with an intermediary name in mergedv2: 49; of those recovered by
          descriptor: 35; unresolved: 14 (all 14 with an ambiguous descriptor in client.txt)
          rows joined by name whose descriptor disagrees: 0
          rows with an unresolvable official descriptor: 0
          skipped non-member tiny lines: 69878 parameters, 0 local variables,
          4569 comments, 0 other
```

* **Class join rate: 100 %** (10 275 / 10 275). The 16 classes that `client.txt`
  knows but Yarn does not are anonymous/package-private leftovers
  (`com.mojang.blaze3d.opengl.GlConst$1`, …); they are outside the scope of a
  Yarn → official table.
* **Member join rate: 99.9863 %** (102 188 / 102 202).
* The 69 878 `p` (parameter) lines and 4 569 `c` (Javadoc) lines in the tiny file
  are not class members and are skipped; Yarn-documented parameters therefore do
  not appear in the member table at all (`descriptor` is the JVM descriptor, so
  no information is lost for renaming).

### Missing counterparts

**Classes:** none. Every Yarn class has an official counterpart.

**Members:** 14 rows (0.0137 %) are emitted with an empty `officialName` /
`officialDescriptor`, all of them methods:

```
net/minecraft/client/gui/widget/EntryListWidget$Entry	getHeight	()I	net/minecraft/client/gui/components/AbstractSelectionList$Entry
net/minecraft/client/gui/widget/EntryListWidget$Entry	getWidth	()I	net/minecraft/client/gui/components/AbstractSelectionList$Entry
net/minecraft/client/gui/widget/EntryListWidget$Entry	getX	()I	net/minecraft/client/gui/components/AbstractSelectionList$Entry
net/minecraft/client/gui/widget/EntryListWidget$Entry	getY	()I	net/minecraft/client/gui/components/AbstractSelectionList$Entry
net/minecraft/client/gui/widget/EntryListWidget$Entry	setX	(I)V	net/minecraft/client/gui/components/AbstractSelectionList$Entry
net/minecraft/client/gui/widget/EntryListWidget$Entry	setY	(I)V	net/minecraft/client/gui/components/AbstractSelectionList$Entry
net/minecraft/entity/mob/BreezeBrain$SlideAroundTask	finishRunning	(Laxf;Lchn;J)V	net/minecraft/world/entity/monster/breeze/BreezeAi$SlideToTargetSink
net/minecraft/entity/mob/BreezeBrain$SlideAroundTask	run	(Laxf;Lchn;J)V	net/minecraft/world/entity/monster/breeze/BreezeAi$SlideToTargetSink
net/minecraft/entity/mob/SpellcastingIllagerEntity$CastSpellGoal	start	()V	net/minecraft/world/entity/monster/illager/SpellcasterIllager$SpellcasterUseSpellGoal
net/minecraft/entity/mob/SpellcastingIllagerEntity$CastSpellGoal	tick	()V	net/minecraft/world/entity/monster/illager/SpellcasterIllager$SpellcasterUseSpellGoal
net/minecraft/entity/passive/BeeEntity$NotAngryGoal	canStart	()Z	net/minecraft/world/entity/animal/bee/Bee$BaseBeeGoal
net/minecraft/entity/passive/BeeEntity$NotAngryGoal	shouldContinue	()Z	net/minecraft/world/entity/animal/bee/Bee$BaseBeeGoal
net/minecraft/nbt/NbtList	get	(I)Lvz;	net/minecraft/nbt/ListTag
net/minecraft/nbt/NbtList	remove	(I)Lvz;	net/minecraft/nbt/ListTag
```

Why they have no counterpart, precisely:

1. **Yarn lost the obf name.** In `yarn-1.21.11+build.6-mergedv2.jar` these 49
   members carry an intermediary name (`method_25368`, …) in the `official`
   column instead of an obf name — an artifact of Yarn merging the separately
   obfuscated client and server jars for members whose obf names do not agree
   between the two, so Fabric cannot pin one obf name.
2. **The descriptor is ambiguous.** Rule 3 requires a *unique*
   `(obf owner, descriptor)` match in `client.txt`, but Mojang's file repeats
   inherited members inside subclass blocks, so e.g. `AbstractSelectionList$Entry`
   (`giy$a`) lists 12 members with descriptor `()I`, and it is impossible to tell
   from that file which of them is the one Yarn means. 14 of the 49 fall in this
   bucket (the other 35 could be matched uniquely and *are* in the table).

   `client.txt` does contain the answer for these 14 (`int getWidth() -> aS_`, …),
   but only keyed by the **obf name**, which is exactly the field Yarn dropped —
   so the information cannot be tied back to the Yarn row without an extra
   source. They are inherited/overridden interface methods in nested classes;
   where the declaring interface/supertype has its own joined row, that row
   carries the rename (`Element.getNavigationFocus` → `getRectangle`,
   `Goal.canStart` → `canStart`, …). The rows are still emitted, with the
   official owner class filled in, so they are easy to find:

   ```sh
   awk -F'\t' 'NR>1 && $5==""' tools/mappings/yarn-1.21.11-to-official-1.21.11-members.tsv
   ```

**Not returned as "missing" but worth knowing:** 48 Yarn classes have no name of
their own (`named == intermediary`, e.g. `net/minecraft/class_6489$1`); they are
included in the class table for completeness.

## Verification

`--verify` asserts all of the following on every run:

* the hand-checked class spot checks below,
* the hand-checked member spot checks (yarn name + obf descriptor → official
  owner, name and descriptor),
* structural invariants: sorted order, no duplicate Yarn keys, every row has an
  official owner, no official descriptor without an official name, well-formed
  descriptors, 0 descriptor mismatches,
* the two yarn artifacts agree on the `named` namespace,
* the files on disk are byte-identical to a fresh build.

Spot checks (all pass), with the authoritative `client.txt` line:

| Yarn | official | `client.txt` evidence |
| --- | --- | --- |
| `net/minecraft/client/MinecraftClient` | `net/minecraft/client/Minecraft` | `net.minecraft.client.Minecraft -> gfj:` |
| `net/minecraft/util/Identifier` | `net/minecraft/resources/Identifier` | `net.minecraft.resources.Identifier -> amo:` |
| `net/minecraft/client/render/WorldRenderer` | `net/minecraft/client/renderer/LevelRenderer` | `net.minecraft.client.renderer.LevelRenderer -> hoh:` |
| `net/minecraft/client/render/RenderLayer` | `net/minecraft/client/renderer/rendertype/RenderType` | `net.minecraft.client.renderer.rendertype.RenderType -> ijs:` |
| `net/minecraft/client/gl/Framebuffer` | `com/mojang/blaze3d/pipeline/RenderTarget` | `com.mojang.blaze3d.pipeline.RenderTarget -> fxt:` |
| `net/minecraft/text/Text` | `net/minecraft/network/chat/Component` | `net.minecraft.network.chat.Component -> yh:` |
| `net/minecraft/world/World` | `net/minecraft/world/level/Level` | `net.minecraft.world.level.Level -> dwo:` |
| `net/minecraft/entity/player/PlayerEntity` | `net/minecraft/world/entity/player/Player` | `net.minecraft.world.entity.player.Player -> ddm:` |
| `net/minecraft/item/ItemStack` | `net/minecraft/world/item/ItemStack` | `net.minecraft.world.item.ItemStack -> dlt:` |
| `net/minecraft/block/Block` | `net/minecraft/world/level/block/Block` | `net.minecraft.world.level.block.Block -> dzq:` |
| `net/minecraft/util/math/BlockPos` | `net/minecraft/core/BlockPos` | `net.minecraft.core.BlockPos -> is:` |
| `net/minecraft/client/gui/screen/Screen` | `net/minecraft/client/gui/screens/Screen` | `net.minecraft.client.gui.screens.Screen -> gsb:` |
| `net/minecraft/client/option/GameOptions` | `net/minecraft/client/Options` | `net.minecraft.client.Options -> gfo:` |
| `net/minecraft/sound/SoundEvent` | `net/minecraft/sounds/SoundEvent` | `net.minecraft.sounds.SoundEvent -> bcz:` |
| `net/minecraft/entity/Entity` | `net/minecraft/world/entity/Entity` | `net.minecraft.world.entity.Entity -> cgk:` |
| `net/minecraft/server/world/ServerWorld` | `net/minecraft/server/level/ServerLevel` | `net.minecraft.server.level.ServerLevel -> axf:` |
| `net/minecraft/client/network/ClientPlayerEntity` | `net/minecraft/client/player/LocalPlayer` | `net.minecraft.client.player.LocalPlayer -> hnh:` |
| `net/minecraft/block/BlockState` | `net/minecraft/world/level/block/state/BlockState` | `net.minecraft.world.level.block.state.BlockState -> eoh:` |
| `net/minecraft/util/math/Vec3d` | `net/minecraft/world/phys/Vec3` | `net.minecraft.world.phys.Vec3 -> ftm:` |
| `net/minecraft/client/render/VertexConsumer` | `com/mojang/blaze3d/vertex/VertexConsumer` | `com.mojang.blaze3d.vertex.VertexConsumer -> fzp:` |
| `net/minecraft/entity/LivingEntity` | `net/minecraft/world/entity/LivingEntity` | `net.minecraft.world.entity.LivingEntity -> chl:` |

| Yarn member (obf descriptor) | official |
| --- | --- |
| `MinecraftClient#getInstance ()Lgfj;` | `Minecraft#getInstance ()Lnet/minecraft/client/Minecraft;` |
| `MinecraftClient#getWindow ()Lfyk;` | `Minecraft#getWindow ()Lcom/mojang/blaze3d/platform/Window;` |
| `MinecraftClient#player Lhnh;` | `Minecraft#player Lnet/minecraft/client/player/LocalPlayer;` |
| `MinecraftClient#getSession ()Lgfx;` | `Minecraft#getUser ()Lnet/minecraft/client/User;` |
| `Identifier#of (Ljava/lang/String;)Lamo;` | `Identifier#parse (Ljava/lang/String;)Lnet/minecraft/resources/Identifier;` |
| `Text#literal (Ljava/lang/String;)Lyw;` | `Component#literal (Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;` |
| `ItemStack#getCount ()I` | `ItemStack#getCount ()I` |
| `Blocks#STONE Ldzq;` | `Blocks#STONE Lnet/minecraft/world/level/block/Block;` |
| `PlayerEntity#getInventory ()Lddl;` | `Player#getInventory ()Lnet/minecraft/world/entity/player/Inventory;` |
| `World#setBlockState (Lis;Leoh;)Z` | `Level#setBlockAndUpdate (Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z` |

Additional independent verification performed when this table was created (not
part of the routine `--verify`, because it needs the 31 MB client jar):

```sh
# client.jar sha1 ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd from the 1.21.11 version json
curl -sSLo client.jar https://piston-data.mojang.com/v1/objects/ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd/client.jar
```

40 random Yarn classes (718 member rows) and then 150 random Yarn classes
(2 054 member rows) were unpacked from that jar and inspected with `javap -p -s`:
**every** `(obf name, obf descriptor)` pair in the member table exists in the
obfuscated jar (0 mismatches; the single exception in the 150-class sample was a
constructor of an unobfuscated `com/mojang/blaze3d` class whose javap spelling
differs, not a table error). A descriptor-level diff of `client.txt` against
`javap` for `BlockPos$MutableBlockPos`, `ListTag` and `ArrayListDeque` also
matched 1:1.

## Regenerating

```sh
# from anywhere; paths are resolved relative to the script
python3 tools/mappings/build-yarn-to-official.py            # build (uses .cache/, downloads only if missing)
python3 tools/mappings/build-yarn-to-official.py --verify   # build + spot checks + on-disk validation
python3 tools/mappings/build-yarn-to-official.py --refresh  # force re-download of all upstream files
```

The script is pure Python 3 standard library (no pip installs), re-runnable and
deterministic: downloads are cached under `tools/mappings/.cache/` (git-ignored),
the client.txt sha1 from the version manifest is verified, and all output rows
are written with a stable sort order (classes by Yarn name; members by Yarn
owner, name, descriptor). Expected exit code is `0` and the last line is `OK`.
