package mchorse.bbs_mod.morphing;

import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.utils.RayTracing;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Arrays;
import java.util.Optional;

public class Morph
{
    private Form form;
    public final MCEntity entity;

    public static Form getMobForm(Player player)
    {
        HitResult hitResult = RayTracing.rayTraceEntity(player, player.level(), player.getEyePosition(), player.getLookAngle(), 64);

        if (hitResult.getType() == HitResult.Type.ENTITY)
        {
            Entity target = ((EntityHitResult) hitResult).getEntity();

            return createMobForm(target);
        }

        return null;
    }

    /**
     * Build a {@link MobForm} snapshotting the given entity's type and NBT, stripping
     * transient world-state keys (position, motion, UUID, etc.) so the form is reusable.
     *
     * @return the form, or {@code null} if the entity's type isn't registered.
     */
    public static MobForm createMobForm(Entity target)
    {
        Optional<ResourceKey<EntityType<?>>> key = BuiltInRegistries.ENTITY_TYPE.getResourceKey(target.getType());

        if (key.isEmpty())
        {
            return null;
        }

        MobForm form = new MobForm();
        TagValueOutput view = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);

        target.save(view);

        CompoundTag compound = view.buildResult();

        for (String s : Arrays.asList("Pos", "Motion", "Rotation", "FallDistance", "Fire", "Air", "OnGround", "Invulnerable", "PortalCooldown", "UUID"))
        {
            compound.remove(s);
        }

        form.mobID.set(key.get().identifier().toString());
        form.mobNBT.set(compound.toString());

        return form;
    }

    public static Morph getMorph(Entity entity)
    {
        if (entity instanceof IMorphProvider provider)
        {
            return provider.getMorph();
        }

        return null;
    }

    public Morph(Entity entity)
    {
        this.entity = new MCEntity(entity);
    }

    public Form getForm()
    {
        return this.form;
    }

    public void setForm(Form form)
    {
        if (form == null && this.form != null && this.entity.getMcEntity() instanceof Player player)
        {
            this.form.onDemorph(player);
        }

        this.form = form;

        if (this.form != null && this.entity.getMcEntity() instanceof Player player)
        {
            this.form.onMorph(player);
            this.form.playMain();
        }

        this.entity.getMcEntity().refreshDimensions();
    }

    public void update()
    {
        this.entity.update();

        if (this.form != null)
        {
            this.form.update(this.entity);
        }
    }

    public Tag toNbt()
    {
        CompoundTag compound = new CompoundTag();

        if (this.form != null)
        {
            compound.put("Form", DataStorageUtils.toNbt(FormUtils.toData(this.form)));
        }

        return compound;
    }

    public void fromNbt(CompoundTag compound)
    {
        if (compound.contains("Form"))
        {
            MapType map = (MapType) DataStorageUtils.fromNbt(compound.getCompoundOrEmpty("Form"));

            this.form = FormUtils.fromData(map);
        }
    }
}