package mchorse.bbs_mod.graphics.gpu;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.BBSMod;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * BBS's custom shader pipelines, as 26.2 wants them.
 *
 * <p>A pipeline in 26.2 is the whole draw state — shaders, vertex layout, blend, depth, cull,
 * primitive topology — plus the <b>bind group layouts</b> that say which samplers and uniform
 * blocks the shaders are allowed to see. The bind group layout is not decoration: the Vulkan
 * backend compiles the GLSL to SPIR-V and then reflects it against those declarations
 * ({@code GlslCompiler.addToBindGroup}), and a shader declaring a block the pipeline did not
 * announce fails to compile with a message about the missing binding.</p>
 *
 * <p>What this replaces, from 1.21.11:</p>
 *
 * <ul>
 *   <li>{@code RenderPipeline.Builder.withVertexFormat(VertexFormats.X, DrawMode.Y)} split into
 *       {@code withVertexBinding(0, format)} and {@code withPrimitiveTopology(...)}, because 26.2
 *       pipelines can take more than one interleaved vertex stream.</li>
 *   <li>{@code withBlend(BlendFunction)} became a colour target state:
 *       {@code withColorTargetState(new ColorTargetState(blend))}. The blend function is now per
 *       attachment, not per pipeline.</li>
 *   <li>{@code withDepthTestFunction(DepthTestFunction.X)} became
 *       {@code withDepthStencilState(new DepthStencilState(compareOp, writeDepth))} — note the
 *       depth <i>write</i> flag is part of it, which is how BBS gets its two-pass translucency
 *       without {@code RenderSystem.depthMask}.</li>
 *   <li>{@code RenderPipelines.register(...)} is gone (that method is private in 26.2). A mod just
 *       builds its pipeline; the builder assigns the sort key that orders draws.</li>
 *   <li>{@code net.minecraft.client.gl.Defines} is now
 *       {@code net.minecraft.client.renderer.ShaderDefines}, reached through
 *       {@code withShaderDefine(...)}.</li>
 * </ul>
 *
 * <p>Sampler and uniform <i>names</i> are unchanged from the 1.21.5+ convention BBS already used:
 * {@code Sampler0}/{@code Sampler1}/{@code Sampler2} for textures and one std140 block per
 * uniform group. Where a block is a vanilla one, the vanilla layout object is reused
 * ({@link BindGroupLayouts}) so the GLSL can keep declaring the vanilla block verbatim.</p>
 */
public final class BBSRenderPipelines
{
    /**
     * The std140 block shared by every migrated BBS picker shader (see {@code BBSShaders}).
     * <pre>layout(std140) uniform BBSPicker { vec4 HighlightColor; int Target; };</pre>
     *
     * <p>It stays BBS-owned rather than reusing a vanilla block because no vanilla block carries a
     * pick target index.</p>
     */
    public static final BindGroupLayout BBSPICKER = BindGroupLayout.builder()
        .withUniform("BBSPicker", UniformType.UNIFORM_BUFFER)
        .build();

    /** Marching-ants selection uniforms. */
    public static final BindGroupLayout SELECTION_INFO = BindGroupLayout.builder()
        .withUniform("SelectionInfo", UniformType.UNIFORM_BUFFER)
        .build();

    /** Subtitle blur parameters. */
    public static final BindGroupLayout SUBTITLES_INFO = BindGroupLayout.builder()
        .withUniform("SubtitlesInfo", UniformType.UNIFORM_BUFFER)
        .build();

    /** Pixelate/erase parameters. */
    public static final BindGroupLayout MULTILINK_INFO = BindGroupLayout.builder()
        .withUniform("MultilinkInfo", UniformType.UNIFORM_BUFFER)
        .build();

    /* ---- model ----
     *
     * POSITION_COLOR_TEXTURE_LIGHT_NORMAL has no 26.2 counterpart: the vanilla formats that carry a
     * lightmap and the ones that carry a normal are disjoint (ENTITY is the only one with both, and
     * it also carries the overlay UV). BBS's cubic model buffers do have position, colour, UV0, UV2
     * and a normal, so the format is built here instead of picked from DefaultVertexFormat. */
    public static final VertexFormat POSITION_COLOR_TEX_LIGHTMAP_NORMAL = VertexFormat.builder(0)
        .addAttribute(DefaultVertexFormat.POSITION_SEMANTIC_NAME, GpuFormat.RGB32_FLOAT)
        .addAttribute(DefaultVertexFormat.COLOR_SEMANTIC_NAME, GpuFormat.RGBA8_UNORM)
        .addAttribute(DefaultVertexFormat.UV0_SEMANTIC_NAME, GpuFormat.RG32_FLOAT)
        .addAttribute(DefaultVertexFormat.UV2_SEMANTIC_NAME, GpuFormat.RG16_SINT)
        .addAttribute(DefaultVertexFormat.NORMAL_SEMANTIC_NAME, GpuFormat.RGBA8_SNORM)
        .build();

