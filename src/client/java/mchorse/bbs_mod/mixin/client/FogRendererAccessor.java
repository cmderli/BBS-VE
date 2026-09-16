package mchorse.bbs_mod.mixin.client;

import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(FogRenderer.class)
public interface FogRendererAccessor
{
    @Accessor("FOG_ENVIRONMENTS")
    static List<FogEnvironment> bbs$getFogModifiers()
    {
        throw new UnsupportedOperationException();
    }
}
