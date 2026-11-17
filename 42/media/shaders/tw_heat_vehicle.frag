#version 330

// Heat buffer of TW/OpticView: red = thermal input / 4 over a vehicle's model
// (VehicleHeat). The heat field in world space (tiles; levels are 2.449
// tiles high): the engine bay round its area centre, the warmth it carries
// along the body (hood, then cabin, then boot), the exhaust at the rear and the
// tyres, each scaled by its own warmth (0 cold .. 1 at its running
// temperature). A cold vehicle writes 0: it shows the scene and hides heat
// behind it, as metal and glass do.

in vec2 texCoords;
in vec3 worldPos;

// The game treats a model shader with a TextureRust sampler as a vehicle
// shader and sets its bone and transform matrices (Model.DrawVehicle); Keep is
// always 0.
uniform sampler2D TextureRust;
uniform float Keep;

uniform vec4 Engine;    // centre (x, y, z levels), radius (tiles)
uniform vec4 Exhaust;   // centre, radius
uniform vec4 Wheels[4]; // centre, radius
uniform vec4 Axis;      // forward (x, y), length (tiles), -
uniform vec4 Level;     // warmth of engine, exhaust, tyres; wheel count
uniform vec4 Peaks;     // input / 4 at full warmth: engine, exhaust, tyres, body

vec3 metres(vec3 w)
{
    return vec3(w.xy, w.z * 2.44949);
}

float spot(vec3 d, float r)
{
    return exp(-dot(d, d) / (r * r));
}

void main()
{
    vec3 p = metres(worldPos);
    vec3 e = p - metres(Engine.xyz);
    float hot = Peaks.x * Level.x * spot(e, Engine.w);
    float along = abs(dot(e.xy, Axis.xy));
    hot = max(hot, Peaks.w * Level.x * exp(-max(0.0, along - Engine.w) / (0.35 * Axis.z)));
    hot = max(hot, Peaks.y * Level.y * spot(p - metres(Exhaust.xyz), Exhaust.w));
    for (int i = 0; i < 4; i++)
    {
        if (float(i) < Level.w)
        {
            hot = max(hot, Peaks.z * Level.z * spot(p - metres(Wheels[i].xyz), Wheels[i].w));
        }
    }
    hot += texture2D(TextureRust, texCoords).r * Keep;
    gl_FragColor = vec4(clamp(hot, 0.0, 1.0), 0.0, 0.0, 1.0);
}
