package mchorse.bbs_mod.client.renderer.entity;

import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.entity.GunProjectileEntity;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.items.GunProperties;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.interps.Lerps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Mth;
import com.mojang.math.Axis;

public class GunProjectileEntityRenderer extends EntityRenderer<GunProjectileEntity, GunProjectileEntityRenderer.GunProjectileRenderState>
{
    public GunProjectileEntityRenderer(EntityRendererProvider.Context ctx)
    {
        super(ctx);
    }

    @Override
    public GunProjectileRenderState createRenderState()
    {
        return new GunProjectileRenderState();
    }

    @Override
    public void extractRenderState(GunProjectileEntity entity, GunProjectileRenderState state, float tickDelta)
    {
        super.extractRenderState(entity, state, tickDelta);

        GunProperties properties = entity.getProperties();
        int out = properties.lifeSpan - 2;

        state.entity = entity;
        state.tickDelta = tickDelta;
        state.bodyYaw = Mth.rotLerp(tickDelta, entity.yRotO, entity.getViewYRot());
        state.entityPitch = Mth.rotLerp(tickDelta, entity.xRotO, entity.getViewXRot());
        state.fadeScale = Lerps.envelope(entity.tickCount + tickDelta, 0, properties.fadeIn, out - properties.fadeOut, out);
    }

    @Override
    public void submit(GunProjectileRenderState state, PoseStack matrices, SubmitNodeCollector queue, CameraRenderState cameraState)
    {
        super.submit(state, matrices, queue, cameraState);

        GunProjectileEntity projectile = state.entity;

        if (projectile == null)
        {
            return;
        }

        GunProperties properties = projectile.getProperties();

        matrices.pushPose();

        if (properties.yaw) matrices.rotateAround(Axis.YP.rotationDegrees(state.bodyYaw));
        if (properties.pitch) matrices.rotateAround(Axis.XP.rotationDegrees(-state.entityPitch));
        matrices.scale(state.fadeScale, state.fadeScale, state.fadeScale);
        MatrixStackUtils.applyTransform(matrices, properties.projectileTransform);

        /* TODO(1.21.11 render): depth state is now pipeline-encoded; RenderSystem.enableDepthTest was removed. */

        /* World draw, so it needs the world-forms span — same rule (and same ghosting under a
         * shaderpack without it) as the actor next door. */
        boolean prevWorldForms = BBSRendering.beginWorldForms();

        try
        {
            FormUtilsClient.render(projectile.getForm(), new FormRenderingContext()
                .set(FormRenderType.ENTITY, projectile.getFormEntity(), matrices, state.lightCoords, OverlayTexture.NO_OVERLAY, state.tickDelta)
                .camera(Minecraft.getInstance().gameRenderer.getMainCamera()));
        }
        finally
        {
            BBSRendering.endWorldForms(prevWorldForms);

            matrices.popPose();
        }
    }

    /**
     * Carries the projectile-specific per-frame data + the live entity through the render-state model.
     *
     * TODO(1.21.11 render): carrying the live entity is a build-only bridge until the BBS form
     * pipeline is adapted to read off the render state.
     */
    public static class GunProjectileRenderState extends EntityRenderState
    {
        public GunProjectileEntity entity;
        public float tickDelta;
        public float bodyYaw;
        public float entityPitch;
        public float fadeScale;
    }
}
