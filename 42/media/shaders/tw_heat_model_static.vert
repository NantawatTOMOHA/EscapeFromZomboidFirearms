#version 330

// A static model part (weapon, attachment) of a warm character for the heat
// buffer: the game's aim_outline_solid_static.vert with the targetDepth shift.

layout (location = 0) in vec4 vertex;
layout (location = 1) in vec4 normal;
layout (location = 2) in vec2 uv;

out vec2 texCoords;

uniform mat4 ModelViewProjection;
uniform mat4 transform;
uniform float targetDepth = 0.5;

void main()
{
    texCoords = uv;
    vec4 o = ModelViewProjection * transform * vec4(vertex.xyz, 1.0);
    float clip = (o.z + 1.0) * 0.5 + targetDepth - 0.5;
    o.z = clip * 2.0 - 1.0;
    gl_Position = o;
}
