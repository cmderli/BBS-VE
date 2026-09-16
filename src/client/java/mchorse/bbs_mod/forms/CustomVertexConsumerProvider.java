package mchorse.bbs_mod.forms;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;

import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * BBS's per-layer vertex buffer, and the form pipeline's replacement for the removed
 * {@code MultiBufferSource.BufferSource}.
 *
 * <p>1.21.11's provider was a {@code VertexConsumerProvider.Immediate}: one buffer per
 * {@link RenderType}, and {@code draw()} finished each buffer into a {@code BuiltBuffer} and handed
 * it to the layer, which drew it there and then. 26.2 removed the class, the drawing call
 * ({@code RenderLayer.draw(BuiltBuffer)} became {@code RenderType.prepare()} plus
 * {@code PreparedRenderType.drawFromBuffer(...)}) and {@code RenderSetup}'s buffer size, so this
 * class owns the per-layer {@link BufferBuilder}s itself and draws a finished layer through the
 * layer's own prepared type.</p>
 *
 * <p>The one exception is the deferred item path: while a {@link FormRenderCapture} session is
 * open, the finished geometry is handed to the capture (exactly what the removed
 * {@code RenderLayerMixin} hook used to do) instead of being drawn, because an item model is
 * recorded where no draw can legally happen.</p>
 */
public class CustomVertexConsumerProvider
{
    private static Consumer<RenderType> runnables;

    private final ByteBufferBuilder allocator;
    private final SequencedMap<RenderType, ByteBufferBuilder> layers;
    private final Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();

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
        this.allocator = allocator;
        this.layers = layers;
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

        BufferBuilder buffer = this.builders.get(renderLayer);

        if (buffer == null)
        {
            ByteBufferBuilder allocator = this.layers.get(renderLayer);

            buffer = new BufferBuilder(allocator == null ? this.allocator : allocator, renderLayer.primitiveTopology(), renderLayer.format());

            this.builders.put(renderLayer, buffer);
        }

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
     *
     * <p>26.2 shape: finishing a layer either captures it (deferred item rendering) or draws it
     * through the layer's prepared type; see {@link #draw(RenderType, MeshData)}. The colour
     * overlay needs nothing here either way: the layer a tinted form draws through IS the tinted
     * one (see FormOverlay#withOverlay), so the tint travels with the layer, not with a flag.</p>
     */
    public void endBatch(RenderType layer)
    {
        /* TODO(1.21.11 render): the deferred branch that used to live here retained the built
         * geometry in a VertexBuffer and handed it to FormTranslucentQueue. Both the buffer type
         * and the replay draw were removed by the GPU-pipeline rewrite, so the queue is disabled
         * on this branch (see FormTranslucentQueue) and every layer draws immediately. The colour
         * overlay needs nothing here either way: the layer a tinted form draws through IS the tinted
         * one (see FormOverlay#withOverlay), so the tint travels with the layer, not with a flag. */
        BufferBuilder builder = this.builders.remove(layer);

        if (builder == null)
        {
            return;
        }

        MeshData built = builder.build();

        if (built == null)
        {
            return;
        }

        this.draw(layer, built);
    }

    public void endBatch()
    {
        for (RenderType layer : new ArrayList<>(this.builders.keySet()))
        {
            this.endBatch(layer);
        }

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

    /** Flush every buffered layer. The name the 1.21.11 call sites use. */
    public void draw()
    {
        this.endBatch();
    }

    private void draw(RenderType layer, MeshData built)
    {
        drawImmediate(layer, built);
    }

    /**
     * Draw a finished mesh through a layer right away — 26.2's answer to {@code RenderType#draw(MeshData)}.
     *
     * <p>While a capture session is open the mesh is captured instead, which is what the removed
     * {@code RenderLayerMixin} hook did for every layer draw: an item model is recorded where no
     * draw can legally happen.</p>
     */
    public static void drawImmediate(RenderType layer, MeshData built)
    {
        if (FormRenderCapture.isActive())
        {
            FormRenderCapture.capture(layer, built);

            return;
        }

        FormRenderCapture.Captured captured = FormRenderCapture.copy(built);

        built.close();

        drawImmediate(layer, captured);
    }

    /**
     * Re-emit already-captured geometry through a layer, immediately.
     *
     * <p>The layer draws through its prepared type, which opens a pass of its own and binds the
     * pipeline, the textures the layer names and the DynamicTransforms block taken from the
     * current model-view matrix — the 26.2 equivalent of 1.21.11's RenderLayer#draw. The finished
     * {@link MeshData} carries only vertex bytes, so the draw needs a vertex buffer, the layer's
     * shared sequential index buffer (the same one {@code RenderLayer#draw} used to bind) and a
     * {@code PreparedRenderType}.</p>
     */
    public static void drawImmediate(RenderType layer, FormRenderCapture.Captured captured)
    {
        MeshData.DrawState state = captured.params();
        int count = state.vertexCount();

        if (count == 0)
        {
            return;
        }

        GpuBuffer vertices = RenderSystem.getDevice().createBuffer(
            () -> "bbs form layer",
            GpuBuffer.USAGE_VERTEX,
            captured.data().duplicate().order(ByteOrder.nativeOrder())
        );

        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(state.primitiveTopology());

        drawLayer(layer);

        layer.prepare().drawFromBuffer(vertices, indices.getBuffer(state.indexCount()), indices.type(), 0, 0, state.indexCount());

        vertices.close();
    }
}
