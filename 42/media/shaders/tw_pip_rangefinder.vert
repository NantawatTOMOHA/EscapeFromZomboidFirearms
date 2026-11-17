#version 330 compatibility
layout(location=0) in vec3 Position;
layout(location=1) in vec2 UV;
out vec2 vUV;
out float vDepth;
void main() {
    gl_Position=vec4(Position.xy,0.0,1.0);
    vUV=UV; vDepth=Position.z;
}
