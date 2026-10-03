package mchorse.bbs_mod.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.types.blocks.PlaceBlockActionClip;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public class BlockItemMixin
{
    /* The descriptor was still Yarn's: ItemPlacementContext is now BlockPlaceContext, and the
     * method returns InteractionResult rather than boolean in 26.2. With no refmap in the jar
     * (26.2 is unobfuscated, so there is nothing to remap) Mixin matches this string literally,
     * which makes a stale descriptor here a failure at APPLY rather than a no-op.
     *
     * The block state is no longer a parameter. Yarn's placedState(BlockPlaceContext) argument was
     * 26.2's BlockItem#getPlacementState, and place() now calls it internally. Rather than
     * recompute it, read the state vanilla ends up writing: place() calls
     * Level#getBlockState(clickedPos) into a local right after placeBlock() and then hands that
     * same local to updateBlockStateFromTag / updateCustomBlockEntityTag / updateBlockEntityComponents.
     * Recording at RETURN therefore captures the block as it was actually placed, data included,
     * and skipping the FAIL early-returns means a placement that never happened is never filmed. */
    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;", at = @At("RETURN"))
    public void onPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> info, @Local(index = 8) BlockState state)
    {
        if (context.getPlayer() instanceof ServerPlayer player)
        {
            BBSMod.getActions().addAction(player, () ->
            {
                PlaceBlockActionClip clip = new PlaceBlockActionClip();
                BlockPos pos = context.getClickedPos();

                clip.x.set(pos.getX());
                clip.y.set(pos.getY());
                clip.z.set(pos.getZ());
                clip.state.set(state);

                return clip;
            });
        }
    }
}