#version 330 compatibility
// GLSL 1.20 dialect of PZ's own shaders (gl_FragColor, texture2D): compiles as written and after PZ's GL 2.1
// rewrite of every shader on macOS (ShaderUnit.processShaderSyntax; tools/check_shader_gl21.py).

// W10 item 11: the eyepiece of the camera-zoom picture (ScopePipZoom), over the zoomed main image. The field stop - the
// same eyepiece centred on the sight's axis, as the picture's own field stop shows the scope shadow when the eye leaves
// the axis (tw_pip_composite) - with the picture's vignette inside. Dark scales the whole mask (the dark round the ocular
// as the eye meets it).
// 30.10.2026 (the player, with a Tarkov and a S.T.A.L.K.E.R. frame; W11 nopipzoom had left only a thin ring in both
// values): Housing 0 - eye to the eyepiece (value 3): black everywhere outside the eyepiece, as in Tarkov; Housing > 1 -
// zoom by magnification (value 2): the eyepiece stays where the scope is, black only on its housing ring out to Housing
// eyepiece radii, the zoomed world beyond it.
// W10 nopip2: PictureOn - a pass of its own before the mask: a night vision or thermal device's image (the sensor stage's
// output for the zoomed frame's square PictureBox) inside the eyepiece's shape, read as the picture reads its image (the
// Catmull-Rom of tw_pip_composite); the mask's field stop and vignette then fall on it as on the picture.
uniform float PictureOn;
uniform sampler2D Picture;
uniform vec4 PictureBox;  // GL pixels: x, y, width, height of the device image's square
uniform vec2 PictureSize; // the device image's level 0 in texels, from ScopePipZoom (textureSize is GLSL 1.30+)
uniform vec4 Lens;        // GL pixels: center.xy, first radius vector.xy
uniform vec2 OtherAxis;   // second radius vector.xy
uniform vec2 Field;       // GL pixels: the sight's axis at infinity
uniform float Dark;
uniform float Housing;    // 0: black outside the eyepiece; > 1: the housing ring's outer radius in eyepiece radii
uniform int GlassCount;
uniform vec2 GlassBoundary[64];

vec2 local(vec2 at) {
    vec2 d = at - Lens.xy;
    float det = Lens.z * OtherAxis.y - Lens.w * OtherAxis.x;
    return vec2(OtherAxis.y * d.x - OtherAxis.x * d.y, -Lens.w * d.x + Lens.z * d.y) / det;
}

// 0 inside the eyepiece's shape, 1 outside, a pixel's soft edge.
float outside(vec2 p) {
    if (GlassCount > 0) {
        bool inside = false;
        vec2 a = GlassBoundary[GlassCount - 1];
        for (int i = 0; i < GlassCount; ++i) {
            vec2 b = GlassBoundary[i];
            if ((a.y > p.y) != (b.y > p.y)
                && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside;
            a = b;
        }
        return inside ? 0.0 : 1.0;
    }
    float r = length(p);
    float pixel = max(fwidth(r), 0.0001);
    return smoothstep(1.0 - pixel, 1.0, r);
}

// tw_pip_composite's sharp(): the 4x4 Catmull-Rom weights folded into 9 bilinear taps (level 0, no mipmaps: the image
// has one level and the same filter both ways, so texture2D reads it as the explicit level 0 did).
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
    vec2 p = local(gl_FragCoord.xy);
    if (PictureOn > 0.5) {
        float inside = 1.0 - outside(p);
        if (inside <= 0.0) discard;
        gl_FragColor = vec4(sharp(Picture, (gl_FragCoord.xy - PictureBox.xy) / PictureBox.zw, PictureSize), inside * clamp(Dark, 0.0, 1.0));
        return;
    }
    vec2 q = p - local(Field);
    float shade = max(outside(p), outside(q));
    float r = length(p);
    float vignette = 0.08 * r * r * r * r;
    float alpha = 1.0 - (1.0 - shade) * (1.0 - min(vignette, 1.0));
    if (Housing > 0.0) alpha *= 1.0 - outside(p / Housing);
    gl_FragColor = vec4(0.0, 0.0, 0.0, clamp(alpha * Dark, 0.0, 1.0));
}
