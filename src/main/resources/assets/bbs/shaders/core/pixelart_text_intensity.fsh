#version 330

/* Same fog guard as pixelart_text.fsh, and needed for the same reason: this pipeline is vanilla's
 * GUI_TEXT_GRAYSCALE, so IS_GUI is defined, the vertex shader writes no distance varyings, and the GUI
 * pipeline carries no Fog bind group for this shader to read (see pixelart_text.fsh for the full note). */
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <bbs:bbs_pixelart.glsl>

// The single-channel half of the pair: the unicode font's glyphs carry coverage in red and nothing in
// the other channels, so the smoothing is done on that one channel and splatted (vanilla's .rrrr).

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#endif

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = vec4(bbs_pixelart_intensity(Sampler0, texCoord0)) * vertexColor * ColorModulator;

    if (color.a < 0.01) {
        discard;
    }

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#else
    fragColor = color;
#endif
}
