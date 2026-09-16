package mchorse.bbs_mod.client.renderer.entity;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.renderer.DeathPose;
import mchorse.bbs_mod.cubic.render.vanilla.ArmorRenderer;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Pose;
import net.minecraft.util.Mth;
import com.mojang.math.Axis;

public class ActorEntityRenderer extends EntityRenderer<ActorEntity, ActorEntityRenderer.ActorRenderState>
{
    public static ArmorRenderer armorRenderer;

    /**
     * The form + vanilla-entity context cannot be carried on a vanilla render state, so the actual
     * BBS form rendering still reads off the live {@link ActorEntity}. It rides on the state the
     * frame is drawn from and never on the renderer: 1.21.11 fills the render states of every entity
     * first (WorldRenderer#fillEntityRenderStates) and draws them only afterwards
     * (WorldRenderer#pushEntityRenders), so a field on the renderer holds whichever actor was
     * extracted last - and every actor of the film gets drawn with that one's form and pose.
     */
    public static class ActorRenderState extends LivingEntityRenderState
    {
        public ActorEntity entity;
        public float tickDelta;
    }

    public ActorEntityRenderer(EntityRendererProvider.Context ctx)
    {
        super(ctx);

        /* 1.21.4+ equipment rewrite: the inner/outer armor layers became per-slot equipment model
         * layers (EntityModelLayers.PLAYER_EQUIPMENT: head/chest/legs/feet). The armor geometry
         * MUST come from these — building the models off the PLAYER layer put the 64x32 armor
         * texture onto player-model UVs, which is exactly the garbled full-body leather look. */
        armorRenderer = new ArmorRenderer(
            ModelLayers.PLAYER_ARMOR.map((layer) -> new HumanoidModel(ctx.bakeLayer(layer))),
            ctx.bakeLayer(ModelLayers.ELYTRA),
            ctx.getEquipmentAssets()
        );

        /* The film draws an actor's shadow itself, sized and offset by the replay. A vanilla shadow
         * underneath would be a second one, at a fixed size nobody asked for. */
        this.shadowRadius = 0F;
    }

    @Override
    public ActorRenderState createRenderState()
    {
        return new ActorRenderState();
    }

    @Override
    public void extractRenderState(ActorEntity entity, ActorRenderState state, float tickDelta)
    {
        super.extractRenderState(entity, state, tickDelta);

        state.entity = entity;
        state.tickDelta = tickDelta;

        state.bodyRot = Mth.rotLerp(tickDelta, entity.yBodyRotO, entity.yBodyRot);
        state.deathTime = entity.deathTime > 0 ? entity.deathTime + tickDelta : 0F;

        /* The red damage flash, exactly as LivingEntityRenderer derives it: a blow OR a death. The
         * death half is what keeps the body red for the whole fall - this renderer extends
         * EntityRenderer and fills the living state itself, so nothing else was setting it. */
        state.hasRedOverlay = entity.hurtTime > 0 || entity.deathTime > 0;
        state.pose = entity.getPose();
        state.isInvisible = entity.isInvisible();
    }

    @Override
    public void submit(ActorRenderState state, PoseStack matrices, SubmitNodeCollector queue, CameraRenderState cameraState)
    {
        ActorEntity entity = state.entity;

        /* A film running on this client draws its own actors, from their keyframes — drawing them
         * here as well would be a second body, a frame behind the first. This is for everyone else:
         * a player who happens to be standing in someone else's scene still sees the cast. */
        if (entity != null && BBSModClient.getFilms().isActorDrawn(entity.getId()))
        {
            return;
        }

        super.submit(state, matrices, queue, cameraState);

        if (entity == null || !this.isVisible(state))
        {
            return;
        }

        matrices.pushPose();

        int overlay = LivingEntityRenderer.getOverlayCoords(state, 0F);

        this.setupTransforms(state, matrices);

        /* TODO(1.21.11 render): blend/depth state is now pipeline-encoded; the explicit
         * RenderSystem.enableBlend/enableDepthTest toggles were removed. */
        Form form = entity.getForm();

        /* An actor standing in the world is a world draw, so it goes inside the world-forms span —
         * the same rule the morph's first-person arm and a held model item already follow. Outside it
         * the draw takes the shared {@code bbs:pipeline/model}, which carries no shaderpack program
         * assignment ("Missing program bbs:pipeline/model in override list" in the log), so under a
         * pack the actor came out ghosted while every replay the film editor draws itself — those go
         * through the span — stayed solid. That asymmetry was the whole bug report: the actor turns
         * see-through the moment shaders are on. */
        boolean prevWorldForms = BBSRendering.beginWorldForms();

        try
        {
            FormUtilsClient.render(form, new FormRenderingContext()
                .set(FormRenderType.ENTITY, entity.getFormEntity(), matrices, state.lightCoords, overlay, state.tickDelta)
                .camera(Minecraft.getInstance().gameRenderer.mainCamera()));
        }
        finally
        {
            BBSRendering.endWorldForms(prevWorldForms);

            matrices.popPose();
        }
    }

    protected boolean isVisible(LivingEntityRenderState state)
    {
        return !state.isInvisible;
    }

    protected void setupTransforms(LivingEntityRenderState state, PoseStack matrices)
    {
        if (!state.hasPose(Pose.SLEEPING))
        {
            matrices.mulPose(Axis.YP.rotationDegrees(-state.bodyRot));
        }

        DeathPose.apply(matrices, state.deathTime);
    }
}
