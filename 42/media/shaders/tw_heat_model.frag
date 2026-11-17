#version 330

// Heat buffer: red = thermal input / 4 of the warm character's model.

in vec2 texCoords;

uniform sampler2D Texture;
uniform vec4 u_color;

void main()
{
    if (texture2D(Texture, texCoords).a < 0.5)
    {
        discard;
    }
    gl_FragColor = vec4(u_color.r, 0.0, 0.0, 1.0);
}
