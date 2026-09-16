package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.render.picker.PickingReplay;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormRenderCapture;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.QueueDispatch;
import mchorse.bbs_mod.forms.ITickable;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.cubic.IBoneHierarchy;
import mchorse.bbs_mod.forms.renderers.mob.MobRenderContext;
import mchorse.bbs_mod.forms.renderers.mob.MobRig;
import mchorse.bbs_mod.forms.renderers.mob.MobRigMatrices;
import mchorse.bbs_mod.forms.renderers.mob.MobRigs;
import mchorse.bbs_mod.forms.renderers.mob.MobStandIn;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.graphics.texture.AdoptedTexture;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.joml.Vectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import com.mojang.math.Axis;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

public class MobFormRenderer extends FormRenderer<MobForm> implements ITickable
{
    private final MatrixCache bones = new MatrixCache();

    /** The vanilla entity this form renders through, kept in step with the form's actor — see {@link MobStandIn}. */
    private final MobStandIn standIn = new MobStandIn();

    private Entity entity;

    public static MobRig getRig(MobForm form)
    {
        return FormUtilsClient.getRenderer(form) instanceof MobFormRenderer renderer ? renderer.getRig() : null;
    }

    public MobFormRenderer(MobForm form)
    {
        super(form);
    }

    @Override
    public List<String> getBones()
    {
        MobRig rig = this.getRig();

        return rig == null ? super.getBones() : rig.getGroupKeysInHierarchyOrder();
    }

    @Override
    public IBoneHierarchy getBoneHierarchy()
    {
        return this.getRig();
    }

    /**
     * The skeleton of the vanilla model this form renders through, or null while there is no
     * entity yet or the entity does not render through a living entity renderer.
     */
    public MobRig getRig()
    {
        this.ensureEntity();

        if (this.entity != null && Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(this.entity) instanceof LivingEntityRenderer renderer)
        {
            return MobRigs.of(renderer.getModel());
        }

        return null;
    }

    /**
     * Claims one pick id for the form and one per bone, in the order the parts drew with (see
     * {@code MobRenderContext.partLight}). Same contract as the model form's
     * {@code ModelInstance.fillStencilMap}: the shader adds the part's offset to the form's base
     * id, so the registration order here IS the decoding table.
     */
    @Override
    protected void updateStencilMap(FormRenderingContext context)
    {
        MobRig rig = this.getRig();

        context.stencilMap.addPicking(this.form, "");

        if (rig != null)
        {
            for (ModelPart part : rig.ordered())
            {
                context.stencilMap.addPicking(this.form, rig.name(part));
            }
        }
    }

