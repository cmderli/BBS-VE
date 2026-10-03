package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.systems.RenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic-only: reports the render area every render pass is created with, so it is possible to see the
 * viewport the interface is actually drawn through.
 *
 * <p>This is the one piece the other probes could not see: they all measured the bound texture or the
 * projection, never the pass's own area. Enabled with {@code -Dbbs.debugRenderPass=true}.</p>
 */
@Mixin(RenderPass.class)
public class RenderPassProbeMixin
{
    private static int bbs$count;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbs$probeRenderArea(CallbackInfo info)
    {
        if (!Boolean.getBoolean("bbs.debugRenderPass") || bbs$count++ > 30)
        {
            return;
        }

        RenderPass pass = (RenderPass) (Object) this;
        RenderPass.RenderArea area = pass.renderArea;

        if (area == null)
        {
            return;
        }

        System.out.println("[BBS pass] area=" + area.x() + "," + area.y() + " " + area.width() + "x" + area.height());
    }
}
