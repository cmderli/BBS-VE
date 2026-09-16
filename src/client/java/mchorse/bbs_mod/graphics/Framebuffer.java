package mchorse.bbs_mod.graphics;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.graphics.gpu.BBSGpu;
import mchorse.bbs_mod.graphics.texture.Texture;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * A render target, rebuilt on 26.2 render passes.
 *
 * <p>The 1.21.11 class was a thin wrapper over a framebuffer object: {@code glGenFramebuffers},
 * {@code glFramebufferTexture2D} per attachment, and then {@code bind()} /
 * {@code glViewport()} / {@code clear()} as ambient state. None of that survives:</p>
 *
 * <ul>
 *   <li><b>Rendering is a pass, not a binding.</b> 26.2 has no "current framebuffer"; a
 *       {@link RenderPass} is created from a {@link RenderPassDescriptor} that names its
 *       attachments, and every draw goes into that pass until it is closed. So {@link #apply()}
 *       now <i>opens</i> a pass and {@link #unbind()} closes it, and the pass has to be handed to
 *       whatever draws in between ({@link #getPass()}). That is the single biggest call-site
 *       change in the render port: a draw helper that used to rely on "the framebuffer is bound"
 *       now needs the pass as a parameter.</li>
 *   <li><b>Clearing is declared when the pass is created.</b> The clear colour and depth are part
 *       of the attachment descriptors, because that is what lets a driver keep the attachment in
 *       tile memory and skip writing it out. {@link #applyClear()} opens a pass that clears;
 *       {@link #apply()} opens one that loads, which is the distinction the old
 *       {@code apply()}-then-{@code clear()} pair was making.</li>
 *   <li><b>Multiple render targets are not a call.</b> {@code glDrawBuffers} is gone; every colour
 *       attachment is listed in the descriptor, and the pipeline declares how many it writes with
 *       its colour target states. {@link #attachments(int...)} therefore has nothing left to
 *       do.</li>
 *   <li><b>There is no blit.</b> {@code glBlitFramebuffer} has no 26.2 equivalent other than the
 *       vanilla {@link RenderTarget#blitAndBlendToTexture} on a vanilla target; a BBS-to-BBS blit
 *       has to be a full-screen quad drawn with a pipeline. {@link #blitTo(Framebuffer)} is
 *       deliberately absent rather than silently wrong.</li>
 * </ul>
 *
 * <p>Attachments are BBS {@link Texture} objects, not vanilla {@code RenderTarget}s, because the
 * mod samples the very textures it renders into (the form preview, the film stencil target, the
 * model-block preview). A vanilla {@code TextureTarget} would allocate its own colour texture and
 * hand it out only as a {@code GpuTextureView}, which is not the object the rest of BBS binds.</p>
 */
public class Framebuffer
{
    private static final Vector4fc CLEAR_COLOR = new Vector4f(0F, 0F, 0F, 0F);
    private static final double CLEAR_DEPTH = 1D;

    public final List<Texture> textures = new ArrayList<>();
    public final List<Renderbuffer> renderbuffers = new ArrayList<>();

    private boolean deleteTextures;
    private boolean advancedClearing;

    private RenderPass pass;
    private int width;
    private int height;

    public Framebuffer()
    {}

    /**
     * Kept for the call sites that used it to mean "clear each attachment with its own clear
     * value". With render passes there is only one way to clear, so this is now documentation
     * rather than a switch.
     */
    public Framebuffer enableAdvancedClearing()
    {
        this.advancedClearing = true;

        return this;
    }

    public boolean isAdvancedClearing()
    {
        return this.advancedClearing;
    }

    public Framebuffer deleteTextures()
    {
        this.deleteTextures = true;

        return this;
    }

    public Texture getMainTexture()
    {
        return this.textures.isEmpty() ? null : this.textures.get(0);
    }

    public Renderbuffer getDepthBuffer()
    {
        return this.renderbuffers.isEmpty() ? null : this.renderbuffers.get(0);
    }

    public int getWidth()
    {
        return this.width;
    }

    public int getHeight()
    {
        return this.height;
    }

    /**
     * Attach a texture as a colour attachment.
     *
     * <p>{@code index} is the attachment's position in the pass, which is what a pipeline's
     * {@code withColorTargetState(index, ...)} refers to. It is passed explicitly now because the
     * old {@code GL_COLOR_ATTACHMENT0 + i} arithmetic was the only thing that ordered them.</p>
     */
    public Framebuffer attachColor(Texture texture, int index)
    {
        while (this.textures.size() <= index)
        {
            this.textures.add(null);
        }

        this.textures.set(index, texture);
        this.updateSizeFrom(texture);

        return this;
    }

    /** Transitional overload that decodes the legacy GL attachment enum. */
    @Deprecated
    public Framebuffer attach(Texture texture, int attachment)
    {
        return this.attachColor(texture, Math.max(0, attachment - 0x8CE0 /* GL_COLOR_ATTACHMENT0 */));
    }

    /**
     * Attach a depth attachment. Only one is possible: a pipeline has a single depth target.
     */
    public void attach(Renderbuffer renderbuffer)
    {
        if (this.renderbuffers.isEmpty())
        {
            this.renderbuffers.add(renderbuffer);
        }
        else
        {
            this.renderbuffers.set(0, renderbuffer);
        }
    }

    /**
     * No-op: 26.2 takes the colour attachment list from the pass descriptor and the written-target
     * count from the pipeline. Kept so the old call sites read as what they meant.
     */
    public void attachments(int count)
    {}

    public void attachments(int... attachments)
    {}

    private void updateSizeFrom(Texture texture)
    {
        if (texture != null && texture.width > 0 && texture.height > 0)
        {
            this.width = texture.width;
            this.height = texture.height;
        }
    }

    /**
     * Open the pass that renders into this target, keeping its contents.
     *
     * <p>Anything drawn after this call must be recorded into {@link #getPass()}; the pass is only
     * submitted when {@link #unbind()} closes it.</p>
     */
    public RenderPass apply()
    {
        return this.begin(false);
    }

    /** Open the pass and clear every attachment that asked to be clearable. */
    public RenderPass applyClear()
    {
        return this.begin(true);
    }

    private RenderPass begin(boolean clear)
    {
        if (this.pass != null)
        {
            throw new IllegalStateException("A BBS framebuffer pass is already open; close it with unbind() before opening another");
        }

        if (this.textures.isEmpty())
        {
            throw new IllegalStateException("Cannot render into a framebuffer with no colour attachment");
        }

        BBSGpu.assertOnRenderThread();

        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "bbs framebuffer");
        boolean hasColor = false;

        for (Texture texture : this.textures)
        {
            if (texture == null || !texture.isValid())
            {
                /* A hole in the middle of the list still needs a placeholder so that the later
                 * attachments keep their index — that is what the unused slots were for under GL
                 * too. */
                descriptor.withUnusedColorAttachment();

                continue;
            }

            Optional<Vector4fc> clearValue = clear && texture.isClearable()
                ? Optional.of(CLEAR_COLOR)
                : Optional.empty();

            descriptor.withColorAttachment(texture.view(), clearValue);
            hasColor = true;
            this.updateSizeFrom(texture);
        }

        if (!hasColor)
        {
            throw new IllegalStateException("Cannot create a render pass whose only attachments are unused color slots");
        }

        for (Renderbuffer renderbuffer : this.renderbuffers)
        {
            if (renderbuffer != null && renderbuffer.isValid())
            {
                descriptor.withDepthAttachment(renderbuffer.view(), clear ? OptionalDouble.of(CLEAR_DEPTH) : OptionalDouble.empty());
            }
        }

        this.pass = BBSGpu.encoder().createRenderPass(descriptor);

        return this.pass;
    }

    /** The open pass, or {@code null}. Draws have to go through it. */
    public RenderPass getPass()
    {
        return this.pass;
    }

    public boolean isPassOpen()
    {
        return this.pass != null;
    }

    /**
     * Clear outside a pass.
     *
     * <p>1.21.11 could clear at any moment; here a clear is either declared when the pass opens
     * ({@link #applyClear()}) or issued through the encoder while no pass is active. Calling this
     * with a pass open is a programming error, and says so, because the encoder would reject the
     * command anyway with a much worse message.</p>
     */
    public void clear()
    {
        if (this.pass != null)
        {
            throw new IllegalStateException("Clears must be declared when the pass opens (use applyClear()); a pass is already open");
        }

        Texture color = this.getMainTexture();
        Renderbuffer depth = this.getDepthBuffer();

        if (color == null || !color.isValid())
        {
            return;
        }

        if (depth != null && depth.isValid())
        {
            BBSGpu.encoder().clearColorAndDepthTextures(color.gpuTexture, CLEAR_COLOR, depth.texture.gpuTexture, CLEAR_DEPTH);
        }
        else
        {
            BBSGpu.encoder().clearColorTexture(color.gpuTexture, CLEAR_COLOR);
        }
    }

    /** Alias of {@link #apply()} for the 1.21.11 call sites. */
    public void bind()
    {
        this.apply();
    }

    /** Close and submit the open pass. */
    public void unbind()
    {
        if (this.pass != null)
        {
            this.pass.close();
            this.pass = null;
        }
    }

    /**
     * Resize every attachment.
     *
     * <p>26.2 textures cannot be resized, so this reallocates them and their contents are lost —
     * the same thing {@code glTexImage2D(..., null)} did on 1.21.11, but now it is visible in the
     * API instead of hidden behind a mutable name.</p>
     */
    public void resize(int width, int height)
    {
        if (width <= 0 || height <= 0)
        {
            return;
        }

        this.width = width;
        this.height = height;

        for (Texture texture : this.textures)
        {
            if (texture != null)
            {
                texture.setSize(width, height);
            }
        }

        for (Renderbuffer renderbuffer : this.renderbuffers)
        {
            renderbuffer.resize(width, height);
        }
    }

    /**
     * Point the game's own main render pass at this target.
     *
     * <p>1.21.11 did this by swapping {@code MinecraftClient.framebuffer} through an access
     * widener, which is how BBS renders the interface at a custom resolution and scales it. 26.2
     * offers a supported hook instead: the pass that draws the main target reads
     * {@link RenderSystem#outputColorTextureOverride} and friends, so no mixin is needed.</p>
     */
    public void overrideMainOutput()
    {
        RenderSystem.outputColorTextureOverride = this.getMainTexture() == null ? null : this.getMainTexture().view();

        Renderbuffer depth = this.getDepthBuffer();

        RenderSystem.outputDepthTextureOverride = depth == null ? null : depth.view();
    }

    public static void clearMainOutputOverride()
    {
        RenderSystem.outputColorTextureOverride = null;
        RenderSystem.outputDepthTextureOverride = null;
    }

    public void delete()
    {
        this.unbind();

        if (this.deleteTextures)
        {
            for (Texture texture : this.textures)
            {
                if (texture != null)
                {
                    texture.delete();
                }
            }

            this.textures.clear();
        }

        for (Renderbuffer renderbuffer : this.renderbuffers)
        {
            renderbuffer.delete();
        }

        this.renderbuffers.clear();
    }
}
