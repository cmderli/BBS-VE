package mchorse.bbs_mod.forms.structure;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;

/**
 * Virtual world that feeds vanilla block/fluid renderers from a {@link StructureRenderData}:
 * neighbor lookups make smooth AO work, {@link #getColor} resolves grass/foliage/water tint
 * against a SELECTED biome (the form's "biome" property) instead of a real-world position.
 *
 * <p>Light comes from {@link StructureLighting}: constant full skylight, and block light traced
 * through the structure from its own emitters.</p>
 */
public class StructureRenderWorld implements BlockAndTintGetter
{
    private final StructureRenderData data;
    private final Biome biome;
    private final LevelLightEngine lighting;

    public StructureRenderWorld(StructureRenderData data, String biomeId)
    {
        this.data = data;
        this.biome = resolveBiome(biomeId);

        /* Never queried for actual light (getLightLevel is overridden), but BlockRenderView
         * requires a non-null provider for default methods */
        this.lighting = new LevelLightEngine(new LightChunkGetter()
        {
            @Nullable
            @Override
            public LightChunk getChunkForLighting(int chunkX, int chunkZ)
            {
                return null;
            }

            @Override
            public BlockGetter getLevel()
            {
                return StructureRenderWorld.this;
            }
        }, false, false);
    }

    private static Biome resolveBiome(String id)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null)
        {
            return null;
        }

        /* 1.21.11: DynamicRegistryManager.get() is gone; getOrThrow is the direct replacement here
         * (the biome registry is always present on a loaded client world). */
        Registry<Biome> registry = mc.level.registryAccess().lookupOrThrow(Registries.BIOME);
        Identifier identifier = Identifier.tryParse(id == null ? "" : id);
        Biome biome = identifier == null ? null : registry.getValue(identifier);

        if (biome == null)
        {
            biome = registry.getValue(Biomes.PLAINS);
        }

        if (biome == null)
        {
            for (Biome b : registry)
            {
                return b;
            }
        }

        return biome;
    }

    /* 26.2: the directional face shade lives on CardinalLighting now; getShade(Direction, boolean)
     * is gone, and CardinalLighting.DEFAULT holds exactly the overworld values this used to
     * return for shaded == true (0.5 / 1.0 / 0.8 / 0.6). */
    @Override
    public CardinalLighting cardinalLighting()
    {
        return CardinalLighting.DEFAULT;
    }

    @Override
    public LevelLightEngine getLightEngine()
    {
        return this.lighting;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver)
    {
        if (this.biome == null)
        {
            return -1;
        }

        return colorResolver.getColor(this.biome, pos.getX(), pos.getZ());
    }

    @Override
    public int getBrightness(LightLayer type, BlockPos pos)
    {
        return this.data.getLighting().getLightLevel(type, pos);
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos)
    {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos)
    {
        return this.data.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos)
    {
        return this.getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight()
    {
        return Math.max(this.data.size.getY(), 16);
    }

    @Override
    public int getMinY()
    {
        return 0;
    }
}
