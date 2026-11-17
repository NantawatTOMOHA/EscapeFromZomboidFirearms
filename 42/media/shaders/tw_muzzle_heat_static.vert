#version 330

// The visible heat of a hot weapon part on its own surface (MuzzleHeatModel, Tarkov HotObject /
// USEHEAT), isometric: the part drawn again right after the game drew it, with the game's
// basicEffect_static.vert transform and depth unchanged (FinalScale, HighResDepthMultiplier,
// targetDepth), pulled DepthPull toward the camera so the same surface passes GL_LEQUAL; the position
// in the part's exported mesh space for its HeatBounds.

layout (location = 0) in vec4 vertex;
layout (location = 1) in vec4 normal;
layout (location = 2) in vec2 uv;

out vec2 texCoords;
out vec2 atlasUV;
out vec3 meshPos;

uniform mat4 ModelViewProjection;
uniform mat4 transform;
uniform float targetDepth = 0.5;
uniform vec2 UVScale;
uniform float HighResDepthMultiplier = 0.0;
uniform float FinalScale = 1.0;
uniform mat4 MeshFrame;
uniform float DepthPull = 0.00001;

void main()
{
	vec4 position = vec4(vertex.xyz, 1);
	texCoords = uv.st * UVScale.xy;
	atlasUV = uv.st;
	meshPos = (MeshFrame * position).xyz;

	vec4 positionScaled = transform * position;
	positionScaled.xyz *= FinalScale;
	vec4 o = ModelViewProjection * positionScaled;

	vec4 origin = ModelViewProjection * vec4(0, 0, 0, 1);
	o.z += (origin.z - o.z) * HighResDepthMultiplier;

	float clip = ((o.z+1.0) / 2.0);
	clip += targetDepth - 0.5;
	o.z = (clip*2)-1;
	o.z -= DepthPull * o.w;

	gl_Position = o;
}
