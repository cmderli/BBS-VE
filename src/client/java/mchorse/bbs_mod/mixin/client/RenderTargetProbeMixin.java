package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Diagnostic-only: records every render target the engine hands out, with the first BBS caller in
 * the stack, so it is possible to see WHICH target the interface is drawn into.
 *
 * <p>Enabled with {@code -Dbbs.debugRenderTarget=true}, and it stops after 40 reports so a launch
 * does not fill the log.</p>
 */
@Mixin(GameRenderer.class)
public class RenderTargetProbeMixin
{
    private static int bbs$count;
    private static String bbs$last = "";

    @Inject(method = "mainRenderTarget", at = @At("RETURN"))
    private void bbs$probeMainRenderTarget(CallbackInfoReturnable<RenderTarget> info)
    {
        if (!Boolean.getBoolean("bbs.debugRenderTarget"))
        {
            return;
        }

        /* Report only when the size CHANGES, or every 200 calls, so a long run stays readable. */
        boolean periodic = bbs$count++ % 200 == 0;

        RenderTarget target = info.getReturnValue();
        String shape = target == null ? "null" : target.width + "x" + target.height;

        if (!periodic && shape.equals(bbs$last))
        {
            return;
        }

        bbs$last = shape;
        String caller = "?";

        for (StackTraceElement frame : new Throwable().getStackTrace())
        {
            if (frame.getClassName().startsWith("mchorse.bbs_mod"))
            {
                caller = frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)
                    + "." + frame.getMethodName() + ":" + frame.getLineNumber();

                break;
            }
        }

        System.out.println("[BBS rt] main=" + (target == null ? "null" : target.width + "x" + target.height)
            + " mcMainRenderTarget=" + (Minecraft.getInstance().gameRenderer.mainRenderTarget() == target)
            + " caller=" + caller);
    }
}
