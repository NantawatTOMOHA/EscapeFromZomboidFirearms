#version 330 compatibility
// The full-screen triangle (0,0), (2,0), (0,2) before clip space, as gl_VertexID's bits gave it: aCorner is
// those bits as vertex data (ScreenCorners; gl_VertexID and bit operations are GLSL 1.30+).
layout(location = 0) in vec2 aCorner;
void main() {
    vec2 p = aCorner * 2.0;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
