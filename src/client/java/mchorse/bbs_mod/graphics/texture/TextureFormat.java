package mchorse.bbs_mod.graphics.texture;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;

/**
 * What a BBS texture is made of, expressed as a 26.2 {@link GpuFormat} plus the usage flags the
 * texture has to be created with.
 *
 * <p>The 1.21.11 version of this enum was four GL triples ({@code internal}, {@code format},
 * {@code type}, {@code attachment}). Two of those have no meaning any more:</p>
 *
 * <ul>
 *   <li>{@code format}/{@code type} described how to read the client-side upload buffer. 26.2
 *       infers that from {@link GpuFormat} and takes the tightest possible row pitch, so the
 *       {@code glPixelStorei(GL_UNPACK_ROW_LENGTH)} dance that went with it is gone.</li>
 *   <li>{@code attachment} was a {@code glFramebufferTexture2D} argument. 26.2 addresses
 *       attachments by their position in a {@code RenderPassDescriptor}, so what matters now is
 *       {@link #usage()} — in particular whether the texture may be rendered into at all.</li>
 * </ul>
 *
 * <p>{@link #usage()} is deliberately generous: every BBS texture is created with
 * {@code USAGE_TEXTURE_BINDING | USAGE_COPY_DST | USAGE_COPY_SRC | USAGE_RENDER_ATTACHMENT},
 * because the mod's render targets are also its samplers (the form preview framebuffers, the
 * film stencil target, the model-block preview) and it reads several of them back to CPU
 * ({@link Texture#pixelsFromTexture}). Vulkan validates these flags at creation, so a texture
 * created without {@code USAGE_RENDER_ATTACHMENT} simply cannot be attached to a pass — the
 * generous set is what keeps one {@code Texture} able to play all the roles it did under GL.</p>
 */
public enum TextureFormat
{
    RGBA_U8(GpuFormat.RGBA8_UNORM, true),
    RGB_U8(GpuFormat.RGB8_UNORM, true),
    RGBA_F16(GpuFormat.RGBA16_FLOAT, true),
    DEPTH_F24(GpuFormat.D24_UNORM_S8_UINT, false);

    public final GpuFormat gpuFormat;

    private final boolean color;

    TextureFormat(GpuFormat gpuFormat, boolean color)
    {
        this.gpuFormat = gpuFormat;
        this.color = color;
    }

    public boolean isColor()
    {
        return this.color;
    }

    public boolean isDepth()
    {
        return !this.color;
    }

    /** Bytes per texel, used for read-back buffers and mip-level sizing. */
    public int blockSize()
    {
        return this.gpuFormat.blockSize();
    }

    public int usage()
    {
        int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC;

        /* Vulkan wants a depth attachment created with RENDER_ATTACHMENT; sampling a depth texture
         * is allowed but needs the same flag, and BBS's picking paths do sample the depth it
         * rendered. */
        return usage | GpuTexture.USAGE_RENDER_ATTACHMENT;
    }

    public static TextureFormat getByName(String name)
    {
        try
        {
            return valueOf(name.toUpperCase());
        }
        catch (Exception e)
        {
            return RGBA_U8;
        }
    }

    public static TextureFormat from(GpuFormat format)
    {
        for (TextureFormat candidate : values())
        {
            if (candidate.gpuFormat == format)
            {
                return candidate;
            }
        }

        return RGBA_U8;
    }
}
