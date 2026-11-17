#version 330

// A vehicle's skinned model (body, doors, hood) for the heat buffer of
// TW/OpticView: the game's vehicle.vert transform and depth shift, plus the
// world position of every vertex (ClipToWorld: the inverse of the iso camera,
// VehicleHeat) for the heat field of the fragment shader.

layout (location = 0) in vec4 vertex;
layout (location = 1) in vec4 normal;
layout (location = 2) in vec4 boneWeights;
layout (location = 3) in vec4 boneIndices;
layout (location = 4) in vec2 uv;

uniform mat4 ModelViewProjection;
uniform mat4 MatrixPalette[60];
uniform float targetDepth = 0.5;
uniform mat4 ClipToWorld;

out vec2 texCoords;
out vec3 worldPos;

void main()
{
    vec4 position = vec4(vertex.xyz, 1.0);
    texCoords = uv;
    mat4 boneEffect = mat4(0.0);
    if (boneWeights.x > 0.0)
        boneEffect += MatrixPalette[int(boneIndices.x)] * boneWeights.x;
    if (boneWeights.y > 0.0)
        boneEffect += MatrixPalette[int(boneIndices.y)] * boneWeights.y;
    if (boneWeights.z > 0.0)
        boneEffect += MatrixPalette[int(boneIndices.z)] * boneWeights.z;
    if (boneWeights.w > 0.0)
        boneEffect += MatrixPalette[int(boneIndices.w)] * boneWeights.w;
    vec4 o = ModelViewProjection * boneEffect * position;
    worldPos = (ClipToWorld * vec4(o.xyz / o.w, 1.0)).xyz;
    float clip = (o.z + 1.0) * 0.5 + targetDepth - 0.5;
    o.z = clip * 2.0 - 1.0;
    gl_Position = o;
}
