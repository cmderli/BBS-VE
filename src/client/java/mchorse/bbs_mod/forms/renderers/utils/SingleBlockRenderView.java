package mchorse.bbs_mod.forms.renderers.utils;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * A world of exactly one block, sitting at {@link BlockPos#ORIGIN} with air all around it.
 *
 * <p>Fluids are the one thing vanilla never bakes into a block model: a water or lava state
 * renders as {@link net.minecraft.block.BlockRenderType#INVISIBLE}, and its geometry gets
 * generated per chunk section by {@link net.minecraft.client.render.block.FluidRenderer},
 * which needs a world to ask about neighbours, light and the biome tint. A form has no world,
 * so it hands the fluid renderer this one: air on every side (so every face draws), the
 * form's own packed light, and the biome tint read from wherever the camera stands.</p>
 *
 * <p>The origin is not arbitrary — the fluid renderer emits vertices at
 * {@code pos.getX() & 15}, chunk local coordinates, so only a position at a section corner
 * puts the block into 0..1.</p>
 */
public class SingleBlockRenderView implements BlockAndTintGetter
{
    /** Water tint of the plains biome — the fallback when there is no world to sample. */
    private static final int DEFAULT_TINT = 0x3F76E4;

    private BlockState state = Blocks.AIR.defaultBlockState();
    private FluidState fluidState = Fluids.EMPTY.defaultFluidState();
    private int light;

    public SingleBlockRenderView set(BlockState state, int light)
    {
        this.state = state;
        this.fluidState = state.getFluidState();
        this.light = light;

        return this;
    }

    @Override
    public BlockState getBlockState(BlockPos pos)
    {
        return pos.equals(BlockPos.ZERO) ? this.state : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos)
    {
        return pos.equals(BlockPos.ZERO) ? this.fluidState : Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos)
    {
        return null;
    }

    @Override
    public CardinalLighting cardinalLighting()
    {
        /* 26.2 replaced BlockAndTintGetter#getShade(Direction, boolean) with the baked
         * CardinalLighting, and the values the fluid renderer used before are exactly the
         * defaults (CardinalLighting.DEFAULT = down 0.5, up 1.0, north/south 0.8, west/east 0.6),
         * so the old switch is now this constant. */
        return CardinalLighting.DEFAULT;
    }

    @Override
    public LevelLightEngine getLightEngine()
    {
        /* Never reached: both light lookups below are overridden, and they are the only
         * things that would go through a lighting provider. */
        return null;
    }

    @Override
    public int getBrightness(LightLayer type, BlockPos pos)
    {
        return type == LightLayer.SKY ? (this.light >> 20) & 0xF : (this.light >> 4) & 0xF;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int ambientDarkness)
    {
        return Math.max(this.getBrightness(LightLayer.SKY, pos) - ambientDarkness, this.getBrightness(LightLayer.BLOCK, pos));
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver)
    {
        ClientLevel world = Minecraft.getInstance().level;

        if (world != null)
        {
            /* The form has no place in the world of its own — biome tint comes from where
             * the camera is, so a water form matches the water it stands next to. */
            return world.getBlockTint(BlockPos.containing(Minecraft.getInstance().gameRenderer.mainCamera().position()), colorResolver);
        }

        return DEFAULT_TINT;
    }

    @Override
    public int getHeight()
    {
        return 384;
    }

    @Override
    public int getMinY()
    {
        return -64;
    }
}
