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
import java.util.concurrent.atomic.AtomicBoolean;

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
    /** One-shot guard for the opt-in scissor diagnostic (see {@code -Dbbs.debugScissor=true}). */
    private static final AtomicBoolean WARNED = new AtomicBoolean();

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
        int x = (int) (rect.left() * scale);
        int y = (int) (height - rect.bottom() * scale);
        int w = Math.max(0, (int) (rect.width() * scale));
        int h = Math.max(0, (int) (rect.height() * scale));

        /* Clip the rectangle to the pass's own render area, which is the only bound that matters.
         *
         * Under GL this was free: an oversized scissor rectangle was silently clipped by the driver, so
         * the interface could hand over whatever it liked. 26.2's backend validates instead, and
         * RenderPass#enableScissor throws "Scissor at 0, 40 with size 854x404 is out of bounds for
         * render area RenderArea[x=0, y=0, width=504, height=248]" — from inside the GUI pass, so the
         * whole frame dies.
         *
         * The window is NOT the right bound here, which is why vanilla's own two Math.min calls against
         * it are not enough. While a BBS editor is open canReplaceFramebuffer() is false, so the Window
         * reports the real window size (854x480) and the ordinary GUI scale (2.0), while the pass draws
         * into the film preview block set by setCustomSize (504x248). Reading the render area off the
         * pass removes the guesswork: it is the bound the backend itself will enforce, and it is not a
         * fixed size. */
        RenderPass.RenderArea area = pass.renderArea;
        int areaX = area.x();
        int areaY = area.y();
        int areaMaxX = areaX + area.width();
        int areaMaxY = areaY + area.height();

        int minX = Math.max(areaX, x);
        int minY = Math.max(areaY, y);
        int maxX = Math.min(areaMaxX, x + w);
        int maxY = Math.min(areaMaxY, y + h);

        x = minX;
        y = minY;
        w = Math.max(0, maxX - minX);
        h = Math.max(0, maxY - minY);

        /* An empty intersection has to reach the backend rather than the public method: the public one
         * rejects a zero size outright ("Scissor size must be >0, was 504x0"), and so does vanilla's own
         * version, so there is no safe fallback to it — its scaling is the thing that was wrong to begin
         * with. The backends take an empty scissor and Vulkan reads it as "clip everything", which is
         * exactly what a clip rectangle lying entirely outside the render area means: this draw can show
         * nothing. Both calls were widened in bbs.accesswidener. */
        if (w <= 0 || h <= 0)
        {
            pass.backend.enableScissor(areaX, areaY, 0, 0);

            info.cancel();

            return;
        }

        /* Off by default. Which space this rectangle arrives in is not fully settled (see
         * bbs$scale()), and the clip above is what keeps it safe either way — so the numbers are worth
         * being able to print, but not worth printing on every launch. */
        if (Boolean.getBoolean("bbs.debugScissor") && WARNED.compareAndSet(false, true))
        {
            System.out.println("[BBS scissor] scale=" + scale
                + " area=" + areaX + "," + areaY + " " + area.width() + "x" + area.height()
                + " rect=" + rect.left() + "," + rect.bottom() + " " + rect.width() + "x" + rect.height()
                + " -> scissor=" + x + "," + y + " " + w + "x" + h);
        }

        pass.enableScissor(x, y, w, h);

        info.cancel();
    }
}
