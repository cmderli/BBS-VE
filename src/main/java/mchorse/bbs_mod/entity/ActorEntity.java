package mchorse.bbs_mod.entity;

import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.network.ServerNetwork;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ActorEntity extends LivingEntity implements IEntityFormProvider
{
    public static AttributeSupplier.Builder createActorAttributes()
    {
        return LivingEntity.createLivingAttributes()
            .add(Attributes.ATTACK_DAMAGE, 1D)
            .add(Attributes.MOVEMENT_SPEED, 0.1D)
            .add(Attributes.ATTACK_SPEED)
            .add(Attributes.LUCK);
    }

    private boolean despawn;
    private MCEntity entity = new MCEntity(this);
    private Form form;

    /**
     * Which replay of which film put this body here. A client needs the pairing to know that this
     * entity is a replay's body rather than a creature, and it has to be able to learn that from
     * the entity alone - the map of actors is broadcast when they spawn, which is of no use to
     * anyone who starts seeing one later.
     */
    private String filmId = "";
    private String replayId = "";

    private boolean pickUpItems = true;
    private final List<ItemStack> pickedUp = new ArrayList<>();

    private Map<EquipmentSlot, ItemStack> equipment = new HashMap<>();

    public ActorEntity(EntityType<? extends LivingEntity> entityType, Level world)
    {
        super(entityType, world);
    }

    public void setReplay(String filmId, String replayId)
    {
        this.filmId = filmId;
        this.replayId = replayId;
    }

    public String getFilmId()
    {
        return this.filmId;
    }

    public String getReplayId()
    {
        return this.replayId;
    }

    /* Not getEntity(): LivingEntity itself declares one in 1.21.11, returning a LivingEntity. */
    public MCEntity getFormEntity()
    {
        return this.entity;
    }

    @Override
    public int getEntityId()
    {
        return this.getId();
    }

    @Override
    public Form getForm()
    {
        return this.form;
    }

    @Override
    public void setForm(Form form)
    {
        Form lastForm = this.form;

        this.form = form;

        if (!this.getEntityWorld().isClientSide())
        {
            if (lastForm != null) lastForm.onDemorph(this);
            if (form != null) form.onMorph(this);
        }

        /* The body changed, so the box around it has to change too */
        this.refreshDimensions();
    }

    /**
     * The form's own hitbox, when it declares one. An actor exists so blows land on it, and a box
     * of vanilla's player size around a four-block model means only its ankles can be hit - the
     * flag promised a body in the world and delivered a shin. Same properties the picking box in
     * the editor already reads, so the two agree.
     */
    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose)
    {
        if (this.form == null || !this.form.hitbox.get())
        {
            return super.getDefaultDimensions(pose);
        }

        float width = this.form.hitboxWidth.get();
        float height = this.form.hitboxHeight.get();

        if (pose == Pose.CROUCHING)
        {
            height *= this.form.hitboxSneakMultiplier.get();
        }

        /* Since 1.21.1 the eye height rides the dimensions instead of an override of its own */
        return EntityDimensions.scalable(width, height).withEyeHeight(this.form.hitboxEyeHeight.get());
    }

    @Override
    public boolean shouldRender(double distance)
    {
        double d = this.getBoundingBox().getSize();

        if (Double.isNaN(d))
        {
            d = 1D;
        }

        return distance < (d * 256D) * (d * 256D);
    }

    public Iterable<ItemStack> getHandItems()
    {
        return List.of(this.getEquippedStack(EquipmentSlot.MAINHAND), this.getEquippedStack(EquipmentSlot.OFFHAND));
    }

    public Iterable<ItemStack> getArmorItems()
    {
        return List.of(this.getEquippedStack(EquipmentSlot.FEET), this.getEquippedStack(EquipmentSlot.LEGS), this.getEquippedStack(EquipmentSlot.CHEST), this.getEquippedStack(EquipmentSlot.HEAD));
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot)
    {
        return this.equipment.getOrDefault(slot, ItemStack.EMPTY);
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack)
    {
        this.equipment.put(slot, stack == null ? ItemStack.EMPTY : stack);
    }

    @Override
    public HumanoidArm getMainArm()
    {
        return HumanoidArm.RIGHT;
    }

    @Override
    public void tick()
    {
        super.tick();

        this.updateSwingTime();

        if (this.form != null)
        {
            this.form.update(this.entity);
        }

        if (this.getEntityWorld().isClientSide())
        {
            return;
        }

        if (!this.pickUpItems)
        {
            return;
        }

        /* Pickup items */
        AABB box = this.getBoundingBox().inflate(1D, 0.5D, 1D);
        List<Entity> list = this.getEntityWorld().getEntities(this, box);

        for (Entity entity : list)
        {
            if (entity instanceof ItemEntity itemEntity)
            {
                ItemStack itemStack = itemEntity.getItem();
                int i = itemStack.getCount();

                if (!entity.isRemoved() && !itemEntity.hasPickUpDelay())
                {
                    ((ServerLevel) this.getEntityWorld()).getChunkSource().sendToTrackingPlayers(entity, new ClientboundTakeItemEntityPacket(entity.getId(), this.getId(), i));

                    /* Kept, not destroyed: an actor has no inventory to put this in, so what it
                     * swept up used to simply cease to exist - a take rolling near someone's
                     * dropped things ate them. Held until the film stops, then put back. */
                    this.pickedUp.add(itemStack.copyFrom());

                    entity.discard();
                }
            }
        }
    }

    public void setPickUpItems(boolean pickUpItems)
    {
        this.pickUpItems = pickUpItems;
    }

    /** Put back everything this body swept up, where it now stands. */
    public void dropPickedUp()
    {
        if (this.pickedUp.isEmpty() || this.getEntityWorld().isClientSide())
        {
            return;
        }

        for (ItemStack stack : this.pickedUp)
        {
            ItemEntity item = new ItemEntity(this.getEntityWorld(), this.getX(), this.getY() + 0.5D, this.getZ(), stack);

            item.setDefaultPickUpDelay();
            this.getEntityWorld().addFreshEntity(item);
        }

        this.pickedUp.clear();
    }

    @Override
    public void checkDespawn()
    {
        super.checkDespawn();

        if (this.despawn)
        {
            this.discard();
        }
    }

    @Override
    public void onStartedTrackingBy(ServerPlayer player)
    {
        super.startSeenByPlayer(player);

        ServerNetwork.sendEntityForm(player, this);

        /* Who this body belongs to, told to whoever just came within sight of it. The cast map is
         * broadcast when the actors spawn and never again, so a player who joined, changed
         * dimension or simply walked over later had no way of pairing this entity with its replay. */
        if (!this.replayId.isEmpty())
        {
            ServerNetwork.sendActor(player, this.filmId, this.replayId, this.getId());
        }
    }

    @Override
    public void readCustomData(ValueInput view)
    {
        super.readCustomData(view);

        this.despawn = view.getBooleanOr("despawn", false);
    }

    @Override
    public void writeCustomData(ValueOutput view)
    {
        super.writeCustomData(view);

        view.putBoolean("despawn", true);
    }
}