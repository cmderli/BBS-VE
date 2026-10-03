package mchorse.bbs_mod.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@link net.minecraft.client.particle.Particle#buildGeometry} takes a vanilla
 * camera and nothing else &mdash; it subtracts {@link Camera#getPos()} from the
 * particle's position and billboards by {@link Camera#getRotation()}. To draw
 * particles inside a UI viewport we hand it a stand-in camera placed where the
 * preview camera stands, which means writing pos/rotation from the outside.
 *
 * <p>An invoker rather than an access widener on purpose: {@link CameraMixin}
 * shadows both methods as {@code protected}, and widening them to public would
 * make that shadow illegal.
 */
@Mixin(Camera.class)
public interface CameraInvoker
{
    @Invoker("setPosition")
    public void bbs$setPos(double x, double y, double z);

    @Invoker("setRotation")
    public void bbs$setRotation(float yaw, float pitch);

    /**
     * The engine's own cull-frustum build, callable again after the camera has been moved — see
     * {@link CameraMixin#onUpdate}. Both are private in 26.2.
     */
    @Invoker("prepareCullFrustum")
    void bbs$prepareCullFrustum(Matrix4fc viewRotation, Matrix4f projection, Vec3 position);

    @Invoker("createProjectionMatrixForCulling")
    Matrix4f bbs$createProjectionMatrixForCulling();

    @Invoker("getViewRotationMatrix")
    Matrix4f bbs$getViewRotationMatrix(Matrix4f target);
}
