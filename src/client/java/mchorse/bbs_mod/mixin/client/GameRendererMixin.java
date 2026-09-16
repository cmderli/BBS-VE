package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.controller.CameraController;
import mchorse.bbs_mod.camera.controller.ICameraController;
import mchorse.bbs_mod.camera.controller.PlayCameraController;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.DeltaTracker;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin
{
    /**
     * This injection cancels bobbing when camera controller takes over
     */
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    public void onBob(CallbackInfo ci)
    {
        if (BBSModClient.getCameraController().getCurrent() != null)
        {
            ci.cancel();
        }
    }

    /**
     * This injection replaces the camera roll when camera controller takes over
     */
    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    public void onTiltViewWhenHurt(CameraRenderState cameraRenderState, PoseStack matrices, CallbackInfo info)
    {
        CameraController controller = BBSModClient.getCameraController();

        if (controller.getCurrent() != null && !BBSRendering.isIrisShadowPass())
        {
            matrices.mulPose(Axis.ZP.rotationDegrees(controller.getRoll()));

            info.cancel();
        }
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    public void onRenderHand(CallbackInfo info)
    {
        ICameraController current = BBSModClient.getCameraController().getCurrent();

        if (current instanceof PlayCameraController)
        {
            info.cancel();
        }
    }

    @Inject(at = @At("HEAD"), method = "renderLevel")
    private void onWorldRenderBegin(CallbackInfo callbackInfo)
    {
        BBSRendering.onWorldRenderBegin();
    }

    /**
     * These injections substitute an orthographic projection when the film
     * editor's orbit camera asks for one (see BBSRendering#getOrthoProjection).
     * The frustum culling matrix gets the same treatment inside
     * WorldRendererMixin#onSetupFrustumProjection (with a loose lower bound on
     * the frame size, so culling stays conservative when zoomed all the way in).
     */
    /**
     * Record the matrix the world is actually viewed through.
     *
     * <p>{@code BBSRendering.camera} is read by the film editor to place its picking pass and to recover
     * the gizmo's world position, but its only writer — {@code WorldRendererMixin#setupFrustum} — is not
     * registered in {@code bbs.client.mixins.json} on this branch, so it stayed IDENTITY: the picking
     * geometry was drawn with no view at all (nothing was pickable) and the gizmo's drag maths worked off
     * a view-less matrix.
     *
     * <p>Argument 4 of this call is {@code new Matrix4f().rotation(camera.getRotation().conjugate(..))},
     * i.e. the world view — verified against {@code GameRenderer.renderWorld}'s bytecode. Recorded, not
     * modified; the value is returned untouched.
     */
    @ModifyArg(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"),
        index = 4
    )
    private Matrix4fc onRenderView(Matrix4fc view)
    {
        BBSRendering.camera.set(view);

        return view;
    }

    /**
     * Ortho projection, render half. On 1.21.11 the projection the world is actually drawn with does
     * NOT travel through WorldRenderer.render's Matrix4f arguments: index 6 feeds only setupFrustum
     * (culling) and index 5 is never even loaded (verified against the bytecode). The GPU reads the
     * matrix uploaded here — renderWorld's single {@code RawProjectionMatrix.set} call, whose slice
     * goes to {@code RenderSystem.setProjectionMatrix}. Substituting THIS argument is what makes the
     * ortho frame real; the culling matrix gets its own (loose) substitution in
     * {@code WorldRendererMixin#onSetupFrustumProjection}.
     */
    @ModifyArg(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
        )
    )
    private Matrix4f onSetWorldProjection(Matrix4f projection)
    {
        Matrix4f result = BBSRendering.getOrthoProjection((GameRenderer) (Object) this, projection, 0F);

        /* Cache what the world is actually drawn with, so the GUI-phase picking passes can bind the same
         * projection instead of the interface ortho that happens to be current there. */
        BBSRendering.setWorldProjection(result);

        return result;
    }

    /**
     * Ortho projection, sorter half: the world upload pairs the UBO slice with a ProjectionType whose
     * VertexSorter orders translucent geometry — keep it consistent with the substituted matrix.
     * Ordinal 0 is the world upload; the later setProjectionMatrix in renderWorld (hand/HUD, built
     * from ProjectionMatrix3) stays untouched.
     */
    @ModifyArg(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V",
            ordinal = 0
        ),
        index = 1
    )
    private ProjectionType onSetWorldProjectionType(ProjectionType type)
    {
        return BBSRendering.isOrthoActive() ? ProjectionType.ORTHOGRAPHIC : type;
    }

    /**
     * Chroma sky: feed the world's recorded "clear" pass the chroma colour instead of the fog
     * colour. This {@code Vector4f} argument is consumed by exactly one thing inside
     * {@code WorldRenderer.render} — the lambda of the "clear" pass (verified against the
     * 1.21.11 bytecode) — so substituting it recolours the background and nothing else. The
     * sky pass that would paint over it is cancelled in {@code WorldRendererMixin#onRenderSky},
     * and the fog UBO (a separate argument) is left untouched.
     */
    @ModifyArg(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"
        ),
        index = 6
    )
    private Vector4f onRenderSkyColor(Vector4f skyColor)
    {
        if (BBSSettings.chromaSkyEnabled.get())
        {
            Integer fromCurve = BBSRendering.getChromaSkyColorArgb();
            int argb = fromCurve != null ? fromCurve : BBSSettings.chromaSkyColor.get();
            Color color = Color.rgba(argb);

            return new Vector4f(color.r, color.g, color.b, 1F);
        }

        return skyColor;
    }

    @Inject(at = @At("RETURN"), method = "renderLevel")
    private void onWorldRenderEnd(CallbackInfo callbackInfo)
    {
        BBSRendering.onWorldRenderEnd();
    }

    @Inject(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V", ordinal = 0), require = 0)
    private void onBeforeHudRendering(DeltaTracker tickCounter, boolean tick, CallbackInfo info)
    {
        ICameraController current = BBSModClient.getCameraController().getCurrent();

        if (Minecraft.getInstance().gui.hud.isHidden() && current == null)
        {
            BBSRendering.onRenderBeforeScreen();
        }
    }

    /**
     * The interface has just been composited into {@code mc.getFramebuffer()}. A world recording holds its
     * snapshot back until here so the hotbar and the rest of the HUD end up in the file, the way they did on
     * 1.21.1 when InGameHud.render still drew instead of recording into a GuiRenderState.
     */
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V", shift = At.Shift.AFTER))
    private void onAfterInterfaceRendering(DeltaTracker tickCounter, boolean tick, CallbackInfo info)
    {
        BBSRendering.onRenderAfterInterface();
    }
}