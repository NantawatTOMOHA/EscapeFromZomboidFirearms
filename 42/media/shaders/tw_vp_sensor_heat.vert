#version 330

// Heat buffer of a perspective camera in Viewpoint's view (pz.escapefromzomboidfirearms.zb.VpHeat):
// clip positions from the CPU (heat volumes: their screen rectangle at w = 1;
// glows and fires: their corners, clipped by the GPU, perspective-correct uv).
// Each attribute line stands alone (PZ's GL 2.1 rewrite turns only a whole "layout(...) in T x;" line into an
// attribute; tools/check_shader_gl21.py):
//   aUV  u, v, kind, input / 4
//   aA   volume: one end, radius; fire: seed, part
//   aB   volume: other end, floor y
//   aC   ceiling y, depth softness
layout (location = 0) in vec4 aClip;
layout (location = 1) in vec4 aUV;
layout (location = 2) in vec4 aA;
layout (location = 3) in vec4 aB;
layout (location = 4) in vec4 aC;

// vKind.zw, vA, vB and vC are the same at every vertex of a primitive (VpHeat). GLSL 1.30+ keeps them flat, as
// before; GLSL 1.20 (PZ's GL 2.1 rewrite on macOS) has no flat: there they are interpolated, the same values to
// within the rounding of a perspective-correct interpolation.
out vec2 vUV;
#if __VERSION__ >= 130
flat out vec4 vKind;
flat out vec4 vA;
flat out vec4 vB;
flat out vec4 vC;
#else
varying vec4 vKind;
varying vec4 vA;
varying vec4 vB;
varying vec4 vC;
#endif

void main()
{
    vUV = aUV.xy;
    vKind = aUV;
    vA = aA;
    vB = aB;
    vC = aC;
    gl_Position = aClip;
}
