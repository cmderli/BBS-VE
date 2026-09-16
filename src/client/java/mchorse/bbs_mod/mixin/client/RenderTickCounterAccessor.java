package mchorse.bbs_mod.mixin.client;

import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DeltaTracker.Timer.class)
public interface RenderTickCounterAccessor
{
    @Accessor("deltaTickResidual")
    public float bbs$getTickDelta();
}