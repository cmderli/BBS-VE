package mchorse.bbs_mod.graphics.texture;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;

/**
 * Filtering and wrapping, as 26.2 expresses them.
 *
 * <p>1.21.11 stored these as raw {@code GL_*} integers on the texture and pushed them into
 * {@code glTexParameteri}, which made a texture's sampling mode part of its identity. 26.2 splits
 * it: filtering is chosen by the {@code GpuSampler} that is bound alongside the texture, and a
 * texture can be sampled two different ways in the same frame by binding two samplers.</p>
 *
 * <p>The GL constants are still accepted — {@link #fromLegacyGL(int)} — purely so that the dozens
 * of BBS call sites that pass {@code GL11.GL_LINEAR} keep working while they are migrated. Those
 * call sites should end up passing this enum directly; the translation exists for the transition,
 * not as an API.</p>
 */
public enum TextureFilter
{
    NEAREST(FilterMode.NEAREST, false),
    LINEAR(FilterMode.LINEAR, false),
    NEAREST_MIPMAP_NEAREST(FilterMode.NEAREST, true),
    LINEAR_MIPMAP_NEAREST(FilterMode.LINEAR, true),
    NEAREST_MIPMAP_LINEAR(FilterMode.NEAREST, true),
    LINEAR_MIPMAP_LINEAR(FilterMode.LINEAR, true);

    public final FilterMode mode;
    public final boolean mipmapped;

    TextureFilter(FilterMode mode, boolean mipmapped)
    {
        this.mode = mode;
        this.mipmapped = mipmapped;
    }

    public boolean isLinear()
    {
        return this.mode == FilterMode.LINEAR;
    }

    /**
     * Translate a legacy {@code GL_*} filter constant. Anything unrecognized falls back to
     * {@link #NEAREST}, which is what the GL state machine did with a bogus enum: it silently kept
     * the previous value, and NEAREST is the safe stand-in for the pixel-art work BBS does.
     */
    public static TextureFilter fromLegacyGL(int filter)
    {
        return switch (filter)
        {
            case 0x2601 /* GL_LINEAR */ -> LINEAR;
            case 0x2700 /* GL_NEAREST_MIPMAP_NEAREST */ -> NEAREST_MIPMAP_NEAREST;
            case 0x2701 /* GL_LINEAR_MIPMAP_NEAREST */ -> LINEAR_MIPMAP_NEAREST;
            case 0x2702 /* GL_NEAREST_MIPMAP_LINEAR */ -> NEAREST_MIPMAP_LINEAR;
            case 0x2703 /* GL_LINEAR_MIPMAP_LINEAR */ -> LINEAR_MIPMAP_LINEAR;
            default -> NEAREST;
        };
    }

    /** Translate a legacy {@code GL_TEXTURE_WRAP_*} constant; anything else clamps. */
    public static AddressMode wrapFromLegacyGL(int wrap)
    {
        return wrap == 0x2901 /* GL_REPEAT */ ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE;
    }
}
