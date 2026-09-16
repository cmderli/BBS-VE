package mchorse.bbs_mod.graphics;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.data.Angle;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.MathUtils;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/**
 * 3D debug/gizmo drawing helpers.
 *
 * Ported to 1.21.11. The geometry-building API is unchanged ({@link MatrixStack} + {@link Matrix4f}
 * + {@link BufferBuilder} still exist and behave as before), so all public method signatures are
 * preserved. What changed is the draw-flush: 1.21.5 removed {@code RenderSystem.setShader(...)} and
 * {@code BufferRenderer.drawWithGlobalProgram(...)}. Immediate-mode geometry is now built into a
 * {@link BufferBuilder}, finished into a {@link BuiltBuffer}, and submitted through a
 * {@link RenderLayer} that carries a {@link RenderPipeline}.
 *
 * These two BBS-owned POSITION_COLOR pipelines (one depth-tested, one not) replace the old
 * {@code GameRenderer::getPositionColorProgram} usage. They are seeded from the vanilla
 * {@code POSITION_COLOR_SNIPPET} (same vertex/fragment shader + transform UBO setup as the GUI/world
 * position-color path) so the model-view/projection matrices flow through unchanged.
 *
 * TODO(1.21.11 render): verify at runtime that drawing through RenderLayer.draw() here picks up the
 * current world model-view/projection. These helpers are invoked from within world/entity render
 * passes where the global transform stack is already configured; if the transforms come out wrong,
 * the pipeline may need the world transform snippet instead of the bare position-color snippet.
 */
public class Draw
{
    private static final BlendFunction BLEND = BlendFunction.TRANSLUCENT;

    /* POSITION_COLOR / TRIANGLES, depth-tested (faithful to the old position-color program used for
     * boxes/arcs/spheres inside the world). */
    private static final RenderPipeline POSITION_COLOR_TRIS = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(BBSMod.MOD_ID, "pipeline/draw_position_color"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
            .withBlend(BLEND)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withCull(false)
            .build()
    );

    /* POSITION_COLOR / TRIANGLES, no depth test (coolerAxes did RenderSystem.disableDepthTest()). */
    private static final RenderPipeline POSITION_COLOR_TRIS_NO_DEPTH = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(BBSMod.MOD_ID, "pipeline/draw_position_color_no_depth"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
            .withBlend(BLEND)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .build()
    );

