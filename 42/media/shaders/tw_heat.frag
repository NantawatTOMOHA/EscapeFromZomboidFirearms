#version 330

// Heat buffer of TW/OpticView: red = thermal input / 4 where the heat
// silhouette is (its alpha); combined with GL_MAX.

uniform sampler2D DIFFUSE;

in vec4 vColor;
in vec2 vUV1;

void main()
{
    float a = texture2D(DIFFUSE, vUV1).a;
    if (a < 0.01)
    {
        discard;
    }
    gl_FragColor = vec4(vColor.r * a, 0.0, 0.0, a);
}
