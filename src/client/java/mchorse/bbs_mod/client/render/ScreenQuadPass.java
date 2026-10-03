package mchorse.bbs_mod.client.render;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.textures.GpuSampler;
import mchorse.bbs_mod.graphics.gpu.BBSGpu;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.MappableRingBuffer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/**
 * A textured screen-space quad drawn with a CUSTOM pipeline and (optionally) a custom std140 UBO,
 * into an {@link OffscreenTarget}.
 *
 * <p>This is the per-draw-uniform dispatch 1.21.5 removed: loose {@code program.getUniform(...)}
 * setters became std140 blocks, and the immediate {@code RenderLayer.draw} path binds only the
 * engine builtins — a custom block simply cannot ride it. So every effect that needs one (the
 * marching-ants selection outline, the multilink pixelate/erase preview, the subtitle blur) drives
 * this manual pass instead: engine builtins + its own UBO + its own samplers, quad at an ortho over
 * the target, result composited back through the recorded GUI blit. Generalized from
 * {@code BBSPickerRenderer.drawHighlight}, the first live instance of the pattern.
 */
public class ScreenQuadPass
{
    /** Shared ring for the small custom UBO blocks (largest current block is 32 bytes; 48 headroom). */
    private static final int UBO_SIZE = 48;

    private static MappableRingBuffer uboRing;
    private static MappableRingBuffer projectionRing;
    private static GpuSampler nearestSampler;
    private static GpuSampler linearSampler;

