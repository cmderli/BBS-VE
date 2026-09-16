package mchorse.bbs_mod.forms;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.client.BBSRendering;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Deferred rendering bridge for BBS forms inside the 1.21.5+ item-model system.
 *
 * <p>BBS's whole form pipeline is immediate: every emitter (cubic models, BOBJ VAOs, the
 * buffered {@link CustomVertexConsumerProvider}, labels, billboards) ends at
 * {@code RenderLayer#draw(BuiltBuffer)}. A {@code SpecialModelRenderer}, on the other hand,
 * is called while the item render state is <em>recorded</em> — no GL pass is open, and drawing
 * immediately lands in the wrong phase (or crashes on a nested pass). The only legal way to
 * draw is to submit commands to the given {@link OrderedRenderCommandQueue}.
 *
 * <p>This class bridges the two: while a capture session is open, the mixin hook in
 * {@code RenderLayerMixin#onDraw} stores the layer and a copy of the bytes instead of drawing;
 * afterwards each captured buffer is submitted as a {@code submitCustom} command and re-emitted
 * vertex by vertex into the queue's consumer at execution time. Vertices were already fully
 * transformed (item display transform + the form's own bone transforms) at capture time, so the
 * raw re-emit needs no matrix of its own.
 *
 * <p>Render thread only. Sessions nest by merging: a form that itself contains a special-model
 * item (gun in a form's hand) re-enters {@code submitForm}, and the inner session folds into the
 * outer one — vertices are fully transformed at capture time, so the outer submit carries them
 * correctly. The depth counter also keeps an unbalanced {@code end()} from leaving the hook armed
 * process-wide (an armed leak silently swallows EVERY RenderLayer draw).
 */
public class FormRenderCapture
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static Map<RenderType, List<Captured>> active;
    private static int depth;

    /** Draw-mode pairs already complained about, so the per-frame path logs each one once. */
    private static final Set<String> reported = new HashSet<>();

    public record Captured(MeshData.DrawState params, ByteBuffer data)
    {}

    public static boolean isActive()
    {
        return active != null;
    }

    public static void begin()
    {
        if (depth++ == 0)
        {
            active = new LinkedHashMap<>();
        }
    }

    /** Returns the captured layers when this call closes the outermost session, null otherwise. */
    public static Map<RenderType, List<Captured>> end()
    {
        if (depth == 0)
        {
            return null;
        }

        if (--depth == 0)
        {
            Map<RenderType, List<Captured>> result = active;

            active = null;

            return result;
        }

        return null;
    }

    /**
     * Temporarily close the session for a nested offscreen render, the way
     * {@link FormTranslucentQueue#suspend()} does for the queue. A framebuffer form draws its parts
     * into a buffer of its own, under its own ortho projection — while a capture session is armed
     * those draws were swallowed by {@code RenderLayerMixin} instead of executed, so the buffer came
     * out empty and the parts were replayed at the item, where ortho-sized geometry filled the
     * screen. Only the finished quad belongs to the item, and it draws after this is restored.
     *
     * <p>The depth goes with it: a form nested inside the buffer may open a session of its own
     * (a model-block item form inside a framebuffer form), and it has to be a whole one, not an
     * inner level of the suspended session that would end without ever returning its capture.</p>
     */
    public static Suspended suspend()
    {
        Suspended was = new Suspended(active, depth);

        active = null;
        depth = 0;

        return was;
    }

    public static void restore(Suspended was)
    {
        active = was.active();
        depth = was.depth();
    }

    /** A capture session set aside by {@link #suspend()}. */
    public record Suspended(Map<RenderType, List<Captured>> active, int depth)
    {}

    /**
     * Called from {@code RenderLayerMixin#onDraw} while a session is open. Consumes the buffer:
     * vanilla's {@code RenderLayer#draw} closes the {@link BuiltBuffer} it is given, so when the
     * draw is cancelled the capture must close it instead — otherwise the allocator slice leaks
     * ("Clearing BufferBuilder with unused batches").
     */
    public static void capture(RenderType layer, MeshData buffer)
    {
        if (active == null)
        {
            return;
        }

        active.computeIfAbsent(layer, (key) -> new ArrayList<>()).add(copy(buffer));

        buffer.close();
    }

    /**
     * Take an independent CPU copy of a built buffer's vertices, so the geometry outlives the
     * {@link BuiltBuffer} (whose allocator slice is recycled the moment it is drawn or closed).
     * Shared by the item capture above and {@link FormTranslucentQueue}'s deferred replay.
     *
     * <p>Does not close the buffer: the item path cancels its draw and closes it, the deferred path
     * draws it immediately and lets the draw close it.
     */
    public static Captured copy(MeshData buffer)
    {
        MeshData.DrawState params = buffer.drawState();
        /* ByteBuffer.duplicate() does NOT inherit byte order — the duplicate is always BIG_ENDIAN,
         * while BufferBuilder wrote the vertex data in native (little-endian) order. Reading floats
         * through a big-endian view turns 1.0f into 4.6e-41: every position collapses to ~0 and the
         * whole capture rasterises to nothing. Restore native order explicitly on every view. */
        ByteBuffer source = buffer.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());

        source.limit(Math.min(source.limit(), params.vertexCount() * params.format().getVertexSize()));

        ByteBuffer copy = ByteBuffer.allocate(source.remaining()).order(ByteOrder.nativeOrder());

        copy.put(source);
        copy.flip();

        return new Captured(params, copy);
    }

    /**
     * Render the form through the immediate pipeline under a capture session and submit every
     * captured layer to the queue. Applies the same transform the 1.21.1 dynamic item renderer
     * used (item origin at the block corner + the BBS transform of the display context).
     */
    public static void submitForm(Form form, Transform transform, IEntity formEntity, ItemDisplayContext displayContext, PoseStack matrices, SubmitNodeCollector queue, int light, int overlay)
    {
        if (form == null)
        {
            return;
        }

        matrices.pushPose();
        matrices.translate(0.5F, 0F, 0.5F);
        MatrixStackUtils.applyTransform(matrices, transform);

        begin();

        Map<RenderType, List<Captured>> captured;

        /* An item form held in the world is a world draw, so it opens the span too — otherwise it takes
         * the shared pipeline, the pack has no program assigned to that, and the item renders ghosted
         * the way the morph's arm did. The span has to be open HERE and not around the submit below,
         * because the capture resolves its RenderLayer while the form renders; by submit time the layer
         * is already chosen.
         *
         * Not for the GUI context, though: an inventory icon is drawn outside the world frame and into
         * a target of its own. Handing it a world pipeline would give it a pack program whose setup
         * binds the pack's G-buffer over that target — the same way the editor viewport's preview used
         * to smear itself across the world. */
        boolean worldDraw = displayContext != ItemDisplayContext.GUI;
        boolean prevWorldForms = worldDraw && BBSRendering.beginWorldForms();

        try
        {
            FormUtilsClient.render(form, new FormRenderingContext()
                .set(FormRenderType.fromModelMode(displayContext), formEntity, matrices, light, overlay, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false))
                .camera(Minecraft.getInstance().gameRenderer.mainCamera()));
        }
        finally
        {
            if (worldDraw)
            {
                BBSRendering.endWorldForms(prevWorldForms);
            }

            matrices.popPose();

            /* end() must run even when the form render throws an Error past FormUtilsClient's
             * catch — a session left armed swallows every RenderLayer draw in the process. */
            captured = end();
        }

        if (captured == null)
        {
            /* Nested session: the enclosing submitForm owns the capture and will submit it. */
            return;
        }

        for (Map.Entry<RenderType, List<Captured>> entry : captured.entrySet())
        {
            RenderType layer = entry.getKey();

            for (Captured single : entry.getValue())
            {
                queue.submitCustomGeometry(matrices, layer, (matricesEntry, consumer) -> emit(single, layer.primitiveTopology(), consumer));
            }
        }
    }

    /**
     * Re-emit a captured buffer into the queue's consumer, rewritten from the buffer's own draw
     * mode into the target layer's.
     *
     * <p>The immediate path takes the primitive mode from the {@link BuiltBuffer} it is handed, so
     * one layer happily served buffers of different modes: cubic models build QUADS, billboards
     * (and labels, trails) build TRIANGLES, and both draw through the BBS model layer. Re-emitting
     * hands vertices to the layer's shared buffer instead, and the indices come from
     * {@link RenderLayer#getDrawMode()} — so a TRIANGLES capture fed to a QUADS layer gets its
     * triangles re-cut every 4 vertices and shreds into diagonal ribbons. Rewrite instead of
     * assuming: a triangle becomes a quad with a doubled last vertex (the second, degenerate
     * triangle rasterises to nothing), a quad becomes its two triangles.
     */
    public static void emit(Captured captured, PrimitiveTopology target, VertexConsumer consumer)
    {
        MeshData.DrawState params = captured.params();
        PrimitiveTopology source = params.primitiveTopology();
        int count = params.vertexCount();

        if (source == target)
        {
            for (int v = 0; v < count; v++)
            {
                emitVertex(captured, v, consumer);
            }
        }
        else if (source == PrimitiveTopology.TRIANGLES && target == PrimitiveTopology.QUADS)
        {
            for (int v = 0; v + 2 < count; v += 3)
            {
                emitVertex(captured, v, consumer);
                emitVertex(captured, v + 1, consumer);
                emitVertex(captured, v + 2, consumer);
                emitVertex(captured, v + 2, consumer);
            }
        }
        else if (source == PrimitiveTopology.QUADS && target == PrimitiveTopology.TRIANGLES)
        {
            for (int v = 0; v + 3 < count; v += 4)
            {
                emitVertex(captured, v, consumer);
                emitVertex(captured, v + 1, consumer);
                emitVertex(captured, v + 2, consumer);
                emitVertex(captured, v, consumer);
                emitVertex(captured, v + 2, consumer);
                emitVertex(captured, v + 3, consumer);
            }
        }
        else if (reported.add(source + " -> " + target))
        {
            /* No rewrite known — emitting raw would draw garbage, so drop the buffer loudly
             * rather than let a mangled mesh pass for a rendering bug elsewhere. Once per
             * combination: this runs per frame, per item. */
            LOGGER.error("Can't re-emit a captured {} buffer into a {} layer", source, target);
        }
    }

    /**
     * Write a single captured vertex into the consumer. Decodes the vertex format by element
     * usage (POSITION / COLOR / UV0-2 / NORMAL), so any layout the form pipeline produces rides
     * through; unknown elements are skipped.
     */
    private static void emitVertex(Captured captured, int index, VertexConsumer consumer)
    {
        VertexFormat format = captured.params().format();
        int base = index * format.getVertexSize();
        /* duplicate() resets byte order to BIG_ENDIAN — see the matching note in capture(). */
        ByteBuffer data = captured.data().duplicate().order(ByteOrder.nativeOrder());

        for (VertexFormatElement element : format.getElements())
        {
            /* 26.2 identifies an element by its semantic NAME (DefaultVertexFormat.*_SEMANTIC_NAME)
             * and carries its own byte offset; the 1.21.11 usage()/index() pair behind them is gone. */
            int offset = base + element.offset();
            String semantic = element.name();

            if (DefaultVertexFormat.POSITION_SEMANTIC_NAME.equals(semantic))
            {
                consumer.addVertex(data.getFloat(offset), data.getFloat(offset + 4), data.getFloat(offset + 8));
            }
            else if (DefaultVertexFormat.COLOR_SEMANTIC_NAME.equals(semantic))
            {
                consumer.setColor(data.get(offset) & 0xFF, data.get(offset + 1) & 0xFF, data.get(offset + 2) & 0xFF, data.get(offset + 3) & 0xFF);
            }
            else if (DefaultVertexFormat.UV0_SEMANTIC_NAME.equals(semantic))
            {
                consumer.setUv(data.getFloat(offset), data.getFloat(offset + 4));
            }
            else if (DefaultVertexFormat.UV1_SEMANTIC_NAME.equals(semantic))
            {
                consumer.setUv1(Short.toUnsignedInt(data.getShort(offset)), Short.toUnsignedInt(data.getShort(offset + 2)));
            }
            else if (DefaultVertexFormat.UV2_SEMANTIC_NAME.equals(semantic))
            {
                consumer.setUv2(Short.toUnsignedInt(data.getShort(offset)), Short.toUnsignedInt(data.getShort(offset + 2)));
            }
            else if (DefaultVertexFormat.NORMAL_SEMANTIC_NAME.equals(semantic))
            {
                consumer.setNormal(data.get(offset) / 127F, data.get(offset + 1) / 127F, data.get(offset + 2) / 127F);
            }
        }
    }

    /** Rough item-space bounds for item-frame culling / oversized-GUI detection. */
    public static void collectItemBounds(Consumer<Vector3fc> consumer)
    {
        for (int x = 0; x <= 1; x++)
        {
            for (int y = 0; y <= 1; y++)
            {
                for (int z = 0; z <= 1; z++)
                {
                    consumer.accept(new Vector3f(x, y, z));
                }
            }
        }
    }
}
