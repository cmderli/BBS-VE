package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The REAL framebuffer size, untouched by {@link WindowMixin}'s own getWidth/getHeight overrides.
 *
 * <p>BBS binds those two to the export size while the world renders, so anything that sizes a render target from
 * them gets the export size instead of the window's. That is how every BBS target ended up at 1280x720, 854x480
 * or 1279x718 while the window was 2560x1350 - and why the GUI render pass was created with an 854x480 area the
 * whole interface was then fitted into.</p>
 *
 * <p>An accessor interface rather than static helpers on the mixin: Mixin refuses non-private static methods in
 * a mixin class ("contains non-private static method"), which is the same rule that makes BBS keep its flag
 * constants on EntityState rather than on the interface mixin.</p>
 */
@Mixin(Window.class)
public interface WindowFramebufferAccessor
{
    @Accessor("framebufferWidth")
    int bbs$getFramebufferWidth();

    @Accessor("framebufferHeight")
    int bbs$getFramebufferHeight();
}
