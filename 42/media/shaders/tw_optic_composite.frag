#version 330

// The scope image over the screen inside the scope's field (soft edge);
// outside it the ordinary view stays. The image is read where the world point
// under this pixel was when the image was drawn (ImageX/ImageY: world floor ->
// its texel): renewed at the scope's frame rate, it still stays on the world
// while the camera pans or zooms.
// The field is the cone on the floor. Warm things standing in it (bodies,
// flames, the hot gas above a fire) show whole: a warm pixel belongs to it
// when a point up to Rise levels below it stands on the floor inside the
// cone, faded in with its heat so the thin edge of the gas blends into the
// world outside. A point h levels up lies on the floor map 3h tiles back
// along (-1, -1). The viewer's own spot is left out of the rise.
// Muzzle effects standing in the field (TW MuzzleFx: flash, sparks, smoke, by
// the point under them on the floor; the viewer's own near the apex) show
// whole too: FieldTex holds them alone on black, light in the colour and smoke
// in the alpha.

uniform sampler2D ImageTex;
uniform sampler2D HeatTex;
uniform vec2 WorldSize;
uniform vec3 MapX;
uniform vec3 MapY;
uniform vec4 Cone;
uniform float Edge;
uniform float Rise;
uniform vec3 ImageX;
uniform vec3 ImageY;
uniform vec4 ImageView;
// QA only: see-through share of the image over the world (0 in play).
uniform float DebugMix;
uniform sampler2D FieldTex;
uniform float FieldOn;

in vec4 vColor;
in vec2 vUV1;

float weight(vec2 world)
{
    vec2 d = world - Cone.xy;
    float a = abs(mod(atan(d.y, d.x) - Cone.z + 3.14159265, 6.28318531) - 3.14159265);
    return 1.0 - smoothstep(Cone.w, Cone.w + Edge, a);
}

// The best weight of the bases below a pixel: the segment from its floor
// point 3*rise tiles along (1, 1). Along a line the bearing from the apex
// changes one way, so the best is at an end or where it crosses the axis of
// the cone (then 1).
float raised(vec2 world, float rise)
{
    vec2 p0 = world - Cone.xy;
    vec2 dv = vec2(3.0 * rise);
    vec2 axis = vec2(cos(Cone.z), sin(Cone.z));
    float den = dv.x * axis.y - dv.y * axis.x;
    if (abs(den) > 1e-6)
    {
        float s = -(p0.x * axis.y - p0.y * axis.x) / den;
        if (s > 0.0 && s <= 1.0 && dot(p0 + s * dv, axis) > 0.6)
        {
            return 1.0;
        }
    }
    return length(p0 + dv) > 0.6 ? weight(world + dv) : 0.0;
}

void main()
{
    vec2 texel = vUV1 * WorldSize;
    vec2 world = vec2(dot(MapX, vec3(texel, 1.0)), dot(MapY, vec3(texel, 1.0)));
    float mask = length(world - Cone.xy) < 0.3 ? 1.0 : weight(world);
    if (FieldOn > 0.5)
    {
        vec4 fx = texture2D(FieldTex, vUV1);
        mask = max(mask, clamp(max(fx.a, 2.0 * max(fx.r, max(fx.g, fx.b))), 0.0, 1.0));
    }
    if (mask < 1.0 && Rise > 0.0)
    {
        float heat = texture2D(HeatTex, vUV1).r;
        if (heat > 0.0)
        {
            mask = max(mask, raised(world, Rise) * smoothstep(0.0, 0.12, heat));
        }
    }
    if (mask <= 0.0)
    {
        discard;
    }
    vec2 drawn = vec2(dot(ImageX, vec3(world, 1.0)), dot(ImageY, vec3(world, 1.0)));
    drawn = clamp(drawn, ImageView.xy + 0.5, ImageView.xy + ImageView.zw - 0.5);
    gl_FragColor = vec4(texture2D(ImageTex, drawn / WorldSize).rgb, mask * (1.0 - 0.5 * DebugMix));
}
