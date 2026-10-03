package mchorse.bbs_mod.client;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import mchorse.bbs_mod.graphics.InverseView;
import mchorse.bbs_mod.client.render.ScreenQuadPass;
import mchorse.bbs_mod.mixin.client.WindowFramebufferAccessor;
import mchorse.bbs_mod.graphics.gpu.BBSGpu;
import mchorse.bbs_mod.graphics.gpu.BBSRenderPipelines;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.camera.clips.misc.CurveClip;
import mchorse.bbs_mod.camera.controller.CameraWorkCameraController;
import mchorse.bbs_mod.camera.controller.PlayCameraController;
import mchorse.bbs_mod.api.events.ModelBlockEntityUpdateCallback;
import mchorse.bbs_mod.client.renderer.MorphRenderer;
import mchorse.bbs_mod.forms.FormRenderLast;
import mchorse.bbs_mod.forms.renderers.utils.RecolorVertexConsumer;
import mchorse.bbs_mod.forms.structure.StructureWand;
import mchorse.bbs_mod.utils.VideoRecorder;
import mchorse.bbs_mod.utils.sodium.SodiumUtils;
import mchorse.bbs_mod.graphics.ScreenPixelProbe;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.texture.TextureFormat;
import mchorse.bbs_mod.mixin.client.FogRendererAccessor;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.FrameOverlays;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.cubic.model.ModelSetupQueue;
import mchorse.bbs_mod.forms.renderers.utils.RenderFrame;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.utils.iris.IrisUtils;
import mchorse.bbs_mod.utils.iris.ShaderCurves;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.profiler.BBSProfiler;
import mchorse.bbs_mod.utils.colors.Colors;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.material.FogType;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.MainTarget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.DeltaTracker;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class BBSRendering
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Cached rendered model blocks
     */
    public static final Set<ModelBlockEntity> capturedModelBlocks = new HashSet<>();

    public static boolean canRender;

    public static boolean renderingWorld;
    public static int lastAction;

    public static final Matrix4f camera = new Matrix4f();

    /**
     * The projection the world was last rendered with, captured per frame by
     * {@code GameRendererMixin#onSetWorldProjection} — the matrix of the world's UBO upload, so it
     * already carries the orthographic substitution when the orbit camera asks for one.
     * {@code GameRendererMixin#onRenderProjectionArg} hands the same matrix to Sodium's chunk capture.
     *
     * <p>Needed because BBS picking runs in the GUI phase, where the engine's bound Projection UBO is the
     * interface's ortho — drawing world geometry against it puts every pixel somewhere other than where the
     * user sees it. 1.21.1 solved this with RenderSystem.setProjectionMatrix; on 1.21.11 the projection is
     * GPU-owned, so the picker passes bind this explicitly instead (BBSPickerRenderer#setProjectionOverride).
     */
    private static final Matrix4f worldProjection = new Matrix4f();

    public static void setWorldProjection(Matrix4f projection)
    {
        worldProjection.set(projection);
    }

    public static Matrix4f getWorldProjection()
    {
        return worldProjection;
    }

    private static boolean customSize;

    /**
     * Resolved at class initialisation rather than in {@link #setup()} on purpose: BBSShaders assigns
     * each pipeline to an Iris program as it registers it, and its own pipelines are static finals, so
     * whether the flag is set yet would otherwise depend on which class the client happened to touch
     * first — and a false read here fails silently, leaving forms invisible under a shaderpack again.
     * FabricLoader is up long before any of this.
     */
    private static boolean iris = FabricLoader.getInstance().isModLoaded("iris");

    /** Same class-init timing (and reason) as {@link #iris}: a late false read fails silently. */
    private static boolean sodium = FabricLoader.getInstance().isModLoaded("sodium");
    private static boolean optifine;

    private static int width;
    private static int height;

    /* Orbit distance for the orthographic projection; negative = perspective.
     * Re-armed every frame by the film editor's orbit camera (which is set up
     * from Camera#update, between renderWorld's HEAD and its projection use),
     * so it can never go stale when another controller takes over. */
    private static float orthoDistance = -1F;

    private static boolean toggleFramebuffer;
    private static RenderTarget framebuffer;
    private static RenderTarget clientFramebuffer;
    private static Texture texture;

    /** Private read FBO used to snapshot our framebuffer's colour attachment into {@link #texture}. */
    private static int captureReadFramebuffer = -1;

    /** Private draw FBO the snapshot {@link #texture} is attached to for that blit. */
    private static int captureDrawFramebuffer = -1;

    /** Set while a world recording is holding its snapshot back until the interface has been drawn. */
    private static boolean deferredCapture;

    private static Runnable pendingExportResolutionAction;

    public static int getMotionBlur()
    {
        return getMotionBlur(BBSSettings.videoFrameRate.get(), getMotionBlurFactor());
    }

    public static int getMotionBlur(double fps, int target)
    {
        int i = 0;

        while (fps < target)
        {
            fps *= 2;

            i++;
        }

        return i;
    }

    public static int getMotionBlurFactor()
    {
        return getMotionBlurFactor(BBSSettings.videoMotionBlur.get());
    }

    public static int getMotionBlurFactor(int integer)
    {
        return integer == 0 ? 0 : (int) Math.pow(2, 6 + integer);
    }

    public static int getVideoWidth()
    {
        return width == 0 ? BBSSettings.videoWidth.get() : width;
    }

    public static int getVideoHeight()
    {
        return height == 0 ? BBSSettings.videoHeight.get() : height;
    }

    public static int getVideoFrameRate()
    {
        int frameRate = BBSSettings.videoFrameRate.get();

        return frameRate * (1 << getMotionBlur(frameRate, getMotionBlurFactor()));
    }

    public static File getVideoFolder()
    {
        File movies = new File(BBSMod.getSettingsFolder().getParentFile(), "movies");
        String configured = BBSSettings.videoExportPath.get();

        /* A blank setting must mean "use the default", which is what the default value of "" implies.
         *
         * It did not: new File("") is the CURRENT DIRECTORY, and isDirectory() answers true for it, so the
         * guard below accepted it and the export folder became "". mkdirs() then fails and
         * ProcessBuilder.directory(new File("")) throws
         *
         *     IOException: Cannot run program "ffmpeg" (in directory ""): Failed to access working directory
         *
         * which aborts the recording before a single frame is captured — no video, and nothing in the log that
         * names the folder, so it reads as "export silently does nothing". */
        if (configured != null && !configured.isBlank())
        {
            File exportPath = new File(configured);

            if (exportPath.isDirectory())
            {
                movies = exportPath;
            }
        }

        movies.mkdirs();

        return movies;
    }

    public static boolean canReplaceFramebuffer()
    {
        /* The world always renders at the export size. The interface (HUD) is drawn after the
         * world but still into our export framebuffer — toggleFramebuffer stays on until the blit —
         * so it must use the export size too. Otherwise it renders at the real window size and, when
         * the window can't physically reach the requested resolution, comes out stretched in the
         * file. Excluded while a BBS editor is open so the film panel's own UI keeps rendering at the
         * real window size. */
        if (Boolean.getBoolean("bbs.disableCustomSize"))
        {
            return false;
        }

        return customSize && (renderingWorld || (toggleFramebuffer && UIScreen.getCurrentMenu() == null));
    }

    public static boolean isCustomSize()
    {
        return customSize;
    }

    public static void setCustomSize(boolean customSize)
    {
        setCustomSize(customSize, 0, 0);
    }

    public static void setCustomSize(boolean customSize, int w, int h)
    {
        int newWidth = !customSize ? 0 : w;
        int newHeight = !customSize ? 0 : h;

        /* No-op when nothing actually changes. A redundant setCustomSize(false)
         * — e.g. a film panel disappearing while custom size is already off, which
         * happens when the dashboard is first lazily created by the teleport/record
         * keybinds — must NOT resize the vanilla framebuffers: that stalls the GPU
         * and freezes the screen for a frame even though the state didn't change. */
        if (BBSRendering.customSize == customSize && width == newWidth && height == newHeight)
        {
            return;
        }

        LOGGER.info("[BBS film] setCustomSize customSize={} w={} h={} (stored width/height will be {})",
            customSize, w, h, customSize ? w + "/" + h : "0/0");
        BBSRendering.customSize = customSize;

        width = newWidth;
        height = newHeight;

        if (!customSize)
        {
            resizeExtraFramebuffers();
        }
    }

    public static Texture getTexture()
    {
        if (texture == null)
        {
            texture = new Texture();
            /* RGBA8, with the alpha dealt with at capture time instead of by the format.
             *
             * The reason for wanting no alpha has not changed: the world framebuffer's sky/cleared regions carry
             * a non-opaque alpha that, if preserved, shows through as the panel background in the preview blit
             * (GUI_TEXTURED multiplies texel alpha). That used to be free, because an RGB8 texture has no alpha
             * to preserve.
             *
             * 26.2 removed that option: GlDevice.createTexture rejects RGB8 ("RGB8_UNORM format cannot be used
             * to create textures"), because the GL internal/external/type triple GlConst builds for it is not a
             * combination glTexImage2D accepts. The alpha is therefore forced opaque by the blit itself — see
             * blitIntoSnapshot. */
            texture.setFormat(TextureFormat.RGBA_U8);
            texture.setFilter(GL11.GL_NEAREST);
        }

        return texture;
    }

    public static void startTick()
    {
        capturedModelBlocks.clear();
    }

    public static void setup()
    {
        /* Iris is coupled again — see the field, which resolves itself, plus the pipeline assignment in
         * BBSShaders. A shaderpack draws BBS forms, the shadow pass is told apart, and PBR maps reach
         * it; the pack's option menus inside BBS's UI are what stays decoupled.
         *
         * Sodium still is: nothing in BBS asks it anything except the ortho frame's point-camera
         * culling relaxation, which is a nicety. */

        if (iris)
        {
            /* The PBR bridge: BBS textures are raw GL names Iris knows nothing about, so it is told
             * about them (trackTexture) and given loaders that answer with their _n/_s files or with
             * the material tab's generated maps. */
            IrisUtils.setup();
        }

        LOGGER.info("[BBS shaders] Iris integration {}", iris ? "on" : "off (mod not present)");
        optifine = FabricLoader.getInstance().isModLoaded("optifabric");

        /* Under the orthographic projection the whole frame sits at roughly the same depth, but
         * blocks near the screen edges are laterally further from the camera point than the view
         * distance — the fog paints them sky coloured, which reads as geometry vanishing at the
         * edges. Push every fog bound out of reach for the ortho frame (the 1.21.1 port did the
         * same via BackgroundRenderer + RenderSystem.setShaderFogStart/End; both are gone, fog is
         * a UBO now, and FogModifier is the remaining override point).
         *
         * Registered at index 0: FogRenderer#applyFog takes the FIRST modifier whose shouldApply
         * passes and stops (verified against the bytecode) — appending would put us behind
         * AtmosphericFogModifier, which matches every normal above-water frame, and we would
         * never run. */
        FogRendererAccessor.bbs$getFogModifiers().add(0, new FogEnvironment()
        {
            @Override
            public boolean isApplicable(FogType submersionType, Entity entity)
            {
                return BBSRendering.isOrthoActive();
            }

            @Override
            public void setupFog(FogData fogData, Camera camera, ClientLevel clientWorld, float f, DeltaTracker renderTickCounter)
            {
                fogData.environmentalStart = 1_000_000F;
                fogData.renderDistanceStart = 1_000_000F;
                fogData.environmentalEnd = 1_001_000F;
                fogData.renderDistanceEnd = 1_001_000F;
                fogData.skyEnd = 1_001_000F;
                fogData.cloudEnd = 1_001_000F;
            }
        });

        ModelBlockEntityUpdateCallback.EVENT.register((entity) ->
        {
            if (entity.getLevel().isClientSide())
            {
                capturedModelBlocks.add(entity);
            }
        });
    }

    /* Framebuffers */

    public static RenderTarget getFramebuffer()
    {
        return framebuffer;
    }

    public static void setupFramebuffer()
    {
        Window window = Minecraft.getInstance().getWindow();

        framebuffer = new MainTarget(window.getWidth(), window.getHeight());
    }

    public static void resizeExtraFramebuffers()
    {
        Set<RenderTarget> buffers = new HashSet<>();
        Minecraft mc = Minecraft.getInstance();

        buffers.add(mc.levelRenderer.entityOutlineTarget());
        buffers.add(mc.levelRenderer.translucentTarget());
        buffers.add(mc.levelRenderer.itemEntityTarget());
        buffers.add(mc.levelRenderer.particlesTarget());
        buffers.add(mc.levelRenderer.weatherTarget());
        buffers.add(mc.levelRenderer.cloudsTarget());

        for (RenderTarget buffer : buffers)
        {
            resizeFramebuffer(buffer);
        }
    }

    public static void resizeFramebuffer(RenderTarget framebuffer)
    {
        if (framebuffer == null)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        /* The REAL framebuffer size, not Window.getWidth/Height - BBS overrides those to the export size while
         * the world renders, and sizing a render target from them is what produced targets at 1280x720,
         * 854x480 and 1279x718 in a 2560x1350 window, and an 854x480 GUI pass area with them. */
        int w = ((WindowFramebufferAccessor) (Object) mc.getWindow()).bbs$getFramebufferWidth();
        int h = ((WindowFramebufferAccessor) (Object) mc.getWindow()).bbs$getFramebufferHeight();

        if (framebuffer.width != w || framebuffer.height != h)
        {
            /* 1.21.11: Framebuffer.resize lost the legacy macOS flag arg. */
            framebuffer.resize(w, h);
        }

        /* Re-derive the window's cached GUI size, ALWAYS - including when the target already had the right
         * size.
         *
         * Window keeps guiScaledWidth/guiScaledHeight as FIELDS, written only by setGuiScale, and vanilla only
         * calls that on a window resize. Resizing a render target never touches them, so
         * Minecraft.getWindow().getGuiScaledWidth() keeps reporting a size that belonged to the previous
         * framebuffer. Everything that lays the interface out reads those fields - UIScreen's width/height and
         * therefore the menu - while the projection and the mouse mapping use the live framebuffer and the live
         * scale. That is why the dashboard was laid out for a fraction of the window and drawn into the top-left
         * of it.
         *
         * This must not sit behind the resize check. BBS binds getWidth/getHeight to the export size during the
         * world phase, so the target is very often already the "right" size by this measure and the early return
         * skipped the refresh exactly when it was needed. */
        mc.getWindow().setGuiScale(mc.getWindow().getGuiScale());
    }

    /**
     * Keep vanilla's cached window size in step with the render target BBS just resized.
     *
     * <p>GameRenderer.render does this, in bytecode:</p>
     *
     * <pre>
     * if (windowRenderState.width != mainRenderTarget.width || windowRenderState.height != mainRenderTarget.height)
     *     this.resize(windowRenderState.width, windowRenderState.height);
     * </pre>
     *
     * <p>So it notices the disagreement and then resizes the target back to the CACHED size rather than to the
     * target's own. BBS resizes its render targets directly (the world/export target, and the client target on
     * re-bind), so the cache holds whatever the size was before, and the next frame silently undoes the resize.
     * That is what kept the interface in a smaller area than the window even after the layout, the projection and
     * the bound target had all been measured correct.</p>
     */
    private static void syncWindowRenderState(RenderTarget target)
    {
        if (target == null)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        GameRenderState state = mc.gameRenderer.gameRenderState();

        if (state != null && state.windowRenderState != null)
        {
            state.windowRenderState.width = target.width;
            state.windowRenderState.height = target.height;
        }
    }

    public static void toggleFramebuffer(boolean toggleFramebuffer)
    {
        if (toggleFramebuffer == BBSRendering.toggleFramebuffer)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        BBSRendering.toggleFramebuffer = toggleFramebuffer;

        if (toggleFramebuffer)
        {
            int w = ((WindowFramebufferAccessor) (Object) mc.getWindow()).bbs$getFramebufferWidth();
            int h = ((WindowFramebufferAccessor) (Object) mc.getWindow()).bbs$getFramebufferHeight();

            resizeExtraFramebuffers();

            if (framebuffer.width != w || framebuffer.height != h)
            {
                framebuffer.resize(w, h);
            }

            clientFramebuffer = mc.gameRenderer.mainRenderTarget();

            reassignFramebuffer(framebuffer);

            /* 1.21.11: Framebuffer.beginWrite(boolean) was removed — render targets are bound implicitly from
             * mc.getFramebuffer() when WorldRenderer/GameRenderer build their render passes. Reassigning
             * mc.framebuffer above is therefore sufficient to redirect the world render into our framebuffer. */
        }
        else
        {
            /* Give the client's own target the window's real size back before re-binding it.
             *
             * BBS binds Window.getWidth/getHeight to the EXPORT size while the world renders, and vanilla creates
             * and resizes its main render target from exactly those two calls. The client target is therefore
             * born at the export size and never grows back on its own, because nothing calls
             * Screen.resize/Window.setGuiScale for it. Measured with a probe on GameRenderer.mainRenderTarget:
             * every single hand-out was 854x480 while the window was 2560x1350.
             *
             * The interface is then extracted into that export-sized target - which is why the dashboard occupied
             * a block in the top-left at exactly the export width, why ui_scale 1 made the components bigger
             * inside the block without growing the block, and why clicks only landed at the full-screen position:
             * the layout and the hit test use the window, the pixels only ever reached the smaller target. */
            if (clientFramebuffer != null)
            {
                int w = ((WindowFramebufferAccessor) (Object) mc.getWindow()).bbs$getFramebufferWidth();
                int h = ((WindowFramebufferAccessor) (Object) mc.getWindow()).bbs$getFramebufferHeight();

                if (clientFramebuffer.width != w || clientFramebuffer.height != h)
                {
                    clientFramebuffer.resize(w, h);
                }

                syncWindowRenderState(clientFramebuffer);
            }

            reassignFramebuffer(clientFramebuffer);

            if (width != 0)
            {
                /* When the film panel is open, the UI draws the preview texture in its block; do not
                 * blit our framebuffer to the full window or the preview would stretch to full screen. */
                UIBaseMenu currentMenu = UIScreen.getCurrentMenu();
                boolean filmPanelShowing = currentMenu instanceof UIDashboard dashboard
                    && dashboard.getPanels().panel instanceof UIFilmPanel;
                if (!filmPanelShowing)
                {
                    composeIntoClientFramebuffer();
                }
            }
        }
    }

    private static void reassignFramebuffer(RenderTarget framebuffer)
    {
        /* The user-facing framebuffer lives on GameRenderer in 26.2 (Minecraft.framebuffer is gone).
         * Swapping it is still how the world render is redirected; see the bbs.accesswidener entry
         * that makes the field writable from outside. */
        Minecraft.getInstance().gameRenderer.mainRenderTarget = framebuffer;
    }

    /**
     * Hand the frame we rendered off-screen back to the client framebuffer, so the game presents it
     * the way it presents any other frame. Only the no-UI recording path needs this: with the film
     * panel open the preview draws the snapshot texture itself.
     *
     * <p>1.21.1 did this with {@code framebuffer.draw(w, h)} — a fullscreen quad into whatever was
     * bound, which the line above had just made the client framebuffer. The 1.21.11 method that
     * inherited the name, {@code blitToScreen()}, is NOT that: it is
     * {@code CommandEncoder.presentTexture}, which goes straight to the window. And vanilla presents
     * again at the end of the same frame — {@code MinecraftClient.render} captures its framebuffer
     * BEFORE the world render (so it captures the client one, not ours) and blits that. Our frame was
     * therefore shown and immediately overwritten by a client framebuffer holding nothing but the
     * frame's opening clear: two presents per frame, the second one black. That is the black flicker
     * that covered the whole screen for the length of every world recording.
     *
     * <p>Copying the colour attachment across is the faithful replacement. {@code drawBlit} is not:
     * it runs through {@code ENTITY_OUTLINE_BLIT}, which alpha-blends (SRC_ALPHA/ONE_MINUS_SRC_ALPHA),
     * and the world framebuffer's sky and cleared regions carry a non-opaque alpha (the same alpha
     * {@link #getTexture()} drops by capturing into RGB8), so blending would darken them into the
     * black destination.
     */
    private static void composeIntoClientFramebuffer()
    {
        RenderTarget client = clientFramebuffer;

        if (client == null || client.getColorTexture() == null || framebuffer.getColorTexture() == null)
        {
            return;
        }

        /* The two normally match (the world export sizes the window to the export resolution, and
         * without that it exports at the window size), but the export size is rounded to even pixels,
         * so a copy of the shared region is what is always defined. */
        int w = Math.min(framebuffer.width, client.width);
        int h = Math.min(framebuffer.height, client.height);

        if (w <= 0 || h <= 0)
        {
            return;
        }

        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
            framebuffer.getColorTexture(), client.getColorTexture(), 0, 0, 0, 0, 0, w, h);
    }

    /* Rendering */

    /**
     * Diagnostic-only timing for -Dbbs.debugRecording, used to find where a recording stalls.
     *
     * <p>Every probe here is a pair of nanoTime calls and one println per frame, and the println is on the
     * same line the freeze investigation needs: it says the stage was ENTERED, so a stage that never prints
     * its exit line is the one that hung.</p>
     */
    private static long dbgT;
    private static int dbgFrames;

    private static void dbgEnter(String stage)
    {
        if (Boolean.getBoolean("bbs.debugRecording"))
        {
            dbgT = System.nanoTime();

            System.out.println("[BBS stage] -> " + stage + " (frame " + dbgFrames + ")");
        }
    }

    private static void dbgExit(String stage)
    {
        if (Boolean.getBoolean("bbs.debugRecording"))
        {
            System.out.println("[BBS stage] <- " + stage + " took " + (System.nanoTime() - dbgT) / 1_000_000 + " ms");
        }
    }

    public static void onWorldRenderBegin()
    {
        dbgEnter("onWorldRenderBegin");
        /* NOTE(ortho lifetime): the ortho flag must NOT be reset here. On 1.21.1 the orbit camera armed
         * it from Camera#update, which ran INSIDE renderWorld — after this HEAD hook — so a HEAD reset
         * was safe. On 1.21.11 Camera#update moved to GameRenderer.render's updateCamera, BEFORE
         * renderWorld: a HEAD reset would wipe the freshly armed flag before anything reads it (that
         * exact inversion made the whole ortho toggle a no-op). The reset lives in onWorldRenderEnd. */
        Minecraft mc = Minecraft.getInstance();

        /* The frame boundary the profiler's counters roll over on; the flag is mirrored here
         * so the hot-path checks read a plain static boolean. */
        BBSProfiler.enabled = BBSSettings.profilerOverlay != null && BBSSettings.profilerOverlay.get();
        BBSProfiler.frame();
        RenderFrame.nextFrame();
        Gizmo.INSTANCE.forgetPlacement();

        /* The budgeted tail of model loading: VAO bakes for whatever the background loader
         * finished, a few milliseconds' worth per frame instead of all of them at once. */
        ModelSetupQueue.drain();

        BBSModClient.getVideos().startFrame();
        BBSModClient.getFilms().startRenderFrame(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));

        UIBaseMenu menu = UIScreen.getCurrentMenu();

        if (menu != null)
        {
            menu.startRenderFrame(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        }

        renderingWorld = true;

        /* A capture deferred to after the interface never got its turn (the interface pass threw, or the
         * frame ended some other way). Unwind it here rather than starting a second frame on top of a
         * still-swapped mc.framebuffer — that is how the screen goes black and stays black. */
        if (deferredCapture)
        {
            deferredCapture = false;

            toggleFramebuffer(false);
        }

        if (!customSize)
        {
            dbgExit("onWorldRenderBegin (no customSize)");

            return;
        }

        /* Redirect the world into our export target. This is what customSize is for, and the snapshot the film
         * preview and the export read is taken from this target.
         *
         * The interface must NOT be drawn through it, though: it is laid out for the WINDOW (menu=1280x675 GUI
         * units against window=2560x1350, i.e. 2560x1350 physical at the GUI scale of 2) while this target is
         * sized in export pixels (getVideoWidth x getVideoHeight). Sharing them is what produced the interface
         * drawn into the top-left with buttons that only answer at their full-screen position.
         *
         * The swap is therefore bounded to the WORLD phase by restoring it at the head of the interface phase —
         * see onRenderBeforeScreen. Reverting the swap here instead (the previous attempt) left the snapshot
         * with no world to capture whenever an editor was open, and the preview went black. */
        toggleFramebuffer(true);
    }

    public static void onWorldRenderEnd()
    {
        if (orthoDistance > 0F)
        {
            /* Give back the culling disabled for this ortho frame (see setOrthoDistance);
             * the orbit re-arms the flag next frame from Camera#update if ortho is still on. */
            Minecraft.getInstance().smartCull = true;

            if (sodium)
            {
                SodiumUtils.restorePointCameraCulling();
            }
        }

        orthoDistance = -1F;

        Minecraft mc = Minecraft.getInstance();

        if (BBSModClient.getCameraController().getCurrent() instanceof PlayCameraController controller)
        {
            /* Recorded into ImmediateGui's private state and flushed right here — a throwaway
             * GuiRenderState nobody renders is how these subtitles used to vanish (two-phase GUI).
             * Flushing now, mid-world-phase, also puts them into mc.framebuffer, which during an
             * export is the export framebuffer: subtitles belong in the film. */
            Batcher2D batcher = new Batcher2D(ImmediateGui.begin());

            /* 1.21.11: FrameOverlays takes a 3D MatrixStack (batcher.getContext().getMatrices() is now
             * a 2D Matrix3x2fStack). The overlays manage their own transform stack, so feed a fresh one. */
            FrameOverlays.render(new PoseStack(), batcher, controller.getContext());
            ImmediateGui.end();
        }

        if (!customSize)
        {
            renderingWorld = false;

            return;
        }

        UIBaseMenu currentMenu = UIScreen.getCurrentMenu();

        if (currentMenu instanceof UIDashboard dashboard)
        {
            if (dashboard.getPanels().panel instanceof UIFilmPanel panel)
            {
                /* Same ImmediateGui flush as the playback branch above: the menu's context here
                 * still points at the PREVIOUS frame's already-composited DrawContext, so recording
                 * into it dropped the overlays on the floor. Flushing immediately also lands them
                 * in mc.framebuffer — the film preview/export framebuffer during this phase — so
                 * they show in the panel preview and in the exported file, like on 1.21.1. */
                Batcher2D batcher = new Batcher2D(ImmediateGui.begin());

                FrameOverlays.render(new PoseStack(), batcher, panel.getRunner().getContext());
                ImmediateGui.end();
            }
        }

        renderingWorld = false;
    }

    public static void onRenderBeforeScreen()
    {
        /* Pin the window framebuffer for the whole interface phase, but ONLY when an editor is open.
         *
         * The render target oscillates between BBS's export target and the client's once per frame (measured:
         * "main=1280x720" alternating with "main=854x480 ... caller=toggleFramebuffer"), and the interface is
         * extracted into whichever happens to be bound. Once the window is larger than the export size the
         * dashboard is therefore rasterised into the export-sized target and only fills that part of the window -
         * and because the layout and the hit test use the window, the buttons stop matching the cursor as well.
         *
         * Gated on a menu being open so the world/export path is untouched: with an editor up there is nothing to
         * capture for the export, and captureAndRestore's own restore still covers the recording path. */
        if (UIScreen.getCurrentMenu() != null)
        {
            toggleFramebuffer(false);
        }

        /* On 1.21.1 InGameHud.render DREW the interface, into whatever was bound — our export framebuffer,
         * because the restore below sat at that method's TAIL, after the drawing. That is why a world
         * recording carried the hotbar, the health bar and everything else. On 1.21.11 InGameHud.render only
         * RECORDS into a GuiRenderState; the drawing happens later, in GuiRenderer.render. Capturing here
         * would capture the bare world, which is exactly what went missing from first-person recordings.
         *
         * So when no BBS menu is up — the world recording — hold both the snapshot and the restore until
         * {@link #onRenderAfterInterface()}, which runs once the interface really has been drawn, still into
         * our framebuffer because mc.framebuffer is still pointed at it. The size the interface lays itself
         * out at already follows (see canReplaceFramebuffer).
         *
         * With a BBS menu open the interface must NOT land in the export framebuffer: the film panel draws
         * its own UI at window size and blits the preview texture into it, so that path captures and restores
         * right here, before the interface is composited. */
        if (customSize && UIScreen.getCurrentMenu() == null)
        {
            deferredCapture = true;

            return;
        }

        captureAndRestore();
    }

    /**
     * Runs right after the interface has been composited (see {@code GameRendererMixin}), for the world
     * recording that deferred its capture in {@link #onRenderBeforeScreen()}.
     */
    public static void onRenderAfterInterface()
    {
        /* The one moment in the frame where the interface is actually on the framebuffer, so this is
         * where a read-back of it belongs (the colour picker's eyedropper). */
        ScreenPixelProbe.fulfill();

        if (!deferredCapture)
        {
            return;
        }

        deferredCapture = false;

        captureAndRestore();
    }

    /**
     * Copy the world that just rendered into {@link #framebuffer} into the BBS snapshot {@link #texture} — the
     * one the film preview draws and {@link mchorse.bbs_mod.utils.VideoRecorder} reads back — rescaling it from
     * the framebuffer's physical size down to the export size.
     *
     * <p>A blit, not a {@code glCopyTexSubImage2D}: copying cannot rescale, and rescaling is the whole point
     * (see the caller). It also buys back the supersampling the HiDPI export had on 1.21.1 — the world is
     * rendered at native resolution and filtered down into the file.</p>
     *
     * <p>1.21.11: {@code Framebuffer.beginWrite()} was removed, so neither end of the blit is bound for us. Both
     * get a private FBO here — the framebuffer's colour attachment as the read source, the snapshot texture as
     * the draw target. The bindings are saved and restored; the modern pipeline rebinds its render-pass targets
     * afterwards, so this stays isolated.</p>
     *
     * <p>The framebuffer's non-opaque sky alpha has to be dropped, or the sky shows through as the panel
     * background in the preview (the preview blits through GUI_TEXTURED, which multiplies texel alpha). 1.21.1
     * got that for free from an RGB8 snapshot; 26.2 cannot create an RGB8 texture at all (see
     * {@link #getTexture()}), so the alpha is pinned here in two steps: clear the destination's alpha to 1, then
     * blit with the alpha channel masked out so only RGB is copied over it.</p>
     *
     * <p>Unlike a copy, a blit is clipped by the scissor box and filtered through the colour write mask, and at
     * this point in the frame both belong to whoever drew last. They are neutralised around the blit and put back
     * through {@link GlStateManager} so its cache stays truthful (touching that state behind it desyncs the cache
     * — the same trap {@link mchorse.bbs_mod.graphics.texture.Texture#bind()} documents).</p>
     */
    /**
     * Copy the frame in {@link #framebuffer} into the BBS snapshot {@link #texture}.
     *
     * <p>Two implementations behind one call, because 26.2 offers no backend-neutral blit-with-scaling:
     * {@code CommandEncoder.copyTextureToTexture} is 1:1, and the snapshot is a rescale (the world may be
     * rendered larger than the export size). A fullscreen textured quad through a render pass is the
     * portable equivalent, and that is what Vulkan gets — the raw-GL {@code glBlitFrameBuffer} below
     * needs a GL context that simply does not exist there, where calling it aborts the JVM.</p>
     */
    private static boolean blitIntoSnapshot(Texture texture, int w, int h)
    {
        dbgEnter("blitIntoSnapshot " + w + "x" + h);

        try
        {
            if (BBSGpu.isVulkan())
            {
                return blitIntoSnapshotDeviceNeutral(texture, w, h);
            }

            blitIntoSnapshotGl(texture, w, h);

            return true;
        }
        finally
        {
            dbgExit("blitIntoSnapshot");
        }
    }

    /**
     * The 26.2 device path: draw the framebuffer's colour texture over a fullscreen quad into the
     * snapshot. Backend-neutral, so it works on both, and the alpha drop lives in the shader.
     */
    private static boolean blitIntoSnapshotDeviceNeutral(Texture texture, int w, int h)
    {
        GpuTexture source = framebuffer.getColorTexture();

        if (source == null || texture.view() == null)
        {
            return false;
        }

        /* One view per colour texture, cached. Allocating one per frame and closing it is what broke the
         * interface: a GpuTextureView OWNS the image it wraps — closing the view destroys the underlying
         * texture — so the frame after the first snapshot had lost the framebuffer it had just rendered
         * into. The symptoms were an interface drawn into one corner and every later texture creation
         * failing with VK_ERROR_INITIALIZATION_FAILED, neither of which pointed at this line.
         *
         * Closing it is right for a texture BBS owns (see Texture.pixelsFromTexture) and wrong for a
         * borrow: this view stays valid as long as the framebuffer's colour texture does, i.e. until the
         * framebuffer is resized or released. */
        if (snapshotSourceView == null || snapshotSourceTexture != source)
        {
            snapshotSourceView = BBSGpu.device().createTextureView(source);
            snapshotSourceTexture = source;
        }

        /* The snapshot is the export resolution and the framebuffer is the render resolution; when they
         * differ this is a rescale, so the sampler has to interpolate. At 1:1 it is a copy either way. */
        GpuSampler sampler = framebuffer.width == w && framebuffer.height == h
            ? BBSGpu.nearestSampler()
            : BBSGpu.linearSampler();

        ScreenQuadPass.Quad quad = new ScreenQuadPass.Quad(BBSRenderPipelines.BLIT, texture.view(), w, h)
            .rect(0F, 0F, w, h)
            .uv(0F, 0F, 1F, 1F)
            .texture(snapshotSourceView, sampler);

        return ScreenQuadPass.draw("bbs:film_snapshot", quad);
    }

    /**
     * The GL fast path, unchanged: bind the two attachments to private FBOs and blit. Kept because it is
     * one driver call rather than a pass, and because it is the path the GL backend has always used.
     */
    private static void blitIntoSnapshotGl(Texture texture, int w, int h)
    {
        int sourceWidth = framebuffer.width;
        int sourceHeight = framebuffer.height;

        if (captureReadFramebuffer == -1)
        {
            captureReadFramebuffer = GL30.glGenFramebuffers();
            captureDrawFramebuffer = GL30.glGenFramebuffers();
        }

        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int sourceId = ((GlTexture) framebuffer.getColorTexture()).glId();

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, captureReadFramebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, sourceId, 0);
        GL30.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, captureDrawFramebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, ((GlTexture) texture.gpuTexture).glId(), 0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);

        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean[] mask = readColorMask();

        if (scissor)
        {
            GlStateManager._disableScissorTest();
        }

        /* Pin the destination's alpha to 1 before copying any colour into it. Only the alpha channel is
         * writable for the clear, so the RGB already in the snapshot is left alone — it is about to be
         * overwritten by the blit anyway, but a blit never writes outside its destination rect and this
         * keeps the two steps independent. */
        GlStateManager._colorMask(0x8);
        GlStateManager._clearBuffer(GL11.GL_COLOR, new Vector4f(0F, 0F, 0F, 1F));

        /* And then copy RGB only, so the source's sky alpha cannot land on the 1 that was just written. */
        GlStateManager._colorMask(0x7);

        /* GL_LINEAR only where it actually resamples: at 1:1 — every display that is not HiDPI — a nearest
         * blit is the same copy the snapshot has always been. */
        GlStateManager._glBlitFrameBuffer(
            0, 0, sourceWidth, sourceHeight,
            0, 0, w, h,
            GL11.GL_COLOR_BUFFER_BIT,
            sourceWidth == w && sourceHeight == h ? GL11.GL_NEAREST : GL11.GL_LINEAR
        );

        GlStateManager._colorMask((mask[0] ? 1 : 0) | (mask[1] ? 2 : 0) | (mask[2] ? 4 : 0) | (mask[3] ? 8 : 0));

        if (scissor)
        {
            GlStateManager._enableScissorTest();
        }

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
    }

    private static boolean[] readColorMask()
    {
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            ByteBuffer mask = stack.malloc(4);

            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);

            return new boolean[] {mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0};
        }
    }

    /** Borrowed view of the framebuffer's colour texture, cached; see blitIntoSnapshotDeviceNeutral. */
    private static GpuTextureView snapshotSourceView;
    private static GpuTexture snapshotSourceTexture;


    /**
     * Diagnostic: read a few texels from a texture and print the first, to tell a black source from a failed
     * copy. One-shot per call because each read blocks on the GPU.
     */
    private static void dbgSamplePixels(String what, GpuTexture texture)
    {
        if (texture == null)
        {
            System.out.println("[BBS px] " + what + ": texture is null");

            return;
        }

        int w = Math.min(2, texture.getWidth(0));
        int h = Math.min(2, texture.getHeight(0));
        GpuBuffer buffer = BBSGpu.device().createBuffer(
            () -> "bbs px probe",
            GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
            (long) w * h * texture.getFormat().blockSize()
        );

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        BBSGpu.encoder().copyTextureToBuffer(texture, buffer, 0, latch::countDown, 0);

        try
        {
            if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS))
            {
                System.out.println("[BBS px] " + what + ": read-back timed out");

                return;
            }

            try (GpuBufferSlice.MappedView mapped = buffer.map(true, false))
            {
                java.nio.ByteBuffer data = mapped.data();

                data.position(0);

                int r = data.get() & 0xFF;
                int g = data.get() & 0xFF;
                int b = data.get() & 0xFF;
                int a = data.get() & 0xFF;

                System.out.println("[BBS px] " + what + " = rgba(" + r + "," + g + "," + b + "," + a + ")");
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        finally
        {
            buffer.close();
        }
    }

    private static void captureAndRestore()
    {
        dbgEnter("captureAndRestore");

        if (Boolean.getBoolean("bbs.debugRecording") && dbgFrames % 150 == 0 && customSize && framebuffer != null)
        {
            dbgSamplePixels("framebuffer", framebuffer.getColorTexture());
        }
        /* Snapshot only when we actually redirected the world into our framebuffer this frame (film panel
         * open / recording). Outside that, mc.framebuffer was never swapped, so our framebuffer holds nothing
         * worth copying and the snapshot would just waste a per-frame GPU copy. */
        if (customSize)
        {
            /* An open editor still wants the snapshot — that is the picture in the preview block — but it must
             * not draw through the export target, because the interface is laid out for the WINDOW: menu=1280x675
             * GUI units against window=2560x1350, i.e. 2560x1350 physical pixels at the GUI scale of 2, while the
             * target is sized in export pixels (getVideoWidth x getVideoHeight). Sharing them is what produced the
             * interface drawn into the top-left with buttons that only answered at their full-screen position.
             *
             * So: capture while the world target is still bound, then hand the window framebuffer back before the
             * interface draws. This is the one point in the frame that is both after the world render and before
             * the interface, which is why the restore lives here rather than in onRenderBeforeScreen — that hook
             * runs after onWorldRenderEnd, and restoring there was both too late and unconditional.
             *
             * toggleFramebuffer is a no-op when it is already off, so the later, unconditional restore at the end
             * of this method costs nothing. */
            boolean menuOpen = UIScreen.getCurrentMenu() != null;

            {
                /* The snapshot IS the recording, so it is sized in video pixels — not in the physical pixels the
                 * world was just rendered at. On a HiDPI display those are not the same number: WindowMixin reports
                 * the framebuffer size as getVideoWidth() * getOriginalFramebufferScale(), so on a Retina Mac
                 * (scale 2) the framebuffer is twice the export size in each axis. Sizing the snapshot from
                 * framebuffer.textureWidth therefore handed VideoRecorder a texture four times the buffer it had
                 * allocated (getVideoWidth() * getVideoHeight() * 3), and glGetTexImage — which downloads the whole
                 * level, there is no size to pass it — wrote straight past the end of it. Every export on a Mac
                 * died there, inside the driver's pixel-store loop (SIGBUS). */
                Texture texture = getTexture();
                int w = getVideoWidth();
                int h = getVideoHeight();

                if (Boolean.getBoolean("bbs.debugRecording") && dbgFrames % 60 == 0)
                {
                    System.out.println("[BBS snap] texBefore=" + texture.width + "x" + texture.height
                        + " want=" + w + "x" + h
                        + " fb=" + framebuffer.width + "x" + framebuffer.height
                        + " stored=" + width + "/" + height
                        + " valid=" + texture.isValid()
                        + " menu=" + menuOpen);
                }


                if (texture.width != w || texture.height != h)
                {
                    /* 26.2 has no texture binding to bracket a resize with: setSize reallocates the
                     * device texture itself. */
                    texture.setSize(w, h);
                }

                /* -Dbbs.skipSnapshot=true leaves the snapshot untouched, which isolates the rest of the frame
                 * from it: the preview then shows the previous contents and nothing else changes. Kept as a
                 * diagnostic because it is the quickest way to tell whether a rendering problem is the
                 * snapshot's or something else. */
                if (!Boolean.getBoolean("bbs.skipSnapshot"))
                {
                    blitIntoSnapshot(texture, w, h);

                    if (Boolean.getBoolean("bbs.debugRecording") && dbgFrames % 60 == 0)
                    {
                        System.out.println("[BBS snap] texAfter=" + texture.width + "x" + texture.height
                            + " valid=" + texture.isValid()
                            + " view=" + (texture.view() != null));
                    }

                    if (Boolean.getBoolean("bbs.debugRecording") && dbgFrames % 150 == 0)
                    {
                        dbgSamplePixels("snapshot", texture.gpuTexture);
                    }
                }

                /* AFTER the capture — the blit reads framebuffer — and still BEFORE the interface draws.
                 * toggleFramebuffer is a no-op when it is already off, so the unconditional restore at the
                 * end of this method costs nothing. */
                if (menuOpen)
                {
                    toggleFramebuffer(false);
                }
            }
        }

        toggleFramebuffer(false);

        /* The snapshot is now filled for this frame, so this is where the recorder reads it. It cannot live at
         * the world-render hook: the world recording defers the capture until after the interface is composited,
         * so a read there saw an untouched snapshot (measured: "captured frame is 0x0"). */
        recordExportFrame();

        /* AFTER the restore: mc.framebuffer points back at the screen, so the operator overlay
         * shows up there and never lands in the exported file. */
        renderRecordingOverlay();
        dbgExit("captureAndRestore");
        dbgFrames++;

        if (pendingExportResolutionAction != null)
        {
            Runnable action = pendingExportResolutionAction;
            pendingExportResolutionAction = null;
            Minecraft.getInstance().execute(action);
        }
    }

    /**
     * Hand the freshly captured snapshot to the recorder, one frame at a time.
     *
     * <p>This is the only point in the frame where the snapshot is guaranteed to hold the frame that was just
     * rendered, on both the panel path (captureAndRestore runs before the interface) and the world-recording
     * path (the deferred capture in onRenderAfterInterface).</p>
     */
    private static void recordExportFrame()
    {
        VideoRecorder recorder = BBSModClient.getVideoRecorder();

        if (recorder != null && recorder.isRecording() && canRender)
        {
            recorder.recordFrame();
        }
    }

    public static void scheduleAfterNextExportFrame(Runnable action)
    {
        pendingExportResolutionAction = action;
    }

    public static void onRenderChunkLayer(Matrix4f positionMatrix)
    {
        /* TODO(1.21.11 render): this Iris-only chunk-layer hook used to hand-build a Fabric WorldRenderContextImpl
         * via the old prepare(worldRenderer, tickCounter, blockOutlines, camera, gameRenderer, lightmap,
         * projectionMatrix, positionMatrix, consumers, profiler, advancedTranslucency, world) signature. That API
         * is gone: the context now lives in net.fabricmc.fabric.impl.client.rendering.world and prepare(...) takes
         * the new world-render-state objects (WorldRenderState/SectionRenderState/GpuBufferSlice command queue),
         * and RenderSystem.getProjectionMatrix()/MinecraftClient.getProfiler() were removed.
         *
         * Still a stub now that Iris is coupled again — and nothing waits on it: forms draw from
         * AFTER_ENTITIES in every case (see BBSModClient), and the reason 1.21.1 needed this hook at all
         * is covered by handing Iris the pipeline-to-program mapping instead. Rebuild it only if a pack
         * turns out to need BBS geometry inside the chunk-layer pass specifically. */
        if (isIrisShadersEnabled())
        {
            /* renderCoolStuff(context) — needs a reconstructed WorldRenderContext (see TODO above). */
        }
    }

    public static void renderHud(GuiGraphicsExtractor drawContext, float tickDelta)
    {
        Batcher2D batcher2D = new Batcher2D(drawContext);

        BBSModClient.getFilms().renderHud(batcher2D, tickDelta);
        StructureWand.renderHud(batcher2D);
    }

    /**
     * Draw the recording countdown / frame-counter overlay. This is operator UI: it is drawn from
     * {@link #onRenderBeforeScreen()} after the export blit but before the buffer is copied to the
     * screen, so it shows up on screen but is never captured into the file.
     */
    private static void renderRecordingOverlay()
    {
        /* Drawn with a BBS menu open as well. It used to bail out there, which meant the one overlay that
         * names the stop key was hidden in precisely the state that needs it: starting an export from the film
         * panel installs UIFilmRecorder, and the export then has to be cancelled — the film panel may be
         * waiting on prepared frames and never reach its own completion check, so without this the recording
         * runs until something else stops it. The keybinds cannot help either: Minecraft routes keys to the
         * open screen, so every BBS keybind is dead until the export ends.
         *
         * Safe to draw here: captureAndRestore has already handed mc.framebuffer back to the client, and the
         * menu is composited from that same target afterwards — this is the same point the recording overlay
         * has always used. */
        if (!BBSSettings.recordingOverlays.get())
        {
            return;
        }

        String label;

        if (BBSModClient.isVideoExportDelayPending())
        {
            int countdown = Math.max(0, (int) Math.ceil(BBSModClient.getVideoExportDelayRemainingMs() / 50D));

            label = String.valueOf(countdown / 20F);
        }
        else if (BBSModClient.getVideoRecorder().isRecording())
        {
            int count = BBSModClient.getVideoRecorder().getCounter();

            label = UIKeys.FILM_VIDEO_RECORDING.format(
                count,
                BBSModClient.getKeyRecordVideo().getTranslatedKeyMessage().getString()
            ).get();
        }
        else
        {
            return;
        }

        /* Recorded and flushed on the spot through ImmediateGui — a bare GuiRenderState that
         * nobody renders (the old code here) never reaches the screen on the two-phase GUI. */
        renderRecordingTimerOverlay(new Batcher2D(ImmediateGui.begin()), label);
        ImmediateGui.end();
    }

    public static void renderRecordingTimerOverlay(Batcher2D batcher2D, String label)
    {
        renderRecordingTimerOverlay(batcher2D, label, 5, 5);
    }

    public static void renderRecordingTimerOverlay(Batcher2D batcher2D, String label, int x, int y)
    {
        int iconX = x + 16;

        batcher2D.icon(Icons.SPHERE, Colors.RED | Colors.A100, iconX, y, 1F, 0F);
        batcher2D.textCard(label, iconX + 3, y + 4, Colors.WHITE, Colors.A50);
    }

    /** Whether the entity pass opened the render-last scope — false when one was already open. */
    private static boolean entityPassRenderLast;

    /**
     * The world's entity pass: between these two calls vanilla draws the actors, model blocks
     * and morphed players, and without a shader pack {@link #renderCoolStuff} draws the films
     * at its end — one render-last scope spans it all, so a form set to render last draws after
     * every other form of the frame. Under Iris the films run earlier, at the solid layer, in a
     * scope of their own; this one still covers what the entity loop drew.
     */
    public static void beginEntityPass()
    {
        entityPassRenderLast = FormRenderLast.open();
    }

    public static void endEntityPass()
    {
        FormRenderLast.close(entityPassRenderLast);

        entityPassRenderLast = false;
    }

    public static void renderCoolStuff(LevelRenderContext worldRenderContext)
    {
        /* 1.21.11: the relocated Fabric WorldRenderContext (api.client.rendering.v1.world) again threads a real
         * MatrixStack through context.matrices(), so the previous position-matrix rebuild is no longer needed. */

        /* Feed the world camera orientation into the holder that replaced RenderSystem's inverse view rotation
         * matrix, so billboards and particles keep facing the camera in world space. The context no longer
         * exposes camera()/positionMatrix(); pull the camera from the game renderer directly. */
        InverseView.set(new Matrix3f().rotation(Minecraft.getInstance().gameRenderer.mainCamera().rotation()));

        /* Draw morph forms collected during the (build-phase) entity render. AFTER_ENTITIES is the only
         * world context where the BBS immediate form pipeline lands correctly (entity queue flushed +
         * camera model-view still active). See MorphRenderer / LivingEntityRendererMorphMixin. */
        MorphRenderer.renderQueued(worldRenderContext);

        /* A scope over everything drawn here, for when this runs on its own — under Iris, at the
         * solid layer: forms set to render last draw when it closes, after the last replay, still
         * in this pass. Inside the entity pass's scope this opens nothing and they wait for it. */
        boolean renderLast = FormRenderLast.open();

        try
        {
            if (Minecraft.getInstance().gui.screen() instanceof UIScreen screen)
            {
                screen.renderInWorld(worldRenderContext);
            }

            BBSModClient.getFilms().render(worldRenderContext);
        }
        finally
        {
            FormRenderLast.close(renderLast);
        }
    }

    public static boolean isOptifinePresent()
    {
        return optifine;
    }

    public static boolean isRenderingWorld()
    {
        return renderingWorld;
    }

    /**
     * Arm the orthographic projection for the current frame. Pass the orbit
     * camera's distance to the pivot; negative disables. The value is reset
     * at the beginning of every world render, so the caller must re-arm it
     * each frame for as long as ortho should stay on.
     */
    public static void setOrthoDistance(float distance)
    {
        orthoDistance = distance;

        if (distance > 0F)
        {
            /* The chunk occlusion culling walks sections outward from the
             * camera POINT, which is only sound for a perspective projection —
             * under ortho's parallel sightlines it over-culls sections near
             * the screen edges. Disable it for the frame (Sodium honours the
             * same flag); the frustum and render distance still cull. Sodium's
             * own point-camera heuristics get the same treatment. */
            Minecraft.getInstance().smartCull = false;

            if (sodium)
            {
                SodiumUtils.disablePointCameraCulling();
            }
        }
    }

    public static boolean isOrthoActive()
    {
        return orthoDistance > 0F;
    }

    /**
     * Build the orthographic projection replacing the given perspective one
     * (returns the input untouched when ortho is not armed). FOV and aspect are
     * derived from the perspective matrix itself, so the ortho frame height
     * matches the perspective frame height at the orbit pivot's distance: the
     * subject keeps its size when toggling projections, and the scroll zoom
     * keeps working through the orbit distance.
     *
     * @param minHalfHeight a lower bound on the frame's half height, and the
     *        slack behind the camera plane the near plane is given; the frustum
     *        culling matrix is built with a loose bound on both, so culling
     *        stays conservative when zoomed all the way in.
     */
    public static Matrix4f getOrthoProjection(GameRenderer renderer, Matrix4f perspective, float minHalfHeight)
    {
        if (orthoDistance <= 0F)
        {
            return perspective;
        }

        float tanHalfFov = 1F / perspective.m11();
        float aspect = perspective.m11() / perspective.m00();
        float halfHeight = Math.max(minHalfHeight, orthoDistance * tanHalfFov);
        float halfWidth = halfHeight * aspect;

        /* The near plane sits exactly at the camera, the way a perspective one
         * effectively does: under ortho's parallel sightlines everything BEHIND
         * the camera projects into the frame as well, so a hillside the camera
         * stands in paints itself over the subject, and no amount of orbiting
         * gets past it. Clipping at the camera plane drops precisely what the
         * eye has already passed and nothing the eye still faces — pushing the
         * plane any further in would slice the ground in front of the camera
         * and leave a hole where it was. Zooming in walks the camera towards
         * the pivot, so the zoom doubles as the control over how much of an
         * obstacle in front gets cut.
         *
         * The far plane is the one vanilla builds its perspective with, which
         * already bounds everything the game draws; together with the near
         * plane it keeps the box tight enough for the frustum to cull with,
         * which matters here because chunk occlusion culling is off (see
         * setOrthoDistance). */
        float near = -minHalfHeight;
        /* 26.2 moved the perspective far plane from GameRenderer#getDepthFar to Camera#depthFar, which
         * has no getter; the extracted camera render state carries the same number and is public. */
        float far = renderer.gameRenderState().levelRenderState.cameraRenderState.depthFar;

        return new Matrix4f().setOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, near, far);
    }

    /**
     * Whether a shaderpack is drawing the world. Around twenty places in BBS already ask this and the
     * matching {@link #isIrisShadowPass()} — the port kept every one of those branches and cut only the
     * sensor, pinning both to false, which is why turning shaders on made replays vanish and left the
     * shadow pass drawing forms and honouring the film camera's FOV.
     */
    public static boolean isIrisShadersEnabled()
    {
        if (!iris)
        {
            return false;
        }

        return IrisUtils.isShaderPackEnabled();
    }

    public static boolean isSodiumLoaded()
    {
        return sodium;
    }

    /**
     * Render into a framebuffer of ours instead of the world's frame: the world-forms span closes for
     * the duration ({@link #suspendWorldForms()} says why a pack program must not claim these draws)
     * and Iris is told the main target is gone, which also turns its shadow pass off for the span —
     * see {@link IrisUtils#renderOffscreen(Runnable)}, where the nesting is counted, so an inner
     * framebuffer form does not hand the main target back while the outer one is still drawing.
     */
    public static void renderOffscreen(Runnable render)
    {
        boolean prev = worldForms;

        worldForms = false;

        try
        {
            if (iris)
            {
                IrisUtils.renderOffscreen(render);
            }
            else
            {
                render.run();
            }
        }
        finally
        {
            worldForms = prev;
        }
    }

    /**
     * Whether Iris is currently filling its shadow map rather than the frame the player sees. That pass
     * runs the world render a second time from the sun's point of view, so anything BBS draws without
     * checking lands in the shadow map at the shadow camera's placement — a form smeared away from the
     * thing it belongs to.
     */
    public static boolean isIrisShadowPass()
    {
        if (!iris)
        {
            return false;
        }

        return IrisUtils.isShadowPass();
    }

    /**
     * Hold the vertex layout Iris hands out steady while a render layer's buffer is uploaded
     * outside of the immediate provider's own draw — the deferred translucent pass ends and
     * uploads those buffers itself (see CustomVertexConsumerProvider#draw). Without it a form
     * drawn where the level isn't rendering, like the form editor's viewport, gets its plain
     * entity vertices read at Iris' extended stride and shreds into stretched triangles. Returns
     * the previous state, to be handed back to {@link #endIrisBufferUpload(boolean)}.
     */
    public static boolean beginIrisBufferUpload(BufferBuilder builder)
    {
        if (!iris)
        {
            return false;
        }

        return IrisUtils.beginBufferUpload(builder);
    }

    public static void endIrisBufferUpload(boolean extended)
    {
        if (!iris)
        {
            return;
        }

        IrisUtils.endBufferUpload(extended);
    }

    /**
     * The family of shaderpack program a BBS pipeline belongs to. Kept BBS-side so that every
     * registration site can name one without dragging Iris's classes into itself;
     * {@link IrisUtils#assignPipeline} does the translation.
     */
    public enum IrisProgramKind
    {
        /** Lit, textured geometry with light and normals: forms. */
        ENTITY,
        /** The same, for the translucent pass. */
        ENTITY_TRANSLUCENT,
        /** BBS's own particle emitter. */
        PARTICLE,
        /** Textured geometry with no colour or light of its own — trail strips. */
        TEXTURED,
        /** Flat coloured triangles: label shadows, gizmo bodies, IK debug, the world overlays. */
        BASIC,
        /** The same in line mode. */
        LINES
    }

    /**
     * Hand a BBS pipeline to Iris so a loaded shaderpack draws it with one of its own programs.
     * Silently does nothing without Iris.
     *
     * <p>Called by {@link BBSShaders} for the WORLD variants of the form pipelines only, and the two
     * conditions that makes it satisfy were both proven by a run that assigned the shared pipelines:
     *
     * <ul>
     *   <li>The program's vertex format must match. Assigning a plain position/colour pipeline (the
     *       gizmo, the world overlays) to the pack's BASIC program drew it BLACK — the pack's program
     *       reads attributes that geometry does not carry. Only the full entity format (model,
     *       billboard-with-shading) and the particle format are handed over.</li>
     *   <li>The pipeline must be world-only. The shared model pipeline also draws the form editor's
     *       preview and the film panel's, into framebuffers of BBS's own; assignment is per pipeline,
     *       so the pack's entity program followed it there and clipped the form against a depth
     *       buffer that has nothing to do with it — "part of the form hidden as if behind blocks",
     *       in a viewport with no blocks in it. Hence the split: the world variants exist for the
     *       world's own frame and nothing else.</li>
     * </ul>
     *
     * <p>Why assignment is the only way a form survives a shaderpack on 1.21.11 (read out of Iris
     * 1.10.7 + vanilla bytecode): a pack draws the world into its OWN G-buffers — every render pass
     * whose program is the pack's binds them via {@code ExtendedShader.iris$setupState} — and at the
     * end of the frame composites the result into the client framebuffer, overwriting it. A draw
     * that keeps a BBS program lands in the client framebuffer (the pass's declared target) and is
     * wiped by that composite even when it is not skipped outright. Only draws carrying the pack's
     * programs land in the G-buffers and survive — and come out lit, fogged and shadowed by the pack.
     */
    public static void assignIrisPipeline(com.mojang.blaze3d.pipeline.RenderPipeline pipeline, IrisProgramKind kind)
    {
        if (!iris || pipeline == null)
        {
            return;
        }

        try
        {
            IrisUtils.assignPipeline(pipeline, kind);

            /* One line per pipeline, and BBS registers a good dozen — worth keeping, because a pipeline
             * missing from this list is geometry a shaderpack will not draw, and in game that reads as
             * "some replays show and some don't" rather than as anything shader-shaped. */
            LOGGER.info("[BBS shaders] {} -> Iris {}", pipeline.getLocation(), kind);
        }
        catch (Throwable e)
        {
            /* A pack-less Iris, a version whose API moved, a double assignment we failed to prevent:
             * none of that is worth taking the editor down for — the geometry just draws unshaded. */
            LOGGER.error("[BBS shaders] failed to hand {} to Iris", pipeline.getLocation(), e);
        }
    }

    /**
     * Make a shaderpack treat a BBS pipeline exactly as it treats {@code prototype}, a vanilla pipeline
     * the BBS one is a re-shadered clone of. Silently does nothing without Iris.
     *
     * <p>Preferred over {@link #assignIrisPipeline} wherever a vanilla counterpart exists, and the
     * reasons are worth keeping here because each one was a bug we shipped:
     *
     * <ul>
     *   <li>Naming a program kind covers the main pass only — forms cast no shadow under a pack, because
     *       Iris's shadow map is a separate assignment table that {@code assignPipeline} never writes.</li>
     *   <li>Naming a kind freezes one program for the whole frame, so the same pipeline drawn by the
     *       hand renderer asks for an entity program instead of a hand one.</li>
     *   <li>Iris resolves a kind to the FIRST matching program in its enum, and for entities that is the
     *       one whose alpha test treats vertex alpha as a discard THRESHOLD — which deleted every fully
     *       opaque form under a pack while a 1% fade slipped through. See {@link IrisUtils#copyPipeline}.</li>
     * </ul>
     */
    public static void mirrorIrisPipeline(com.mojang.blaze3d.pipeline.RenderPipeline pipeline, com.mojang.blaze3d.pipeline.RenderPipeline prototype)
    {
        if (!iris || pipeline == null || prototype == null)
        {
            return;
        }

        try
        {
            IrisUtils.copyPipeline(prototype, pipeline);

            LOGGER.info("[BBS shaders] {} mirrors {}", pipeline.getLocation(), prototype.getLocation());
        }
        catch (Throwable e)
        {
            /* Same reasoning as assignIrisPipeline: an Iris whose internals moved must not take the
             * editor down — the geometry just draws with BBS's own shader. */
            LOGGER.error("[BBS shaders] failed to mirror {} onto {}", prototype.getLocation(), pipeline.getLocation(), e);
        }
    }

    /**
     * True while form draws are aimed at the world's own frame — the film's AFTER_ENTITIES pass and
     * the model block's vanilla pass. {@link BBSShaders} reads it (through {@link #isIrisWorldForms()})
     * to hand those draws the world variants of its pipelines, the ones assigned to a shaderpack's
     * programs; everywhere else (editor previews, GUI, offscreen framebuffers) forms keep the shared
     * pipelines and BBS's own shaders.
     */
    private static boolean worldForms;

    /**
     * Open the span in which form draws belong to the world's frame; close with
     * {@link #endWorldForms(boolean)}, passing back what this returned.
     *
     * <p>Spans nest — a morph form drawn inside the world span can hold an item whose own form opens
     * one of its own — so this returns the previous state instead of assuming there was none. Closing
     * a nested span with a plain "off" would end the enclosing one early, and the rest of the world's
     * forms would silently fall back to the shared pipelines.
     *
     * <p>This used to set Iris's {@code isMainBound} false for the span, the 1.21.1 lever against a
     * pack dropping BBS's draws — and a run proved that on 1.21.11 it is worse than useless. The write
     * gate it opens no longer matters (the draws land in the client framebuffer either way, and the
     * pack's end-of-frame composite wipes them — see {@link #assignIrisPipeline}), while the SAME flag
     * gates Iris's program substitution ({@code shouldOverrideShaders()} in
     * {@code MixinShaderManager_Overrides}): with it forced false, the vanilla-layer draws inside the
     * span — item forms, mob forms — lost the pack's programs too and died with everything else.
     * So the span now marks context only; the Iris flag is left alone.
     */
    public static boolean beginWorldForms()
    {
        boolean prev = worldForms;

        worldForms = true;

        return prev;
    }

    /** Closes {@link #beginWorldForms()}, restoring whatever span enclosed it. */
    public static void endWorldForms(boolean prev)
    {
        worldForms = prev;
    }

    /**
     * Pause the world-forms span for a nested offscreen render: those draws must keep BBS's shared
     * pipelines — a pack program would bind the pack's G-buffers underneath them and the pixels would
     * leave the target's framebuffer entirely. Restore with {@link #restoreWorldForms(boolean)}.
     *
     * <p>Two callers, both drawing into a target of their own during the world phase: a framebuffer
     * form rendering its children ({@code FramebufferFormRenderer}), and an in-panel 3D viewport
     * rendering into its preview texture ({@code UIModelRenderer#renderModelToTexture}). The viewport
     * is the sharper case, because it draws through a VANILLA entity layer rather than a BBS pipeline:
     * without this the pack claims that layer, binds its own G-buffer over the preview's, and the
     * viewport's geometry ends up smeared across the world in the panel's projection.
     *
     * <p>This also tells Iris the main target is unbound for the span ({@link IrisUtils#setMainBound}):
     * inside the world render Iris otherwise disables colour/depth writes for any program that is not
     * its own ({@code MixinCompiledShaderProgram} → {@code DepthColorStorage.disableDepthColor}), and
     * these draws are BBS's own programs into BBS's own framebuffer. That is the one place the 1.21.1
     * lever is still right: the target really is not the main one.
     */
    public static boolean suspendWorldForms()
    {
        boolean prev = worldForms;

        worldForms = false;

        setIrisMainBound(false);

        return prev;
    }

    /** Closes {@link #suspendWorldForms()}. */
    public static void restoreWorldForms(boolean prev)
    {
        setIrisMainBound(true);

        worldForms = prev;
    }

    /**
     * Whether form draws right now should carry a shaderpack's programs: inside the world-forms span
     * with a pack enabled. This is the single switch {@link BBSShaders} keys its world pipeline
     * variants on, and {@link mchorse.bbs_mod.forms.FormTranslucentQueue} its two-pass split — the
     * pack's program ignores the PASS_MODE define, so splitting under it would draw both passes in
     * full and double every translucent texel.
     */
    public static boolean isIrisWorldForms()
    {
        return worldForms && isIrisShadersEnabled();
    }

    private static void setIrisMainBound(boolean bound)
    {
        if (!iris)
        {
            return;
        }

        try
        {
            IrisUtils.setMainBound(bound);
        }
        catch (Throwable e)
        {
            LOGGER.error("[BBS shaders] failed to tell Iris the main target is {}", bound ? "bound" : "unbound", e);
        }
    }

    /**
     * Tell Iris that this albedo copy carries a material's PBR sliders, so a pack asking it for
     * normal/specular maps gets the ones baked from those sliders (see {@code IrisPbrConstLoader}).
     */
    public static void trackPbrVariant(Texture variant, Link albedo, float smoothness, float metallic, float sss, float emission, float relief)
    {
        if (!iris)
        {
            return;
        }

        try
        {
            IrisUtils.trackPbrVariant(variant, albedo, smoothness, metallic, sss, emission, relief);
        }
        catch (Throwable e)
        {
            LOGGER.error("[BBS shaders] failed to track a PBR variant with Iris", e);
        }
    }

    /**
     * Tell Iris which of its own texture a BBS GL name is, so a shaderpack asking that albedo for
     * its PBR maps reaches {@link IrisUtils} instead of the pack's flat defaults. Called for every
     * texture the manager binds; Iris keys its holders by GL name, and an untracked name gets the
     * default holder cached against it.
     */
    public static void trackTexture(Texture texture)
    {
        if (!iris)
        {
            return;
        }

        try
        {
            IrisUtils.trackTexture(texture);
        }
        catch (Throwable e)
        {
            LOGGER.error("[BBS shaders] failed to track a texture with Iris", e);
        }
    }

    /**
     * Options the loaded shaderpack declares as sliders. {@link mchorse.bbs_mod.utils.iris.ShaderCurves}
     * exposes only these as curves: a slider is an option the pack itself says is continuous, so turning
     * it into an animatable uniform cannot break a {@code #if} branch the way a toggle would.
     */
    public static List<String> getShadersSliderOptions()
    {
        if (!iris)
        {
            return Collections.emptyList();
        }

        return IrisUtils.getSliderProperties();
    }

    /** The pack's own names for its options, for the curve picker. Empty without a pack. */
    public static Map<String, String> getShadersLanguageMap(String language)
    {
        if (!iris)
        {
            return Collections.emptyMap();
        }

        return IrisUtils.getShadersLanguageMap(language);
    }

    /* Curves */

    public static Long getTimeOfDay()
    {
        if (!Minecraft.getInstance().isSameThread())
        {
            return null;
        }

        if (BBSModClient.getCameraController().getCurrent() instanceof CameraWorkCameraController controller)
        {
            Map<String, Double> values = CurveClip.getValues(controller.getContext());
            Double v = values != null ? values.get("sun_rotation") : null;

            if (v != null)
            {
                return (long) (v * 1000L);
            }
        }

        return null;
    }

    public static Double getBrightness()
    {
        if (!Minecraft.getInstance().isSameThread())
        {
            return null;
        }

        if (BBSModClient.getCameraController().getCurrent() instanceof CameraWorkCameraController controller)
        {
            Map<String, Double> values = CurveClip.getValues(controller.getContext());
            Double v = values != null ? values.get("brightness") : null;

            if (v != null)
            {
                return v;
            }
        }

        return null;
    }

    public static Double getWeather()
    {
        if (!Minecraft.getInstance().isSameThread())
        {
            return null;
        }

        if (BBSModClient.getCameraController().getCurrent() instanceof CameraWorkCameraController controller)
        {
            Map<String, Double> values = CurveClip.getValues(controller.getContext());
            Double v = values != null ? values.get("weather") : null;

            if (v != null)
            {
                return v;
            }
        }

        return null;
    }

    public static float getSunHorizontalRotation()
    {
        if (!Minecraft.getInstance().isSameThread())
        {
            return 0F;
        }

        if (BBSModClient.getCameraController().getCurrent() instanceof CameraWorkCameraController controller)
        {
            Map<String, Double> values = CurveClip.getValues(controller.getContext());
            Double v = values != null ? values.get(ShaderCurves.SUN_HORIZONTAL_ROTATION) : null;

            if (v != null)
            {
                return v.floatValue();
            }
        }

        return 0F;
    }

    public static Integer getChromaSkyColorArgb()
    {
        if (!Minecraft.getInstance().isSameThread())
        {
            return null;
        }

        if (BBSModClient.getCameraController().getCurrent() instanceof CameraWorkCameraController controller)
        {
            Map<String, Integer> values = CurveClip.getColorValues(controller.getContext());

            if (values != null)
            {
                return values.get(CurveClip.CHROMA_SKY_COLOR);
            }
        }

        return null;
    }

    public static Function<VertexConsumer, VertexConsumer> getColorConsumer(Color color)
    {
        if (sodium)
        {
            /* Sodium's intrinsic writers bypass the vanilla VertexConsumer chain; the Sodium-aware
             * wrapper forwards them (see RecolorVertexSodiumConsumer). Class touch is gated. */
            return (b) -> SodiumUtils.createVertexBuffer(b, color);
        }

        return (b) -> new RecolorVertexConsumer(b, color);
    }
}
