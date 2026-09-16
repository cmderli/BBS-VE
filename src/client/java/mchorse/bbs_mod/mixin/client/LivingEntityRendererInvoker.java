package mchorse.bbs_mod.mixin.client;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererInvoker
{
    @Invoker("getAnimationCounter")
    float bbs$getAnimationCounter(LivingEntityRenderState state);

    /* State-based since 1.21.2: the entity and the tick delta are already baked into the render
     * state, and the scale attribute rides along on it too — hence four arguments, not six.
     * Hand swing has no accessor left at all; it is a plain field on the state. */
    @Invoker("setupTransforms")
    void bbs$setupTransforms(LivingEntityRenderState state, PoseStack matrices, float animationProgress, float bodyYaw);

    @Invoker("scale")
    void bbs$scale(LivingEntityRenderState state, PoseStack matrices);
}
