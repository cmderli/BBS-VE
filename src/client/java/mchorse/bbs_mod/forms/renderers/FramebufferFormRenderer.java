package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.client.render.picker.BBSPickerRenderer;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormRenderCapture;
import mchorse.bbs_mod.forms.FormRenderLast;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.FramebufferForm;
import mchorse.bbs_mod.forms.renderers.utils.FramebufferDebug;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.graphics.FormFramebuffer;
import mchorse.bbs_mod.graphics.FramebufferPool;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.Quad;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.joml.Vectors;
import mchorse.bbs_mod.utils.profiler.BBSProfiler;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.platform.Lighting;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Map;

public class FramebufferFormRenderer extends FormRenderer<FramebufferForm>
{
    private static final Quad quad = new Quad();
    private static final Quad uvQuad = new Quad();

    /* The box the nested forms render into: the whole texture, 500 units of depth either way. One box for
     * every framebuffer form, nested ones included — it never depends on the form, so a nested render
     * writing the same values over the outer one's slice changes nothing.
     *
     * 1.21.1 flipped this box in Y and culled FRONT faces to match, which is how the picture came out the
     * way up the quad's UVs expected. Culling is the pipeline's own business now — a flipped box would
     * turn every part's front faces into back ones and the culling layers would throw the whole picture
     * away — so the box stands upright and the flip moved to the quad's UVs, where vanilla's own
     * off-screen previews put theirs. */
    private static final Matrix4f ORTHO = new Matrix4f().setOrtho(-1F, 1F, -1F, 1F, -500F, 500F);
    /* 26.2 renamed the perspective-only buffer: ProjectionMatrixBuffer carries any projection and
     * its getBuffer(Matrix4f) is the same call the old one made. */
    private static final ProjectionMatrixBuffer PROJECTION = new ProjectionMatrixBuffer("bbs_framebuffer_form");

    private static GpuBuffer lightsBuffer;
    private static GpuBufferSlice lights;

    /** Whatever the parts inside want to ask about the entity wearing the form; a cell has none. */
    private final IEntity entity = new StubEntity();

    public FramebufferFormRenderer(FramebufferForm form)
    {
        super(form);
    }

    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        if (this.form.parts.getAll().isEmpty())
        {
            /* Nothing in it yet, so there is no picture to show - stand a figure in the cell
             * instead, at the size the video form draws its own placeholder at. */
            int size = 32;

            context.batcher.scaledIcon(Icons.PLAYER, Colors.WHITE, (x1 + x2 - size) / 2F, (y1 + y2 - size) / 2F, size);

            return;
        }

