package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.systems.RenderPass;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.render.special.BbsFormGuiElementRenderer;
import mchorse.bbs_mod.ui.utils.InterfaceBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Inject BBS's own {@link SpecialGuiElementRenderer} into the otherwise-CLOSED special-element registry.
 * Vanilla freezes the {@code List<SpecialGuiElementRenderer<?>>} constructor argument into an ImmutableMap
 * keyed by {@code getElementClass()} ({@code GuiRenderer.<init>}), with no Fabric hook. We widen the
 * (immutable) {@code List.of(...)} into a mutable copy at HEAD of the constructor and append our renderer,
 * built from the shared {@code VertexConsumerProvider.Immediate} — the same instance vanilla passes to every
 * built-in renderer (GameRenderer uses {@code buffers.getEntityVertexConsumers()}; we fetch the identical
 * object via {@code client.getBufferBuilders().getEntityVertexConsumers()}).
 */
@Mixin(GuiRenderer.class)
public class GuiRendererMixin
{
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static List<PictureInPictureRenderer<?>> bbs$addBbsRenderers(List<PictureInPictureRenderer<?>> original)
    {
        List<PictureInPictureRenderer<?>> list = new ArrayList<>(original);

        list.add(new BbsFormGuiElementRenderer());

        return list;
    }

    /**
     * How much of the framebuffer one GUI unit covers, while BBS's UI is on screen.
     *
     * <p>Vanilla asks the window for an {@code int} here — the two places below are where that rounding
     * would otherwise undo BBS's fractional ui_scale: the projection decides how many GUI units the
     * screen is wide, the scissor decides where a clipped element's edges land in pixels. They have to
     * agree with the scaled size {@code WindowMixin} wrote, or the interface is laid out at one scale
     * and drawn at another.</p>
     *
     * @return the fractional scale, or 0 when BBS is not driving it (leave vanilla alone).
     */
    private static float bbs$scale()
    {
        return BBSModClient.getCustomGUIScale() > 0F ? BBSModClient.getGUIScale() : 0F;
    }

    @Redirect(
        method = "draw",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Projection;setupOrtho(FFFFZ)V")
    )
    private void bbs$guiProjection(Projection projection, float zNear, float zFar, float width, float height, boolean invertY)
    {
        float scale = bbs$scale();

        if (scale <= 0F)
        {
            projection.setupOrtho(zNear, zFar, width, height, invertY);

            return;
        }

        Window window = Minecraft.getInstance().getWindow();

        projection.setupOrtho(zNear, zFar, window.getWidth() / scale, window.getHeight() / scale, invertY);
    }

    /**
     * The blur slot of the deferred GUI: vanilla composites the layers up to the one
     * {@code DrawContext.applyBlur()} marked, blurs the framebuffer here, then draws the rest.
     * BBS marks that layer under its overlays and dashboard tint ({@link InterfaceBlur#apply}),
     * so its own box blur with the live radius runs in the slot; frames without a BBS mark
     * (vanilla screens, pause menu) keep vanilla's blur.
     */
    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V"))
    private void bbs$interfaceBlur(GameRenderer renderer)
    {
        if (!InterfaceBlur.render())
        {
            renderer.processBlurEffect();
        }
    }

    @Inject(method = "enableScissor", at = @At("HEAD"), cancellable = true)
    private void bbs$fractionalScissor(ScreenRectangle rect, RenderPass pass, CallbackInfo info)
    {
        float scale = bbs$scale();

        if (scale <= 0F)
        {
            return;
        }

        Window window = Minecraft.getInstance().getWindow();
        int height = window.getHeight();

        /* Vanilla's own arithmetic with a float scale: bottom-left origin, and a rect that rounds down
         * to nothing still clamps to zero rather than going negative. */
        pass.enableScissor(
            (int) (rect.left() * scale),
            (int) (height - rect.bottom() * scale),
            Math.max(0, (int) (rect.width() * scale)),
            Math.max(0, (int) (rect.height() * scale)));

        info.cancel();
    }
}
