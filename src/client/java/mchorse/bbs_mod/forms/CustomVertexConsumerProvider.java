package mchorse.bbs_mod.forms;

import com.mojang.blaze3d.platform.CompareOp;

import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;

import java.util.SequencedMap;
import java.util.function.Consumer;
import java.util.function.Function;

public class CustomVertexConsumerProvider extends MultiBufferSource.BufferSource
{
    private static Consumer<RenderType> runnables;

    private Function<VertexConsumer, VertexConsumer> substitute;
    private Function<RenderType, RenderType> layerMapper;
    private boolean ui;

    public static void drawLayer(RenderType layer)
    {
        if (runnables != null)
        {
            runnables.accept(layer);
        }
    }

    public static void hijackVertexFormat(Consumer<RenderType> runnable)
    {
        runnables = runnable;
    }

    public static void clearRunnables()
    {
        runnables = null;
    }

    public CustomVertexConsumerProvider(ByteBufferBuilder allocator, SequencedMap<RenderType, ByteBufferBuilder> layers)
    {
        super(allocator, layers);
    }

    public void setSubstitute(Function<VertexConsumer, VertexConsumer> substitute)
    {
        this.substitute = substitute;
    }

    /**
     * Remap the layer a draw lands on (null result = keep the original). The mob form's
     * custom-texture feature routes its first body layer onto a layer carrying the form's own
     * texture — the per-layer replacement for 1.21.1's global texture bind.
     */
    public void setLayerMapper(Function<RenderType, RenderType> layerMapper)
    {
        this.layerMapper = layerMapper;
    }

    public void setUI(boolean ui)
    {
        this.ui = ui;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderLayer)
    {
        if (this.layerMapper != null)
        {
            RenderType mapped = this.layerMapper.apply(renderLayer);

            if (mapped != null)
            {
                renderLayer = mapped;
            }
        }

        VertexConsumer buffer = super.getBuffer(renderLayer);

        if (this.substitute != null)
        {
            VertexConsumer apply = this.substitute.apply(buffer);

            if (apply != null)
            {
                return apply;
            }
        }

        return buffer;
    }

    /**
     * Translucent layers of buffered forms (blocks, items) defer into the frame's sorted
     * translucent queue instead of drawing immediately — otherwise their semi-transparent
     * pixels write depth mid-frame and occlude forms drawn after them. Active only when the
     * current form renderer published its sort origin (never in picking or UI paths).
     */
    @Override
    public void endBatch(RenderType layer)
    {
        /* TODO(1.21.11 render): the deferred branch that used to live here retained the built
         * geometry in a VertexBuffer and handed it to FormTranslucentQueue. Both the buffer type
         * and the replay draw were removed by the GPU-pipeline rewrite, so the queue is disabled
         * on this branch (see FormTranslucentQueue) and every layer draws immediately. The colour
         * overlay needs nothing here either way: the layer a tinted form draws through IS the tinted
         * one (see FormOverlay#withOverlay), so the tint travels with the layer, not with a flag. */
        super.endBatch(layer);
    }

    @Override
    public void endBatch()
    {
        super.endBatch();

        if (this.ui)
        {
            /* In 1.21.1 this forced the depth func back to GL_ALWAYS because stuff
             * rendered by a vertex consumer was resetting the depth func to GL_LESS.
             *
             * As of 1.21.5 the GPU-pipeline rewrite removed imperative GL state from
             * RenderSystem (RenderSystem.depthFunc is gone) — depth testing is now baked
             * into each RenderLayer's RenderPipeline via DepthTestFunction. The UI layers
             * therefore have to carry a NO_DEPTH_TEST / GL_ALWAYS-equivalent pipeline
             * themselves; there is no longer a global func to "force back" here.
             *
             * TODO(1.21.11 render): verify at runtime. If UI vertex-consumer draws still
             * leak a depth func that hides later UI, encode CompareOp.ALWAYS_PASS
             * on the affected BBS UI RenderLayer pipelines (see BBSShaders) rather than
             * trying to mutate global state from here.
             */
        }
    }
}