    /**
     * Write a custom std140 block into the shared ring and return the slice to hand to {@link Quad}.
     * Call BEFORE the pass runs (ring rotation fences; an open pass rejects it) — in practice, just
     * before {@link #draw}.
     */
    public static GpuBufferSlice writeUbo(Consumer<Std140Builder> writer)
    {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        if (uboRing == null)
        {
            uboRing = new MappableRingBuffer(() -> "bbs:screen_quad_ubo", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
        }

        uboRing.rotate();

        GpuBuffer ubo = uboRing.currentBuffer();

        try (GpuBufferSlice.MappedView view = ubo.slice(0L, UBO_SIZE).map(false, true))
        {
            writer.accept(Std140Builder.intoBuffer(view.data()));
        }

        return ubo.slice(0L, UBO_SIZE);
    }

    public static GpuSampler nearest()
    {
        if (nearestSampler == null)
        {
            nearestSampler = BBSGpu.clampToEdge(FilterMode.NEAREST, false);
        }

        return nearestSampler;
    }

    public static GpuSampler linear()
    {
        if (linearSampler == null)
        {
            linearSampler = BBSGpu.clampToEdge(FilterMode.LINEAR, false);
        }

        return linearSampler;
    }

    /** One quad draw. Coordinates are target-local pixels (ortho over the whole target, y-down). */
    public static class Quad
    {
        public RenderPipeline pipeline;
        public GpuTextureView target;
        public int targetWidth;
        public int targetHeight;

        public float x, y, w, h;
        public float u1, v1, u2, v2;
        public int color = 0xFFFFFFFF;

        public GpuTextureView sampler0;
        public GpuSampler sampler0Sampler;
        public String sampler3Name;
        public GpuTextureView sampler3;
        public GpuSampler sampler3Sampler;

        public String uboName;
        public GpuBufferSlice ubo;

        /** Clear the target to transparent before drawing (first draw of the frame into it). */
        public boolean clear;

        public Quad(RenderPipeline pipeline, GpuTextureView target, int targetWidth, int targetHeight)
        {
            this.pipeline = pipeline;
            this.target = target;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
        }

        public Quad rect(float x, float y, float w, float h)
        {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;

            return this;
        }

        public Quad uv(float u1, float v1, float u2, float v2)
        {
            this.u1 = u1;
            this.v1 = v1;
            this.u2 = u2;
            this.v2 = v2;

            return this;
        }

        public Quad texture(GpuTextureView view, GpuSampler sampler)
        {
            this.sampler0 = view;
            this.sampler0Sampler = sampler;

            return this;
        }

        public Quad texture3(String name, GpuTextureView view, GpuSampler sampler)
        {
            this.sampler3Name = name;
            this.sampler3 = view;
            this.sampler3Sampler = sampler;

            return this;
        }

        public Quad ubo(String name, GpuBufferSlice slice)
        {
            this.uboName = name;
            this.ubo = slice;

            return this;
        }

        public Quad clear()
        {
            this.clear = true;

            return this;
        }
    }

    public static boolean draw(String name, Quad quad)
    {
        if (quad.target == null || quad.targetWidth <= 0 || quad.targetHeight <= 0)
        {
            return false;
        }

        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();

        /* Identity model-view, neutral ColorModulator; the vertex colour carries any tint. */
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
            .writeTransform(new Matrix4f(), new Vector4f(1F, 1F, 1F, 1F), new Vector3f(), new Matrix4f());

        if (projectionRing == null)
        {
            projectionRing = new MappableRingBuffer(() -> "bbs:screen_quad_projection", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, 64);
        }

        projectionRing.rotate();

        GpuBuffer projection = projectionRing.currentBuffer();

        try (GpuBufferSlice.MappedView view = projection.slice(0L, 64).map(false, true))
        {
            Std140Builder.intoBuffer(view.data())
                .putMat4f(new Matrix4f().ortho(0F, quad.targetWidth, quad.targetHeight, 0F, -1000F, 1000F));
        }

        /* 26.2 dropped the Tesselator: a BufferBuilder is built on a ByteBufferBuilder (which owns the
         * native arena, so it is closed with the mesh) and finished into MeshData. */
        ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize() * 4);
        BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        builder.addVertex(quad.x, quad.y + quad.h, 0F).setUv(quad.u1, quad.v2).setColor(quad.color);
        builder.addVertex(quad.x + quad.w, quad.y + quad.h, 0F).setUv(quad.u2, quad.v2).setColor(quad.color);
        builder.addVertex(quad.x + quad.w, quad.y, 0F).setUv(quad.u2, quad.v1).setColor(quad.color);
        builder.addVertex(quad.x, quad.y, 0F).setUv(quad.u1, quad.v1).setColor(quad.color);

        MeshData buffer = builder.build();

        if (buffer == null)
        {
            bytes.close();

            return false;
        }

        VertexFormat format = buffer.drawState().format();
        GpuBuffer vertexBuffer = device.createBuffer(() -> "bbs:screen_quad", GpuBuffer.USAGE_VERTEX, buffer.vertexBuffer());
        RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(buffer.drawState().primitiveTopology());
        GpuBuffer indexBuffer = sequential.getBuffer(buffer.drawState().indexCount());
        IndexType indexType = sequential.type();

        try (RenderPass pass = encoder.createRenderPass(() -> name, quad.target,
            quad.clear ? Optional.of(GuiRenderer.CLEAR_COLOR) : Optional.empty()))
        {
            pass.setPipeline(quad.pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", projection.slice(0L, 64));
            pass.setUniform("DynamicTransforms", dynamicTransforms);

            if (quad.ubo != null)
            {
                pass.setUniform(quad.uboName, quad.ubo);
            }

            if (quad.sampler0 != null)
            {
                pass.bindTexture("Sampler0", quad.sampler0, quad.sampler0Sampler == null ? nearest() : quad.sampler0Sampler);
            }

            if (quad.sampler3 != null)
            {
                pass.bindTexture(quad.sampler3Name, quad.sampler3, quad.sampler3Sampler == null ? nearest() : quad.sampler3Sampler);
            }

            pass.setVertexBuffer(0, vertexBuffer.slice());
            pass.setIndexBuffer(indexBuffer, indexType);

            /* drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance).
             *
             * The argument ORDER is the 26.2 one, and it is not the 1.21.11 one
             * (baseVertex, firstIndex, indexCount, instanceCount) that this call was ported from. Passing
             * the old order here means the engine reads the first argument as indexCount - which was the
             * literal 0 - so the draw is issued with nothing to draw. It does not fail, it does not warn,
             * and the pass still opens, binds and closes: the target is simply left exactly as it was.
             * That is why the film snapshot stayed a zeroed texture, and with it the film preview. */
            pass.drawIndexed(buffer.drawState().indexCount(), 1, 0, 0, 0);
        }
        finally
        {
            buffer.close();
            vertexBuffer.close();
            bytes.close();
        }

        return true;
    }
}
