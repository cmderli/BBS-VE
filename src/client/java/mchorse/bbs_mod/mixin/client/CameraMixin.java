package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.controller.CameraController;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.items.GunZoom;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
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

    /** The camera's own position, as the frustum build needs it. */
    @Shadow public abstract Vec3 position();


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

            /* Rebuild the CULL FRUSTUM from the camera we just moved.
             *
             * 26.2 builds it inside this very method: alignWithEntity puts the camera on the player, then
             * prepareCullFrustum builds a frustum from that player position and rotation - all of it
             * before this injection runs at RETURN. Moving the camera afterwards therefore left the
             * frustum pointing where the PLAYER was looking, and Camera#extractRenderState hands that
             * same frustum to the level extractor, which is what chooses the visible sections. The film
             * editor then drew only the wedge of terrain inside the player's first-person view and the
             * rest of the film camera's view came out as empty sky.
             *
             * Rebuilt out of the engine's own two pieces - the culling projection it just used and the
             * view rotation of the camera as it stands now - so nothing else about the frame changes.
             * Going through prepareCullFrustum also keeps the ortho widening this class hooks on it. */
            CameraInvoker invoker = (CameraInvoker) (Object) this;

            invoker.bbs$prepareCullFrustum(
                invoker.bbs$getViewRotationMatrix(new Matrix4f()),
                invoker.bbs$createProjectionMatrixForCulling(),
                this.position()
            );
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