#version 330

/* A plain textured fullscreen quad: the vertex half of BBS's device-neutral replacement for the
 * raw-GL framebuffer blit (see BBSRendering.blitIntoSnapshot). ScreenQuadPass draws the quad, so this
 * only has to transform the position and hand the texture coordinate on. */

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 texCoord0;
out vec4 vertexColor;

void main()
{
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexColor = Color;
}
