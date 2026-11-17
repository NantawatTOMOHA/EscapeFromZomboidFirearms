#version 330

// Fire quads of the heat buffer (tw_heat_fire.frag); as tw_heat.vert.
layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec4 aColor;
layout (location = 2) in vec2 aUV1;

uniform mat4 ModelViewProjection;

out vec4 vColor;
out vec2 vUV1;

void main()
{
    vColor = aColor;
    vUV1 = aUV1;
    gl_Position = ModelViewProjection * vec4(aPosition.xyz, 1.0);
}