    private boolean hasBoundBodyParts()
    {
        for (BodyPart part : this.form.parts.getAllTyped())
        {
            if (!part.bone.get().isEmpty())
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Body parts bound to a bone ride that bone's frame; the rest stay exactly where they were,
     * in the form's own space. Only parts that name a bone the model actually has move, so nothing
     * that was authored before mob bones existed shifts underfoot.
     */
    @Override
    public void renderBodyParts(FormRenderingContext context)
    {
        /* Where the bones are, for the parts that ride one. Asked for only when something is bound,
         * because it walks the whole part tree; the mob's own draw is over by now (bodies render
         * after render3D), so the evaluation is free to write and restore the shared model. */
        if (this.hasBoundBodyParts())
        {
            MobRigMatrices.evaluate(this.entity, this.getRig(), this.form.pose.get(), this.form.poseOverlay.get(), context.getTransition(), this.bones);
        }

        for (BodyPart part : this.form.parts.getAllTyped())
        {
            Matrix4f matrix = part.filterBoneMatrix(this.bones.get(part.bone.get()).matrix());

            if (matrix == null)
            {
                this.renderBodyPart(part, context);

                continue;
            }

            context.stack.pushPose();
            if (context.world != null)
            {
                context.world.pushPose();
            }

            MatrixStackUtils.multiply(context.stack, matrix);
            if (context.world != null)
            {
                MatrixStackUtils.multiply(context.world, matrix);
            }

            this.renderBodyPart(part, context);

            context.stack.popPose();
            if (context.world != null)
            {
                context.world.popPose();
            }
        }

        this.bones.clear();
    }

    /**
     * The same bones, asked for outside a render - what the gizmo, the anchor system, trackers and
     * the motion path read. Body parts recurse through their bone's frame, so a form anchored to a
     * mob's head resolves under {@code <path>/head} the way a model form's bones do.
     */
    @Override
    public void collectMatrices(IEntity entity, PoseStack stack, MatrixCache matrices, String prefix, float transition)
    {
        this.ensureEntity();

        Matrix4f mm = new Matrix4f();
        Matrix4f oo = new Matrix4f();

        stack.pushPose();
        this.applyTransforms(stack, true, transition);
        oo.set(stack.last().pose());
        stack.popPose();

        stack.pushPose();
        this.applyTransforms(stack, false, transition);
        mm.set(stack.last().pose());

        matrices.put(prefix, mm, oo);

        MatrixCache collected = new MatrixCache();

        MobRigMatrices.evaluate(this.entity, this.getRig(), this.form.pose.get(), this.form.poseOverlay.get(), transition, collected);

        for (Map.Entry<String, MatrixCacheEntry> entry : collected.entrySet())
        {
            Matrix4f matrix = new Matrix4f();
            Matrix4f o = new Matrix4f();

            stack.pushPose();
            MatrixStackUtils.multiply(stack, entry.getValue().matrix());
            matrix.set(stack.last().pose());
            stack.popPose();

            stack.pushPose();
            MatrixStackUtils.multiply(stack, entry.getValue().origin());
            o.set(stack.last().pose());
            stack.popPose();

            matrices.put(StringUtils.combinePaths(prefix, entry.getKey()), matrix, o);
        }

        for (BodyPart part : this.form.parts.getAllTyped())
        {
            Form form = part.getForm();

            if (form == null)
            {
                continue;
            }

            Matrix4f matrix = part.filterBoneMatrix(collected.get(part.bone.get()).matrix());

            stack.pushPose();

            if (matrix != null)
            {
                MatrixStackUtils.multiply(stack, matrix);
            }

            MatrixStackUtils.applyTransform(stack, part.transform.get());
            FormUtilsClient.getRenderer(form).collectMatrices(entity, stack, matrices, StringUtils.combinePaths(prefix, part.getId()), transition);

            stack.popPose();
        }

        stack.popPose();
    }

    private void bindTexture()
    {
        Link link = this.form.texture.get();

        if (link != null)
        {
            BBSModClient.getTextures().bindTexture(link);
        }
    }

    private void ensureEntity()
    {
        this.entity = this.standIn.ensure(this.form.mobID.get(), this.form.mobNBT.get(), this.form.slim.get(), this.form.isPlayer());
    }

    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        this.ensureEntity();

        if (this.entity != null)
        {
            /* List/icon preview goes through the special GUI element FBO pass, same as
             * item/block/model forms — a direct immediate draw can't composite in the
             * two-phase GUI (1.21.6+). */
            this.submitUIPreview(context, x1, y1, x2, y2);
        }
    }

    @Override
    public void renderUIPreview(PoseStack stack, float angle, float transition, int x1, int y1, int x2, int y2)
    {
        if (this.entity == null)
        {
            return;
        }

        Matrix4f uiMatrix = getUIPreviewMatrix(angle, y1, y2);
        float scale = this.form.uiScale.get();
        float width = this.entity.getBbWidth();
        float height = this.entity.getBbHeight();

        /* Big mobs are normalized into the cell, exactly like the 1.21.1 preview did. */
        scale = scale * Math.min(1.8F / Math.max(width, height), 1F);

        stack.pushPose();
        MatrixStackUtils.multiply(stack, uiMatrix);
        stack.scale(scale, scale, scale);

        if (!this.form.mobID.get().equals("minecraft:ender_dragon"))
        {
            stack.rotateAround(Axis.YP.rotation(MathUtils.PI));
        }

        stack.last().normal().getScale(Vectors.EMPTY_3F);
        stack.last().normal().scale(1F / Vectors.EMPTY_3F.x, -1F / Vectors.EMPTY_3F.y, 1F / Vectors.EMPTY_3F.z);

        this.renderEntity(stack, transition, LightTexture.FULL_BLOCK, OverlayTexture.NO_OVERLAY, null);

        stack.popPose();
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        this.ensureEntity();

        if (this.entity == null)
        {
            return;
        }

        if (context.isPicking())
        {
            this.setupTarget(context);
        }

        Matrix4f cached = new Matrix4f(RenderSystem.getModelViewMatrix());

        context.stack.pushPose();

        if (context.world != null)
        {
            context.world.pushPose();
        }

        if (this.form.mobID.get().equals("minecraft:ender_dragon"))
        {
            context.stack.rotateAround(Axis.YP.rotation(MathUtils.PI));

            if (context.world != null)
            {
                context.world.rotateAround(Axis.YP.rotation(MathUtils.PI));
            }
        }

        if (this.entity instanceof LivingEntity entity)
        {
            int v = context.overlay >> 16 & 0xFFFF;

            /* The damage flash: the render state derives the red overlay from hurtTime, so the
             * BBS overlay coordinate is translated back into it before the state snapshot. */
            entity.hurtTime = v != 10 ? 100 : 0;
        }

        if (context.isPicking())
        {
            /* The same draw, captured off its vanilla layers and replayed through the picker
             * pipeline (see PickingReplay) — 1.21.1 picked these forms by swapping the global
             * shader, and the pipeline system has no global program to swap. The parts write
             * their own bone ids into the light channel while the context says it is picking
             * (ModelPartMixin), which is what makes a mob pickable limb by limb; the light
             * argument therefore goes in clean. */
            FormRenderCapture.begin();

            Map<RenderType, List<FormRenderCapture.Captured>> captured;

            try
            {
                this.renderEntity(context.stack, context.getTransition(), 0, context.overlay, context.stencilMap);
            }
            finally
            {
                captured = FormRenderCapture.end();
            }

            PickingReplay.draw(captured);
        }
        else
        {
            /* Publishing the form's camera-space origin opts its translucent layers (slime
             * bodies, ghost textures) into the deferred sorted pass. */
            Vector3f origin = context.stack.last().pose().getTranslation(new Vector3f());

            FormTranslucentQueue.setSortOrigin(new Matrix4f(RenderSystem.getModelViewMatrix()).transformPosition(origin));

            this.renderEntity(context.stack, context.getTransition(), context.light, context.overlay, null);

            FormTranslucentQueue.setSortOrigin(null);
        }

        context.stack.popPose();

        if (context.world != null)
        {
            context.world.popPose();
        }

        /* Restore the shared model-view in case a command renderer touched it — the 2D UI batch
         * inherits it when a MobForm is nested as a body part inside a list preview. */
        RenderSystem.getModelViewMatrix().set(cached);
    }

    /**
     * The shared draw path: snapshot the entity into a render state, submit it into BBS's private
     * command queue and flush the queue synchronously through the BBS provider. See
     * {@link QueueDispatch} for why the private queue exists at all.
     */
    private void renderEntity(PoseStack stack, float transition, int light, int overlay, StencilMap stencilMap)
    {
        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();
        EntityRenderDispatcher manager = Minecraft.getInstance().getEntityRenderDispatcher();

        /* Resolves (and caches) the part map as a side effect, for the pose below. */
        this.getBones();

        EntityRenderState state = manager.extractEntity(this.entity, transition);

        /* The film/preview owns placement and lighting: the entity nominally stands wherever the
         * real world put it, but it draws at the stack's origin with the form's light. Labels,
         * vanilla blob shadows and leashes are world-decorations that never made sense on a form. */
        state.x = state.y = state.z = 0;
        state.distanceToCameraSq = 0;
        state.lightCoords = light;
        state.nameTag = null;
        state.nameTagAttachment = null;
        state.shadowPieces.clear();
        state.leashStates = null;
        state.outlineColor = 0;

        /* Publish the rig and the pose for the length of this flush: the parts take their final
         * vanilla angles inside ModelCommandRenderer, at flush time, so the pose can only be added
         * there (see ModelCommandRendererMixin). */
        MobRenderContext mob = MobRenderContext.push(this.getRig(), this.form.pose.get(), this.form.poseOverlay.get()).picking(stencilMap);

        /* The custom-texture feature: 1.21.1 GL-bound the texture over the first drawn layer
         * (the body). Textures are per-layer now, so the first requested layer of this flush is
         * remapped onto an entity layer carrying the form's texture instead. */
        Link textureLink = this.form.texture.get();

        if (textureLink != null)
        {
            Texture texture = BBSModClient.getTextures().getTexture(textureLink);
            Identifier adopted = AdoptedTexture.identifier(texture);
            boolean[] first = {true};

            consumers.setLayerMapper((layer) ->
            {
                if (first[0])
                {
                    first[0] = false;

                    return RenderTypes.entityTranslucent(adopted);
                }

                return null;
            });
        }

        try
        {
            manager.submit(state, QueueDispatch.cameraState(), 0D, 0D, 0D, stack, QueueDispatch.queue());
            QueueDispatch.flush();
            consumers.draw();
        }
        finally
        {
            consumers.setLayerMapper(null);
            mob.pop();
        }
    }

    @Override
    public void tick(IEntity entity)
    {
        this.ensureEntity();
        this.standIn.tick(entity);
    }

}
