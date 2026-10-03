#version 330

/* Samples the frame BBS just rendered and writes it out opaque.
 *
 * The alpha is forced to 1 rather than taken from the source. The world framebuffer's sky and cleared
 * regions carry a non-opaque alpha, and the snapshot feeds the film preview, which blits through
 * GUI_TEXTURED — that multiplies texel alpha, so a preserved sky alpha shows through as the panel
 * background. The old raw-GL path got this for free by capturing into an RGB8 texture; 26.2 cannot
 * create one (see TextureFormat), so the format is RGBA8 and the alpha is dropped here instead.
 *
 * This is also why the snapshot does not simply use CommandEncoder.copyTextureToTexture: that copies
 * 1:1, and the snapshot is a rescale (the world may render larger than the export size), so the copy
 * would both lose the scaling and carry the wrong alpha. */

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main()
{
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;

    fragColor = vec4(color.rgb, 1.0);
}
