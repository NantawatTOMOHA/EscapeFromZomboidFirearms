#version 330 compatibility
// GLSL 1.20 dialect of PZ's own shaders (gl_FragColor, texture2D): compiles as written and after PZ's GL 2.1
// rewrite of every shader on macOS (ShaderUnit.processShaderSyntax; tools/check_shader_gl21.py).

uniform sampler2D ScopeImage;
uniform sampler2D SceneDepth;
uniform vec4 Lens;        // GL pixels: center.xy, first radius vector.xy
uniform vec3 OtherAxis;   // second radius vector.xy, projected lens depth
uniform vec2 DepthSlope;  // window-depth change along the two projected axes
uniform int GlassCount;
uniform vec2 GlassBoundary[64];
uniform vec4 Viewport;
uniform float DepthOn;
uniform vec2 ImageOrigin; // optical ray in the unchanged physical ellipse's local basis
uniform vec4 ImageBasis = vec4(1.0, 0.0, 0.0, 1.0); // physical glass axes -> camera right/up
uniform float Bilinear;   // QA (A/B in one run): 1 - one bilinear tap, as before the eyepiece-sized picture
uniform vec2 ImageSize;    // ScopeImage's level 0 in texels, from ScopePip (textureSize is GLSL 1.30+)

// The user's performance question (20.10.2026): the picture is rendered at about the eyepiece's own size on the screen
// (ScopePipGeometry.lensSize, ~1.25 texels a pixel). One bilinear tap there spreads a texel over ~1.5 pixels and softens
// it; the Catmull-Rom spline (its 4x4 weights folded into 9 bilinear taps) reconstructs it as sharp as the twice larger
// picture looked. Linear filtering, clamped to the edge (ScopePip's sampler).
vec3 sharp(sampler2D image, vec2 uv, vec2 size) {
    vec2 at = uv * size;
    vec2 base = floor(at - 0.5) + 0.5;
    vec2 f = at - base;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 wm = w1 + w2;
    vec2 a = (base - 1.0) / size, m = (base + w2 / wm) / size, b = (base + 2.0) / size;
    vec3 c = (texture2D(image, vec2(a.x, a.y)).rgb * w0.x + texture2D(image, vec2(m.x, a.y)).rgb * wm.x
            + texture2D(image, vec2(b.x, a.y)).rgb * w3.x) * w0.y
        + (texture2D(image, vec2(a.x, m.y)).rgb * w0.x + texture2D(image, vec2(m.x, m.y)).rgb * wm.x
            + texture2D(image, vec2(b.x, m.y)).rgb * w3.x) * wm.y
        + (texture2D(image, vec2(a.x, b.y)).rgb * w0.x + texture2D(image, vec2(m.x, b.y)).rgb * wm.x
            + texture2D(image, vec2(b.x, b.y)).rgb * w3.x) * w3.y;
    return max(c, vec3(0.0));
}

void main() {
    vec2 d = gl_FragCoord.xy - Lens.xy;
    float det = Lens.z * OtherAxis.y - Lens.w * OtherAxis.x;
    vec2 p = vec2(OtherAxis.y * d.x - OtherAxis.x * d.y, -Lens.w * d.x + Lens.z * d.y) / det;
    float r = GlassCount > 0 ? max(abs(p.x), abs(p.y)) : length(p);
    if (GlassCount > 0) {
        bool inside = false;
        vec2 a = GlassBoundary[GlassCount - 1];
        for (int i = 0; i < GlassCount; ++i) {
            vec2 b = GlassBoundary[i];
            if ((a.y > p.y) != (b.y > p.y)
                && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside;
            a = b;
        }
        if (!inside) discard;
    } else if (r >= 1.0) discard;
    vec2 mainUV = (gl_FragCoord.xy - Viewport.xy) / Viewport.zw;
    if (DepthOn > 0.5 && texture2D(SceneDepth, mainUV).r + 0.00002 < OtherAxis.z + dot(DepthSlope, p)) discard;
    // Keep r for the physical mask/vignette; only the source picture moves.
    p -= ImageOrigin;
    p = vec2(dot(ImageBasis.xy, p), dot(ImageBasis.zw, p));
    // FB5 item 8 (20.10.2026: "entering ADS the scope picture is stretched at the sides for a second"): while the eye
    // is still off the scope's axis (the raise, a kick) part of the eyepiece looks past the rendered field, and the
    // edge texels were repeated across it (clamp to edge). Past the field there is no picture: the field stop's black,
    // as a real scope shows it (the scope shadow).
    float field = GlassCount > 0 ? max(abs(p.x), abs(p.y)) : length(p);
    float stop = 1.0 - smoothstep(1.0 - 2.0 * max(fwidth(field), 0.0001), 1.0, field);
    vec3 color = (Bilinear > 0.5 ? texture2D(ScopeImage, p * 0.5 + 0.5).rgb : sharp(ScopeImage, p * 0.5 + 0.5, ImageSize)) * stop;
    float pixel = max(fwidth(r), 0.0001);
    float edge = GlassCount > 0 ? 1.0 : 1.0 - smoothstep(1.0 - pixel, 1.0, r);
    // The authored eye point is an ADS datum, not a measured exit pupil.
    // Only the proven ocular aperture clips the picture; pose mismatch must
    // not multiply an otherwise valid second render by zero.
    float vignette = 1.0 - 0.08 * r * r * r * r;
    gl_FragColor = vec4(color * vignette, edge);
}
