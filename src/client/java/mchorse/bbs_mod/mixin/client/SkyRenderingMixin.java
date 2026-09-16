package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.client.BBSRendering;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(SkyRenderer.class)
public class SkyRenderingMixin
{
    @ModifyConstant(method = "renderSunMoonAndStars", constant = @Constant(floatValue = -90F))
    private float rotateSunHorizontally(float rotation)
    {
        return rotation + BBSRendering.getSunHorizontalRotation();
    }
}
