package mchorse.bbs_mod.graphics.gpu;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;

/**
 * The single doorway into Minecraft 26.2's GPU abstraction.
 *
 * <p>1.21.11 still let a mod talk to OpenGL: {@code RenderSystem} owned mutable global state, a
 * {@code Framebuffer} was an integer name, a texture was bound to a unit and stayed bound, and
 * {@code GlStateManager} was the cache in front of it. 26.2 does not have that layer at all. The
 * game is built on a backend-neutral device API ({@link GpuDevice} / {@link CommandEncoder} /
 * {@code RenderPass}) with two implementations underneath — {@code com.mojang.blaze3d.vulkan} for
 * the Vulkan backend and {@code com.mojang.blaze3d.opengl} for the OpenGL one — and every draw is
 * recorded into an explicit render pass instead of being issued against global state.</p>
 *
 * <p>What follows from that, and what this class therefore exists to centralize:</p>
 *
 * <ul>
 *   <li><b>There is no "current" texture or framebuffer.</b> State is passed to a pass when the
 *       pass is created or when a pipeline is bound, so a helper that used to write
 *       {@code glBindTexture} has nowhere to write it. Use
 *       {@code RenderPass.bindTexture(name, view, sampler)} against an open pass instead.</li>
 *   <li><b>Handles must be closed.</b> {@link GpuTexture}, {@code GpuBuffer} and the samplers are
 *       {@link AutoCloseable} driver objects, not names that get recycled by the driver.</li>
 *   <li><b>The device knows things the mod used to guess.</b> {@link #zZeroToOne()} replaces the
 *       "is this GL or not" questions: Vulkan clips depth to [0,1] while OpenGL clips to [-1,1], so
 *       any projection matrix BBS builds by hand must be told which convention to use.</li>
 * </ul>
 *
 * <p>Every method here asserts the render thread, because the backend queues are only safe from
 * there — 26.2 enforces this with {@code RenderSystem.assertOnRenderThread()} in the encoder
 * itself, and a violation surfaces as an exception thrown from deep inside a draw.</p>
 */
public final class BBSGpu
{
    private BBSGpu()
    {}

    /** The active device. Throws if the renderer has not been initialised yet. */
    public static GpuDevice device()
    {
        return RenderSystem.getDevice();
    }

    /**
     * The active device, or {@code null} before {@code RenderSystem.initRenderer}.
     *
     * <p>BBS's resource loading runs during the client's constructor, before the device exists,
     * which is exactly the window where the old GL code could still create a texture name and get
     * away with it. It cannot here, so callers that run that early have to defer.</p>
     */
    public static GpuDevice tryDevice()
    {
        return RenderSystem.tryGetDevice();
    }

    /** A fresh command encoder. Cheap: the backend hands out a recorded-command wrapper. */
    public static CommandEncoder encoder()
    {
        return device().createCommandEncoder();
    }

    public static DeviceInfo info()
    {
        return device().getDeviceInfo();
    }

    /** {@code true} on Vulkan (depth clip [0,1] and the inverted-Y clip space), {@code false} on GL. */
    public static boolean zZeroToOne()
    {
        return info().isZZeroToOne();
    }

    /** The backend's own name, for logs and for the one or two places BBS branched on it. */
    public static String backend()
    {
        return info().backendName();
    }

    public static boolean isVulkan()
    {
        return backend().toLowerCase(java.util.Locale.ROOT).contains("vulkan");
    }

    public static void assertOnRenderThread()
    {
        RenderSystem.assertOnRenderThread();
    }

    /* Samplers.
     *
     * In 1.21.11 filtering and wrapping were properties of the texture object itself
     * (glTexParameteri), so a texture could only be sampled one way at a time and BBS's
     * setFilter/setWrap mutated whatever happened to be bound. 26.2 moves all of it into the
     * sampler, which is a separate object passed at bind time. Samplers are immutable, so they are
     * cached by value: the vanilla SamplerCache already keys them, and rebuilding one per draw
     * would leak a driver object per frame. */

    public static GpuSampler sampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, boolean mipmapped)
    {
        return RenderSystem.getSamplerCache().getSampler(u, v, min, mag, mipmapped);
    }

    public static GpuSampler clampToEdge(FilterMode mode)
    {
        return RenderSystem.getSamplerCache().getClampToEdge(mode);
    }

    public static GpuSampler clampToEdge(FilterMode mode, boolean mipmapped)
    {
        return RenderSystem.getSamplerCache().getClampToEdge(mode, mipmapped);
    }

    public static GpuSampler repeat(FilterMode mode)
    {
        return RenderSystem.getSamplerCache().getRepeat(mode);
    }

    public static GpuSampler repeat(FilterMode mode, boolean mipmapped)
    {
        return RenderSystem.getSamplerCache().getRepeat(mode, mipmapped);
    }
}
