package mchorse.bbs_mod.forms.structure;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsed structure NBT (the vanilla structure block format): size + non-air blocks. Parsing is
 * done by hand instead of {@code StructureTemplate.place()} because placing requires a
 * {@code ServerWorldAccess} while we only need block states for client-side rendering.
 */
public class StructureRenderData
{
    public final String id;
    public final Vec3i size;

    /** Structure-local position → state, insertion order = file order. Air and structure void excluded. */
    private final Map<BlockPos, BlockState> blocks;

    /** Structure-local position → block entity NBT (chests, signs, beds, ...). */
    private final Map<BlockPos, CompoundTag> blockEntities;

    /** Traced on first use: light depends on the blocks alone, so it outlives biome changes and rebakes. */
    private StructureLighting lighting;

    private StructureRenderData(String id, Vec3i size, Map<BlockPos, BlockState> blocks, Map<BlockPos, CompoundTag> blockEntities)
    {
        this.id = id;
        this.size = size;
        this.blocks = Collections.unmodifiableMap(blocks);
        this.blockEntities = Collections.unmodifiableMap(blockEntities);
    }

    public Map<BlockPos, BlockState> getBlocks()
    {
        return this.blocks;
    }

    public Map<BlockPos, CompoundTag> getBlockEntities()
    {
        return this.blockEntities;
    }

    /** How this structure is lit — shared by both fake worlds, so they agree; see {@link StructureLighting}. */
    public StructureLighting getLighting()
    {
        if (this.lighting == null)
        {
            this.lighting = StructureLighting.compute(this);
        }

        return this.lighting;
    }

    public BlockState getBlockState(BlockPos pos)
    {
        BlockState state = this.blocks.get(pos);

        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    public boolean isEmpty()
    {
        return this.blocks.isEmpty();
    }

    public static StructureRenderData parse(String id, CompoundTag root)
    {
        /* 1.21.11 rebuilt the NBT getters: getList/getCompound/getInt take no element type any more
         * and return an Optional, each with a plain twin (getListOrEmpty / getCompoundOrEmpty /
         * getInt(key, default)). The type argument used to be the guard against a wrong-typed tag;
         * the empty/default value is that guard now, so the reads below keep their old outcome on a
         * malformed structure file. */
        ListTag sizeList = root.getListOrEmpty("size");
        Vec3i size = new Vec3i(sizeList.getIntOr(0, 0), sizeList.getIntOr(1, 0), sizeList.getIntOr(2, 0));

        ListTag paletteNbt;

        if (root.contains("palette"))
        {
            paletteNbt = root.getListOrEmpty("palette");
        }
        else
        {
            /* "palettes" variant: several random palettes, the first one is good enough */
            ListTag palettes = root.getListOrEmpty("palettes");

            paletteNbt = palettes.isEmpty() ? new ListTag() : palettes.getListOrEmpty(0);
        }

        BlockState[] palette = new BlockState[paletteNbt.size()];

        for (int i = 0; i < palette.length; i++)
        {
            /* Registry implements RegistryEntryLookup itself now — getReadOnlyWrapper() is gone. */
            palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, paletteNbt.getCompoundOrEmpty(i));
        }

        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        Map<BlockPos, CompoundTag> blockEntities = new LinkedHashMap<>();
        ListTag blocksNbt = root.getListOrEmpty("blocks");

        for (int i = 0; i < blocksNbt.size(); i++)
        {
            CompoundTag block = blocksNbt.getCompoundOrEmpty(i);
            int stateIndex = block.getIntOr("state", 0);

            if (stateIndex < 0 || stateIndex >= palette.length)
            {
                continue;
            }

            BlockState state = palette[stateIndex];

            if (state.isAir() || state.is(Blocks.STRUCTURE_VOID))
            {
                continue;
            }

            ListTag posList = block.getListOrEmpty("pos");
            BlockPos pos = new BlockPos(posList.getIntOr(0, 0), posList.getIntOr(1, 0), posList.getIntOr(2, 0));

            blocks.put(pos, state);

            block.getCompound("nbt").ifPresent((nbt) -> blockEntities.put(pos, nbt));
        }

        return new StructureRenderData(id, size, blocks, blockEntities);
    }
}
