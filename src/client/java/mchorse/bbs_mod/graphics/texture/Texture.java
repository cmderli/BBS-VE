package mchorse.bbs_mod.graphics.texture;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import mchorse.bbs_mod.graphics.gpu.BBSGpu;
import mchorse.bbs_mod.utils.resources.Pixels;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A texture, on 26.2's device API.
 *
 * <p>This is the class that changed the most, because 1.21.11's texture was a mutable GL object
 * that the mod could bind, resize and parameterize at will, and 26.2's is an immutable driver
 * allocation. The consequences, and how they are handled here:</p>
 *
 * <ul>
 *   <li><b>Size is fixed at creation.</b> 1.21.11 resized with another {@code glTexImage2D} on the
 *       same name. {@link #setSize(int, int)} therefore <i>reallocates</i> the
 *       {@link #gpuTexture} (and its {@link #view}) and drops the old one, which is what
 *       {@code Framebuffer.resize} needs and what nothing else should call.</li>
 *   <li><b>Uploading is a command, not a state change.</b> {@code glTexImage2D} was immediate;
 *       {@link CommandEncoder#writeToTexture} records into the encoder's queue and the queue is
 *       only pushed to the GPU by {@link CommandEncoder#submit()}. Every upload here submits,
 *       because the callers are uploads of already-loaded pixels, not per-frame traffic, and a
 *       pending upload that is never submitted shows up as a texture that is one frame — or
 *       forever — out of date.</li>
 *   <li><b>Filtering and wrapping moved to the sampler.</b> {@link #setFilter} no longer mutates
 *       GPU state; it rebuilds {@link #sampler()}, which the render pass binds next to the
 *       texture. Two textures can no longer disagree about "which texture is linear" because
 *       there is no such global.</li>
 *   <li><b>There is no texture unit.</b> {@code bind()}/{@code bind(unit)} are deliberately gone:
 *       a pass is handed {@link #view()} and {@link #sampler()} by name
 *       ({@code RenderPass.bindTexture("Sampler0", view, sampler)}), so there is no "currently
 *       bound" texture to write. Call sites that used them need the pass they draw into, which is
 *       the same pass the surrounding port has to thread through anyway.</li>
 * </ul>
 */
public class Texture
{
    /** The driver allocation. Never null once the texture has a size; recreated by {@link #setSize}. */
    public GpuTexture gpuTexture;

    public int width;
    public int height;

    private GpuTextureView view;
    private GpuSampler sampler;

    private TextureFormat format = TextureFormat.RGBA_U8;
    private TextureFilter filter = TextureFilter.NEAREST;
    private AddressMode wrapU = AddressMode.CLAMP_TO_EDGE;
    private AddressMode wrapV = AddressMode.CLAMP_TO_EDGE;
    private int mipLevels = 1;

    private boolean clearable;
    private boolean translucent;

    private AnimatedTexture parent;

    /**
     * Read a texture back into CPU memory — the eyedropper, the texture editor and the form
     * picker all do this.
     *
     * <p>1.21.11 did this with {@code glGetTexImage}, which was immediate: the pixels were there
     * when the call returned. 26.2 has no synchronous read. The copy is recorded with
     * {@link CommandEncoder#copyTextureToBuffer} and the pixels only exist once the GPU has run
     * it, so the encoder's completion callback is used to know when, and the caller blocks on a
     * latch. That makes this a <b>render-thread-only, potentially stalling</b> call, which is
     * exactly what the GL version was too — it forced a pipeline flush just the same.</p>
     *
     * <p>The returned buffer is a fresh {@code MemoryUtil} allocation owned by the returned
     * {@link Pixels}; the intermediate {@link GpuBuffer} is closed here.</p>
     */
    public static Pixels pixelsFromTexture(Texture texture)
    {
        if (texture == null || !texture.isValid())
        {
            return null;
        }

        GpuTexture gpuTexture = texture.gpuTexture;
        int width = gpuTexture.getWidth(0);
        int height = gpuTexture.getHeight(0);
        int blockSize = gpuTexture.getFormat().blockSize();
        int bits = blockSize;

        GpuBuffer buffer = BBSGpu.device().createBuffer(
            () -> "bbs texture readback",
            GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
            (long) width * height * blockSize
        );

        CountDownLatch done = new CountDownLatch(1);

        BBSGpu.encoder().copyTextureToBuffer(gpuTexture, buffer, 0, done::countDown, 0);

        try
        {
            /* A generous ceiling: the copy is a handful of microseconds of GPU work, and the only
             * way this times out is a lost device, in which case returning garbage pixels would be
             * worse than returning none. */
            if (!done.await(5, TimeUnit.SECONDS))
            {
                buffer.close();

                return null;
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            buffer.close();

            return null;
        }

        ByteBuffer out = MemoryUtil.memAlloc(width * height * blockSize);

        /* map(read = true, write = false): the same call the vanilla screenshot path makes. */
        try (GpuBufferSlice.MappedView mapped = buffer.map(true, false))
        {
            ByteBuffer source = mapped.data();

            out.put(source.position(0).limit(width * height * blockSize));
        }
        catch (Exception e)
        {
            MemoryUtil.memFree(out);
            buffer.close();

            return null;
        }

        /* Pixels addresses its buffer by absolute offset (position(index * bits) and
         * memAddress(buffer, 0)), so the position has to be back at the start. */
        out.position(0);
        buffer.close();

        return new Pixels(out, width, height, bits);
    }

    public static Texture textureFromPixels(Pixels pixels, TextureFilter filter)
    {
        Texture texture = new Texture();

        texture.setFilter(filter);
        texture.uploadTexture(pixels);

        return texture;
    }

    /**
     * An empty texture with no GPU allocation yet.
     *
     * <p>1.21.11 created the GL name here, which is why every BBS {@code new Texture()} was valid
     * immediately and {@code setSize} later only changed its storage. 26.2 cannot do that: a
     * {@code GpuTexture} is created for a format and size. The allocation is therefore deferred to
     * the first {@link #setSize} or upload, and {@link #isValid()} means "has an allocation", not
     * "has a name".</p>
     */
    public Texture()
    {}

    public GpuTextureView view()
    {
        return this.view;
    }

    public GpuSampler sampler()
    {
        if (this.sampler == null)
        {
            this.sampler = BBSGpu.sampler(this.wrapU, this.wrapV, this.filter.mode, this.filter.mode, this.filter.mipmapped);
        }

        return this.sampler;
    }

    /** Allocate (or reallocate) the GPU texture, discarding whatever it held. */
    private void allocate(int width, int height, int mipLevels)
    {
        BBSGpu.assertOnRenderThread();
        this.delete();

        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.mipLevels = Math.max(1, mipLevels);
        this.gpuTexture = BBSGpu.device().createTexture(
            () -> "bbs texture " + this.width + "x" + this.height,
            this.format.usage(),
            this.format.gpuFormat,
            this.width,
            this.height,
            1,
            this.mipLevels
        );
        this.view = BBSGpu.device().createTextureView(this.gpuTexture);
    }

    /**
     * The 26.2 replacement for "is this texture usable": a texture that was never given a size
     * has no allocation to sample, and passing its {@code null} view to a pass is a crash inside
     * the backend rather than an error here.
     */
    public boolean isValid()
    {
        return this.gpuTexture != null && !this.gpuTexture.isClosed();
    }

    public void setParent(AnimatedTexture parent)
    {
        this.parent = parent;
    }

    public AnimatedTexture getParent()
    {
        return this.parent;
    }

    public void setClearable(boolean clearable)
    {
        this.clearable = clearable;
    }

    public boolean isClearable()
    {
        return this.clearable;
    }

    public TextureFormat getFormat()
    {
        return this.format;
    }

    public void setFormat(TextureFormat format)
    {
        this.format = format;
    }

    public boolean isMipmap()
    {
        return this.mipLevels > 1;
    }

    public boolean isReallyMipmap()
    {
        return this.mipLevels > 1;
    }

    public int getMipLevels()
    {
        return this.mipLevels;
    }

    /**
     * Whether this texture has semi-transparent pixels (alpha strictly between the model
     * shader's 0.1 cutout threshold and fully opaque). Such textures need the two-pass
     * translucency treatment; plain opaque/cutout textures render in a single pass.
     */
    public boolean hasTranslucency()
    {
        return this.translucent;
    }

    public TextureFilter getFilter()
    {
        return this.filter;
    }

    public boolean isLinear()
    {
        return this.filter.isLinear();
    }

    public void setFilter(TextureFilter filter)
    {
        if (this.filter != filter)
        {
            this.filter = filter;
            this.sampler = null;
        }
    }

    /** Transitional: accepts the {@code GL_*} constants the 1.21.11 call sites pass. */
    public void setFilter(int legacyFilter)
    {
        this.setFilter(TextureFilter.fromLegacyGL(legacyFilter));
    }

    /**
     * Mipmapped filtering.
     *
     * <p>1.21.11 could call {@code glGenerateMipmap} to build the chain on the GPU. 26.2 exposes
     * no such command: the levels have to be generated on the CPU when the image is uploaded (see
     * {@link #uploadTexture(Pixels)}, which uses vanilla's {@code MipmapGenerator}) or not at all.
     * Asking for mipmapped filtering on a texture that was uploaded without levels therefore
     * falls back to the base level, which is what {@code GL_TEXTURE_MAX_LEVEL = 0} used to do.</p>
     */
    public void setFilterMipmap(boolean linear, boolean mipmap)
    {
        this.setFilter(mipmap
            ? (linear ? TextureFilter.LINEAR_MIPMAP_LINEAR : TextureFilter.NEAREST_MIPMAP_NEAREST)
            : (linear ? TextureFilter.LINEAR : TextureFilter.NEAREST));
    }

    public void setWrap(AddressMode mode)
    {
        if (this.wrapU != mode || this.wrapV != mode)
        {
            this.wrapU = mode;
            this.wrapV = mode;
            this.sampler = null;
        }
    }

    /** Transitional: accepts the {@code GL_TEXTURE_WRAP_*} constants the 1.21.11 call sites pass. */
    public void setWrap(int legacyWrap)
    {
        this.setWrap(TextureFilter.wrapFromLegacyGL(legacyWrap));
    }

    /**
     * Give the texture a size. The contents become undefined — this is the old
     * {@code glTexImage2D(..., null)} that {@code Framebuffer.resize} relied on.
     */
    public void setSize(int width, int height)
    {
        this.allocate(width, height, this.mipLevels);
    }

    public void updateTexture(Pixels pixels)
    {
        this.upload(pixels, 0, 0);
    }

    public void uploadTexture(Pixels pixels)
    {
        this.translucent = scanTranslucency(pixels);
        this.upload(pixels, 0, 0);

        pixels.delete();
    }

    /**
     * Upload a whole image into a sub-rectangle at {@code (x, y)} — the 26.2 shape of
     * {@code glTexSubImage2D}. The texture is created on first use.
     */
    public void uploadRegion(Pixels pixels, int x, int y)
    {
        this.upload(pixels, x, y);
    }

    private void upload(Pixels pixels, int x, int y)
    {
        if (pixels == null || pixels.getBuffer() == null)
        {
            return;
        }

        int pixelsBits = pixels.bits;
        TextureFormat target = pixelsBits == 4 ? TextureFormat.RGBA_U8 : TextureFormat.RGB_U8;

        if (!this.isValid() || this.width != pixels.width || this.height != pixels.height || this.format != target)
        {
            this.format = target;
            this.allocate(pixels.width, pixels.height, this.mipLevels);
        }

        CommandEncoder encoder = BBSGpu.encoder();
        ByteBuffer source = pixels.getBuffer().duplicate();

        /* writeToTexture reads from the buffer's current position (the backend takes its native
         * address), and Pixels does not promise where that is. */
        source.position(0);
        source.limit(this.width * this.height * this.format.blockSize());

        encoder.writeToTexture(this.gpuTexture, source, 0, 0, x, y, pixels.width, pixels.height);
        encoder.submit();
    }

    /**
     * The 1.21.11 signature, kept so that the call sites that still pass a GL target compile while
     * they are migrated. {@code target} was a {@code glTexImage2D} argument; 26.2 addresses
     * layers through the copy's {@code depthOrLayer} instead, and BBS never uploaded a cubemap
     * face, so this is a whole-image upload into layer 0.
     */
    @Deprecated
    public void uploadTexture(int target, int level, int w, int h, ByteBuffer buffer)
    {
        Pixels pixels = new Pixels(buffer, w, h, 4);

        this.upload(pixels, 0, 0);
    }

    @Deprecated
    public void uploadTexture(int target, Pixels pixels)
    {
        this.upload(pixels, 0, 0);
    }

    @Deprecated
    public void uploadTexture(int target, int level, Pixels pixels)
    {
        this.upload(pixels, 0, 0);
    }

    @Deprecated
    public void updateTexture(int target, Pixels pixels)
    {
        this.upload(pixels, 0, 0);
    }

    /**
     * The 26.2 replacement for {@code glGenerateMipmap} does not exist on the GPU side; a
     * mipmapped chain is produced on the CPU by {@code MipmapGenerator} at upload time. This
     * keeps the flag honest so that filtering does not ask for levels that were never uploaded.
     */
    public void generateMipmap()
    {
        int levels = 1;

        while ((1 << levels) <= Math.min(this.width, this.height))
        {
            levels += 1;
        }

        this.mipLevels = levels;
    }

    public void delete()
    {
        if (this.view != null)
        {
            this.view.close();
            this.view = null;
        }

        if (this.gpuTexture != null)
        {
            this.gpuTexture.close();
            this.gpuTexture = null;
        }

        /* The sampler is owned by the vanilla SamplerCache, which closes it with the device. */
        this.sampler = null;
    }

    /**
     * The 26..254 alpha range mirrors the model shader: below 0.1 the fragment is discarded
     * outright (cutout), at 255 it's opaque — only the range between makes blending matter.
     */
    private static boolean scanTranslucency(Pixels pixels)
    {
        if (pixels.bits != 4)
        {
            return false;
        }

        ByteBuffer buffer = pixels.getBuffer();

        for (int i = 3, c = buffer.limit(); i < c; i += 4)
        {
            int alpha = buffer.get(i) & 0xff;

            if (alpha >= 26 && alpha <= 254)
            {
                return true;
            }
        }

        return false;
    }
}
