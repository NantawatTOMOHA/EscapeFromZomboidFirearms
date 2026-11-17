#version 330

// Additive muzzle particles: the sparks (Tarkov Custom/SimpleParticleMoveAdd,
// disassembled: texture x tint x HDR x brightness of the streak's lifetime)
// and the glow, flame and streaks of a pivot effect (RShG-2; additive
// particle families: texture colour x its alpha x the vertex colour).
// Blend One/One, colour only.

uniform sampler2D DIFFUSE;

in vec4 vColor;
in vec2 vUV1;
in float vDistort;

void main()
{
    vec4 t = texture2D(DIFFUSE, vUV1);
    gl_FragColor = vec4(t.rgb * t.a * vColor.rgb, 1.0);
}
