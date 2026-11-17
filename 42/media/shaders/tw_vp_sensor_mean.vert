#version 330

// The whole target (VpSensorGL.mean): four vertices as a triangle strip, their corners (ScreenCorners).
// aCorner: bit 0 and bit 1 of the vertex index as vertex data (ScreenCorners; gl_VertexID and bit operations are
// GLSL 1.30+).
layout(location = 0) in vec2 aCorner;

void main()
{
    vec2 c = vec2(aCorner.x > 0.5 ? 1.0 : -1.0, aCorner.y > 0.5 ? 1.0 : -1.0);
    gl_Position = vec4(c, 0.0, 1.0);
}
