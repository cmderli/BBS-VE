package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.client.renderer.MorphRenderer;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mark the span in which an entity is drawn into the GUI (the inventory's player window and anything
 * else using this renderer), so a morph is drawn there and then instead of being queued for a world
 * pass that will not come — see {@link MorphRenderer#setGuiPass}.
 *
 * <p>The span is closed in a {@code finally}-equivalent pair of injections rather than a single
 * wrapper because vanilla's own render must keep running between them: this hook decides nothing, it
 * only says where we are.</p>
 */
@Mixin(GuiEntityRenderer.class)
public class EntityGuiElementRendererMixin
{
    @Inject(
        method = "renderToTexture(Lnet/minecraft/client/renderer/state/gui/pip/GuiEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
        at = @At("HEAD")
    )
    private void bbs$guiEntityBegin(GuiEntityRenderState state, PoseStack matrices, SubmitNodeCollector collector, CallbackInfo info)
    {
        MorphRenderer.setGuiPass(true);
    }

    @Inject(
        method = "renderToTexture(Lnet/minecraft/client/renderer/state/gui/pip/GuiEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
        at = @At("RETURN")
    )
    private void bbs$guiEntityEnd(GuiEntityRenderState state, PoseStack matrices, SubmitNodeCollector collector, CallbackInfo info)
    {
        MorphRenderer.setGuiPass(false);
    }
}
