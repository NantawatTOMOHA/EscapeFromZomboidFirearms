#version 330

// The mod's perspective pass in the Viewpoint frame (ViewpointPass): lasers,
// light beams and spots, in Viewpoint's HDR target (before its fog, bloom and
// tone mapping; Gain scales our colours there) or, as the fallback, over its
// finished image (Gain 1), with a soft depth test against Viewpoint's scene
// depth (its G-buffer depth, render resolution).
//   Params.x  0 plain colour, 1 texture x colour, 2 strip (soft profile across v)
//   Params.y  softness of the depth test, scene units: a fragment fades out over
//             this distance in front of the scene surface
//   Params.z  1 with Viewpoint's depth, 0 without (drawn over everything)

uniform sampler2D DIFFUSE;
uniform sampler2D SceneDepth;
uniform vec4 Viewport;      // the output viewport: x, y, width, height
uniform vec2 DepthScale;    // render size / depth texture size
uniform vec2 DepthLinear;   // view distance = x / (ndc z + y) of the frame projection
uniform vec4 Params;
uniform float Gain;        // colour scale (HDR path)

in vec4 vColor;
in vec2 vUVw;
in float vInvW;

void main()
{
    float dist = 1.0 / max(vInvW, 1e-6);
    vec2 uv = vUVw * dist;
    vec4 c = vColor;
    if (Params.x > 1.5) {
        float t = abs(uv.y * 2.0 - 1.0);
        c.a *= 1.0 - t * t;
    } else if (Params.x > 0.5) {
        c *= texture2D(DIFFUSE, uv);
    }
    if (Params.z > 0.5) {
        vec2 st = (gl_FragCoord.xy - Viewport.xy) / Viewport.zw * DepthScale;
        float ndc = texture2D(SceneDepth, st).r * 2.0 - 1.0;
        float scene = DepthLinear.x / (ndc + DepthLinear.y);
        c.a *= clamp((scene - dist) / Params.y, 0.0, 1.0);
    }
    if (c.a <= 0.0) {
        discard;
    }
    gl_FragColor = vec4(c.rgb * Gain, c.a);
}
