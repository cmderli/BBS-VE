package mchorse.bbs_mod.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Roll is how far into a glide or a riptide the entity is - vanilla counts it up a tick at a
 * time while flying, and the elytra's dive and the trident's spin are both driven off it.
 *
 * <p>An entity played back from a replay is placed frame by frame rather than flown, so nothing
 * counts for it. The count is recorded and handed back instead, which is also the only version
 * of it that survives scrubbing to an arbitrary tick.</p>
 */
@Mixin(LivingEntity.class)
public interface LivingEntityRollAccessor
{
    /* The field behind this has been renamed twice: fallFlyingTicks on 1.21.1, glidingTicks in the
     * Yarn names for 1.21.11, and fallFlyTicks in 26.2's official names. Aiming at a name that does
     * not exist is not a no-op - an accessor whose target is missing takes the client down at mixin
     * APPLY, which is exactly what an out-of-date name here does. */
    @Accessor("fallFlyTicks")
    public void bbs$setRoll(int roll);
}
