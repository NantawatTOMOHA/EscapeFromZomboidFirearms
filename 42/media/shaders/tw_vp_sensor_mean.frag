#version 330
// GLSL 1.20 dialect of PZ's own shaders (gl_FragColor, texture2D): compiles as written and after PZ's GL 2.1
// rewrite of every shader on macOS (ShaderUnit.processShaderSyntax; tools/check_shader_gl21.py).

// Mean brightness of a picture for a device's automatic gain (VpSensorGL.mean).
// Mode 0: a 32 x 32 target, each cell the mean luminance of 4 x 4 samples of
// its part of the source rectangle (uv origin, size); its mipmap then holds
// the mean. Mode 1: the 1 x 1 level into the 1 x 1 average of the view
// (blended by its alpha, the weight Gain: an exponential average in time).
// The 1 x 1 level is level 5: VpSensorGL pins the texture's base level to it
// for this draw (an explicit-LOD read is not GLSL 1.20 in a fragment stage).

uniform sampler2D Input;
uniform float Mode;
uniform vec4 Source;
uniform float Gain;

void main()
{
    if (Mode > 0.5)
    {
        gl_FragColor = vec4(texture2D(Input, vec2(0.5)).rgb, Gain);
        return;
    }
    vec2 cell = floor(gl_FragCoord.xy);
    float s = 0.0;
    for (int j = 0; j < 4; j++)
    {
        for (int i = 0; i < 4; i++)
        {
            vec2 f = (cell + (vec2(i, j) + 0.5) / 4.0) / 32.0;
            vec3 c = texture2D(Input, Source.xy + f * Source.zw).rgb;
            s += dot(c, vec3(0.2126, 0.7152, 0.0722));
        }
    }
    gl_FragColor = vec4(vec3(s / 16.0), 1.0);
}
