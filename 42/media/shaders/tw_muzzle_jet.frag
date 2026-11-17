#version 330

// The muzzle flash (Tarkov MuzzleJet material, shader Custom/Additive,
// disassembled): the texture alpha above the window threshold of the quad
// (vertex alpha) times the texture colour; tint x HDR in the vertex colour.
// A pixel below 0.01 in every channel is dropped. Blend One/One, colour only.

uniform sampler2D DIFFUSE;

in vec4 vColor;
in vec2 vUV1;
in float vDistort;

void main()
{
    vec4 t = texture2D(DIFFUSE, vUV1);
    vec3 c = t.rgb * (t.a - vColor.a);
    if (c.r < 0.01 && c.g < 0.01 && c.b < 0.01)
    {
        discard;
    }
    gl_FragColor = vec4(c * vColor.rgb, 1.0);
}
