#version 330

// Our screen-space stream preserves perspective-correct source UVs after CPU projection.
layout (location = 0) in vec3 aPosition;
layout (location = 2) in vec2 aUV1;
layout (location = 3) in vec4 aColorW;
uniform mat4 ModelViewProjection;
uniform vec4 Lens;
uniform vec2 OtherAxis;
out vec2 vUVw;
out float vInvW;
out vec2 vLens;
out vec4 vColorW;

void main()
{
    vUVw = aUV1;
    vInvW = aPosition.z;
    vColorW = aColorW;
    vec2 d = aPosition.xy - Lens.xy;
    float det = Lens.z * OtherAxis.y - Lens.w * OtherAxis.x;
    vLens = vec2(d.x * OtherAxis.y - d.y * OtherAxis.x, Lens.z * d.y - Lens.w * d.x) / det;
    gl_Position = ModelViewProjection * vec4(aPosition.xy, 0.0, 1.0);
}
