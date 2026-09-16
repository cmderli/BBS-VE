package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.render.picker.PickingReplay;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormRenderCapture;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.ItemForm;
import mchorse.bbs_mod.forms.renderers.utils.FormColorBlend;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.joml.Vectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.entity.ItemRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

public class ItemFormRenderer extends FormRenderer<ItemForm>
{
    /* Reused per render to avoid per-frame allocation; the form renderers run single-threaded on the
     * client render thread (same assumption as BlockFormRenderer.color). clearAndUpdate() wipes it first. */
    private static final ItemStackRenderState renderState = new ItemStackRenderState();

    public ItemFormRenderer(ItemForm form)
    {
        super(form);
    }

    @Override
    public void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        /* List/icon preview: submit a special GUI element so the item draws off-screen during the GUI prepare
         * phase. A direct immediate item draw is dropped by the two-phase GUI here (so the item was invisible in
         * form/morph/replay lists), and it also inherited whatever DiffuseLighting state happened to be set (dark
         * in the form-properties editor until the window lost focus). The FBO render pass composites correctly AND
         * binds the form-preview diffuse lighting in BbsFormGuiElementRenderer.lights() — fixing both symptoms.
         * Same path as BlockForm / ModelForm. */
        this.submitUIPreview(context, x1, y1, x2, y2);
    }

    @Override
    public void renderUIPreview(PoseStack stack, float angle, float transition, int x1, int y1, int x2, int y2)
    {
        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();

        /* The base renderer pre-translated the stack to the cell (centre, 0.85*height down) + scale(f,f,-f); apply
         * the rest of the original getUIMatrix framing here (cell scale + 22.5 tilt + cursor yaw), then the same
         * recolor + command-queue item draw renderInUI/render3D use. No -0.5 block centering — items render
         * centred at the origin. */
        Matrix4f uiMatrix = getUIPreviewMatrix(angle, y1, y2);

        stack.pushPose();
        MatrixStackUtils.multiply(stack, uiMatrix);
        stack.scale(this.form.uiScale.get(), this.form.uiScale.get(), this.form.uiScale.get());

        stack.last().normal().getScale(Vectors.EMPTY_3F);
        stack.last().normal().scale(1F / Vectors.EMPTY_3F.x, -1F / Vectors.EMPTY_3F.y, 1F / Vectors.EMPTY_3F.z);

        Color set = Color.white();
        FormColorBlend.blend(set, this.form.color.get());

        consumers.setSubstitute(BBSRendering.getColorConsumer(set));
        consumers.setUI(true);
        renderItem(this.form.stack.get(), this.form.modelTransform.get(), stack, consumers, Minecraft.getInstance().level, LightTexture.FULL_BLOCK, OverlayTexture.NO_OVERLAY);
        consumers.draw();
        consumers.setUI(false);
        consumers.setSubstitute(null);

        stack.popPose();
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();
        int light = context.light;

        context.stack.pushPose();

        if (context.isPicking())
        {
            /* Picking: capture the item's vanilla-layer geometry and replay it through the
             * picker_models pipeline into the stencil — the global-shader swap 1.21.1 used has no
             * 1.21.5+ equivalent, the capture+replay does the same job. */
            this.setupTarget(context);

            FormRenderCapture.begin();

            Map<RenderType, List<FormRenderCapture.Captured>> captured;

            try
            {
                Level pickWorld = context.entity == null ? null : context.entity.getWorld();

                renderItem(this.form.stack.get(), this.form.modelTransform.get(), context.stack, consumers, pickWorld, 0, context.overlay);
                consumers.draw();
            }
            finally
            {
                captured = FormRenderCapture.end();
            }

            PickingReplay.draw(captured);

            context.stack.popPose();

            return;
        }

        /* TODO(1.21.11 render): RenderSystem.enableBlend() is gone; blend state now lives in each
         * RenderLayer's RenderPipeline. */
        CustomVertexConsumerProvider.hijackVertexFormat((l) -> {});

        BlockFormRenderer.color.set(context.color);
        FormColorBlend.blend(BlockFormRenderer.color, this.form.color.get());

        /* Publishing the form's camera-space origin opts its translucent layers into the
         * deferred sorted pass (see CustomVertexConsumerProvider#draw(RenderLayer)). */
        if (!context.isPicking())
        {
            Vector3f origin = context.stack.last().pose().getTranslation(new Vector3f());

            FormTranslucentQueue.setSortOrigin(new Matrix4f(RenderSystem.getModelViewMatrixCopy()).transformPosition(origin));
        }

        consumers.setSubstitute(BBSRendering.getColorConsumer(BlockFormRenderer.color));

        /* 1.21.1 called renderItem(stack, modelTransform, light, overlay, context.stack, consumers,
         * entity world, 0). Same faithful command-queue replacement as renderInUI. */
        Level world = context.entity == null ? null : context.entity.getWorld();

        renderItem(this.form.stack.get(), this.form.modelTransform.get(), context.stack, consumers, world, light, context.overlay);
        consumers.draw();
        consumers.setSubstitute(null);
        FormTranslucentQueue.setSortOrigin(null);

        CustomVertexConsumerProvider.clearRunnables();

        context.stack.popPose();

        /* TODO(1.21.11 render): RenderSystem.enableDepthTest() was removed in 1.21.5; depth testing is
         * now encoded per RenderLayer via DepthTestFunction on its pipeline. */
    }

    /**
     * Faithful 1.21.11 replacement for the removed high-level {@code ItemRenderer.renderItem(ItemStack, ...,
     * VertexConsumerProvider, ...)} overload used by the 1.21.1 renderer.
     *
     * <p>The 1.21.4 item-model rewrite resolves an {@link ItemStack} into an {@link ItemRenderState} of baked
     * draw layers, then renders it into an {@code OrderedRenderCommandQueue} (bypassing
     * {@link net.minecraft.client.render.VertexConsumerProvider}). To preserve the BBS recolor/picking hook
     * (which works by substituting the {@code VertexConsumerProvider}), we:</p>
     * <ol>
     *     <li>resolve the stack into per-layer item commands via {@link ItemModelManager#clearAndUpdate} +
     *     {@link ItemRenderState#render} into a private {@link OrderedRenderCommandQueueImpl};</li>
     *     <li>replay each queued {@code ItemCommand} through the surviving low-level static
     *     {@link ItemRenderer#renderItem(ItemDisplayContext, MatrixStack, net.minecraft.client.render.VertexConsumerProvider, int, int, int[], java.util.List, net.minecraft.client.render.RenderLayer, ItemRenderState.Glint)}
     *     (which still accepts a {@code VertexConsumerProvider}) feeding the BBS {@code consumers}.</li>
     * </ol>
     * This mirrors what {@link net.minecraft.client.render.command.ItemCommandRenderer} does internally, but
     * routes geometry through the recolor provider instead of the engine's {@code Immediate}.
     *
     * <p>Also the shared held-item renderer: {@code ModelFormRenderer.renderItems} draws the items a form
     * holds on its bones through here (the 1.21.1 high-level renderItem it used is gone the same way).
     */
    public static void renderItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack matrices, CustomVertexConsumerProvider consumers, Level world, int light, int overlay)
    {
        renderItem(stack, displayContext, matrices, consumers, world, light, overlay, null);
    }

    /**
     * The same, for an item held by a body: the holder answers vanilla's model predicates (a bow
     * bends and shows its arrow, a shield blocks, a trident lifts). 1.21.4+ takes it as the
     * {@link net.minecraft.util.HeldItemContext} of the resolve, which {@link LivingEntity}
     * implements - the seed is vanilla's own from {@code ItemModelManager.updateForLivingEntity}.
     */
    public static void renderItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack matrices, CustomVertexConsumerProvider consumers, Level world, int light, int overlay, LivingEntity holder)
    {
        if (stack == null || stack.isEmpty())
        {
            return;
        }

        ItemModelResolver modelManager = Minecraft.getInstance().getItemModelResolver();

        /* Resolve the item into baked per-layer geometry (replaces 1.21.1's implicit model resolution inside
         * the old renderItem). Without a holder this is what updateForNonLivingEntity passes for a
         * free-standing item: no HeldItemContext and seed 0. */
        modelManager.updateForTopItem(renderState, stack, displayContext, world, holder, holder == null ? 0 : holder.getId() + displayContext.ordinal());

        SubmitNodeStorage queue = new SubmitNodeStorage();

        /* light/overlay/outlineColor: outlineColor=0 (no glow outline). render() enqueues one ItemCommand per
         * layer into the queue's batching queues. */
        renderState.submit(matrices, queue, light, overlay, 0);

        for (SubmitNodeCollection batch : queue.getSubmitsPerOrder().values())
        {
            for (SubmitNodeStorage.ItemSubmit command : batch.getItemSubmits())
            {
                /* Replay faithfully (see ItemCommandRenderer#render): push a copy of the captured entry, draw,
                 * pop. The VertexConsumerProvider is the BBS recolor/picking-substituting `consumers`. */
                matrices.pushPose();
                matrices.last().set(command.positionMatrix());
                ItemRenderer.renderItem(
                    command.displayContext(),
                    matrices,
                    consumers,
                    command.lightCoords(),
                    command.overlayCoords(),
                    command.tintLayers(),
                    command.quads(),
                    command.renderLayer(),
                    command.glintType()
                );
                matrices.popPose();
            }

            /* BBS special-model items (model block, gun) land in the queue as CUSTOM commands —
             * FormRenderCapture.submitForm captures the form's immediate draws and re-emits them via
             * submitCustom. ItemCommand replay alone would silently drop exactly the mod's own items
             * (field opened by bbs.accesswidener; vanilla's CustomCommandRenderer reads it the same way). */
            for (Map.Entry<RenderType, List<SubmitNodeStorage.CustomGeometrySubmit>> entry : batch.getCustomGeometrySubmits().customGeometrySubmits.entrySet())
            {
                VertexConsumer buffer = consumers.getBuffer(entry.getKey());

                for (SubmitNodeStorage.CustomGeometrySubmit command : entry.getValue())
                {
                    command.customRenderer().render(command.matricesEntry(), buffer);
                }
            }
        }
    }
}
