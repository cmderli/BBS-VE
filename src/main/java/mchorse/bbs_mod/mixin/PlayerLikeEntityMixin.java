package mchorse.bbs_mod.mixin;

import mchorse.bbs_mod.morphing.MorphHitbox;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The morph's hitbox for players, which {@link LivingEntityMixin}'s hook cannot reach.
 *
 * <p>1.21.9 pushed a {@code PlayerLikeEntity} (now {@code Avatar}) in between {@code LivingEntity} and
 * {@code Player} with a straight lookup in its own pose table — no {@code super} call anywhere in it.
 * So the {@code LivingEntity} injection never influenced a player, and the form's hitbox settings did
 * nothing at all for the one entity they are used on most. On 1.21.1 the same gap existed one class
 * lower and was covered by the same hook in {@code PlayerEntityMixin}.</p>
 *
 * <p>The override is no longer on the method the {@code LivingEntity} hook targets. 26.2 splits the
 * two: {@code LivingEntity#getDimensions(Pose)} is the entry point that scales the result, and the
 * per-class pose table lives behind {@code getDefaultDimensions(Pose)}, which is what {@code Avatar}
 * actually overrides. Targeting {@code getDimensions} on {@code Avatar} finds no matching method
 * there - Mixin resolves selectors against the target class, not its supertypes - and fails the whole
 * mixin at APPLY. {@code getDefaultDimensions} also sits upstream of the scale multiply, which is
 * where a replacement box has to be returned anyway.</p>
 */
@Mixin(Avatar.class)
public class PlayerLikeEntityMixin
{
    @Inject(method = "getDefaultDimensions", at = @At("RETURN"), cancellable = true)
    public void onGetBaseDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> info)
    {
        EntityDimensions dimensions = MorphHitbox.override((LivingEntity) (Object) this, info.getReturnValue());

        if (dimensions != null)
        {
            info.setReturnValue(dimensions);
        }
    }
}
