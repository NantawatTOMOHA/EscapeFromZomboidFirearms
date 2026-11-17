#version 330

// The mod's perspective pass in the Viewpoint frame (pz.escapefromzomboidfirearms.zb.ViewpointPass):
// screen triangles projected on the CPU (VBORenderer position/colour/uv format).
// x, y: output pixels; z: 1 / view distance; uv: uv / view distance (perspective
// correct after the division in the fragment shader).
layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec4 aColor;
layout (location = 2) in vec2 aUV1;

uniform mat4 ModelViewProjection;

out vec4 vColor;
out vec2 vUVw;
out float vInvW;

void main()
{
    vColor = aColor;
    vUVw = aUV1;
    vInvW = aPosition.z;
    gl_Position = ModelViewProjection * vec4(aPosition.xy, 0.0, 1.0);
}
