#version 330

// Muzzle smoke (Tarkov Fume system, shader Particles/Smoke Distorted,
// disassembled) and the barrel smoke trail (Custom/MuzzleSmoke). Three
// scrolling samples of the noise texture move the texture coordinate by
// (sum - 1.5) x vertex distortion x _Distortion; colour = texture x vertex
// colour (the scene light at the particle x its brightness), alpha = texture
// alpha x vertex alpha. The trail has no distortion. Alpha blend.

uniform sampler2D DIFFUSE;
uniform sampler2D NoiseTex;
uniform float Scroll;
uniform float Distortion;

in vec4 vColor;
in vec2 vUV1;
in float vDistort;

void main()
{
    vec2 uv = vUV1;
    if (Distortion > 0.0)
    {
        vec2 n = texture2D(NoiseTex, uv + vec2(0.0, 0.96 * Scroll)).xy
               + texture2D(NoiseTex, uv + Scroll * vec2(0.866, -0.5)).yz
               + texture2D(NoiseTex, uv + 1.03 * Scroll * vec2(-0.866, -0.5)).zw
               - 1.5;
        uv += n * vDistort * Distortion;
    }
    vec4 t = texture2D(DIFFUSE, uv);
    gl_FragColor = vec4(t.rgb * vColor.rgb, t.a * vColor.a);
}
