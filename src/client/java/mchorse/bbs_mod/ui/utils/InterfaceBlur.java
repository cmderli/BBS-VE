package mchorse.bbs_mod.ui.utils;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.UniformValue;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.resources.Identifier;
import org.joml.Vector2f;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Blurs what is on screen under an overlay panel, on top of the dimming — the way the game's
 * own menus do it from 1.21 on. Two passes of a box blur over the main framebuffer, with the
 * overlay landing sharp on top.
 *
 * <p>The GUI in 1.21.11 is deferred: {@code Batcher2D} only records into the
 * {@link net.minecraft.client.gui.render.state.GuiRenderState}, and nothing reaches the
 * framebuffer until {@code GuiRenderer.render()} composites it after {@code Screen.render}
 * returns. Blurring the framebuffer at the moment an overlay paints would therefore blur the
 * bare world and leave every panel drawn so far sharp on top of it. So this class works the
 * way vanilla's own {@code DrawContext.applyBlur()} does — as a marker: {@link #apply} opens a
 * fresh root layer and records the blur there, and {@code GuiRenderer.renderPreparedDraws}
 * composites the layers before the marker, runs the blur (its call to
 * {@code GameRenderer.renderBlur} is redirected into {@link #render}), then draws the rest.
 * Everything recorded before the call ends up under the glass, the caller's own dim and
 * chrome on top.</p>
 *
 * <p>The effect is built in code rather than read from a json, so the radius is a live value
 * from the settings. 1.21.1 could load a bare json and add the passes by hand afterwards; in
 * 1.21.11 a {@link PostEffectProcessor} is parsed whole from a {@link PostEffectPipeline} and
 * carries its uniform VALUES with it, so a json would have to name one fixed radius. Assembling
 * the pipeline here keeps the setting, at the cost of rebuilding when it changes — which is
 * exactly as often as the user drags the slider.</p>
 *
 * <p>Once per frame, and this time without exception: the render state holds a single blur
 * layer, and {@code GuiRenderState.applyBlur} throws on a second one. A panel that blurred the
 * world under itself and an overlay that comes up over that panel share one pass.</p>
 */
public class InterfaceBlur
{
    /** Screen-sized scratch target: the horizontal pass writes it, the vertical pass reads it back. */
    private static final Identifier SWAP = Identifier.fromNamespaceAndPath(BBSMod.MOD_ID, "swap");

    /** Vanilla's screen-quad vertex shader; there is nothing mod-specific about a full-screen pass. */
    private static final Identifier SCREEN_QUAD = Identifier.fromNamespaceAndPath("minecraft", "core/screenquad");

    /** Vanilla's box blur, minus the alpha averaging — see the shader for why. */
    private static final Identifier BOX_BLUR = Identifier.fromNamespaceAndPath(BBSMod.MOD_ID, "post/box_blur_opaque");

    private static PostChain processor;

    /** Owned by us because {@link PostEffectProcessor#parseEffect} takes one and ShaderLoader's is private. */
    private static ProjectionMatrixBuffer projection;

    /**
     * The near/far/invert {@code ShaderLoader} builds its own post-chain projection with. 26.2 made
     * the projection and its UBO two separate objects handed to {@code PostChain.load}, so the
     * matrix is owned here next to the buffer it is uploaded through.
     */
    private static final Projection POST_CHAIN_PROJECTION = new Projection();

    static
    {
        POST_CHAIN_PROJECTION.setupOrtho(0.1F, 1000F, 1F, 1F, false);
    }

    /** The radius baked into the built effect; a different one means a rebuild. */
    private static float builtRadius = Float.NaN;

    /** Whether this frame's render state carries our blur marker, waiting for {@link #render}. */
    private static boolean marked;

    /** Set when the effect failed to build, so a broken shader costs one stack trace, not one per frame. */
    private static boolean broken;

    /** Called where the frame's interface rendering starts. */
    public static void beginFrame()
    {
        marked = false;
    }

    /**
     * Mark the blur layer: everything recorded before lands under the blur, the caller's own
     * draws (its dim, its chrome) go into the fresh root layer on top. Does nothing when the
     * effect is broken or the setting is off.
     *
     * <p>A second caller in the same frame TAKES the mark over rather than being turned away.
     * Vanilla allows exactly one blur per frame ("Can only blur once per frame") and would keep
     * the first claimant — which is the dashboard's tint, recorded long before the overlay panel
     * that comes up over it, so the overlay ended up with no glass behind it at all. The topmost
     * claimant is the right owner: the pass blurs everything recorded UNDER the mark, so moving
     * the mark up still covers what the earlier claimant wanted blurred.</p>
     */
    public static void apply(Batcher2D batcher)
    {
        if (broken || !BBSSettings.interfaceBlur.get())
        {
            return;
        }

        if (marked)
        {
            /* Vanilla's "nothing marked yet" sentinel; the field is private, so the constant it
             * compares against is unmapped and cannot be named here. */
            batcher.getContext().guiRenderState.firstStratumAfterBlur = Integer.MAX_VALUE;
        }

        marked = true;

        batcher.newRootLayer();
        batcher.applyBlur();
    }

    /**
     * The world under a panel that paints its own background over it (morphing, the texture
     * manager). The same mark as {@link #apply}, and an overlay that comes up over the panel
     * later takes it over — one pass, owned by whatever is on top.
     */
    public static void applyUnder(Batcher2D batcher)
    {
        apply(batcher);
    }

    /**
     * {@code GuiRenderer}'s blur slot, reached from the redirect in {@code GuiRendererMixin} when
     * it composites up to the marked layer. The framebuffer holds the world and every layer
     * before the marker at this point, which is exactly the picture to blur.
     *
     * @return false when this frame has no BBS blur, so vanilla's own {@code renderBlur} runs instead.
     */
    public static boolean render()
    {
        if (!marked)
        {
            return false;
        }

        marked = false;

        Minecraft mc = Minecraft.getInstance();
        float radius = BBSSettings.interfaceBlurRadius.get();

        if (processor == null || radius != builtRadius)
        {
            if (!rebuild(mc, radius))
            {
                return true;
            }
        }

        /* The frame graph sizes the swap target off the framebuffer and puts the result back into
         * it, so the blend/framebuffer/texture-unit restoration 1.21.1 had to do by hand afterwards
         * has nothing left to undo — a render pass owns its own state now. */
        processor.process(mc.gameRenderer.mainRenderTarget(), GraphicsResourceAllocator.UNPOOLED);

        return true;
    }

    private static boolean rebuild(Minecraft mc, float radius)
    {
        close();

        try
        {
            /* The same near/far/invert ShaderLoader builds its own with, so our passes project
             * their screen quad exactly like every vanilla post effect does. */
            projection = new ProjectionMatrixBuffer("bbs_interface_blur");

            processor = PostChain.load(pipeline(radius), mc.getTextureManager(),
                Set.of(PostChain.MAIN_TARGET_ID), Identifier.fromNamespaceAndPath(BBSMod.MOD_ID, "interface_blur"), POST_CHAIN_PROJECTION, projection);

            builtRadius = radius;

            return true;
        }
        catch (Exception e)
        {
            e.printStackTrace();

            close();
            broken = true;

            return false;
        }
    }

    /** Horizontal into the swap, vertical back into the main target — a separable box blur. */
    private static PostChainConfig pipeline(float radius)
    {
        return new PostChainConfig(
            Map.of(SWAP, new PostChainConfig.InternalTarget(Optional.empty(), Optional.empty(), false, 0)),
            List.of(
                pass(PostChain.MAIN_TARGET_ID, SWAP, 1F, 0F, radius),
                pass(SWAP, PostChain.MAIN_TARGET_ID, 0F, 1F, radius)
            )
        );
    }

    /**
     * One blur pass. The uniform list is written into the {@code BlurConfig} std140 block in the
     * order given, so it has to match the shader's declaration — BlurDir then Radius. Bilinear
     * sampling is on because the shader halves its sample count by stepping between pixels.
     */
    private static PostChainConfig.Pass pass(Identifier in, Identifier out, float dirX, float dirY, float radius)
    {
        return new PostChainConfig.Pass(SCREEN_QUAD, BOX_BLUR,
            List.of(new PostChainConfig.TargetInput("In", in, false, true)),
            out,
            Map.of("BlurConfig", List.of(
                new UniformValue.Vec2Uniform(new Vector2f(dirX, dirY)),
                new UniformValue.FloatUniform(radius)
            ))
        );
    }

    private static void close()
    {
        if (processor != null)
        {
            processor.close();
        }

        if (projection != null)
        {
            projection.close();
        }

        processor = null;
        projection = null;
        builtRadius = Float.NaN;
    }
}
