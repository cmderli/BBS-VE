package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.ui.dashboard.DashboardWarmup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelLoadingScreen.class)
public class LevelLoadingScreenMixin
{
    @Inject(method = "onClose", at = @At("HEAD"), cancellable = true)
    private void waitForDashboard(CallbackInfo ci)
    {
        /* Keep rendering the loading screen between build steps. Finishing synchronously
         * here would freeze the last frame instead. Vanilla retries close next tick. */
        if (DashboardWarmup.shouldKeepLoading(Minecraft.getInstance()))
        {
            ci.cancel();
        }
    }
}
