package mchorse.bbs_mod.graphics;

import com.mojang.blaze3d.textures.GpuTextureView;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.texture.TextureFormat;

/**
 * A depth (or depth-stencil) attachment.
 *
 * <p>1.21.11 had real renderbuffers: {@code glGenRenderbuffers} + {@code glRenderbufferStorage},
 * a storage-only object that could not be sampled. 26.2 does not: an attachment is always a
 * {@code GpuTexture}, only the format tells it apart. This class therefore shrinks to a named
 * depth texture, and it survives at all only because {@code Framebuffer.attach(Renderbuffer)}
 * reads well and there are a handful of call sites.</p>
 *
 * <p>The stencil half of {@link TextureFormat#DEPTH_F24} ({@code D24_UNORM_S8_UINT}) is
 * <b>not usable</b>: 26.2's pipeline state has no stencil test, write mask or reference value, so
 * a pass can hold a depth-stencil texture but nothing can program the stencil half of it. See the
 * port report for what replaces BBS's stencil-based picking.</p>
 */
public class Renderbuffer
{
    public final Texture texture;

    public Renderbuffer()
    {
        this(TextureFormat.DEPTH_F24);
    }

    public Renderbuffer(TextureFormat format)
    {
        this.texture = new Texture();
        this.texture.setFormat(format);
    }

    /** Transitional: the 1.21.11 constructor took GL enums, which have no 26.2 meaning. */
    @Deprecated
    public Renderbuffer(int legacyTarget, int legacyStorage)
    {
        this();
    }

    public GpuTextureView view()
    {
        return this.texture.view();
    }

    public boolean isValid()
    {
        return this.texture.isValid();
    }

    public void resize(int width, int height)
    {
        this.texture.setSize(width, height);
    }

    public void delete()
    {
        this.texture.delete();
    }
}
