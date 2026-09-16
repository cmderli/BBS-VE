package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.platform.CompareOp;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.fonts.FontManager;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormRenderCapture;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.LabelForm;
import mchorse.bbs_mod.forms.renderers.utils.FormColorBlend;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.colors.OverlayBlend;
import mchorse.bbs_mod.utils.joml.Vectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

public class LabelFormRenderer extends FormRenderer<LabelForm>
{
    /* ----------------------------------------------------------------------------------------
     * 1.21.11 render: the label background box used GameRenderer::getPositionColorProgram via
     * RenderSystem.setShader + BufferRenderer.drawWithGlobalProgram (both removed). It is now drawn
     * through a BBS-owned POSITION_COLOR pipeline wrapped in a RenderLayer.
     * ---------------------------------------------------------------------------------------- */
    private static final RenderPipeline SHADOW_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(BBSMod.MOD_ID, "pipeline/label_shadow"))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
            .withCull(false)
            .build()
    );

    private static RenderType shadowLayer;

    private static RenderType getShadowLayer()
    {
        if (shadowLayer == null)
        {
            shadowLayer = RenderType.create(BBSMod.MOD_ID + "_label_shadow",
                RenderSetup.builder(SHADOW_PIPELINE).sortOnUpload().createRenderSetup());
        }

        return shadowLayer;
    }

    public static void fillQuad(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4, float r, float g, float b, float a)
    {
        Matrix4f matrix4f = stack.last().pose();

        /* 1 - BR, 2 - BL, 3 - TL, 4 - TR */
        builder.addVertex(matrix4f, x1, y1, z1).setColor(r, g, b, a).setUv(0F, 0F);
        builder.addVertex(matrix4f, x2, y2, z2).setColor(r, g, b, a).setUv(0F, 0F);
        builder.addVertex(matrix4f, x3, y3, z3).setColor(r, g, b, a).setUv(0F, 0F);
        builder.addVertex(matrix4f, x1, y1, z1).setColor(r, g, b, a).setUv(0F, 0F);
        builder.addVertex(matrix4f, x3, y3, z3).setColor(r, g, b, a).setUv(0F, 0F);
        builder.addVertex(matrix4f, x4, y4, z4).setColor(r, g, b, a).setUv(0F, 0F);
    }

    public LabelFormRenderer(LabelForm form)
    {
        super(form);
    }

    /**
     * The label's own font, or the default one when it doesn't have (or can't load) one.
     * The scale says how many screen pixels a unit of the layout covers where it's about
     * to be drawn - see {@link FontManager#get(Link, int, float)}.
     */
    private FontRenderer getFont(float scale)
    {
        FontRenderer font = BBSModClient.getFonts().get(this.form.font.get(), this.form.fontSize.get(), scale);

        return font == null ? Batcher2D.getDefaultTextRenderer() : font;
    }

    private int getLineHeight(FontRenderer font)
    {
        int lineHeight = this.form.lineHeight.get();

        return lineHeight > 0 ? lineHeight : font.getLineHeight();
    }

    @Override
    public void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        int color = this.form.color.get().getARGBColor();
        String text = StringUtils.processColoredText(this.form.text.get());
        /* The interface draws a unit of the layout over as many pixels as it is scaled by. */
        FontRenderer font = this.getFont((float) Minecraft.getInstance().getWindow().getGuiScale());
        FontRenderer previous = context.batcher.setFont(font);

        try
        {
            List<String> wrap = font.wrap(text, x2 - x1 - 4);

            int th = font.getHeight();
            int lineHeight = th + 4;
            int h = th + (wrap.size() - 1) * lineHeight;
            int y = (y2 + y1) / 2 - h / 2;

            for (String s : wrap)
            {
                context.batcher.textShadow(s, x1 + 2, y, color);

                y += lineHeight;
            }
        }
        finally
        {
            context.batcher.setFont(previous);
        }
    }

    @Override
    public void render3D(FormRenderingContext context)
    {
        /* The film editor's stencil pass re-renders the film with a stencilMap on every frame the
         * mouse spends over the viewport. On 1.21.1 the picking branch here hijacked these draws
         * onto the picker program aimed at the raw-bound stencil FBO; that hijack has no 1.21.5+
         * equivalent yet, so a picking "draw" went through the ordinary vanilla text layer — whose
         * RenderLayer.draw targets MAIN_TARGET, the visible frame (StencilFormFramebuffer redirects
         * only BBSPickerRenderer draws). Combined with the old light = 0 it painted a lightmap-dark
         * copy of the text OVER the real one whenever the viewport was hovered: "the label is dark,
         * the colour applies badly". Until label picking moves onto BBSPickerRenderer (the same debt
         * as block/item/mob forms), the picking pass must draw nothing at all: the stencil keeps its
         * indices (updateStencilMap does not depend on what was drawn), and the label simply is not
         * pixel-pickable — which it already was not. */
        if (context.isPicking())
        {
            return;
        }

        context.stack.pushPose();

        if (this.form.billboard.get())
        {
            MatrixStackUtils.billboard(context.stack);
        }

        FontRenderer font = this.getFont(FontManager.MAX_DETAIL);
        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();
        float scale = 1F / 16F;
        int light = context.light;

        MatrixStackUtils.scaleStack(context.stack, scale, -scale, scale);

        /* TODO(1.21.11 render): RenderSystem.disableCull/enableCull removed; cull is now per-pipeline
         * state. Text renders through the vanilla text RenderLayer which sets its own cull. */

        /* The whole label (text + background) records as ONE deferred group: its parts rely on
         * each other's depth (glyphs over the background quad), so they replay together in
         * original order, while the group as a whole sorts against other translucent forms. */
        boolean grouped = FormTranslucentQueue.isActive();

        if (grouped)
        {
            Vector3f origin = context.stack.last().pose().getTranslation(new Vector3f());

            FormTranslucentQueue.beginGroup(new Matrix4f(RenderSystem.getModelViewMatrixCopy()).transformPosition(origin), false);
        }

        if (this.form.max.get() <= 10)
        {
            this.renderString(context, consumers, font, light);
        }
        else
        {
            this.renderLimitedString(context, consumers, font, light);
        }

        if (grouped)
        {
            FormTranslucentQueue.endGroup();
        }

        CustomVertexConsumerProvider.clearRunnables();

        /* TODO(1.21.11 render): RenderSystem.enableDepthTest/enableCull removed (per-pipeline now). */

        context.stack.popPose();
    }

    private void renderString(FormRenderingContext context, CustomVertexConsumerProvider consumers, FontRenderer font, int light)
    {
        Font renderer = font.getRenderer();
        String content = StringUtils.processColoredText(this.form.text.get());
        float transition = context.getTransition();
        int w = renderer.width(content) - 1;
        int h = font.getHeight();
        int x = (int) (-w * this.form.anchorX.get());
        int y = (int) (-h * this.form.anchorY.get());

        Color shadowColor = this.form.shadowColor.get().copy();
        Color color = new Color().set(context.color, true);

        FormColorBlend.blend(color, this.form.color.get());
        /* Text is a flat fill, so the CPU mix is exactly what the overlay texture would do. */
        OverlayBlend.apply(color, this.form.overlayColor.get());
        shadowColor.mul(context.color);

        if (shadowColor.a > 0)
        {
            context.stack.pushPose();
            context.stack.translate(0F, 0F, -0.1F);
            renderer.prepareText(content, x + this.form.shadowX.get(), y + this.form.shadowY.get(), shadowColor.getARGBColor(), false, 0).visit(
                glyphs(context.stack.last().pose(), consumers, light));
            context.stack.popPose();
        }

        renderer.prepareText(content, x, y, color.getARGBColor(), false, 0).visit(
            glyphs(context.stack.last().pose(), consumers, light));

        /* TODO(1.21.11 render): RenderSystem.enableDepthTest removed (per-pipeline now). */

        consumers.draw();

        this.renderShadow(context, x, y, w, h);
    }

    private void renderLimitedString(FormRenderingContext context, CustomVertexConsumerProvider consumers, FontRenderer font, int light)
    {
        Font renderer = font.getRenderer();
        int lineHeight = this.getLineHeight(font);
        float transition = context.getTransition();
        int w = 0;
        int h = font.getHeight();
        String content = StringUtils.processColoredText(this.form.text.get());
        List<String> lines = FontRenderer.wrap(renderer, content, this.form.max.get());

        if (lines.size() <= 1)
        {
            this.renderString(context, consumers, font, light);

            return;
        }

        for (int i = 0; i < lines.size(); i++)
        {
            lines.set(i, lines.get(i).trim());
        }

        for (String line : lines)
        {
            w = Math.max(renderer.width(line) - 1, w);
            h += lineHeight;
        }

        h -= lineHeight;

        int x = (int) (-w * this.form.anchorX.get());
        int y = (int) (-h * this.form.anchorY.get());
        int y2 = y;

        Color shadowColor = this.form.shadowColor.get().copy();

        shadowColor.mul(context.color);

        if (shadowColor.a > 0)
        {
            context.stack.pushPose();
            context.stack.translate(0F, 0F, -0.1F);

            for (String line : lines)
            {
                int x2 = x + (this.form.anchorLines.get() ? (int) ((w - renderer.width(line)) * this.form.anchorX.get()) : 0);

                renderer.prepareText(line, x2 + this.form.shadowX.get(), y2 + this.form.shadowY.get(), shadowColor.getARGBColor(), false, 0).visit(
                    glyphs(context.stack.last().pose(), consumers, light));

                y2 += lineHeight;
            }

            context.stack.popPose();

            y2 = y;
        }

        Color cColor = new Color().set(context.color, true);

        FormColorBlend.blend(cColor, this.form.color.get());
        OverlayBlend.apply(cColor, this.form.overlayColor.get());

        int color = cColor.getARGBColor();

        for (String line : lines)
        {
            int x2 = x + (this.form.anchorLines.get() ? (int) ((w - renderer.width(line)) * this.form.anchorX.get()) : 0);

            renderer.prepareText(line, x2, y2, color, false, 0).visit(
                glyphs(context.stack.last().pose(), consumers, light));

            y2 += lineHeight;
        }

        consumers.draw();

        /* TODO(1.21.11 render): RenderSystem.enableDepthTest removed (per-pipeline now). */

        this.renderShadow(context, x, y, w, h);
    }

    private void renderShadow(FormRenderingContext context, int x, int y, int w, int h)
    {
        float offset = this.form.offset.get();
        Color color = this.form.background.get().copy();

        color.mul(context.color);

        if (color.a <= 0)
        {
            return;
        }

        context.stack.pushPose();
        context.stack.translate(0, 0, -0.2F);


        /* 26.2 has no Tesselator: the growable staging buffer it owned is created here (the size
         * Draw/Gizmo use for their immediate geometry) and released after the draw below. */
        ByteBufferBuilder allocator = new ByteBufferBuilder(1536);
        BufferBuilder builder = new BufferBuilder(allocator, PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        fillQuad(
            builder, context.stack,
            x + w + offset, y - offset, 0,
            x - offset, y - offset, 0,
            x - offset, y + h + offset, 0,
            x + w + offset, y + h + offset, 0,
            color.r, color.g, color.b, color.a
        );

        /* Was: enableBlend + enableDepthTest + setShader(getPositionColorProgram) +
         * drawWithGlobalProgram. The POSITION_COLOR pipeline now encodes blend + depth test. */
        MeshData built = builder.build();

        if (built != null)
        {
            drawLayer(getShadowLayer(), built);
        }

        allocator.close();

        context.stack.popPose();
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
        GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "bbs label geometry", GpuBuffer.USAGE_VERTEX, built.vertexBuffer());
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(state.primitiveTopology());

        layer.prepare().drawFromBuffer(vertices, indices.getBuffer(state.indexCount()), indices.type(), 0, 0, state.indexCount());

        vertices.close();
        built.close();
    }

    /**
     * 26.2 has no {@code Font#drawInBatch}: the text is prepared into a
     * {@link Font.PreparedText} and every renderable it yields is written into the layer it names
     * through the same vertex consumer the old batch call used — so the label still records as one
     * deferred group for {@link FormTranslucentQueue}. {@code Font.TextLayerType.NORMAL} is
     * {@code Font.DisplayMode.NORMAL}, and {@code false} is the world (non-flat) glyph depth the
     * old call's Matrix4f path got.
     */
    private static Font.GlyphVisitor glyphs(Matrix4f matrix, CustomVertexConsumerProvider consumers, int light)
    {
        return new Font.GlyphVisitor()
        {
            @Override
            public void acceptRenderable(TextRenderable renderable)
            {
                renderable.render(matrix, consumers.getBuffer(renderable.renderType(Font.DisplayMode.NORMAL)), light, false);
            }
        };
    }
}