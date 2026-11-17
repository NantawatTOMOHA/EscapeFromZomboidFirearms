#version 330

// Full-view quad of TW/OpticView (VBORenderer position/colour/uv format).
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
