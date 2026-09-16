package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.renderer.MorphRenderer;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.renderers.utils.FramebufferDebug;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class WorldRendererMixin
{
    /* 1.21.11 renamed and privatized the outlines framebuffer field. */
    @Shadow @Final
    private RenderTarget entityOutlineTarget;

    /* Deferred form translucency spans the frame: forms enqueue their translucent pass while
     * entities render, and the queue flushes at the end of WorldRenderEvents.AFTER_ENTITIES (see
     * BBSModClient), right after the last form has drawn. The RETURN hook here is the safety net
     * for anything that draws a form later in the frame — the flush deactivates the queue, so
     * whichever of the two runs first owns the replay and the other is a no-op. */
    @Inject(method = "render", at = @At("HEAD"))
    public void onRenderWorldStart(CallbackInfo info)
    {
        FormTranslucentQueue.begin();
        FramebufferDebug.newFrame();

        /* The GUI entity span is closed on RETURN of that draw, which a throw inside it would skip —
         * and a stuck flag would make every world morph draw in the build phase, i.e. vanish. The world
         * is the one place that can state the truth unconditionally: we are not in the GUI here. */
        MorphRenderer.setGuiPass(false);
    }

    @Inject(method = "render", at = @At("RETURN"))
    public void onRenderWorldEnd(CallbackInfo info)
    {
        FormTranslucentQueue.flush();
    }

    /**
     * Chroma sky: skip the vanilla sky pass so the frame keeps the flat chroma colour that the
     * recorded "clear" pass was fed with (GameRendererMixin substitutes the fog/clear colour
     * argument of {@code WorldRenderer.render} when chroma is enabled).
     *
     * <p>On 1.21.1 this hook cleared the colour buffer by hand at {@code renderSky} HEAD; in the
     * frame-graph world the clear is a recorded pass of its own, so the hook shrinks to cancelling
     * the sky geometry. Terrain hiding moved to {@code SectionRenderStateMixin} (the old
     * {@code renderLayer} chunk hook is gone with the chunk {@code RenderLayer} pipeline), and the
     * fog UBO is intentionally left alone — terrain, when shown, still fades toward the real fog
     * colour rather than the chroma colour.
     */
    @Inject(
        method = "addSkyPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onRenderSky(FrameGraphBuilder frameGraphBuilder, CameraRenderState camera, GpuBufferSlice fog, CallbackInfo info)
    {
        if (BBSSettings.chromaSkyEnabled.get())
        {
            info.cancel();
        }
    }

    @Inject(at = @At("RETURN"), method = "resize")
    private void onResized(int width, int height, CallbackInfo info)
    {
        if (this.entityOutlineTarget == null)
        {
            return;
        }

        BBSRendering.resizeExtraFramebuffers();
    }
}
