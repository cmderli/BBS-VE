package mchorse.bbs_mod.forms;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * BBS's own command queue + flusher for drawing vanilla-rendered things (entities, their held
 * items, name-tag text, fire) OUTSIDE the world's entity pass — in the film's form pass and in
 * UI viewports.
 *
 * <p>The 1.21.2 render-state split removed every immediate entity-drawing API: renderers only
 * <em>submit commands</em> into an {@link OrderedRenderCommandQueue}, and the queue is flushed
 * once per frame by the world's {@link RenderDispatcher}. BBS draws forms at its own moments
 * (AFTER_ENTITIES, editor FBO passes), so it owns a private queue and flushes it synchronously:
 * submit through {@link #queue()}, then {@link #flush()} right after. The dispatcher is built
 * over {@link FormUtilsClient#getProvider()} — the BBS recolor/substitution provider — so every
 * command family renders through the same {@code VertexConsumerProvider} the rest of the form
 * pipeline uses (and the substitution hooks keep working).
 *
 * <p>Layered customs (the new particle path) are deliberately NOT flushed through the vanilla
 * {@code LayeredCustomCommandRenderer}: it binds the client framebuffer directly (checked against
 * the 1.21.11 bytecode), which would punch UI-viewport draws onto the screen. Nothing BBS submits
 * uses them; the world's own particles go through the world's dispatcher as always.
 *
 * <p>Render thread only, non-reentrant by design (a flush must complete before the next submit
 * cycle; nested form-in-form draws all land in the same cycle before the single flush).
 */
public class QueueDispatch
{
    private static FeatureRenderDispatcher dispatcher;
    private static SubmitNodeStorage storage;

    private static FeatureRenderDispatcher get()
    {
        if (dispatcher == null)
        {
            Minecraft mc = Minecraft.getInstance();

            /* 26.2's dispatcher is built over the frame's own render buffers, model manager, atlas
             * manager, font and game render state; the block renderer and the outline/crumbling
             * buffer sources of 1.21.11 no longer exist. The submit storage is the queue this class
             * hands out, and renderAllFeatures() drains it (PreparedFrame#close clears the phases),
             * so it is reusable across flushes. */
            storage = new SubmitNodeStorage();

            dispatcher = new FeatureRenderDispatcher(
                mc.gameRenderer.renderBuffers(),
                mc.getModelManager(),
                mc.getAtlasManager(),
                mc.font,
                mc.gameRenderer.gameRenderState()
            );
        }

        return dispatcher;
    }

    public static SubmitNodeCollector queue()
    {
        get();

        return storage;
    }

    /** The dispatcher itself — {@code ImmediateGui} builds its private GuiRenderer over it. */
    public static FeatureRenderDispatcher dispatcher()
    {
        return get();
    }

    /**
     * Render everything submitted since the last flush through the BBS provider. The caller still
     * owns the provider's {@code draw()} (this only replays commands into it), matching how the
     * other form renderers batch-then-draw.
     */
    public static void flush()
    {
        get().renderAllFeatures(storage);
    }

    /**
     * A camera render state for the active vanilla camera — fire billboards read its orientation.
     */
    public static CameraRenderState cameraState()
    {
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        CameraRenderState state = new CameraRenderState();
        Vec3 pos = camera.position();

        state.initialized = true;
        state.pos = pos;
        state.blockPos = camera.blockPosition();
        state.orientation = new Quaternionf(camera.rotation());

        return state;
    }
}
