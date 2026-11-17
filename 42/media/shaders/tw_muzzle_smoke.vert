#version 330

// Muzzle effect quads of TW MuzzleFx (VBORenderer position/colour/uv format);
// the z of a smoke puff carries its distortion (Particles/Smoke Distorted
// vertex G), the quads lie in the plane of the world frame.
layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec4 aColor;
layout (location = 2) in vec2 aUV1;

uniform mat4 ModelViewProjection;

out vec4 vColor;
out vec2 vUV1;
out float vDistort;

void main()
{
    vColor = aColor;
    vUV1 = aUV1;
    vDistort = aPosition.z;
    gl_Position = ModelViewProjection * vec4(aPosition.xy, 0.0, 1.0);
}
