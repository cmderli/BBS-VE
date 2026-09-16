package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.forms.structure.StructureWand;
import mchorse.bbs_mod.graphics.window.Window;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseMixin
{
    @Inject(method = "onScroll", at = @At("HEAD"))
    public void mouseScroll(long window, double horizontal, double vertical, CallbackInfo ci)
    {
        if (window == Window.getWindow())
        {
            Window.setVerticalScroll((int) vertical);
        }
    }

    /** A notch the structure wand spends on its box must not reach the hotbar. */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    public void wandScroll(long window, double horizontal, double vertical, CallbackInfo ci)
    {
        if (window == Window.getWindow() && StructureWand.onScroll(vertical))
        {
            ci.cancel();
        }
    }
}
