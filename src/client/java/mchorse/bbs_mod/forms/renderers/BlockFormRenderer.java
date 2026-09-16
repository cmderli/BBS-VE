package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.render.picker.PickingReplay;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormRenderCapture;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.QueueDispatch;
import mchorse.bbs_mod.forms.forms.BlockForm;
import mchorse.bbs_mod.forms.renderers.utils.FluidVertexConsumer;
import mchorse.bbs_mod.forms.renderers.utils.FormColorBlend;
import mchorse.bbs_mod.forms.renderers.utils.FormOverlay;
import mchorse.bbs_mod.forms.renderers.utils.SingleBlockRenderView;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.colors.OverlayBlend;
import mchorse.bbs_mod.utils.joml.Vectors;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BlockFormRenderer extends FormRenderer<BlockForm>
{
    public static final Color color = new Color();

    private final SingleBlockRenderView fluidView = new SingleBlockRenderView();

    /* 26.2 keeps the fluid renderer, but no longer behind Minecraft#getBlockRenderer(): it is a
     * standalone object fed through FluidRenderer.Output. Built from the same model manager the
     * block models come from, once per renderer. */
    private FluidRenderer fluidRenderer;

    private BlockEntity blockEntity;
    private BlockState blockEntityState;

    public BlockFormRenderer(BlockForm form)
    {
        super(form);
    }

    @Override
    public void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        /* List/icon preview: submit a special GUI element so the block draws off-screen during the GUI prepare
         * phase (two-phase GUI drops a direct immediate draw here). BbsFormGuiElementRenderer calls back into
         * renderUIPreview inside the FBO render pass — same path as ModelForm. */
        this.submitUIPreview(context, x1, y1, x2, y2);
    }

    @Override
    public void renderUIPreview(PoseStack stack, float angle, float transition, int x1, int y1, int x2, int y2)
    {
        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();

        /* The base renderer pre-translated the stack to the cell (centre, 0.85*height down) + scale(f,f,-f);
         * apply the rest of the original getUIMatrix framing here, then the original block post-ops + draw
         * (renderBlockAsEntity + consumers.draw, the same path render3D uses, confirmed working in-world). */
        Matrix4f uiMatrix = getUIPreviewMatrix(angle, y1, y2);

        stack.pushPose();
        MatrixStackUtils.multiply(stack, uiMatrix);
        stack.scale(this.form.uiScale.get(), this.form.uiScale.get(), this.form.uiScale.get());
        stack.translate(-0.5F, 0F, -0.5F);

        stack.last().normal().getScale(Vectors.EMPTY_3F);
        stack.last().normal().scale(1F / Vectors.EMPTY_3F.x, -1F / Vectors.EMPTY_3F.y, 1F / Vectors.EMPTY_3F.z);

        Color set = Color.white();
        FormColorBlend.blend(set, this.form.color.get());

        Color overlay = this.form.overlayColor.get();
        boolean overlayActive = OverlayBlend.isActive(overlay);

        if (overlayActive)
        {
            FormOverlay.swatch(overlay);
        }

        consumers.setSubstitute(BBSRendering.getColorConsumer(set));
        consumers.setUI(true);
        consumers.setLayerMapper(overlayActive ? FormOverlay::withOverlay : null);
        this.renderBlock(stack, consumers, LightCoordsUtil.pack(15, 0), OverlayTexture.NO_OVERLAY, false);
        consumers.setLayerMapper(null);
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
        if (context.world != null)
        {
            context.world.pushPose();
        }
        context.stack.translate(-0.5F, 0F, -0.5F);
        if (context.world != null)
        {
            context.world.translate(-0.5F, 0F, -0.5F);
        }

        Color overlay = this.form.overlayColor.get();
        boolean overlayActive = !context.isPicking() && OverlayBlend.isActive(overlay);

        if (overlayActive)
        {
            FormOverlay.swatch(overlay);
        }

        if (context.isPicking())
        {
            /* Picking: capture the block's vanilla-layer geometry and replay it through the
             * picker_models pipeline into the stencil (see PickingReplay — the 1.21.1 global-shader
             * swap has no equivalent, the capture+replay does the same job). */
            this.setupTarget(context);

            FormRenderCapture.begin();

            Map<RenderType, List<FormRenderCapture.Captured>> captured;

            try
            {
                this.renderBlock(context.stack, consumers, 0, context.overlay, true);
                consumers.draw();
            }
            finally
            {
                captured = FormRenderCapture.end();
            }

            PickingReplay.draw(captured);

            context.stack.popPose();

            if (context.world != null)
            {
                context.world.popPose();
            }

            return;
        }

        color.set(context.color);
        FormColorBlend.blend(color, this.form.color.get());

        /* Publishing the form's camera-space origin opts its translucent layers into the
         * deferred sorted pass (see CustomVertexConsumerProvider#draw(RenderLayer)); the
         * picking branch above never publishes, so the stencil keeps every pixel. */
        if (!context.isPicking())
        {
            Vector3f origin = context.stack.last().pose().getTranslation(new Vector3f());

            FormTranslucentQueue.setSortOrigin(new Matrix4f(RenderSystem.getModelViewMatrixCopy()).transformPosition(origin));
        }

        consumers.setSubstitute(BBSRendering.getColorConsumer(color));
        consumers.setLayerMapper(overlayActive ? FormOverlay::withOverlay : null);
        this.renderBlock(context.stack, consumers, light, context.overlay, context.isPicking());
        consumers.setLayerMapper(null);
        consumers.draw();
        consumers.setSubstitute(null);
        FormTranslucentQueue.setSortOrigin(null);

        context.stack.popPose();
        if (context.world != null)
        {
            context.world.popPose();
        }

        /* TODO(1.21.11 render): RenderSystem.enableDepthTest() was removed in 1.21.5; depth testing is
         * now encoded per RenderLayer via DepthTestFunction on its pipeline. No restore needed here. */
    }

    /**
     * Draw the block state the way the world would draw it.
     *
     * <p>Vanilla's renderBlockAsEntity() draws a baked block model and nothing else, so
     * everything the world puts on top of that model, or instead of it, was silently missing
     * here: water and lava, whose geometry the fluid renderer generates per chunk section;
     * signs, banners, skulls and the end portal, which render as
     * {@link BlockRenderType#INVISIBLE} and are drawn entirely by a block entity renderer;
     * the bell's body, the campfire's food, the lectern's book, which a block entity renderer
     * adds on top of the model; and marker blocks like the barrier, which only ever exist as
     * an item icon. Each of those gets its own path below.</p>
     */
    private void renderBlock(PoseStack matrices, CustomVertexConsumerProvider consumers, int light, int overlay, boolean picking)
    {
        Minecraft mc = Minecraft.getInstance();
        BlockState state = this.form.blockState.get();
        RenderShape type = state.getRenderShape();
        FluidState fluidState = state.getFluidState();

        /* Not only water and lava: this is also where a waterlogged block gets its water,
         * on top of its own model below. */
        if (!fluidState.isEmpty())
        {
            if (this.fluidRenderer == null)
            {
                this.fluidRenderer = new FluidRenderer(mc.getModelManager().getFluidStateModelSet());
            }

            /* 26.2 removed BlockRenderDispatcher#renderLiquid: the fluid renderer is called directly
             * and asks its Output for a buffer per ChunkSectionLayer (chunk rendering has no
             * RenderType any more). Terrain geometry in the entity pass goes through the block-item
             * sheets, so those are what the layers map onto here. */
            FluidRenderer.Output output = (layer) -> new FluidVertexConsumer(consumers.getBuffer(blockItemSheet(layer)), matrices.last(), overlay);

            this.fluidRenderer.tesselate(this.fluidView.set(state, light), BlockPos.ZERO, output, state, fluidState);
        }

        if (type != RenderShape.INVISIBLE)
        {
            renderBlockState(matrices, consumers, state, light, overlay);
        }

        if (picking)
        {
            /* Picking stays out of the paths below on purpose: they draw through layers of
             * their own, and a sign's text or an end portal's sides are not even in the
             * entity vertex format the picking shader is compiled for. Such a form gets
             * selected from the outliner instead. */
            return;
        }

        /* 1.21.4+ dropped BlockRenderType.ENTITYBLOCK_ANIMATED: a chest, a bed or a shulker box
         * has a plain model like anything else, and everything animated about it comes from its
         * block entity renderer - so there is no longer a family to exclude here. */
        if (this.renderBlockEntity(mc, state, matrices, consumers, light, overlay))
        {
            return;
        }

        if (type == RenderShape.INVISIBLE && fluidState.isEmpty())
        {
            /* Barrier, light block, structure void: invisible in the world, but they do have
             * an icon, and a form of one should show something. The item model is centered on
             * the origin, while a block model spans 0..1, hence the half block nudge. */
            ItemStack stack = new ItemStack(state.getBlock());

            if (!stack.isEmpty())
            {
                matrices.pushPose();
                matrices.translate(0.5F, 0.5F, 0.5F);
                ItemFormRenderer.renderItem(stack, ItemDisplayContext.NONE, matrices, consumers, mc.level, light, overlay);
                matrices.popPose();
            }
        }
    }

    /** The fluid renderer's chunk layers as the block-item sheets the entity pass draws terrain with. */
    private static RenderType blockItemSheet(ChunkSectionLayer layer)
    {
        return layer.translucent() ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet();
    }

    /**
     * Draw one block state's baked model into the BBS consumers — what 1.21.11 got from
     * {@code BlockRenderDispatcher#renderSingleBlock}, removed in 26.2 along with
     * {@code Minecraft#getBlockRenderer()}.
     *
     * <p>This mirrors the vanilla entity-pass block renderer (BlockModelFeatureRenderer), which is
     * what draws a block model that is not part of a chunk: every part's quads are tinted with the
     * block's own tint sources and drawn through the per-quad render type the model was baked with
     * (the block-item sheets), with the caller's light and overlay. Shared with
     * StructureFormRenderer's structure-block placeholder.</p>
     */
    public static void renderBlockState(PoseStack matrices, CustomVertexConsumerProvider consumers, BlockState state, int light, int overlay)
    {
        Minecraft mc = Minecraft.getInstance();
        BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        List<BlockTintSource> tintSources = mc.getBlockColors().getTintSources(state);
        int[] tintLayers = new int[tintSources.size()];
        QuadInstance quadInstance = new QuadInstance();

        /* 1.21.11's renderSingleBlock resolved the state's render types with RandomSource.create(42L);
         * the same seed keeps a random/multipart model picking the same variant. */
        model.collectParts(RandomSource.create(42L), parts);

        for (int i = 0; i < tintLayers.length; i++)
        {
            tintLayers[i] = tintSources.get(i).color(state);
        }

        quadInstance.setLightCoords(light);
        quadInstance.setOverlayCoords(overlay);

        for (BlockStateModelPart part : parts)
        {
            for (Direction direction : Direction.values())
            {
                putQuads(matrices, consumers, part.getQuads(direction), quadInstance, tintLayers);
            }

            putQuads(matrices, consumers, part.getQuads(null), quadInstance, tintLayers);
        }
    }

    private static void putQuads(PoseStack matrices, CustomVertexConsumerProvider consumers, List<BakedQuad> quads, QuadInstance quadInstance, int[] tintLayers)
    {
        for (BakedQuad quad : quads)
        {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            int tintIndex = material.tintIndex();
            boolean tinted = tintIndex != -1 && tintIndex < tintLayers.length;

            /* -1 is the untinted colour: white, which the BBS colour consumer then multiplies. */
            quadInstance.setColor(tinted ? tintLayers[tintIndex] : -1);
            consumers.getBuffer(material.itemRenderType()).putBakedQuad(matrices.last(), quad, quadInstance);
        }
    }

    /**
     * Run the block state's block entity renderer, keeping the block entity itself around
     * between frames: it is an argument the renderer needs, not state of the form.
     *
     * @return whether there was a renderer to run
     */
    private boolean renderBlockEntity(Minecraft mc, BlockState state, PoseStack matrices, CustomVertexConsumerProvider consumers, int light, int overlay)
    {
        if (mc.level == null || !(state.getBlock() instanceof EntityBlock provider))
        {
            return false;
        }

        if (this.blockEntity == null || this.blockEntityState != state)
        {
            this.blockEntity = provider.newBlockEntity(BlockPos.ZERO, state);
            this.blockEntityState = state;
        }

        if (this.blockEntity == null)
        {
            return false;
        }

        if (this.blockEntity.getLevel() != mc.level)
        {
            /* Renderers of blocks that tick or move (the bell, the beacon) read the world off
             * the block entity, and the client's is the only one a form can offer. */
            this.blockEntity.setLevel(mc.level);
        }

        BlockEntityRenderDispatcher manager = mc.getBlockEntityRenderDispatcher();
        BlockEntityRenderer renderer = manager.getRenderer(this.blockEntity);

        if (renderer == null)
        {
            return false;
        }

        /* Not manager.getRenderState(): it refuses anything further than the renderer's render
         * distance from the camera, and a form's block entity always sits at the world origin -
         * a chest form would have stopped opening 64 blocks away from spawn. The state is built
         * by hand instead, and the form's own light replaces the light at that origin. */
        BlockEntityRenderState renderState = renderer.createRenderState();

        renderer.extractRenderState(this.blockEntity, renderState, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false), mc.gameRenderer.mainCamera().position(), null);

        renderState.lightCoords = light;

        /* Block entity renderers only submit commands since 1.21.2, so they go through the BBS
         * queue and are flushed right here - the same path the mob form's entity takes. */
        manager.submit(renderState, matrices, QueueDispatch.queue(), QueueDispatch.cameraState());
        QueueDispatch.flush();
        consumers.draw();

        return true;
    }
}
