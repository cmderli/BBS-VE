package mchorse.bbs_mod.cubic.render.vanilla;

import mchorse.bbs_mod.cubic.model.ArmorType;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.renderers.utils.RecolorVertexConsumer;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.Sheets;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Vanilla armor rendering onto BBS cubic-model bones.
 *
 * <p>Ported to 1.21.11. The 1.21.4+ equipment rewrite removed the entire API this class was built
 * on:
 * <ul>
 *   <li>{@code net.minecraft.item.ArmorItem} no longer exists — armor identity is now carried by the
 *       {@link net.minecraft.component.DataComponentTypes#EQUIPPABLE} data component (an
 *       {@link EquippableComponent} whose {@link EquippableComponent#assetId()} points at an
 *       {@link EquipmentAsset}).</li>
 *   <li>{@code ArmorMaterial} moved to {@code net.minecraft.item.equipment} and no longer exposes a
 *       per-item material/key the way it used to; {@code ArmorTrim} moved to
 *       {@code net.minecraft.item.equipment.trim} and replaced
 *       {@code getGenericModelId}/{@code getLeggingsModelId} with
 *       {@link ArmorTrim#getTextureId(String, RegistryKey)}.</li>
 *   <li>{@code RenderLayer.getArmorCutoutNoCull(Identifier)}, {@code RenderLayer.getArmorEntityGlint()}
 *       and every other {@code RenderLayer.getXxx(...)} entity-layer factory were removed —
 *       {@code RenderLayer} now only exposes {@code of(String, RenderSetup)}. Entity/equipment drawing
 *       moved to {@code EquipmentRenderer} + the {@code OrderedRenderCommandQueue} command system, a
 *       different architecture from this per-{@link ArmorType}, per-{@link ModelPart} renderer.</li>
 *   <li>{@code BakedModelManager.getAtlas(Identifier)} was removed — the armor-trims sprite atlas
 *       comes from {@code AtlasManager} instead.</li>
 * </ul>
 *
 * <p>Vanilla's own {@code EquipmentRenderer} cannot be reused: it renders a whole {@code Model<S>}
 * through the command queue, while this one puts individual {@link ModelPart}s onto individual cubic
 * bones. So the draw is BBS's, but everything it draws is read out of vanilla's data - which shapes
 * an equipment asset has, and which texture each of them uses, come from the
 * {@link EquipmentModelLoader} index rather than from a path built out of the asset id.
 */
public class ArmorRenderer
{
    /** Per-slot armor models (1.21.4+: the inner/outer pair became head/chest/legs/feet layers). */
    private final ArmorModelSet<HumanoidModel> models;
    private final ModelPart elytra;
    private final ModelPart elytraLeftWing;
    private final ModelPart elytraRightWing;

    /**
     * Vanilla's own {@code assets/<ns>/equipment/<asset>.json} index. It is what says WHICH shapes a
     * piece of equipment has (a humanoid body, wings, a horse's barding) and which texture each of
     * them draws with - guessing that from the asset id is what put a missing texture on the arms.
     */
    private final EquipmentAssetManager equipment;

    public ArmorRenderer(ArmorModelSet<HumanoidModel> models, ModelPart elytra, EquipmentAssetManager equipment)
    {
        this.models = models;
        this.elytra = elytra;
        this.elytraLeftWing = elytra.getChild("left_wing");
        this.elytraRightWing = elytra.getChild("right_wing");
        this.equipment = equipment;
    }

    public void renderArmorSlot(PoseStack matrices, CustomVertexConsumerProvider vertexConsumers, IEntity entity, EquipmentSlot armorSlot, ArmorType type, int light)
    {
        ItemStack itemStack = entity.getEquipmentStack(armorSlot);

        if (itemStack.isEmpty())
        {
            return;
        }

        /* 1.21.4+: armor is identified by the EQUIPPABLE component + its asset id, not by an
         * ArmorItem subclass. We still gate on the slot matching so this only fires for the armour
         * the model actually wears in that slot. */
        Equippable equippable = itemStack.get(DataComponents.EQUIPPABLE);

        if (equippable == null || equippable.slot() != armorSlot || equippable.assetId().isEmpty())
        {
            return;
        }

        ResourceKey<EquipmentAsset> assetId = equippable.assetId().get();
        EquipmentClientInfo model = this.equipment.get(assetId);

        if (!model.getLayers(EquipmentClientInfo.LayerType.WINGS).isEmpty())
        {
            /* Wings hang off the torso alone. Both arms share EquipmentSlot.CHEST with it (see
             * ArmorType), so without this an elytra was drawn three times - and the two arm draws
             * went down the humanoid path below, which has no texture for it: that missing texture
             * is what turned the body black. */
            if (type == ArmorType.CHEST)
            {
                this.renderElytra(matrices, vertexConsumers, entity, itemStack, model, light);
            }

            return;
        }

        boolean innerModel = this.usesInnerModel(armorSlot);
        EquipmentClientInfo.LayerType layerType = innerModel ? EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS : EquipmentClientInfo.LayerType.HUMANOID;
        List<EquipmentClientInfo.Layer> layers = model.getLayers(layerType);

        if (layers.isEmpty())
        {
            /* Worn, but not shaped like a body: a carved pumpkin, a mob head, a horse's barding on
             * something that is not a horse. Vanilla draws nothing for those either. */
            return;
        }

        HumanoidModel bipedModel = this.getModel(armorSlot);
        ModelPart part = this.getPart(bipedModel, type);

        /* 26.2 removed HumanoidModel.setAllVisible(boolean); these are the same seven parts it set.
         * Nothing in 26.2 hides an armour part — the per-slot armour meshes are trimmed instead
         * (see HumanoidModel#createArmorMeshSet) — but the parts still have to be visible for
         * ModelPart#render to emit them. */
        bipedModel.head.visible = true;
        bipedModel.hat.visible = true;
        bipedModel.body.visible = true;
        bipedModel.rightArm.visible = true;
        bipedModel.leftArm.visible = true;
        bipedModel.rightLeg.visible = true;
        bipedModel.leftLeg.visible = true;

        part.x = part.y = part.z = 0F;
        part.xRot = part.yRot = part.zRot = 0F;
        part.xScale = part.yScale = part.zScale = 1F;

        /* One draw per declared layer, in order: leather is a grey mask plus an undyed overlay, and
         * the dye (or the material's own default tint) belongs to the first of the two. */
        for (EquipmentClientInfo.Layer layer : layers)
        {
            this.renderArmorLayer(part, matrices, vertexConsumers, light, layer.getTextureLocation(layerType), this.tint(itemStack, layer));
        }

        ArmorTrim trim = itemStack.get(DataComponents.TRIM);

        if (trim != null)
        {
            this.renderTrim(part, assetId, matrices, vertexConsumers, light, trim, innerModel);
        }

        if (itemStack.hasFoil())
        {
            this.renderGlint(part, matrices, vertexConsumers, light);
        }
    }

    /* Vanilla elytra, verified against 1.20.4 bytecode: ElytraFeatureRenderer.render
     * (translate 0,0,0.125; armor cutout layer; glint) + ElytraEntityModel.setAngles
     * (non-player branch: standing / sneaking / fall flying wing angles). 1.21.11 keeps the
     * same numbers, it only moved them onto the entity's own ElytraFlightController - which an
     * actor driven by keyframes never ticks, so they are computed here as before. */
    private void renderElytra(PoseStack matrices, CustomVertexConsumerProvider vertexConsumers, IEntity entity, ItemStack itemStack, EquipmentClientInfo model, int light)
    {
        float pitch = 0.2617994F;
        float roll = -0.2617994F;
        float originY = 0F;
        float yaw = 0F;

        if (entity.isFallFlying())
        {
            float spread = 1F;
            Vec3 velocity = entity.getVelocity();

            if (velocity.y < 0D)
            {
                spread = 1F - (float) Math.pow(-velocity.normalize().y, 1.5D);
            }

            pitch = spread * 0.34906584F + (1F - spread) * pitch;
            roll = spread * -1.5707964F + (1F - spread) * roll;
        }
        else if (entity.isSneaking())
        {
            pitch = 0.6981317F;
            roll = -0.7853982F;
            originY = 3F;
            yaw = 0.08726646F;
        }

        this.elytraLeftWing.y = originY;
        this.elytraLeftWing.xRot = pitch;
        this.elytraLeftWing.yRot = yaw;
        this.elytraLeftWing.zRot = roll;
        this.elytraRightWing.y = originY;
        this.elytraRightWing.xRot = pitch;
        this.elytraRightWing.yRot = -yaw;
        this.elytraRightWing.zRot = -roll;

        matrices.pushPose();
        matrices.translate(0F, 0F, 0.125F);

        /* The texture comes off the wings layer, not a hardcoded path. (Vanilla swaps in the wearer's
         * own cape when the layer asks for it - an actor has none, so the default stands.) */
        EquipmentClientInfo.Layer wings = model.getLayers(EquipmentClientInfo.LayerType.WINGS).get(0);
        VertexConsumer vertexConsumer = vertexConsumers.getBuffer(RenderTypes.armorCutoutNoCull(wings.getTextureLocation(EquipmentClientInfo.LayerType.WINGS)));

        this.elytra.render(matrices, vertexConsumer, light, OverlayTexture.NO_OVERLAY);

        if (itemStack.hasFoil())
        {
            this.renderGlint(this.elytra, matrices, vertexConsumers, light);
        }

        matrices.popPose();
    }

    private ModelPart getPart(HumanoidModel bipedModel, ArmorType type)
    {
        switch (type)
        {
            case HELMET -> {
                return bipedModel.head;
            }
            case CHEST, LEGGINGS -> {
                return bipedModel.body;
            }
            case LEFT_ARM -> {
                return bipedModel.leftArm;
            }
            case RIGHT_ARM -> {
                return bipedModel.rightArm;
            }
            case LEFT_LEG, LEFT_BOOT -> {
                return bipedModel.leftLeg;
            }
            case RIGHT_LEG, RIGHT_BOOT -> {
                return bipedModel.rightLeg;
            }
        }

        return bipedModel.head;
    }

    private void renderArmorLayer(ModelPart part, PoseStack matrices, CustomVertexConsumerProvider vertexConsumers, int light, Identifier texture, Color color)
    {
        /* The armor layer factories came back as static RenderLayers.armorCutoutNoCull (the 1.21.4
         * rewrite moved them off RenderLayer, it didn't remove them). Same draw as 1.21.1: the layer
         * carries the texture, the recolor consumer carries the dye. */
        VertexConsumer base = vertexConsumers.getBuffer(RenderTypes.armorCutoutNoCull(texture));
        VertexConsumer vertexConsumer = new RecolorVertexConsumer(base, color);

        part.render(matrices, vertexConsumer, light, OverlayTexture.NO_OVERLAY);
    }

    /**
     * The colour one layer draws with. Only a layer that declares itself dyeable takes one: the dye
     * on the stack when there is one, and the material's own default otherwise - a leather texture
     * is a grey mask since 1.21.4, so an undyed piece drawn white would have come out grey.
     */
    private Color tint(ItemStack itemStack, EquipmentClientInfo.Layer layer)
    {
        if (layer.dyeable().isEmpty())
        {
            return Color.white();
        }

        DyedItemColor dyed = itemStack.get(DataComponents.DYED_COLOR);
        int rgb = dyed != null ? dyed.rgb() : layer.dyeable().get().colorWhenUndyed().orElse(0xFFFFFF);

        return new Color((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, 1F);
    }

    private void renderTrim(ModelPart part, ResourceKey<EquipmentAsset> assetId, PoseStack matrices, CustomVertexConsumerProvider vertexConsumers, int light, ArmorTrim trim, boolean leggings)
    {
        /* 1.21.4+: the trim texture id comes off the trim itself and the atlas lives in
         * AtlasManager (BakedModelManager.getAtlas is gone). */
        TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(Sheets.ARMOR_TRIMS_SHEET);
        TextureAtlasSprite sprite = atlas.getSprite(trim.layerAssetId(leggings ? "leggings" : "armor", assetId));
        VertexConsumer vertexConsumer = sprite.wrap(vertexConsumers.getBuffer(Sheets.armorTrimsSheet(trim.pattern().value().decal())));

        part.render(matrices, vertexConsumer, light, OverlayTexture.NO_OVERLAY);
    }

    private void renderGlint(ModelPart part, PoseStack matrices, CustomVertexConsumerProvider vertexConsumers, int light)
    {
        part.render(matrices, vertexConsumers.getBuffer(RenderTypes.armorEntityGlint()), light, OverlayTexture.NO_OVERLAY);
    }

    private HumanoidModel getModel(EquipmentSlot slot)
    {
        return this.models.get(slot);
    }

    private boolean usesInnerModel(EquipmentSlot slot)
    {
        return slot == EquipmentSlot.LEGS;
    }
}
