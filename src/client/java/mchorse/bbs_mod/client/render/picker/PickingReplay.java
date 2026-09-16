package mchorse.bbs_mod.client.render.picker;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.forms.FormRenderCapture;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

/**
 * Picking for the vanilla-rendered forms (item, block, mob).
 *
 * <p>1.21.1 picked them by swapping the GLOBAL shader for the picker program while the vanilla
 * renderer drew — the 1.21.5 pipeline system has no global program to swap, which is why these
 * forms went unpickable in the port. What it does have is {@link FormRenderCapture}: the form's
 * vanilla-layer geometry is captured (fully transformed CPU copies), and this class replays every
 * captured buffer through the {@code picker_models} pipeline into the picking stencil via
 * {@link BBSPickerRenderer} — the same manual pass the cubic/BOBJ models already pick with.
 *
 * <p>The captured layers come in whatever vertex formats the vanilla renderers used (full entity
 * format for item quads, POSITION_TEXTURE for glint, ...), while the picker pipeline wants the
 * entity format. Each vertex is decoded by element usage with defaults for anything the source
 * lacks, so every layer replays: position is what matters, colour is overwritten by the picker
 * anyway, UV0 feeds the alpha cutout and UV2 carries the bone id a mob form wrote there.
 *
 * <p>Replayed one layer at a time, each with the texture that layer draws with: the cutout has to
 * be judged against the same picture the visible pass used, or a mob's UVs read against the block
 * atlas punch holes through its silhouette. A layer without a Sampler0 of its own falls back to
 * the block atlas, where item and block geometry points anyway.
 */
public class PickingReplay
{
    /**
     * Replay captured geometry into the picking target. The picking index must already be
     * recorded (the renderer's {@code setupTarget}); the model-view is the global one — the
     * capture bakes the form's stack into the vertices, same as the cubic picking draw.
     */
    public static void draw(Map<RenderType, List<FormRenderCapture.Captured>> captured)
    {
        if (captured == null || captured.isEmpty())
        {
            return;
        }

        for (Map.Entry<RenderType, List<FormRenderCapture.Captured>> entry : captured.entrySet())
        {
            PreparedRenderType.Texture texture = sampler0(entry.getKey());

            BBSPickerRenderer.setSampler0(texture.textureView(), texture.sampler());

            /* The source buffers come in whatever formats the vanilla renderers used, so the QUADS the
             * replay emits have no fixed size up front; ByteBufferBuilder grows on demand, exactly like
             * the Tesselator's buffer did. */
            ByteBufferBuilder bytes = new ByteBufferBuilder(DefaultVertexFormat.ENTITY.getVertexSize() * 32);
            BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);

            for (FormRenderCapture.Captured single : entry.getValue())
            {
                emit(single, builder);
            }

            MeshData built = builder.build();

            if (built != null)
            {
                BBSPickerRenderer.draw(BBSShaders.getPickerModelsProgram(), built, RenderSystem.getModelViewMatrixCopy());
            }

            bytes.close();
        }
    }

    /**
     * What the layer's own Sampler0 is, or the block atlas when it has none — item and block
     * geometry points there, and a layer that samples nothing of its own (glint) only duplicates
     * geometry the base layer has already written.
     */
    private static PreparedRenderType.Texture sampler0(RenderType layer)
    {
        /* 1.21.11 read the layer's TextureSetup straight out of its RenderSetup; 26.2 keeps the setup
         * private and resolves it through prepare(), which is also what turns the layer's texture id
         * into the view + sampler pair the picker pass binds. */
        for (PreparedRenderType.Texture texture : layer.prepare().textures())
        {
            if (texture.name().equals("Sampler0"))
            {
                return texture;
            }
        }

        AbstractTexture atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);

        return new PreparedRenderType.Texture("Sampler0", atlas.getTextureView(), atlas.getSampler());
    }

    /** Re-emit one captured buffer as QUADS, doubling the last vertex of each triangle. */
    private static void emit(FormRenderCapture.Captured captured, VertexConsumer consumer)
    {
        PrimitiveTopology mode = captured.params().primitiveTopology();
        int count = captured.params().vertexCount();

        if (mode == PrimitiveTopology.QUADS)
        {
            for (int v = 0; v < count; v++)
            {
                emitVertex(captured, v, consumer);
            }
        }
        else if (mode == PrimitiveTopology.TRIANGLES)
        {
            for (int v = 0; v + 2 < count; v += 3)
            {
                emitVertex(captured, v, consumer);
                emitVertex(captured, v + 1, consumer);
                emitVertex(captured, v + 2, consumer);
                emitVertex(captured, v + 2, consumer);
            }
        }
    }

    /**
     * Decode one vertex by element usage and write the FULL entity-format vertex, defaulting
     * whatever the source format lacks (unlike {@code FormRenderCapture.emitVertex}, which writes
     * only the source's elements and would underfill the picker's format).
     */
    private static void emitVertex(FormRenderCapture.Captured captured, int index, VertexConsumer consumer)
    {
        VertexFormat format = captured.params().format();
        int base = index * format.getVertexSize();
        ByteBuffer data = captured.data().duplicate().order(ByteOrder.nativeOrder());

        float x = 0F, y = 0F, z = 0F;
        float u = 0F, v = 0F;
        int overlay = OverlayTexture.NO_OVERLAY;
        int light = 0;

        /* 26.2's VertexFormatElement is (semantic name, offset, GpuFormat): the usage/index pair of
         * 1.21.11 became the semantic name, so the element is matched by DefaultVertexFormat's own
         * name constants instead of by usage enum. */
        for (VertexFormatElement element : format.getElements())
        {
            int offset = base + element.offset();
            String name = element.name();

            if (name.equals(DefaultVertexFormat.POSITION_SEMANTIC_NAME))
            {
                x = data.getFloat(offset);
                y = data.getFloat(offset + 4);
                z = data.getFloat(offset + 8);
            }
            else if (name.equals(DefaultVertexFormat.UV0_SEMANTIC_NAME))
            {
                u = data.getFloat(offset);
                v = data.getFloat(offset + 4);
            }
            else if (name.equals(DefaultVertexFormat.UV2_SEMANTIC_NAME))
            {
                /* The light channel, where a mob form's parts wrote their bone ids (see
                 * MobRenderContext.partLight) — dropping it would flatten every bone back
                 * onto the form's own id. */
                light = Short.toUnsignedInt(data.getShort(offset)) | Short.toUnsignedInt(data.getShort(offset + 2)) << 16;
            }
        }

        /* Colour is replaced by the picker's Target index in the shader; light.x carries the
         * per-vertex bone sub-index, which stays 0 for everything without a rig. */
        consumer.addVertex(x, y, z)
            .setColor(255, 255, 255, 255)
            .setUv(u, v)
            .setOverlay(overlay)
            .setLight(light)
            .setNormal(0F, 1F, 0F);
    }
}