    /** Translucent, depth-tested, depth-writing, no culling — BBS's ordinary form draw. */
    public static final RenderPipeline MODEL = pipeline("model")
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.LIGHTING)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2)
        .withVertexBinding(0, POSITION_COLOR_TEX_LIGHTMAP_NORMAL)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(translucent())
        .withDepthStencilState(depth(true))
        .withCull(false)
        .build();

    /** The same draw with backface culling on (billboard quads emit both windings). */
    public static final RenderPipeline MODEL_CULLED = pipeline("model_culled")
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.LIGHTING)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2)
        .withVertexBinding(0, POSITION_COLOR_TEX_LIGHTMAP_NORMAL)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(translucent())
        .withDepthStencilState(depth(true))
        .withCull(true)
        .build();

    /** Flat single-quad forms: they must not write depth, or they occlude their own back face. */
    public static final RenderPipeline MODEL_NO_DEPTH_WRITE = pipeline("model_nodepth")
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.LIGHTING)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2)
        .withVertexBinding(0, POSITION_COLOR_TEX_LIGHTMAP_NORMAL)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(translucent())
        .withDepthStencilState(depth(false))
        .withCull(false)
        .build();

    /* ---- picking ----
     *
     * The picker family renders an index into a colour attachment and reads it back; the index
     * arrives in the BBSPicker block. Depth is tested but not written. */
    public static final RenderPipeline PICKER_MODELS = picker("picker_models", POSITION_COLOR_TEX_LIGHTMAP_NORMAL);
    public static final RenderPipeline PICKER_BILLBOARD = picker("picker_billboard", DefaultVertexFormat.ENTITY);
    public static final RenderPipeline PICKER_PARTICLES = picker("picker_particles", DefaultVertexFormat.PARTICLE);

    /* ---- 2D interface shaders ---- */

    public static final RenderPipeline MULTILINK = pipeline("multilink")
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(MULTILINK_INFO)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(translucent())
        .withDepthStencilState(Optional.empty())
        .withCull(false)
        .build();

    public static final RenderPipeline SUBTITLES = pipeline("subtitles")
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(SUBTITLES_INFO)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(translucent())
        .withDepthStencilState(Optional.empty())
        .withCull(false)
        .build();

    public static final RenderPipeline SELECTION = pipeline("selection")
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(SELECTION_INFO)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(translucent())
        .withDepthStencilState(Optional.empty())
        .withCull(false)
        .build();

    /* ---- particles ----
     *
     * PARTICLE's element order (Position, UV0, Color, UV2) is what BBS's particle emitter writes,
     * and it is also POSITION_TEXTURE_LIGHTMAP_COLOR in 26.2's own naming. */
    public static final RenderPipeline PARTICLES = pipeline("particles")
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
        .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withColorTargetState(translucent())
        .withDepthStencilState(depth(true))
        .withCull(true)
        .build();

    /** {@code particles_opaque} / {@code particles_alpha}: cutout only, no blending. */
    public static final RenderPipeline PARTICLES_OPAQUE = pipeline("particles_opaque")
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
        .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
        .withDepthStencilState(depth(true))
        .withCull(true)
        .build();

    /**
     * Where a BBS shader's GLSL lives, as an Identifier. 26.2 resolves
     * {@code bbs:core/model} to {@code assets/bbs/shaders/core/model.vsh} and {@code .fsh} through
     * the shader source's id converter, so the existing asset layout is already correct.
     */
    public static Identifier shader(String name)
    {
        return Identifier.fromNamespaceAndPath(BBSMod.MOD_ID, "core/" + name);
    }

    private static RenderPipeline.Builder pipeline(String name)
    {
        return RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(BBSMod.MOD_ID, "pipeline/" + name))
            .withVertexShader(shader(name))
            .withFragmentShader(shader(name));
    }

    private static RenderPipeline picker(String name, VertexFormat format)
    {
        return pipeline(name)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BBSPICKER)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, format)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(translucent())
            .withDepthStencilState(depth(false))
            .withCull(false)
            .build();
    }

    private static ColorTargetState translucent()
    {
        return new ColorTargetState(BlendFunction.TRANSLUCENT);
    }

    private static DepthStencilState depth(boolean write)
    {
        return new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, write);
    }

    /* Kept so the class cannot be instantiated but still documents the full layout list. */
    private BBSRenderPipelines()
    {}

    /** Every bind group a BBS pipeline may declare, for validation and diagnostics. */
    public static List<BindGroupLayout> customLayouts()
    {
        return List.of(BBSPICKER, SELECTION_INFO, SUBTITLES_INFO, MULTILINK_INFO);
    }
}
