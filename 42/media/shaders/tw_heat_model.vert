#version 330

// A warm character's skinned model in one colour (its thermal input) for the
// heat buffer of TW/OpticView: the game's aim_outline_solid.vert with the depth
// shift of basicEffect.vert (targetDepth), so walls in front hide it.

varying vec2 texCoords;

layout (location = 0) in vec4 vertex;
layout (location = 1) in vec4 normal;
layout (location = 2) in vec4 boneWeights;
layout (location = 3) in vec4 boneIndices;
layout (location = 4) in vec2 uv;

uniform mat4 ModelViewProjection;
uniform mat4 MatrixPalette[60];
uniform float FinalScale;
uniform float targetDepth = 0.5;

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
    position = boneEffect * position;
    position.xyz = position.xyz * FinalScale;
    vec4 o = ModelViewProjection * position;
    float clip = (o.z + 1.0) * 0.5 + targetDepth - 0.5;
    o.z = clip * 2.0 - 1.0;
    gl_Position = o;
}
