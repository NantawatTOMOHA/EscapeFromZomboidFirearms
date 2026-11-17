#version 330

// A rectangle of the target (VpSensorGL): Clip = x0, y0, x1, y1 in NDC; four
// vertices as a triangle strip, their corners (ScreenCorners).
// aCorner: bit 0 and bit 1 of the vertex index as vertex data (ScreenCorners; gl_VertexID and bit operations are
// GLSL 1.30+).
layout(location = 0) in vec2 aCorner;
uniform vec4 Clip;

void main()
{
    vec2 c = vec2(aCorner.x > 0.5 ? Clip.z : Clip.x, aCorner.y > 0.5 ? Clip.w : Clip.y);
    gl_Position = vec4(c, 0.0, 1.0);
}
