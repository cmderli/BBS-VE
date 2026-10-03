package mchorse.bbs_mod.mixin;

import com.mojang.brigadier.ParseResults;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.types.blocks.InteractBlockActionClip;
import mchorse.bbs_mod.actions.types.chat.CommandActionClip;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerPlayNetworkHandlerMixin
{
    @Shadow
    public ServerPlayer player;

    /* parseCommand is what both command entry points call - performUnsignedChatCommand for a
     * command typed in chat and performSignedChatCommand for a signed one - exactly once each
     * before Commands.performCommand. Watching it therefore records each executed command once,
     * which is what the original target did. The returns type stays raw because that is how the
     * method is declared. */
    @Inject(method = "parseCommand", at = @At("HEAD"))
    public void onParse(String command, CallbackInfoReturnable<ParseResults> info)
    {
        BBSMod.getActions().addAction(this.player, () ->
        {
            CommandActionClip clip = new CommandActionClip();

            clip.command.set(command);

            return clip;
        });
    }

    /* The handler is official handleUseItemOn(ServerboundUseItemOnPacket), and the implementation
     * it delegates to is official ServerPlayerGameMode#useItemOn. Both names in this target were
     * still Yarn (onPlayerInteractBlock, ServerPlayerInteractionManager#interactBlock) and every
     * type in the descriptor had moved, so the whole string was rebuilt from 26.2 bytecode. */
    @Redirect(method = "handleUseItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayerGameMode;useItemOn(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult redirectOnBlockInteract(ServerPlayerGameMode manager, ServerPlayer player, Level world, ItemStack stack, InteractionHand hand, BlockHitResult hitResult)
    {
        BBSMod.getActions().addAction(this.player, () ->
        {
            InteractBlockActionClip clip = new InteractBlockActionClip();

            clip.hit.setHitResult(hitResult);
            clip.hand.set(hand == InteractionHand.MAIN_HAND);

            return clip;
        });

        return manager.useItemOn(player, world, stack, hand, hitResult);
    }
}