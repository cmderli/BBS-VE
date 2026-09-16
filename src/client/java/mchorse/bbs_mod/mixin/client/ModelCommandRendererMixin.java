package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.forms.renderers.mob.MobRenderContext;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the BBS mob-form pose to a queued model command.
 *
 * <p>On 1.21.1 the pose hook lived in {@code LivingEntityRenderer.render} right after its
 * immediate {@code setAngles} call. The 1.21.2+ command queue moved that call to FLUSH time:
 * {@link ModelCommandRenderer} calls {@code Model.setAngles(state)} when the command renders
 * (verified against the 1.21.11 bytecode), so any part mutation made at submit time would be
 * overwritten. This hook fires right after that setAngles and restores the parts when the
 * command is done, so the shared vanilla model instances stay clean.
 *
 * <p>Active only while a mob form is mid-flush (it publishes itself in {@link MobRenderContext});
 * the world's own dispatcher never runs inside that window, so vanilla mobs are unaffected. Parts
 * outside the form's rig — armor, a held item, a model built outside the named-children path —
 * are not in the rig and pass through untouched.
 */
@Mixin(ModelFeatureRenderer.class)
public class ModelCommandRendererMixin
{
    @Inject(
        method = "prepareModel(Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$Submit;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V", shift = At.Shift.AFTER)
    )
    private void bbs$applyMobPose(ModelFeatureRenderer.Submit<?> command, CallbackInfo info)
    {
        MobRenderContext context = MobRenderContext.current();

        if (context != null)
        {
            context.applyPose();
        }
    }

    @Inject(
        method = "prepareModel(Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$Submit;)V",
        at = @At("TAIL")
    )
    private void bbs$restoreMobPose(ModelFeatureRenderer.Submit<?> command, CallbackInfo info)
    {
        MobRenderContext context = MobRenderContext.current();

        if (context != null)
        {
            context.restorePose();
        }
    }
}
