package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.client.BBSRendering;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops vanilla from resizing the render target back to a stale cached size while BBS is sizing it.
 *
 * <p>{@code GameRenderer.render} does this:</p>
 *
 * <pre>
 * if (windowRenderState.width != mainRenderTarget.width || windowRenderState.height != mainRenderTarget.height)
 *     this.resize(windowRenderState.width, windowRenderState.height);
 * </pre>
 *
 * <p>It notices that the target and the cache disagree, and then resizes the target to the CACHED size. BBS
 * sizes its targets directly — the world/export target, and the client target when it re-binds it — so the cache
 * holds the previous size and this silently undoes the resize on the next frame. Measured with
 * {@code -Dbbs.debugRenderTarget}: the target flipped between 1280x720 and 2560x1350 every frame, and the
 * interface was extracted into whichever was current. Once the window is larger than the export size that put
 * the whole dashboard into the export-sized target.</p>
 *
 * <p>While BBS owns the sizes this call is wrong by definition — the cache is not the authority — so it is
 * skipped. Outside that (no custom size) the original call runs unchanged.</p>
 */
@Mixin(GameRenderer.class)
public class GameRendererResizeMixin
{
    @Redirect(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;resize(II)V")
    )
    private void bbs$skipStaleResize(GameRenderer renderer, int width, int height)
    {
        if (BBSRendering.isCustomSize())
        {
            return;
        }

        renderer.resize(width, height);
    }
}