    /* POSITION_COLOR / DEBUG_LINES, depth-tested. Replaces the old DEBUG_LINES + GameRenderer::getPositionColorProgram
     * path (e.g. the 3D model-preview ground grid); GL_LINES width 1, no cull, drawn under LEQUAL depth as the
     * original did via RenderSystem.depthFunc(GL_LEQUAL). */
    private static final RenderPipeline POSITION_COLOR_LINES = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(BBSMod.MOD_ID, "pipeline/draw_position_color_lines"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.DrawMode.DEBUG_LINES)
            .withBlend(BLEND)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withCull(false)
            .build()
    );

    private static RenderType positionColorLayer;
    private static RenderType positionColorNoDepthLayer;
    private static RenderType positionColorLinesLayer;

    private static RenderType getPositionColorLayer()
    {
        if (positionColorLayer == null)
        {
            positionColorLayer = RenderType.create(BBSMod.MOD_ID + "_draw_position_color",
                RenderSetup.builder(POSITION_COLOR_TRIS).sortOnUpload().createRenderSetup());
        }

        return positionColorLayer;
    }

    private static RenderType getPositionColorNoDepthLayer()
    {
        if (positionColorNoDepthLayer == null)
        {
            positionColorNoDepthLayer = RenderType.create(BBSMod.MOD_ID + "_draw_position_color_no_depth",
                RenderSetup.builder(POSITION_COLOR_TRIS_NO_DEPTH).sortOnUpload().createRenderSetup());
        }

        return positionColorNoDepthLayer;
    }

    private static RenderType getPositionColorLinesLayer()
    {
        if (positionColorLinesLayer == null)
        {
            positionColorLinesLayer = RenderType.create(BBSMod.MOD_ID + "_draw_position_color_lines",
                RenderSetup.builder(POSITION_COLOR_LINES).sortOnUpload().createRenderSetup());
        }

        return positionColorLinesLayer;
    }

    /**
     * Submit a DEBUG_LINES POSITION_COLOR buffer through the BBS line pipeline. Replacement for the old
     * {@code BufferRenderer.drawWithGlobalProgram} flush used by immediate-mode line geometry (e.g. the
     * model-preview ground grid). No-op on an empty buffer.
     */
    public static void flushLines(BufferBuilder builder)
    {
        flush(builder, getPositionColorLinesLayer());
    }

    /**
     * Submit a TRIANGLES POSITION_COLOR buffer through the BBS depth-tested triangle pipeline.
     * Replacement for the old {@code BufferRenderer.drawWithGlobalProgram} used by immediate-mode
     * world overlays (e.g. the film motion path). No-op on an empty buffer.
     */
    public static void flushTriangles(BufferBuilder builder)
    {
        flush(builder, getPositionColorLayer());
    }

    /**
     * Same, through the pipeline that does not depth-test — the replacement for wrapping a draw in
     * {@code RenderSystem.disableDepthTest()}, which the GPU rewrite removed. For world overlays that
     * have to read through terrain, like the structure wand's selection.
     */
    public static void flushTrianglesNoDepth(BufferBuilder builder)
    {
        flush(builder, getPositionColorNoDepthLayer());
    }

    /** Finish a buffer and submit it through the given layer (no-op on an empty buffer). */
    private static void flush(BufferBuilder builder, RenderType layer)
    {
        MeshData built = builder.build();

        if (built != null)
        {
            /* TODO(1.21.11 render): verify at runtime. RenderLayer.draw uploads + draws with the
             * layer pipeline; previously this was BufferRenderer.drawWithGlobalProgram. */
            layer.draw(built);
        }
    }

    public static void renderBox(PoseStack stack, double x, double y, double z, double w, double h, double d)
    {
        renderBox(stack, x, y, z, w, h, d, 1, 1, 1);
    }

    public static void renderBox(PoseStack stack, double x, double y, double z, double w, double h, double d, float r, float g, float b)
    {
        renderBox(stack, x, y, z, w, h, d, r, g, b, 1F);
    }

    public static void renderBox(PoseStack stack, double x, double y, double z, double w, double h, double d, float r, float g, float b, float a)
    {
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        renderBox(builder, stack, x, y, z, w, h, d, r, g, b, a);

        flush(builder, getPositionColorLayer());
    }

    /**
     * The wireframe box written into a batch of the caller's own, for a caller that has more to draw
     * into it or wants it submitted through a different layer — the structure wand builds its whole
     * selection this way and flushes it without depth testing, so it reads through terrain.
     */
    public static void renderBox(BufferBuilder builder, PoseStack stack, double x, double y, double z, double w, double h, double d, float r, float g, float b, float a)
    {
        stack.pushPose();
        stack.translate(x, y, z);
        float fw = (float) w;
        float fh = (float) h;
        float fd = (float) d;
        float t = 1 / 96F + (float) (Math.sqrt(w * w + h + h + d + d) / 2000);

        /* Pillars: fillBox(builder, -t, -t, -t, t, t, t, r, g, b, a); */
        fillBox(builder, stack, -t, -t, -t, t, t + fh, t, r, g, b, a);
        fillBox(builder, stack, -t + fw, -t, -t, t + fw, t + fh, t, r, g, b, a);
        fillBox(builder, stack, -t, -t, -t + fd, t, t + fh, t + fd, r, g, b, a);
        fillBox(builder, stack, -t + fw, -t, -t + fd, t + fw, t + fh, t + fd, r, g, b, a);

        /* Top */
        fillBox(builder, stack, -t, -t + fh, -t, t + fw, t + fh, t, r, g, b, a);
        fillBox(builder, stack, -t, -t + fh, -t + fd, t + fw, t + fh, t + fd, r, g, b, a);
        fillBox(builder, stack, -t, -t + fh, -t, t, t + fh, t + fd, r, g, b, a);
        fillBox(builder, stack, -t + fw, -t + fh, -t, t + fw, t + fh, t + fd, r, g, b, a);

        /* Bottom */
        fillBox(builder, stack, -t, -t, -t, t + fw, t, t, r, g, b, a);
        fillBox(builder, stack, -t, -t, -t + fd, t + fw, t, t + fd, r, g, b, a);
        fillBox(builder, stack, -t, -t, -t, t, t, t + fd, r, g, b, a);
        fillBox(builder, stack, -t + fw, -t, -t, t + fw, t, t + fd, r, g, b, a);

        stack.popPose();
    }

    /**
     * Fill a quad for {@link net.minecraft.client.render.VertexFormats#POSITION_TEXTURE_COLOR_NORMAL}. Points should
     * be supplied in this order:
     *
     *     3 -------> 4
     *     ^
     *     |
     *     |
     *     2 <------- 1
     *
     * I.e. bottom left, bottom right, top left, top right, where left is -X and right is +X,
     * in case of a quad on fixed on Z axis.
     */
    public static void fillTexturedNormalQuad(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4, float u1, float v1, float u2, float v2, float r, float g, float b, float a, float nx, float ny, float nz)
    {
        Matrix4f matrix4f = stack.last().pose();

        /* 1 - BL, 2 - BR, 3 - TR, 4 - TL */
        builder.addVertex(matrix4f, x2, y2, z2).setUv(u1, v2).setColor(r, g, b, a).setNormal(nx, ny, nz);
        builder.addVertex(matrix4f, x1, y1, z1).setUv(u2, v2).setColor(r, g, b, a).setNormal(nx, ny, nz);
        builder.addVertex(matrix4f, x4, y4, z4).setUv(u2, v1).setColor(r, g, b, a).setNormal(nx, ny, nz);

        builder.addVertex(matrix4f, x2, y2, z2).setUv(u1, v2).setColor(r, g, b, a).setNormal(nx, ny, nz);
        builder.addVertex(matrix4f, x4, y4, z4).setUv(u2, v1).setColor(r, g, b, a).setNormal(nx, ny, nz);
        builder.addVertex(matrix4f, x3, y3, z3).setUv(u1, v1).setColor(r, g, b, a).setNormal(nx, ny, nz);
    }

    public static void fillQuad(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4, float r, float g, float b, float a)
    {
        Matrix4f matrix4f = stack.last().pose();

        /* 1 - BR, 2 - BL, 3 - TL, 4 - TR */
        builder.addVertex(matrix4f, x1, y1, z1).setColor(r, g, b, a);
        builder.addVertex(matrix4f, x2, y2, z2).setColor(r, g, b, a);
        builder.addVertex(matrix4f, x3, y3, z3).setColor(r, g, b, a);
        builder.addVertex(matrix4f, x1, y1, z1).setColor(r, g, b, a);
        builder.addVertex(matrix4f, x3, y3, z3).setColor(r, g, b, a);
        builder.addVertex(matrix4f, x4, y4, z4).setColor(r, g, b, a);
    }

    public static void fillBoxTo(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, float thickness, float r, float g, float b, float a)
    {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Angle angle = Angle.angle(dx, dy, dz);

        stack.pushPose();

        stack.translate(x1, y1, z1);
        stack.rotateAround(com.mojang.math.Axis.YP.rotationDegrees(angle.yaw));
        stack.rotateAround(com.mojang.math.Axis.XP.rotationDegrees(angle.pitch));

        fillBox(builder, stack, -thickness / 2, -thickness / 2, 0, thickness / 2, thickness / 2, (float) distance, r, g, b, a);

        stack.popPose();
    }

    public static void fillBox(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, int color)
    {
        fillBox(builder, stack, x1, y1, z1, x2, y2, z2, Colors.getR(color), Colors.getG(color), Colors.getB(color), Colors.getOpaqueA(color));
    }

    public static void fillBox(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b)
    {
        fillBox(builder, stack, x1, y1, z1, x2, y2, z2, r, g, b, 1F);
    }

    public static void fillBox(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a)
    {
        /* X */
        fillQuad(builder, stack, x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1, r, g, b, a);
        fillQuad(builder, stack, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a);

        /* Y */
        fillQuad(builder, stack, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a);
        fillQuad(builder, stack, x2, y2, z1, x1, y2, z1, x1, y2, z2, x2, y2, z2, r, g, b, a);

        /* Z */
        fillQuad(builder, stack, x2, y1, z1, x1, y1, z1, x1, y2, z1, x2, y2, z1, r, g, b, a);
        fillQuad(builder, stack, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, r, g, b, a);
    }

    public static void coolerAxes(PoseStack stack, float axisSize, float axisOffset)
    {
        float scale = BBSSettings.axesScale.get();
        float thickness = BBSSettings.axesThickness.get();

        axisSize *= scale;
        axisOffset *= scale * thickness;

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        fillBox(builder, stack, 0, -axisOffset, -axisOffset, axisSize, axisOffset, axisOffset, Colors.RED);
        fillBox(builder, stack, -axisOffset, 0, -axisOffset, axisOffset, axisSize, axisOffset, Colors.GREEN);
        fillBox(builder, stack, -axisOffset, -axisOffset, 0, axisOffset, axisOffset, axisSize, Colors.BLUE);
        fillBox(builder, stack, -axisOffset, -axisOffset, -axisOffset, axisOffset, axisOffset, axisOffset, Colors.WHITE);

        /* The old code did RenderSystem.disableDepthTest() before drawing; depth state now lives in
         * the pipeline, so this draws through the no-depth POSITION_COLOR layer instead. */
        flush(builder, getPositionColorNoDepthLayer());
    }

    public static void arc3D(BufferBuilder builder, PoseStack stack, Axis axis, float radius, float thickness, int color)
    {
        arc3D(builder, stack, axis, radius, thickness, Colors.getR(color), Colors.getG(color), Colors.getB(color), 0F, 360F);
    }

    public static void arc3D(BufferBuilder builder, PoseStack stack, Axis axis, float radius, float thickness, float r, float g, float b)
    {
        arc3D(builder, stack, axis, radius, thickness, r, g, b, 0F, 360F);
    }

    /**
     * Based on ElGatoPro300's code from BBS mod CML edition
     */
    public static void arc3D(BufferBuilder builder, PoseStack stack, Axis axis, float radius, float thickness, float r, float g, float b, float startDeg, float sweepDeg)
    {
        arc3D(builder, stack, axis, radius, thickness, r, g, b, startDeg, sweepDeg, 64, 12);
    }

    /** Arc with an explicit vertex alpha. */
    public static void arc3D(BufferBuilder builder, PoseStack stack, Axis axis, float radius, float thickness, float r, float g, float b, float startDeg, float sweepDeg, float a)
    {
        arc3D(builder, stack, axis, radius, thickness, r, g, b, startDeg, sweepDeg, 64, 12, a);
    }

    /**
     * Tessellate an arc of a torus. The tube's cross-section circle is precomputed once and the
     * ring-angle trig lives outside the inner loop — the old shape recomputed both per quad,
     * which put ~18k trig calls into a single ring.
     */
    public static void arc3D(BufferBuilder builder, PoseStack stack, Axis axis, float radius, float thickness, float r, float g, float b, float startDeg, float sweepDeg, int segU, int segV)
    {
        arc3D(builder, stack, axis, radius, thickness, r, g, b, startDeg, sweepDeg, segU, segV, 1F);
    }

    /** Tessellated arc with an explicit vertex alpha. */
    public static void arc3D(BufferBuilder builder, PoseStack stack, Axis axis, float radius, float thickness, float r, float g, float b, float startDeg, float sweepDeg, int segU, int segV, float a)
    {
        double u0 = Math.toRadians(startDeg);
        double uStep = Math.toRadians(sweepDeg / (double) segU);
        double vStep = Math.PI * 2D / (double) segV;

        stack.pushPose();

        if (axis == Axis.X) stack.rotateAround(com.mojang.math.Axis.ZP.rotation(MathUtils.PI / 2F));
        if (axis == Axis.Z) stack.rotateAround(com.mojang.math.Axis.XP.rotation(MathUtils.PI / 2F));

        float tubeR = thickness * 0.5F;
        Matrix4f mat = stack.last().pose();

        /* The tube cross-section: ring-of-the-tube radii and heights, shared by every u step. */
        double[] ringR = new double[segV + 1];
        float[] ringY = new float[segV + 1];

        for (int iv = 0; iv <= segV; iv++)
        {
            double v = vStep * iv;

            ringR[iv] = radius + tubeR * Math.cos(v);
            ringY[iv] = (float) (tubeR * Math.sin(v));
        }

        double cosU2 = Math.cos(u0);
        double sinU2 = Math.sin(u0);

        for (int iu = 0; iu < segU; iu++)
        {
            double cosU1 = cosU2;
            double sinU1 = sinU2;
            double u2 = u0 + uStep * (iu + 1);

            cosU2 = Math.cos(u2);
            sinU2 = Math.sin(u2);

            for (int iv = 0; iv < segV; iv++)
            {
                double r1 = ringR[iv];
                double r2 = ringR[iv + 1];
                float y1 = ringY[iv];
                float y2 = ringY[iv + 1];

                float x11 = (float) (r1 * cosU1);
                float z11 = (float) (r1 * sinU1);

                float x12 = (float) (r2 * cosU1);
                float z12 = (float) (r2 * sinU1);

                float x21 = (float) (r1 * cosU2);
                float z21 = (float) (r1 * sinU2);

                float x22 = (float) (r2 * cosU2);
                float z22 = (float) (r2 * sinU2);

                builder.addVertex(mat, x11, y1, z11).setColor(r, g, b, a);
                builder.addVertex(mat, x12, y2, z12).setColor(r, g, b, a);
                builder.addVertex(mat, x22, y2, z22).setColor(r, g, b, a);

                builder.addVertex(mat, x11, y1, z11).setColor(r, g, b, a);
                builder.addVertex(mat, x22, y2, z22).setColor(r, g, b, a);
                builder.addVertex(mat, x21, y1, z21).setColor(r, g, b, a);
            }
        }

        stack.popPose();
    }

    public static void sphere(BufferBuilder builder, PoseStack stack, float radius, int rings, int sectors, float r, float g, float b, float a)
    {
        float constR = 1.0F / (float) (rings - 1);
        float constS = 1.0F / (float) (sectors - 1);

        Matrix4f mat = stack.last().pose();

        for (int i = 0; i < rings - 1; i++)
        {
            for (int j = 0; j < sectors - 1; j++)
            {
                float y0 = (float) Math.sin(-Math.PI / 2 + Math.PI * i * constR);
                float x0 = (float) Math.cos(2 * Math.PI * j * constS) * (float) Math.sin(Math.PI * i * constR);
                float z0 = (float) Math.sin(2 * Math.PI * j * constS) * (float) Math.sin(Math.PI * i * constR);

                float y1 = (float) Math.sin(-Math.PI / 2 + Math.PI * (i + 1) * constR);
                float x1 = (float) Math.cos(2 * Math.PI * j * constS) * (float) Math.sin(Math.PI * (i + 1) * constR);
                float z1 = (float) Math.sin(2 * Math.PI * j * constS) * (float) Math.sin(Math.PI * (i + 1) * constR);

                float y2 = (float) Math.sin(-Math.PI / 2 + Math.PI * (i + 1) * constR);
                float x2 = (float) Math.cos(2 * Math.PI * (j + 1) * constS) * (float) Math.sin(Math.PI * (i + 1) * constR);
                float z2 = (float) Math.sin(2 * Math.PI * (j + 1) * constS) * (float) Math.sin(Math.PI * (i + 1) * constR);

                float y3 = (float) Math.sin(-Math.PI / 2 + Math.PI * i * constR);
                float x3 = (float) Math.cos(2 * Math.PI * (j + 1) * constS) * (float) Math.sin(Math.PI * i * constR);
                float z3 = (float) Math.sin(2 * Math.PI * (j + 1) * constS) * (float) Math.sin(Math.PI * i * constR);

                builder.addVertex(mat, x0 * radius, y0 * radius, z0 * radius).setColor(r, g, b, a);
                builder.addVertex(mat, x1 * radius, y1 * radius, z1 * radius).setColor(r, g, b, a);
                builder.addVertex(mat, x2 * radius, y2 * radius, z2 * radius).setColor(r, g, b, a);

                builder.addVertex(mat, x0 * radius, y0 * radius, z0 * radius).setColor(r, g, b, a);
                builder.addVertex(mat, x2 * radius, y2 * radius, z2 * radius).setColor(r, g, b, a);
                builder.addVertex(mat, x3 * radius, y3 * radius, z3 * radius).setColor(r, g, b, a);
            }
        }
    }
}
