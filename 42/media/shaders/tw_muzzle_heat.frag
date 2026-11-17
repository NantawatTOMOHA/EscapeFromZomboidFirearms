#version 330

// Tarkov's USEHEAT on a hot weapon part (MuzzleHeatModel), isometric, added to the part just drawn:
// per HotObject q = Q x p in the part's mesh space ((p - _HeatCenter) / _HeatSize), w = max(0, 1 - |q|)
// x ((T - 30) / 10)^2, colour lerp(_HeatColor1, _HeatColor2, w) x w x specular term x VisibleHeatAlpha x
// HeatVisibleFactor, on the HotObject's own sub-mesh (its atlas rectangle) only; the game's texture
// cut-out (basicEffect: alpha < 0.01) and the character's fade.

in vec2 texCoords;
in vec2 atlasUV;
in vec3 meshPos;

uniform sampler2D Texture;
uniform float Alpha = 1.0;
// Per box: rows 0..2 of Q, c1 + specular term, c2 + VisibleHeatAlpha x HeatVisibleFactor, the atlas rectangle.
uniform vec4 Boxes[48];
uniform float Count;
uniform float Level;
// > 0: this pass only raises the surface under the glow to the screen grade's black point (GL_MAX blending).
uniform float Lift = 0.0;

void main()
{
	if (texture2D(Texture, texCoords).a < 0.01)
	{
		discard;
	}
	vec4 p = vec4(meshPos, 1.0);
	vec3 heat = vec3(0.0);
	for (int i = 0; i < 8; i++)
	{
		if (float(i) >= Count)
		{
			break;
		}
		vec4 rect = Boxes[i * 6 + 5];
		if (atlasUV.x < rect.x || atlasUV.y < rect.y || atlasUV.x > rect.z || atlasUV.y > rect.w)
		{
			continue;
		}
		vec3 q = vec3(dot(Boxes[i * 6], p), dot(Boxes[i * 6 + 1], p), dot(Boxes[i * 6 + 2], p));
		float w = max(0.0, 1.0 - length(q)) * Level;
		heat += mix(Boxes[i * 6 + 3].rgb, Boxes[i * 6 + 4].rgb, w) * w * Boxes[i * 6 + 3].a * Boxes[i * 6 + 4].a;
	}
	if (max(heat.r, max(heat.g, heat.b)) < 0.002)
	{
		discard;
	}
	if (Lift > 0.0)
	{
		gl_FragColor = vec4(vec3(Lift), 0.0);
		return;
	}
	gl_FragColor = vec4(heat * Alpha, 0.0);
}
