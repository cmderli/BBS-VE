#version 330

/* Fog is guarded by IS_GUI exactly as vanilla's own core/text.fsh guards it, and on 26.2 that guard is
 * load-bearing rather than cosmetic.
 *
 * BBS builds this pipeline from vanilla's GUI_TEXT, so IS_GUI is defined and the vertex shader
 * (vanilla's core/text.vsh, which carries the same guards) neither imports fog.glsl nor writes the two
 * distance varyings. This fragment shader used to import fog.glsl and call apply_fog unconditionally,
 * which declared the Fog block and read varyings the vertex shader never wrote.
 *
 * Under GL that was tolerated. The Vulkan backend reflects the compiled shader against the pipeline's
 * declared bind group layouts and rejects the mismatch —
 * "Couldn't compile pipeline bbs:pipeline/pixelart_text: Unable to find shader defined uniform (Fog)"
 * — which leaves the pipeline invalid, and the next setPipeline on it throws "Pipeline is not valid"
 * from inside the GUI pass. The GUI pipeline has no Fog layout to offer, so the reference has to go. */
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <bbs:bbs_pixelart.glsl>

// Vanilla's rendertype_text.fsh with two changes: the atlas is sampled through bbs_pixelart (the seam
// between texels compressed into one screen pixel), and the discard threshold drops from 0.1 to 0.01 —
// vanilla's would eat the smoothed edge of every glyph. The vertex shader is vanilla's own, so the
// varyings below are exactly the ones it writes.

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#endif

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = bbs_pixelart(Sampler0, texCoord0) * vertexColor * ColorModulator;

    if (color.a < 0.01) {
        discard;
    }

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#else
    fragColor = color;
#endif
}
