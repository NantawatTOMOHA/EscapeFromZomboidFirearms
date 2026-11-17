#version 330

// Our CPU-projected muzzle geometry. All varying attributes carry 1/w so
// clipping and interpolation preserve the atlas and colours in perspective.
layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec4 aColor;
layout (location = 2) in vec2 aUV1;
uniform mat4 ModelViewProjection;
out vec4 vColor;
out vec2 vUVw;
out float vInvW;

void main()
{
    vColor = aColor * aPosition.z;
    vUVw = aUV1;
    vInvW = aPosition.z;
    gl_Position = ModelViewProjection * vec4(aPosition.xy, 0.0, 1.0);
}
