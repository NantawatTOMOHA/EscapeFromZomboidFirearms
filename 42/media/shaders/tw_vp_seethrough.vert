#version 330

// See-through weapon (pz.escapefromzomboidfirearms.zb.SeeThrough): the box of the own weapon and hands in
// Viewpoint's HDR target, in pixels of its viewport (origin bottom-left).
layout (location = 0) in vec2 aPosition;

uniform mat4 ModelViewProjection;

void main()
{
    gl_Position = ModelViewProjection * vec4(aPosition, 0.0, 1.0);
}
