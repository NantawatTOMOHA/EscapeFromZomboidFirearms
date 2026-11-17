#version 330

// A vehicle's static model part (wheels, body of a static vehicle) for the heat
// buffer: the game's vehicle_static.vert transform and depth shift, plus the
// world position of every vertex (see tw_heat_vehicle.vert).

layout (location = 0) in vec4 vertex;
layout (location = 1) in vec4 normal;
layout (location = 2) in vec2 uv;

uniform mat4 ModelViewProjection;
uniform mat4 transform;
uniform float targetDepth = 0.5;
uniform mat4 ClipToWorld;

out vec2 texCoords;
out vec3 worldPos;

void main()
{
    texCoords = uv;
    vec4 o = ModelViewProjection * transform * vec4(vertex.xyz, 1.0);
    worldPos = (ClipToWorld * vec4(o.xyz / o.w, 1.0)).xyz;
    float clip = (o.z + 1.0) * 0.5 + targetDepth - 0.5;
    o.z = clip * 2.0 - 1.0;
    gl_Position = o;
}
