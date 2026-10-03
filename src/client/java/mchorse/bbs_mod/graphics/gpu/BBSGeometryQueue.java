package mchorse.bbs_mod.graphics.gpu;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.graphics.texture.Texture;
import net.minecraft.client.renderer.StagedVertexBuffer;

import java.util.Map;

/**
 * Per-frame immediate geometry, on 26.2's staging system.
 *
 * <p>This is the primitive that BBS's <code>Batcher2D</code> (the 2D interface batcher) and
 * {@code Draw} (the 3D gizmo/wireframe helpers) are rebuilt on, and it exists because the old shape
 * of that work has no 26.2 equivalent. On 1.21.11 an immediate draw was: build vertices into a
 * {@code BufferBuilder} from {@code Tessellator}, finish it into a {@code BuiltBuffer}, and hand it
 * to a {@code RenderLayer} — with the layer's pipeline and the engine's ambient state doing the rest.
 * Two of those are gone ({@code Tessellator}, and the ambient state), and the third
 * ({@code BufferBuilder}) now feeds a staging buffer instead of a draw call.</p>
 *
 * <p>The 26.2 shape is:</p>
 *
 * <ol>
 *   <li>{@link #begin(VertexFormat, PrimitiveTopology)} opens a batch and returns the
 *       {@link VertexConsumer} to write into. One queue can hold several batches with different
 *       formats and topologies — that is what {@code end()} / {@code begin()} do — and they are all
 *       uploaded together.</li>
 *   <li>{@link #upload()} pushes everything written into the GPU buffer. This has to happen
 *       <b>outside</b> a render pass: 26.2's encoder rejects any command issued between a pass
 *       opening and closing, so a queue that uploads lazily during a draw throws.</li>
 *   <li>{@link #submit} records the batches into an already open pass, in the order they were
 *       built, binding the pipeline, the per-draw uniform block and whichever textures the draw
 *       needs.</li>
 *   <li>{@link #endFrame()} rewinds the staging ring for the next frame.</li>
 * </ol>
 *
 * <p>{@link StagedVertexBuffer} is vanilla's own implementation of that staging ring — it is what
 * the world renderer batches its extracted geometry with — so BBS gets the same memory behaviour
 * (a ring of buffers, no per-draw allocation) instead of growing a private buffer per frame.</p>
 */
public class BBSGeometryQueue implements AutoCloseable
{
    private final StagedVertexBuffer staged;

    private StagedVertexBuffer.Draw draw;
    private VertexConsumer consumer;
    private boolean uploaded;

    public BBSGeometryQueue(String label)
    {
        this(label, 1536);
    }

    /**
     * @param capacity the initial staging capacity in kilobytes; the ring grows on demand, so this
     *                 is a starting point, not a limit.
     */
    public BBSGeometryQueue(String label, int capacity)
    {
        this.staged = new StagedVertexBuffer(() -> label, capacity);
    }

    /**
     * Open a batch, or switch to a new one when the format or topology changes.
     *
     * <p>The previous batch is closed first, because a staged draw carries its own format and
     * topology — unlike the old {@code Tessellator}, which had one implicit buffer per draw mode.</p>
     */
    public VertexConsumer begin(VertexFormat format, PrimitiveTopology topology)
    {
        if (this.draw != null)
        {
            this.staged.endDraw();
        }

        this.draw = this.staged.appendDraw(format, topology);
        this.consumer = this.staged.getVertexBuilder(this.draw);
        this.uploaded = false;

        return this.consumer;
    }

    /** Close the open batch. Safe to call twice. */
    public void end()
    {
        if (this.draw != null)
        {
            this.staged.endDraw();
            this.draw = null;
            this.consumer = null;
        }
    }

    public VertexConsumer consumer()
    {
        return this.consumer;
    }

    public boolean isEmpty()
    {
        return this.draw == null || this.draw.isEmpty();
    }

    /** Push the staged vertices to the GPU. Must not be called while a render pass is open. */
    public void upload()
    {
        /* endDraw() is what hands the finished vertices to the staging buffer; upload() then copies
         * them into GPU-visible memory for the next pass to read. */
        if (this.draw != null)
        {
            this.staged.endDraw();
            this.draw = null;
            this.consumer = null;
        }

        this.staged.upload();
        this.uploaded = true;
    }

    public void submit(RenderPass pass, RenderPipeline pipeline, GpuBufferSlice dynamicTransforms)
    {
        this.submit(pass, pipeline, dynamicTransforms, Map.of());
    }

    /**
     * Record the staged geometry into an open pass.
     *
     * @param pass              the pass to record into; it must already be open and is not closed here
     * @param pipeline          the pipeline the geometry was built for — its vertex format must be
     *                          the format the batch was opened with
     * @param dynamicTransforms the {@code "DynamicTransforms"} block for this draw, normally
     *                          {@code RenderSystem.getDynamicUniforms().writeTransform(...)}
     * @param textures          textures to bind as {@code Sampler0}, {@code Sampler1}, … — the names
     *                          are the shader's sampler names, which is how 26.2 replaced texture units
     */
    public void submit(RenderPass pass, RenderPipeline pipeline, GpuBufferSlice dynamicTransforms, Map<String, Texture> textures)
    {
        if (this.draw == null)
        {
            return;
        }

        if (!this.uploaded)
        {
            throw new IllegalStateException("The geometry queue has to be uploaded() before it can be submitted");
        }

        StagedVertexBuffer.ExecuteInfo info = this.staged.getExecuteInfo(this.draw);

        pass.setPipeline(pipeline);
        pass.setUniform("DynamicTransforms", dynamicTransforms);

        for (Map.Entry<String, Texture> entry : textures.entrySet())
        {
            Texture texture = entry.getValue();

            if (texture != null && texture.isValid())
            {
                pass.bindTexture(entry.getKey(), texture.view(), texture.sampler());
            }
        }

        pass.setVertexBuffer(0, info.vertexBuffer().slice());
        pass.setIndexBuffer(info.indexBuffer(), info.indexType());

        /* 26.2's order is drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance); the
         * 1.21.11 order was (baseVertex, firstIndex, indexCount, instanceCount). Handing the engine the old
         * one makes it read baseVertex as the index count - and baseVertex is normally 0, so the usual
         * result is a draw of nothing at all, issued without an error from a pass that still opens, binds
         * and closes. See ScreenQuadPass#draw for the same correction. */
        pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
    }

    /** Rewind the staging ring. Call once per frame, after the frame's last pass has closed. */
    public void endFrame()
    {
        this.end();
        this.staged.endFrame();
        this.uploaded = false;
    }

    @Override
    public void close()
    {
        this.staged.close();
        this.draw = null;
        this.consumer = null;
    }
}
