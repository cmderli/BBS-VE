package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.forms.renderers.mob.MobRenderContext;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
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
        method = "render(Lnet/minecraft/client/render/command/OrderedRenderCommandQueueImpl$ModelCommand;Lnet/minecraft/client/render/RenderLayer;Lnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/client/render/OutlineVertexConsumerProvider;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;setAngles(Ljava/lang/Object;)V", shift = At.Shift.AFTER)
    )
    private void bbs$applyMobPose(SubmitNodeStorage.ModelSubmit<?> command, RenderType layer, VertexConsumer consumer, OutlineBufferSource outline, MultiBufferSource.BufferSource crumbling, CallbackInfo info)
    {
        MobRenderContext context = MobRenderContext.current();

        if (context != null)
        {
            context.applyPose();
        }
    }

    @Inject(
        method = "render(Lnet/minecraft/client/render/command/OrderedRenderCommandQueueImpl$ModelCommand;Lnet/minecraft/client/render/RenderLayer;Lnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/client/render/OutlineVertexConsumerProvider;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;)V",
        at = @At("TAIL")
    )
    private void bbs$restoreMobPose(SubmitNodeStorage.ModelSubmit<?> command, RenderType layer, VertexConsumer consumer, OutlineBufferSource outline, MultiBufferSource.BufferSource crumbling, CallbackInfo info)
    {
        MobRenderContext context = MobRenderContext.current();

        if (context != null)
        {
            context.restorePose();
        }
    }
}
