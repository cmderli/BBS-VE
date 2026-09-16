package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.controller.CameraController;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.items.GunZoom;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin
{
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "update", at = @At(value = "RETURN"))
    public void onUpdate(DeltaTracker tracker, CallbackInfo ci)
    {
        CameraController controller = BBSModClient.getCameraController();

        controller.setup(controller.camera, tracker.getGameTimeDeltaPartialTick(false));

        if (controller.getCurrent() != null)
        {
            Vector3d position = controller.getPosition();
            float yaw = controller.getYaw();
            float pitch = controller.getPitch();

            this.setPosition(position.x, position.y, position.z);
            this.setRotation(yaw, pitch);
        }
    }

    /**
     * This injection replaces the camera FOV when camera controller takes over
     *
     * <p>Moved here from {@code GameRendererMixin}: 26.2's {@code GameRenderer} no longer computes the
     * field of view — {@code Camera.update} does, through this method, and {@code setupPerspective}
     * turns its result into the projection matrix the level is drawn with.</p>
     */
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    public void onGetFov(float partialTicks, CallbackInfoReturnable<Float> info)
    {
        GunZoom gunZoom = BBSModClient.getGunZoom();

        if (gunZoom != null)
        {
            info.setReturnValue(gunZoom.getFOV(info.getReturnValue()));

            return;
        }

        CameraController controller = BBSModClient.getCameraController();

        if (controller.getCurrent() != null && !BBSRendering.isIrisShadowPass())
        {
            info.setReturnValue((float) controller.getFOV());
        }
    }

    /**
     * Ortho frustum widening: substitute the culling projection with the loose ortho frame
     * (20-block lower bound) so ortho frames don't clip sections near the screen edges when
     * zoomed in. On 1.21.1 this was a {@code @ModifyArg} on the {@code GameRenderer.renderWorld}
     * call site of {@code setupFrustum}; in 26.2 {@code Camera.update} builds the frustum itself,
     * so the hook sits on that construction instead. The projection the world actually
     * renders with is substituted separately in {@code GameRendererMixin#onSetWorldProjection}
     * (the UBO upload).
     */
    @ModifyArg(
        method = "prepareCullFrustum",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/culling/Frustum;<init>(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4f;)V"
        ),
        index = 1
    )
    private Matrix4f onSetupFrustumProjection(Matrix4f projection)
    {
        return BBSRendering.getOrthoProjection(Minecraft.getInstance().gameRenderer, projection, 20F);
    }
}