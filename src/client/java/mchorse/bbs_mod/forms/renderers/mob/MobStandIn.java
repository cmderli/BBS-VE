package mchorse.bbs_mod.forms.renderers.mob;

import com.mojang.authlib.GameProfile;
import mchorse.bbs_mod.forms.entities.EntityState;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.mixin.EntityInvoker;
import mchorse.bbs_mod.mixin.LimbAnimatorAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * A vanilla entity standing in for a form's actor, so vanilla's own model code can be run for it.
 *
 * <p>The mob form renders through the game's entity renderer, and a CEM model runs the vanilla model's
 * animation under its pack's program ({@code CemVanillaStage}); both need a real {@link Entity} of the
 * right kind that vanilla's code will accept, posed the way the form's actor is. This makes one — an
 * entity type's own instance in the client world, or a player for the player's model — and keeps it in
 * step with the actor tick by tick: the limbs, the hand swing, head and body yaw, the position, the
 * flags vanilla's animation reads, the equipment, the age. The entity is remade when what it is made
 * from changes.</p>
 */
public class MobStandIn
{
    public static final GameProfile WIDE = new GameProfile(UUID.fromString("b99a2400-28a8-4288-92dc-924beafbf756"), "McHorseYT");
    public static final GameProfile SLIM = new GameProfile(UUID.fromString("5477bd28-e672-4f87-a209-c03cf75f3606"), "osmiq");

    private Entity entity;

    private String lastId = "";
    private String lastNbt = "";
    private boolean lastSlim;

    private float prevHandSwing;
    private float prevYawHead;
    private float prevPitch;

    /** The entity standing in, or null while there is none — see {@link #ensure}. */
    public Entity entity()
    {
        return this.entity;
    }

    /**
     * The entity for an id, made on the first ask and remade when the id, the NBT or the arm width
     * changes. Null when the game has no world to make it in, or no such kind of entity: an id the
     * registry does not know is not the pig it answers with by default. The player has no entity type
     * to create from — asked for one ({@code player}), a player of the given arm width stands in.
     */
    public Entity ensure(String id, String nbt, boolean slim, boolean player)
    {
        if (!this.lastId.equals(id) || !this.lastNbt.equals(nbt) || slim != this.lastSlim)
        {
            this.lastId = id;
            this.lastNbt = nbt;
            this.lastSlim = slim;
            this.entity = null;
        }

        ClientLevel world = Minecraft.getInstance().level;

        if (this.entity != null || world == null)
        {
            return this.entity;
        }

        CompoundTag compound = new CompoundTag();

        try
        {
            /* 1.21.5: new StringNbtReader(StringReader).parseCompound() -> StringNbtReader.readCompound(String). */
            compound = TagParser.parseCompoundFully(nbt);
        }
        catch (Exception e)
        {}

        Identifier identifier = Identifier.tryBuild(id);
        EntityType<?> type = identifier != null && BuiltInRegistries.ENTITY_TYPE.containsKey(identifier) ? BuiltInRegistries.ENTITY_TYPE.get(identifier) : null;

        /* 1.21.2: EntityType.create(World) -> create(World, SpawnReason). */
        this.entity = type == null ? null : type.create(world, EntitySpawnReason.COMMAND);

        if (this.entity == null && player)
        {
            this.entity = new RemotePlayer(world, slim ? SLIM : WIDE);
            /* 1.21.9: PlayerEntity.PLAYER_MODEL_PARTS moved to PlayerLikeEntity.PLAYER_MODE_CUSTOMIZATION_ID
             * (same tracked byte, renamed; opened via bbs.accesswidener). All cosmetic layers on, as before. */
            this.entity.getEntityData().set(Avatar.DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0b1111111);
        }

        if (this.entity != null)
        {
            compound.putString("id", id);

            try
            {
                /* 1.21.6 persistence rewrite: Entity.readNbt(NbtCompound) -> readData(ReadView).
                 * The user-typed NBT can be anything, and a mob that fails mid-read is still
                 * usable — it just ignores the broken tags, like the old readNbt did. */
                this.entity.load(TagValueInput.create(ProblemReporter.DISCARDING, world.getRegistryManager(), compound));
            }
            catch (Exception e)
            {}

            this.entity.noPhysics = true;
        }

        return this.entity;
    }

    /** Step the entity and bring it in line with the actor: what vanilla's animation will read off it next frame. */
    public void tick(IEntity source)
    {
        if (this.entity == null)
        {
            return;
        }

        this.entity.tick();

        /* 1.21.9: Entity prevPitch/prevYaw -> lastPitch/lastYaw; LivingEntity prevHeadYaw/
         * prevBodyYaw -> lastHeadYaw/lastBodyYaw. */
        this.entity.xRotO = this.prevPitch;
        this.entity.yRotO = 0F;

        if (this.entity instanceof LivingEntity livingEntity)
        {
            livingEntity.lastHeadYaw = this.prevYawHead;
            livingEntity.lastBodyYaw = 0F;

            /* Limb swing is so ugly */
            if (livingEntity.walkAnimation instanceof LimbAnimatorAccessor a && source.getLimbAnimator() instanceof LimbAnimatorAccessor b)
            {
                a.setPrevSpeed(b.getPrevSpeed());
                a.setSpeed(b.getSpeed());
                a.setPos(b.getPos());
            }

            /* Arm swing */
            float handSwingProgress = source.getHandSwingProgress(0F);

            if (handSwingProgress < this.prevHandSwing)
            {
                this.prevHandSwing = 0;
            }

            if (handSwingProgress > 0 && this.prevHandSwing == 0)
            {
                livingEntity.swing(InteractionHand.MAIN_HAND);
            }

            this.prevHandSwing = handSwingProgress;
        }

        this.entity.setYRot(0F);
        this.entity.setYHeadRot(source.getHeadYaw() - source.getBodyYaw());
        this.entity.setXRot(source.getPitch());
        this.entity.setYBodyRot(0F);

        this.entity.setPosRaw(source.getX(), source.getY(), source.getZ());
        this.entity.setOnGround(source.isOnGround());
        this.entity.setShiftKeyDown(source.isSneaking());
        this.entity.setSprinting(source.isSprinting());
        this.entity.setSwimming(source.isSwimming());
        ((EntityInvoker) this.entity).bbs$setFlag(EntityState.FALL_FLYING_FLAG, source.isFallFlying());
        this.entity.setPose(EntityState.pose(source));

        /* Since 1.21.1 equipStack belongs to LivingEntity, not Entity */
        if (this.entity instanceof LivingEntity living)
        {
            living.setItemSlot(EquipmentSlot.MAINHAND, source.getEquipmentStack(EquipmentSlot.MAINHAND));
            living.setItemSlot(EquipmentSlot.OFFHAND, source.getEquipmentStack(EquipmentSlot.OFFHAND));
            living.setItemSlot(EquipmentSlot.HEAD, source.getEquipmentStack(EquipmentSlot.HEAD));
            living.setItemSlot(EquipmentSlot.CHEST, source.getEquipmentStack(EquipmentSlot.CHEST));
            living.setItemSlot(EquipmentSlot.LEGS, source.getEquipmentStack(EquipmentSlot.LEGS));
            living.setItemSlot(EquipmentSlot.FEET, source.getEquipmentStack(EquipmentSlot.FEET));
        }

        this.entity.tickCount = source.getAge();
        this.entity.noPhysics = true;

        this.prevYawHead = source.getHeadYaw() - source.getBodyYaw();
        this.prevPitch = source.getPitch();
    }
}
