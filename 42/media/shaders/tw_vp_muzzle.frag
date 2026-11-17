#version 330

// Our existing Tarkov effect formulas (tw_muzzle_*) in the perspective pass.
// Params.x: 0 smoke, 1 jet window, 2 additive particle/heat, 3 smoke ribbon, 4 QA marker.
// Smoke: distortion including the particle's G channel, animated noise time.
uniform sampler2D DIFFUSE;
uniform sampler2D NoiseTex;
uniform sampler2D SceneDepth;
uniform vec4 Viewport;
uniform vec2 DepthScale;
uniform vec2 DepthLinear;
uniform vec4 Params;
uniform vec2 Smoke;
uniform float Gain;
in vec4 vColor;
in vec2 vUVw;
in float vInvW;

void main()
{
    float dist = 1.0 / max(vInvW, 1e-6);
    vec2 uv = vUVw * dist;
    vec4 col = vColor * dist;
    // Only grenade smoke uses negative U. Dense source particles and an
    // inside-cloud veil share the same run; ordinary muzzle UVs stay unchanged.
    bool insideSmoke = Params.x < 0.5 && uv.x < -4.0;
    bool denseSmoke = Params.x < 0.5 && uv.x < -1.0;
    if (denseSmoke) { uv.x += insideSmoke ? 6.0 : 3.0; }
    if (Params.x < 0.5 && Smoke.x > 0.0) {
        vec2 n = texture2D(NoiseTex, uv + vec2(0.0, 0.96 * Smoke.y)).xy
               + texture2D(NoiseTex, uv + Smoke.y * vec2(0.866, -0.5)).yz
               + texture2D(NoiseTex, uv + 1.03 * Smoke.y * vec2(-0.866, -0.5)).zw - 1.5;
        uv += n * Smoke.x;
    }
    float fade = 1.0;
    if (Params.z > 0.5) {
        vec2 st = (gl_FragCoord.xy - Viewport.xy) / Viewport.zw * DepthScale;
        float ndc = texture2D(SceneDepth, st).r * 2.0 - 1.0;
        float scene = DepthLinear.x / (ndc + DepthLinear.y);
        fade = clamp((scene - dist) / Params.y, 0.0, 1.0);
    }
    if (fade <= 0.0) { discard; }
    // QA marker: the same geometry/projection/depth, an opaque procedural material.
    if (Params.x > 3.5) {
        gl_FragColor = vec4(col.rgb * Gain, col.a * fade);
        return;
    }
    vec4 texel = texture2D(DIFFUSE, uv);
    vec3 rgb;
    float alpha;
    if (Params.x > 0.5 && Params.x < 1.5) {
        vec3 windowed = texel.rgb * (texel.a - col.a);
        if (all(lessThan(windowed, vec3(0.01)))) { discard; }
        rgb = windowed * col.rgb * fade;
        alpha = 1.0;
    } else if (Params.x > 1.5 && Params.x < 2.5) {
        rgb = texel.rgb * texel.a * col.rgb * fade;
        alpha = 1.0;
    } else if (denseSmoke) {
        rgb = insideSmoke ? col.rgb * (0.5 + 0.5 * texel.a) : texel.rgb * col.rgb;
        alpha = (insideSmoke ? 1.0 : clamp(texel.a * 8.0, 0.0, 1.0)) * col.a * fade;
    } else {
        rgb = texel.rgb * col.rgb;
        alpha = texel.a * col.a * fade;
    }
    gl_FragColor = vec4(rgb * Gain, alpha);
}
