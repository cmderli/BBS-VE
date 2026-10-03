package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.BBSModClient;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the record key stop a running export while a screen is open.
 *
 * <p>Without this there is no way out of a world recording once any screen is up. Minecraft routes key events
 * to the screen first, and {@code UIBaseMenu#handleKey} returns true for every event an enabled menu consumes,
 * so the event never reaches Minecraft's keybind handling and {@code keyRecordVideo.consumeClick()} cannot fire.
 * The only keys that still answered were the ones Minecraft handles itself at this level - Escape, F2, F3 -
 * which is exactly the reported symptom ("after pressing F4 only Esc, F2 and F3 work").</p>
 *
 * <p>Only acts while an export is running, so it never steals the key otherwise.</p>
 */
@Mixin(Screen.class)
public class ScreenKeyMixin
{
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void bbs$stopExportOnRecordKey(net.minecraft.client.input.KeyEvent event, CallbackInfoReturnable<Boolean> info)
    {
        int bound = KeyMappingHelper.getBoundKeyOf(BBSModClient.getKeyRecordVideo()).getValue();

        if (bound == org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN || event.key() != bound)
        {
            return;
        }

        if (BBSModClient.cancelAnyExport())
        {
            info.setReturnValue(true);
        }
    }
}
