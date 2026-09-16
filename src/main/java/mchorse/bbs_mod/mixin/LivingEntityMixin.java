package mchorse.bbs_mod.mixin;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.types.AttackActionClip;
import mchorse.bbs_mod.actions.types.item.ReleaseUseItemActionClip;
import mchorse.bbs_mod.morphing.MorphHitbox;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class LivingEntityMixin
{
    @Inject(method = "applyDamage", at = @At("HEAD"))
    public void onApplyDamage(ServerLevel world, DamageSource source, float amount, CallbackInfo info)
    {
        Entity attacker = source.getEntity();

        if (source.isDirect() && attacker != null && attacker.getClass() == ServerPlayer.class)
        {
            BBSMod.getActions().addAction((ServerPlayer) attacker, () ->
            {
                AttackActionClip clip = new AttackActionClip();

                clip.damage.set(amount);

                return clip;
            });
        }
    }

    @Inject(method = "getBaseDimensions", at = @At("RETURN"), cancellable = true)
    public void onGetBaseDimensions(CallbackInfoReturnable<EntityDimensions> info)
    {
        EntityDimensions dimensions = MorphHitbox.override((LivingEntity) (Object) this, info.getReturnValue());

        if (dimensions != null)
        {
            info.setReturnValue(dimensions);
        }
    }

    /**
     * The exact vanilla moment a drawn bow fires or a trident launches:
     * stopUsingItem() calls onStoppedUsing() with the remaining use ticks, so
     * this is where the release is recorded - with the very charge the take
     * had. The getClass() check keeps the playback fake player out.
     */
    @Inject(method = "stopUsingItem", at = @At("HEAD"))
    public void onStopUsingItem(CallbackInfo info)
    {
        if ((Object) this instanceof ServerPlayer player && player.getClass() == ServerPlayer.class)
        {
            ItemStack active = player.getUseItem();

            if (active.isEmpty())
            {
                return;
            }

            boolean mainHand = player.getUsedItemHand() == InteractionHand.MAIN_HAND;
            int charge = active.getUseDuration(player) - player.getUseItemRemainingTicks();
            ItemStack stack = active.copy();
            ItemStack recordedProjectile = player.getProjectile(active).copy();

            /* Creative players shoot without ammo and vanilla substitutes a
             * plain arrow, but the fake player has no creative mode - so the
             * substitute is baked into the clip instead */
            if (recordedProjectile.isEmpty() && stack.getItem() instanceof ProjectileWeaponItem && player.getAbilities().instabuild)
            {
                recordedProjectile = new ItemStack(Items.ARROW);
            }

            ItemStack projectile = recordedProjectile;

            /* TridentItem.onStoppedUsing's own fork, evaluated here because it
             * asks the WORLD, not the item: a riptide trident released in water
             * or rain launches its owner and is never thrown. The playback fake
             * player always stands dry, so the answer has to be recorded. */
            boolean riptide = charge >= 10 && EnchantmentHelper.getTridentSpinAttackStrength(active, player) > 0F && player.isInWaterOrRain();

            BBSMod.getActions().addAction(player, () ->
            {
                ReleaseUseItemActionClip clip = new ReleaseUseItemActionClip();

                clip.itemStack.set(stack);
                clip.hand.set(mainHand);
                clip.charge.set(charge);
                clip.projectile.set(projectile);
                clip.riptide.set(riptide);

                return clip;
            });
        }
    }

    /* @Inject(method = "swingHand(Lnet/minecraft/util/Hand;Z)V", at = @At("HEAD"), cancellable = true)
    public void onSwingHand(Hand hand, boolean fromServerPlayer, CallbackInfo info)
    {
        info.cancel();
    } */
}