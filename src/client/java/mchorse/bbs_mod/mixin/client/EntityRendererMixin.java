package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.client.renderer.IRenderStateEntityHolder;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.MobForm;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin
{
    @Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"), cancellable = true)
    public void onRenderLabelIfPresent(CallbackInfo info)
    {
        if (FormUtilsClient.getCurrentForm() instanceof MobForm form && form.isPlayer())
        {
            info.cancel();
        }
    }

    /**
     * Stash the live entity + tickDelta onto the render state so the morph path can recover them
     * (the 1.21.2 render-state split stopped threading the entity through render()).
     * See {@link IRenderStateEntityHolder}.
     */
    @Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("RETURN"))
    private void bbs$stashRenderedEntity(Entity entity, float tickDelta, CallbackInfoReturnable<EntityRenderState> info)
    {
        EntityRenderState state = info.getReturnValue();

        if (state instanceof IRenderStateEntityHolder holder)
        {
            holder.bbs$setRenderedEntity(entity, tickDelta);
        }
    }
}