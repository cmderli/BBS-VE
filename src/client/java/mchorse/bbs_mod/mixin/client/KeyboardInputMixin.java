package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIScreen;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.Options;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput
{
    /* NOTE(1.21.11 port): movementVector is declared in the superclass Input (this mixin's declared
     * parent), so it is a normal inherited protected field accessed directly — NOT @Shadow. Mixin only
     * attaches @Shadow members found in the target class, and this field lives in the mixin's superclass. */

    private static float getMovementMultiplier(boolean positive, boolean negative)
    {
        return positive == negative ? 0F : (positive ? 1F : -1F);
    }

    /**
     * Whether the key this binding is bound to is down right now.
     *
     * <p>Asked of the window rather than of the binding, because a screen is open the whole time
     * an actor is puppeteered (the film editor is one) and vanilla stops updating its bindings
     * while a screen holds the keyboard. That is why this used to read W/A/S/D straight from GLFW
     * &mdash; which also meant a rebound layout steered nothing at all.
     */
    private static boolean isBoundKeyDown(KeyMapping binding)
    {
        InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(binding);

        if (key.getValue() == GLFW.GLFW_KEY_UNKNOWN)
        {
            return false;
        }

        if (key.getType() == InputConstants.Type.MOUSE)
        {
            return GLFW.glfwGetMouseButton(Window.getWindow(), key.getValue()) == GLFW.GLFW_PRESS;
        }

        return key.getType() == InputConstants.Type.KEYSYM && Window.isKeyPressed(key.getValue());
    }

    @Inject(method = "tick", at = @At("RETURN"))
    public void onTick(CallbackInfo info)
    {
        UIBaseMenu menu = UIScreen.getCurrentMenu();

        if (!(menu instanceof UIDashboard dashboard) || !(dashboard.getPanels().panel instanceof UIFilmPanel filmPanel))
        {
            return;
        }

        /* canControl, not isControlling: an overlay over the preview takes the input for itself,
         * and the actor used to keep walking on the last keys it saw while its author was busy in
         * a popup. The mouse already stood down for an overlay; the keyboard did not. */
        if (!filmPanel.getController().canControl())
        {
            return;
        }

        Options options = Minecraft.getInstance().options;

        boolean forward = isBoundKeyDown(options.keyUp);
        boolean back = isBoundKeyDown(options.keyDown);
        boolean left = isBoundKeyDown(options.keyLeft);
        boolean right = isBoundKeyDown(options.keyRight);
        boolean jump = isBoundKeyDown(options.keyJump);
        boolean sneak = isBoundKeyDown(options.keyShift);

        /* Sprinting is not part of the input vanilla reads here — the player's own tick asks the
         * binding itself — so the binding is what has to be told, or a take could never record a
         * sprint at all. */
        options.keySprint.setDown(isBoundKeyDown(options.keySprint));

        this.keyPresses = new Input(forward, back, left, right, jump, sneak, this.keyPresses.sprint());
        this.moveVector = new Vec2(getMovementMultiplier(left, right), getMovementMultiplier(forward, back)).normalized();
    }
}