        /* A list or icon cell: the picture is submitted as a special GUI element and drawn off-screen in
         * the GUI prepare phase, because the two-phase GUI (1.21.6+) drops an immediate 3D draw recorded
         * here. BbsFormGuiElementRenderer calls back into renderUIPreview — the same path the model and
         * billboard forms take. 1.21.1 drew straight onto the batcher's 3D matrix stack, which is a 2D
         * one now, and bracketed it with RenderSystem.depthFunc, which is gone. */
        this.submitUIPreview(context, x1, y1, x2, y2);
    }

    /**
     * The cell preview: the form's own {@link #renderBodyParts} draws the parts into a buffer and puts the
     * quad on screen, so a cell shows exactly what the world does — only framed for the cell.
     *
     * <p>The base renderer has already translated the stack to the cell and set its ortho projection; what
     * is left is the shared cell framing plus the same lift and scale the billboard gives its own flat
     * preview, the quad being the same kind of flat thing. The normal matrix takes the Y flip the preview
     * frame is mirrored by, or the quad is lit from the wrong side (see ModelFormRenderer).</p>
     */
    @Override
    public void renderUIPreview(PoseStack stack, float angle, float transition, int x1, int y1, int x2, int y2)
    {
        if (this.form.parts.getAll().isEmpty())
        {
            return;
        }

        Matrix4f uiMatrix = getUIPreviewMatrix(angle, y1, y2);

        this.applyTransforms(uiMatrix, transition);

        stack.pushPose();

        MatrixStackUtils.multiply(stack, uiMatrix);
        stack.translate(0F, 1F, 0F);
        stack.scale(1.5F, 1.5F, 1.5F);

        stack.last().normal().getScale(Vectors.EMPTY_3F);
        stack.last().normal().scale(1F / Vectors.EMPTY_3F.x, -1F / Vectors.EMPTY_3F.y, 1F / Vectors.EMPTY_3F.z);

        this.renderBodyParts(new FormRenderingContext()
            .set(FormRenderType.ENTITY, this.entity, stack, LightCoordsUtil.pack(15, 15), OverlayTexture.NO_OVERLAY, transition)
            .inUI());

        stack.popPose();
    }

    /**
     * How deep in nested framebuffer forms the render currently is. The profiler's timer keeps
     * a single start per subsystem, so only the outermost framebuffer runs it - an inner one
     * would restart the clock and the outer one's remainder would be lost.
     */
    private static int renderDepth;

    @Override
    public void renderBodyParts(FormRenderingContext context)
    {
        FramebufferPool pool = BBSModClient.getFramebuffers().getFormFramebuffers();
        FormFramebuffer framebuffer = pool.get(MathUtils.clamp(this.form.width.get(), 2, 4096), MathUtils.clamp(this.form.height.get(), 2, 4096));
        boolean outermost = renderDepth == 0;

        BBSProfiler.count(BBSProfiler.Section.FRAMEBUFFER_RENDERS);

        if (outermost)
        {
            BBSProfiler.begin(BBSProfiler.Timer.FRAMEBUFFER_FORMS);
        }

        renderDepth += 1;
        FramebufferDebug.beginRender(this.form, context, framebuffer);

        try
        {
            this.renderFramebuffer(context, framebuffer);
        }
        finally
        {
            renderDepth -= 1;
            FramebufferDebug.endRender();
            pool.release(framebuffer);

            if (outermost)
            {
                BBSProfiler.end(BBSProfiler.Timer.FRAMEBUFFER_FORMS);
            }
        }
    }

    /** Report each nested part and the pixels it has left in the current target. */
    @Override
    protected void renderBodyPart(BodyPart part, FormRenderingContext context)
    {
        if (!FramebufferDebug.inside())
        {
            super.renderBodyPart(part, context);

            return;
        }

        String name = part.getForm() == null ? "null" : part.getForm().getClass().getSimpleName();

        FramebufferDebug.log("part", "begin " + name + " id=" + part.getId() + " | " + FramebufferDebug.bindings());
        super.renderBodyPart(part, context);
        FramebufferDebug.log("part", "end " + name + " | " + FramebufferDebug.bindings());
        FramebufferDebug.log("part", "end " + name + " | " + FramebufferDebug.glState());
        FramebufferDebug.readViewport("part end " + name);
    }

    private void renderFramebuffer(FormRenderingContext context, FormFramebuffer framebuffer)
    {
        FramebufferDebug.state("entry", context);

        /* Snapshotted by hand, not through RenderSystem.backupProjectionMatrix(): that backup is a single
         * slot, and a framebuffer form nested inside another would overwrite the outer one's saved world
         * projection with the inner one's ortho. Put back below as they WERE, not as they usually are —
         * the projection type is also what the frame's translucency sorts by. */
        GpuBufferSlice previousLights = RenderSystem.getShaderLights();
        GpuBufferSlice previousProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType previousProjectionType = RenderSystem.getProjectionType();

        /* Where the draws land. 1.21.1 bound this framebuffer by hand and every draw in the frame
         * followed; on 1.21.11 a draw goes through a render pass the backend builds out of texture
         * views, and a hand-bound FBO is never consulted — the parts would have gone to the screen and
         * the buffer would have stayed empty. So the target is named instead, and the picker is named
         * separately: its passes are built by hand (BBSPickerRenderer#draw) and keep a target of their
         * own. Both are put back to what they WERE, not to the default: the caller may be drawing
         * off-screen itself — a framebuffer form nested inside another, a form-list thumbnail rendering
         * into its cell — and the default would send the rest of its picture to the screen. */
        GpuTextureView previousColor = RenderSystem.outputColorTextureOverride;
        GpuTextureView previousDepth = RenderSystem.outputDepthTextureOverride;
        GpuTextureView previousPickColor = BBSPickerRenderer.getRenderTargetColor();
        GpuTextureView previousPickDepth = BBSPickerRenderer.getRenderTargetDepth();

        /* Cleared to fully transparent, so only what the parts draw carries alpha, and to depth 1 so they
         * sort among themselves. Was a glClear against the bound FBO, which no longer means anything.
         * 26.2 takes the clear colour as a Vector4fc rather than the old packed 0x00000000 (all four
         * channels zero either way). */
        RenderSystem.getDevice().createCommandEncoder()
            .clearColorAndDepthTextures(framebuffer.getColor(), new Vector4f(0F, 0F, 0F, 0F), framebuffer.getDepth(), 1.0D);

        RenderSystem.outputColorTextureOverride = framebuffer.getColorView();
        RenderSystem.outputDepthTextureOverride = framebuffer.getDepthView();
        BBSPickerRenderer.setRenderTarget(framebuffer.getColorView(), framebuffer.getDepthView());

        RenderSystem.setShaderLights(lights());
        RenderSystem.setProjectionMatrix(PROJECTION.getBuffer(ORTHO), ProjectionType.ORTHOGRAPHIC);

        /* The programs read the model-view off this stack, and in the interface it carries the GUI's
         * translate(0, 0, -11000): with our ortho reaching only 500 units deep, every vertex of the parts
         * landed outside it and the buffer came out empty. In the world it is the identity already, so
         * nothing changes there. 1.21.1 needed an applyModelViewMatrix() to go with this; on 1.21.11 the
         * dynamic uniforms read the stack at draw time, so pushing the identity is the whole of it. */
        Matrix4fStack modelView = RenderSystem.getModelViewStack();

        modelView.pushMatrix();
        modelView.identity();

        context.stack.pushPose();
        context.stack.last().pose().identity();
        context.stack.last().normal().identity();

        /* The nested forms render under an ortho projection into this framebuffer — deferring
         * their translucent pixels into the world's queue would replay them with the wrong
         * projection, so they render single-pass as before. Render-last is off here for the
         * same reason: a part postponed out of this buffer would come back in the world. */
        boolean queueWasActive = FormTranslucentQueue.suspend();
        boolean renderLastWasActive = FormRenderLast.suspend();

        /* And the item capture, for the same reason once more: a model block held in hand renders
         * its form inside an armed FormRenderCapture session, where RenderLayerMixin cancels every
         * layer draw and keeps the geometry for the item command queue. The parts below must draw
         * for real, into this buffer — captured instead, they left the buffer empty and came back at
         * the item with their ortho-sized coordinates, filling the screen. The quad that follows is
         * what the item is meant to capture, and it draws after this is restored. */
        FormRenderCapture.Suspended captureWasActive = FormRenderCapture.suspend();

        /* Full bright on the way in: the quad that draws the finished picture applies the
         * caller's lightmap once, so letting it shade the parts inside the buffer too would
         * land the very same shading on them twice. */
        int light = context.light;

        context.light = LightCoordsUtil.FULL_BRIGHT;

        /* Iris can leave indexed blend overrides behind while GlStateManager already caches the
         * default. Reset the real factors as well as the cache before the parts select their own
         * pipelines, or their alpha can stay at the transparent clear value (ZERO/ONE).
         * 1.21.11 moved the tracked calls from RenderSystem to GlStateManager. */
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glEnable(GL11.GL_BLEND);
        GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        /* 26.2's _enableBlend() takes the tracked blend-state slot it enables; 0 is the one
         * _blendFuncSeparate just configured (it hardcodes BLEND[0]), so this is the old no-arg call. */
        GlStateManager._enableBlend(0);

        try
        {
            BBSRendering.renderOffscreen(() -> super.renderBodyParts(context));
        }
        finally
        {
            context.light = light;

            FormTranslucentQueue.restore(queueWasActive);
            FormRenderLast.restore(renderLastWasActive);
            FormRenderCapture.restore(captureWasActive);
        }

        FramebufferDebug.readBuffer("after parts", framebuffer);
        FramebufferDebug.state("after parts", context);

        context.stack.popPose();

        modelView.popMatrix();

        RenderSystem.setProjectionMatrix(previousProjection, previousProjectionType);

        if (previousLights != null)
        {
            RenderSystem.setShaderLights(previousLights);
        }

        RenderSystem.outputColorTextureOverride = previousColor;
        RenderSystem.outputDepthTextureOverride = previousDepth;
        BBSPickerRenderer.setRenderTarget(previousPickColor, previousPickDepth);

        boolean shading = !context.isPicking();

        /* The finished picture goes onto the quad through the same pair of layers the billboard draws
         * through: the shaded one in the world (formerly getRenderTypeEntityTranslucentProgram), the
         * unlit one while picking, where the buffer holds ids as colours and any shading would alter
         * them (formerly getPositionTexColorProgram).
         *
         * Both layers cull backfaces, because renderQuad emits the quad TWICE — once per side, with
         * opposite winding and normals — and counts on the GPU to keep the side facing the viewer. On
         * 1.21.1 that came for free from the global GL state; here it is the layer's own pipeline.
         *
         * The unlit layer is vanilla's position_tex_color, so the picking format loses the LIGHT element
         * the 1.21.1 one carried: that program never read it, and on 1.21.11 the buffer's format has to
         * be exactly the pipeline's own. Neither layer needs a texture bind: the picture is a device
         * texture, and the layers name it by the id it was adopted under. */
        Identifier identifier = framebuffer.getIdentifier();
        VertexFormat format = shading ? DefaultVertexFormat.ENTITY : DefaultVertexFormat.POSITION_TEX_COLOR;
        RenderType layer = shading
            ? BBSShaders.getModelLayer(BBSShaders.ModelVariant.SINGLE.withCull(true), identifier)
            : BBSShaders.getBillboardLayer(identifier);

        if (FramebufferDebug.logging)
        {
            FramebufferDebug.log("quad", "layer=" + layer + " shading=" + shading + " texture=" + identifier);
        }

        FramebufferDebug.state("before quad", context);

        if (shading)
        {
            this.renderModel(framebuffer, identifier, format, layer, context.stack, context.overlay, context.light, context.color, context.getTransition(), true);
            FramebufferDebug.state("after quad", context);

            return;
        }

        /* Picking composites the ids the parts wrote into the buffer, and those have to reach the
         * PICKER's target — a layer draw follows RenderSystem's, which during the pick pass still points
         * at the screen. Pointed at the picking target for the one draw and put back after: the ids stay
         * per nested part, exactly as on 1.21.1, instead of the whole form picking as one object. */
        RenderSystem.outputColorTextureOverride = previousPickColor == null ? previousColor : previousPickColor;
        RenderSystem.outputDepthTextureOverride = previousPickColor == null ? previousDepth : previousPickDepth;

        try
        {
            this.renderModel(framebuffer, identifier, format, layer, context.stack, context.overlay, context.light, context.color, context.getTransition(), false);
            FramebufferDebug.state("after quad", context);
        }
        finally
        {
            RenderSystem.outputColorTextureOverride = previousColor;
            RenderSystem.outputDepthTextureOverride = previousDepth;
        }
    }

    /**
     * Both lights along Z, one each way, as a Lighting UBO of our own.
     *
     * <p>The picture in here is meant to be flat, and the two vanilla lights are what a flat one is made
     * of — but pointing both at the camera lights only the faces that happen to look back at it. The
     * framebuffer renders under a Y-flipped ortho with front faces culled, so a two-sided quad (a
     * billboard draws both of its sides) keeps the side whose normal points away, and that side came out
     * at MINECRAFT_AMBIENT_LIGHT alone — 40% — while a one-sided model next to it stayed lit.</p>
     *
     * <p>1.21.1 said this in one setShaderLights(Vector3f, Vector3f); the 1.21.5 rewrite left only the
     * GpuBufferSlice overload, and the Lighting UBO behind it is exactly two std140 vec3s — so the two
     * directions are built here, the way the list previews build theirs (BbsFormGuiElementRenderer).</p>
     */
    private static GpuBufferSlice lights()
    {
        if (lights == null)
        {
            try (MemoryStack stack = MemoryStack.stackPush())
            {
                ByteBuffer data = Std140Builder.onStack(stack, Lighting.UBO_SIZE)
                    .putVec3(new Vector3f(0F, 0F, 1F))
                    .putVec3(new Vector3f(0F, 0F, -1F))
                    .get();

                /* usage 136 = UNIFORM | COPY_DST, mirroring DiffuseLighting's own Lighting UBO. */
                lightsBuffer = RenderSystem.getDevice().createBuffer(() -> "BBS framebuffer form lights UBO", 136, data);
                lights = lightsBuffer.slice(0, Lighting.UBO_SIZE);
            }
        }

        return lights;
    }

    private void renderModel(FormFramebuffer framebuffer, Identifier identifier, VertexFormat format, RenderType layer, PoseStack matrices, int overlay, int light, int overlayColor, float transition, boolean defer)
    {
        float w = framebuffer.width;
        float h = framebuffer.height;

        /* TL = top left, BR = bottom right*/
        Vector4f crop = new Vector4f(0, 0, 0, 0);
        float uvTLx = crop.x / w;
        float uvTLy = crop.y / h;
        float uvBRx = 1 - crop.z / w;
        float uvBRy = 1 - crop.w / h;

        /* Flipped in V: the texture's first row is its BOTTOM one, while p1/p2 are the quad's top edge.
         * 1.21.1 got this from the flipped ortho the parts rendered under; that flip is gone (see ORTHO),
         * so it is spelled out here — the same way vanilla composites its own off-screen previews. */
        uvQuad.p1.set(uvTLx, uvBRy, 0);
        uvQuad.p2.set(uvBRx, uvBRy, 0);
        uvQuad.p3.set(uvTLx, uvTLy, 0);
        uvQuad.p4.set(uvBRx, uvTLy, 0);

        /* Calculate quad's size (vertices, not UV). The scale sizes the quad the framebuffer is
         * shown on, not what is drawn into it — the body parts always fill the whole texture,
         * so raising it can't push them past the framebuffer's own edges. */
        float scale = this.form.scale.get() * 2F;
        float ratioX = (w > h ? h / w : 1F) * scale;
        float ratioY = (h > w ? w / h : 1F) * scale;
        float TLx = (uvTLx - 0.5F) * ratioY;
        float TLy = -(uvTLy - 0.5F) * ratioX;
        float BRx = (uvBRx - 0.5F) * ratioY;
        float BRy = -(uvBRy - 0.5F) * ratioX;

        quad.p1.set(TLx, TLy, 0);
        quad.p2.set(BRx, TLy, 0);
        quad.p3.set(TLx, BRy, 0);
        quad.p4.set(BRx, BRy, 0);

        this.renderQuad(format, identifier, layer, matrices, overlay, light, overlayColor, transition, defer);
    }

    private void renderQuad(VertexFormat format, Identifier identifier, RenderType layer, PoseStack matrices, int overlay, int light, int overlayColor, float transition, boolean defer)
    {
        Color color = Color.white();
        Matrix4f matrix = matrices.last().pose();
        PoseStack.Pose entry = matrices.last();

        color.mul(overlayColor);

        /* Was: lightmap.enable() + overlay.setupOverlayColor() + RenderSystem.setShader(shader). Lightmap,
         * overlay and the program all belong to the layer now — the BBS model layer declares
         * useLightmap()/useOverlay() and its pipeline is the shader, and the layer carries the picture in
         * its own Sampler0, so there is nothing left to bind here. */
        /* 26.2 has no Tesselator: the growable staging buffer it owned is created here (the size
         * Draw/Gizmo use for their immediate geometry) and released after the draw below. */
        ByteBufferBuilder allocator = new ByteBufferBuilder(1536);
        BufferBuilder builder = new BufferBuilder(allocator, PrimitiveTopology.TRIANGLES, format);

        /* Front */
        this.fill(format, builder, matrix, quad.p3.x, quad.p3.y, color, uvQuad.p3.x, uvQuad.p3.y, overlay, light, entry, 1F);
        this.fill(format, builder, matrix, quad.p2.x, quad.p2.y, color, uvQuad.p2.x, uvQuad.p2.y, overlay, light, entry, 1F);
        this.fill(format, builder, matrix, quad.p1.x, quad.p1.y, color, uvQuad.p1.x, uvQuad.p1.y, overlay, light, entry, 1F);

        this.fill(format, builder, matrix, quad.p3.x, quad.p3.y, color, uvQuad.p3.x, uvQuad.p3.y, overlay, light, entry, 1F);
        this.fill(format, builder, matrix, quad.p4.x, quad.p4.y, color, uvQuad.p4.x, uvQuad.p4.y, overlay, light, entry, 1F);
        this.fill(format, builder, matrix, quad.p2.x, quad.p2.y, color, uvQuad.p2.x, uvQuad.p2.y, overlay, light, entry, 1F);

        /* Back */
        this.fill(format, builder, matrix, quad.p1.x, quad.p1.y, color, uvQuad.p1.x, uvQuad.p1.y, overlay, light, entry, -1F);
        this.fill(format, builder, matrix, quad.p2.x, quad.p2.y, color, uvQuad.p2.x, uvQuad.p2.y, overlay, light, entry, -1F);
        this.fill(format, builder, matrix, quad.p3.x, quad.p3.y, color, uvQuad.p3.x, uvQuad.p3.y, overlay, light, entry, -1F);

        this.fill(format, builder, matrix, quad.p2.x, quad.p2.y, color, uvQuad.p2.x, uvQuad.p2.y, overlay, light, entry, -1F);
        this.fill(format, builder, matrix, quad.p4.x, quad.p4.y, color, uvQuad.p4.x, uvQuad.p4.y, overlay, light, entry, -1F);
        this.fill(format, builder, matrix, quad.p3.x, quad.p3.y, color, uvQuad.p3.x, uvQuad.p3.y, overlay, light, entry, -1F);

        /* Was: defaultBlendFunc + enableBlend + BufferRenderer.drawWithGlobalProgram. Blend is encoded in
         * the layer's pipeline now, and the quad is submitted through the layer, which carries this
         * framebuffer's texture in its own Sampler0. */
        MeshData built = builder.build();

        if (built != null)
        {
            if (defer && FormTranslucentQueue.isActive())
            {
                /* The framebuffer's content is transparent-background by nature, so the whole quad defers
                 * into the sorted translucent pass. A flat quad has nothing to occlude itself with, so the
                 * deferred pass drops the depth write — except under a shaderpack, which reconstructs its
                 * shading from the depth buffer and would paint the backdrop's shadows over our face.
                 *
                 * The pool hands the same target to the next form of the same size, so several deferred
                 * quads end up showing the same content — a known trade-off of the pooled scheme, exactly
                 * as on 1.21.1. */
                RenderType deferred = BBSShaders.getModelLayer(new BBSShaders.ModelVariant(
                    FormTranslucentQueue.PASS_SINGLE, BBSRendering.isIrisWorldForms(), true), identifier);
                Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrixCopy());
                Vector3f origin = modelView.transformPosition(matrix.getTranslation(new Vector3f()));

                /* The quad's opaque texels also draw right here, writing depth, because the sort
                 * alone cannot order this quad against a model it sits inside: a semi-transparent
                 * layer of the parent model (a skin's hat layer) sorts by its group's pivot, which
                 * is always further than the quad's own plane, so it replays first. With depth in
                 * the buffer that layer lands over the quad by the depth test, pixel by pixel,
                 * instead of the two fighting over who overwrites whom.
                 *
                 * 1.21.1 drew this half with the vanilla cutout program and a global depth mask; here
                 * it is the same PASS_OPAQUE + depthWrite pair {@link FormTranslucentQueue#submit} uses
                 * for a split model, and the opaque half always writes depth even though the deferred
                 * one above may not. Drawing hands the buffer over, so the capture is taken first. */
                FormRenderCapture.Captured captured = FormRenderCapture.copy(built);
                RenderType opaque = BBSShaders.getModelLayer(new BBSShaders.ModelVariant(
                    FormTranslucentQueue.PASS_OPAQUE, true, true), identifier);

                drawLayer(opaque, built);

                FormTranslucentQueue.add(new FormTranslucentQueue.BufferCommand(deferred, captured, origin));
            }
            else
            {
                drawLayer(layer, built);
            }
        }

        allocator.close();
    }

    /**
     * 26.2's replacement for {@code RenderLayer.draw(BuiltBuffer)}, including what
     * {@code RenderLayerMixin}'s hook did on 1.21.11: while a {@link FormRenderCapture} session is
     * open (deferred item-model rendering) the draw is captured instead of executed — no GL pass is
     * open at item-record time — and otherwise the layer's hijack runnable fires before the draw.
     *
     * <p>The finished vertices then go to a device buffer and the layer's shared sequential index
     * buffer feeds {@code PreparedRenderType.drawFromBuffer}, which opens the pass on the layer's own
     * output target (the same translation {@code Draw#flush} and {@code Gizmo#flush} use).</p>
     */
    private static void drawLayer(RenderType layer, MeshData built)
    {
        if (FormRenderCapture.isActive())
        {
            FormRenderCapture.capture(layer, built);

            return;
        }

        CustomVertexConsumerProvider.drawLayer(layer);

        MeshData.DrawState state = built.drawState();
        GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "bbs framebuffer form geometry", GpuBuffer.USAGE_VERTEX, built.vertexBuffer());
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(state.primitiveTopology());

        layer.prepare().drawFromBuffer(vertices, indices.getBuffer(state.indexCount()), indices.type(), 0, 0, state.indexCount());

        vertices.close();
        built.close();
    }

    private VertexConsumer fill(VertexFormat format, VertexConsumer consumer, Matrix4f matrix, float x, float y, Color color, float u, float v, int overlay, int light, PoseStack.Pose entry, float nz)
    {
        if (format == DefaultVertexFormat.POSITION_TEX_COLOR)
        {
            /* The unlit path: vanilla position_tex_color reads exactly Position/UV0/Color. */
            return consumer.addVertex(matrix, x, y, 0F).setUv(u, v).setColor(color.r, color.g, color.b, color.a);
        }

        if (format == DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR)
        {
            return consumer.addVertex(matrix, x, y, 0F).setUv(u, v).setLight(light).setColor(color.r, color.g, color.b, color.a);
        }

        return consumer.addVertex(matrix, x, y, 0F).setColor(color.r, color.g, color.b, color.a).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(entry, 0F, 0F, nz);
    }

    @Override
    public void collectMatrices(IEntity entity, PoseStack stack, MatrixCache matrices, String prefix, float transition)
    {
        stack.pushPose();
        this.applyTransforms(stack, true, transition);
        Matrix4f origin = new Matrix4f(stack.last().pose());
        stack.popPose();

        stack.pushPose();
        this.applyTransforms(stack, false, transition);
        matrices.put(prefix, new Matrix4f(stack.last().pose()), origin);

        float width = MathUtils.clamp(this.form.width.get(), 2, 4096);
        float height = MathUtils.clamp(this.form.height.get(), 2, 4096);
        float scale = this.form.scale.get();

        Matrix4f parent = new Matrix4f(stack.last().pose());
        PoseStack childStack = new PoseStack();
        MatrixCache children = new MatrixCache();

        /* The body parts live in the framebuffer's ortho box (-1..1 across the whole texture),
         * and the quad that shows it is that box times the scale and the aspect ratio. */
        float scaleX = scale * (height > width ? width / height : 1F);
        float scaleY = scale * (width > height ? height / width : 1F);

        for (BodyPart part : this.form.parts.getAllTyped())
        {
            Form form = part.getForm();

            if (form != null)
            {
                childStack.pushPose();
                MatrixStackUtils.applyTransform(childStack, part.transform.get());

                FormUtilsClient.getRenderer(form).collectMatrices(entity, childStack, children, StringUtils.combinePaths(prefix, part.getId()), transition);

                childStack.popPose();
            }
        }

        stack.popPose();

        for (Map.Entry<String, MatrixCacheEntry> entry : children.entrySet())
        {
            MatrixCacheEntry child = entry.getValue();

            matrices.put(entry.getKey(), this.projectOrigin(parent, child.matrix(), scaleX, scaleY), this.projectOrigin(parent, child.origin(), scaleX, scaleY));
        }
    }

    private Matrix4f projectOrigin(Matrix4f parent, Matrix4f child, float scaleX, float scaleY)
    {
        if (child == null)
        {
            return null;
        }

        /* Flatten positions only: gizmo orientation and rotation sampling need a full basis. */
        Matrix4f projected = new Matrix4f(child).setTranslation(child.m30() * scaleX, child.m31() * scaleY, 0F);

        return new Matrix4f(parent).mul(projected);
    }
}
