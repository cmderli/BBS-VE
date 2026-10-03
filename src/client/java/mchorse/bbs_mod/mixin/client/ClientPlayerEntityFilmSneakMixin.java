package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.film.FilmPlayerPose;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep keyboard input from replacing the film's crouch between world ticks. */
@Mixin(LocalPlayer.class)
public class ClientPlayerEntityFilmSneakMixin
{
    /**
     * The raw id, not {@code getId()}.
     *
     * <p>26.2 made {@code Entity#getId()} throw {@code IllegalStateException("Tried to access entity
     * ID before ID assignment")} while the id is still {@code INVALID_ENTITY_ID}, and an id is only
     * assigned when the entity is added to a level. These two hooks run on the render path -
     * {@code Minecraft.renderFrame} → {@code LocalPlayer.raycastHitResult} → {@code isShiftKeyDown} -
     * and the client does that with a local player that is not in any level yet, on the main menu,
     * so the throw took the whole game down before the title screen appeared. Reading the field is
     * also the honest question here: "is there a film pose for this entity" can only be answered for
     * an entity that has an id, and -1 means there is none to look up.</p>
     *
     * <p>Read through the access widener rather than a {@code @Shadow} field: {@code id} is declared
     * on {@code Entity}, and Mixin resolves shadowed members against the target class itself, so a
     * shadow here fails at APPLY with "was not located in the target class LocalPlayer".</p>
     */
    private FilmPlayerPose bbs$filmPose()
    {
        Entity entity = (Entity) (Object) this;

        return entity.id == Entity.INVALID_ENTITY_ID ? null : FilmPlayerPose.get(entity.id);
    }

    @Inject(method = "isShiftKeyDown", at = @At("HEAD"), cancellable = true)
    private void bbsFilmIsSneaking(CallbackInfoReturnable<Boolean> info)
    {
        FilmPlayerPose pose = this.bbs$filmPose();

        if (pose != null)
        {
            info.setReturnValue(pose.sneaking());
        }
    }

    @Inject(method = "isCrouching", at = @At("HEAD"), cancellable = true)
    private void bbsFilmIsInSneakingPose(CallbackInfoReturnable<Boolean> info)
    {
        FilmPlayerPose pose = this.bbs$filmPose();

        if (pose != null)
        {
            info.setReturnValue(pose.pose() == Pose.CROUCHING);
        }
    }
}
