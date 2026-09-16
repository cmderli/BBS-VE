package mchorse.bbs_mod.mixin;

import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WalkAnimationState.class)
public interface LimbAnimatorAccessor
{
    @Accessor("lastSpeed")
    public float getPrevSpeed();

    @Accessor("lastSpeed")
    public void setPrevSpeed(float v);

    @Accessor("speed")
    public float getSpeed();

    @Accessor("speed")
    public void setSpeed(float v);

    @Accessor("animationProgress")
    public float getPos();

    @Accessor("animationProgress")
    public void setPos(float v);
}