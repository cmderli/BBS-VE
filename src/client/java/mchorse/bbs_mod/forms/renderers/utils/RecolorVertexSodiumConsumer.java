package mchorse.bbs_mod.forms.renderers.utils;

import mchorse.bbs_mod.utils.colors.Color;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * The Sodium-aware recolor wrapper: Sodium's intrinsic vertex writers bypass the vanilla
 * {@link VertexConsumer} chain entirely (they push whole vertex blocks through
 * {@link VertexBufferWriter}), so a plain wrapper silently drops every intrinsic write.
 *
 * <p>Forwarding alone is not enough — the tint has to be applied to the block on the way through,
 * because the per-vertex {@code color} calls it would otherwise ride on are exactly what the
 * intrinsic path skips. On 1.21.1 that was done from the far end, by mixing into Sodium's
 * {@code ColorAttribute#set}; the block is patched here instead, which needs no mixin and so does
 * not care which Sodium version is installed (only the public writer API and the vertex format's
 * own element offsets are used).</p>
 *
 * TODO(26.2 render): Sodium is not a dependency of this build at all (see the closing comment in
 * build.gradle: it "comes back only once utils/iris and utils/sodium are ported"), so the
 * {@code VertexBufferWriter} interface and its {@code push} override cannot be compiled. They were
 * removed here; restore them — and the block-patching body that lived in push — when Sodium
 * returns. {@link RecolorVertexConsumer#tintPackedABGR(int, Color)} is kept for exactly that.
 */
public class RecolorVertexSodiumConsumer extends RecolorVertexConsumer
{
    public RecolorVertexSodiumConsumer(VertexConsumer consumer, Color color)
    {
        super(consumer, color);
    }
}
