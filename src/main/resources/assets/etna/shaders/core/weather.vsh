#version 330
#extension GL_ARB_separate_shader_objects : require

// Based on Minecraft's vanilla shader, (c) Mojang AB, not covered by Etna's MIT licence (CREDITS.md).
// Etna's rain and snow vertex shader, made from vanilla 26.4's particle shader. Only Etna's copies of the
// weather pipelines use it (EtnaWeather.java), and only while Etna is on and the rain look is Etna's: with
// Etna off the game draws rain and snow with its own pipelines and shaders.
//
// The columns are vanilla's, built the same way every frame (WeatherEffectRenderer): one upright quad per
// block column around the camera, its texture scrolling down. What changes:
// * the light. Vanilla multiplies the column's lightmap colour into the vertex colour; here the sky's part
//   of it takes the colour of Etna's skylight (blue by day, grey in rain, navy at night: the model the land
//   is lit by) and the part torches, lava and glowstone add takes their flame colour, so rain falling past
//   a torch glows orange as the ground under it does. The sun (or the moon) catching the drops is passed
//   on separately: weather.fsh adds it where the drops are seen against the light;
// * the wind. Columns lean the way terrain.vsh's wind blows, a little more in its gusts, so the rain slants
//   with the swaying leaves and the slant moves across the land with each gust;
// * which of the two a quad is, rain or snow, so weather.fsh can thin the rain and soften the snow.

// Etna's terrain block (terrain_ubo.glsl), bound to the copies by EtnaWeather.bind.
#define ETNA_WEATHER_LIGHT

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <etna:terrain_ubo.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec4 vertexColor;    // rgb: the light on the drops from the sky and from blocks, a: vanilla's alpha
layout(location = 4) out vec3 weatherSun;     // the sun's (or moon's) light on drops under the open sky, colour times strength
layout(location = 5) out vec3 weatherViewPos; // relative to the camera, in blocks
layout(location = 6) flat out float weatherRain; // 1 on a rain column, 0 on a snow column

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

// The wind of terrain.vsh, with the same numbers so the rain leans with the leaves (keep the two in step):
// it blows towards WIND in gusts about 16 blocks deep and 28 wide that it carries across the land at 4
// blocks a second, repeating every GUST_CELLS patches along the wind so it carries on unbroken when
// GameTime starts again.
const vec2 WIND = vec2(0.9285, 0.3714);
const vec2 GUST_SIZE = vec2(16.0, 28.0);
const float GUST_SPEED = 4.0;
const float GUST_CELLS = 300.0;

// How far a column leans, in blocks sideways per block of height: rain a little in a lull and more in the
// strongest gust; snow drifts further, as it falls slower. The lean pivots at the camera's height, so the
// part of a column at eye level stays in its own block column and rain never reaches far under a roof.
const float RAIN_LEAN = 0.06;
const float RAIN_GUST_LEAN = 0.16;
const float SNOW_LEAN = 0.10;
const float SNOW_GUST_LEAN = 0.22;

// Torch light on the drops counts a little more than on the ground: water drops scatter the light that
// reaches them towards the eye, so rain around a lantern glows.
const float RAIN_TORCH_GAIN = 1.25;

// terrain.vsh's gust pattern: a random value 0 .. 1 per cell (the integer hash "lowbias32", after Chris
// Wellons), smoothed from cell to cell.
float gustCell(vec2 cell) {
    uint h = uint(int(mod(cell.x, GUST_CELLS))) | (uint(int(cell.y)) << 9);
    h ^= h >> 16;
    h *= 0x7feb352du;
    h ^= h >> 15;
    h *= 0x846ca68bu;
    h ^= h >> 16;
    return float(h) * (1.0 / 4294967296.0);
}

float windGust(vec2 xz, float time) {
    float along = dot(xz, WIND);
    float across = dot(xz, vec2(-WIND.y, WIND.x));
    vec2 p = vec2(along - GUST_SPEED * time, across) / GUST_SIZE + vec2(0.0, 0.3 * sin(along / 37.0));
    vec2 cell = floor(p);
    vec2 f = p - cell;
    f *= f * (3.0 - 2.0 * f);
    float near = mix(gustCell(cell), gustCell(cell + vec2(1.0, 0.0)), f.x);
    float far = mix(gustCell(cell + vec2(0.0, 1.0)), gustCell(cell + vec2(1.0, 1.0)), f.x);
    return mix(near, far, f.y);
}

// terrain.fsh's torch colour: a flame, yellow-white close to the source and deepening to orange as it
// fades, at the brightness of vanilla's block light, as far as the torch warmth setting takes it (less in
// dimensions without a sun, which are lit by little else).
vec3 torchColor(vec3 vanilla, float level) {
    float warmth = ETNA_TORCH_WARMTH * mix(0.35, 1.0, ETNA_SUN_WORLD);
    vec3 flame = mix(vec3(1.0, 0.60, 0.30), vec3(1.0, 0.86, 0.66), smoothstep(0.45, 0.95, level));
    flame /= dot(flame, LUMA);
    return mix(vanilla, flame * dot(vanilla, LUMA), warmth);
}

// terrain.fsh's sunColor with Etna's sky model: the light the land gets from the sun (or the moon), at the
// sky model's colour while it has one, else a fixed gold-to-white or moon blue; luma about 1.
vec3 sunColor() {
    if (ETNA_SUN_WORLD > 0.5 && ETNA_SKY_MODEL) {
        return ETNA_SKY_SUNLIGHT;
    }
    return ETNA_MOON ? vec3(0.62, 0.74, 1.0) : mix(vec3(1.0, 0.68, 0.40), vec3(1.0, 0.97, 0.92), smoothstep(0.04, 0.45, ETNA_LIGHT_DIR.y));
}

void main() {
    // Rain columns are drawn with u running exactly from 0 to 1 (WeatherEffectRenderer gives them no u
    // offset); snow columns start at a random offset that drifts with time, which is never a whole number.
    float rain = UV0.x == 0.0 || UV0.x == 1.0 ? 1.0 : 0.0;

    // GameTime is the time of day from 0 to 1 over 20 minutes; in seconds. The world position as terrain.vsh
    // has it: the camera-relative position, moved by the camera's block and the offset within it.
    float time = GameTime * 1200.0;
    vec3 world = Position + vec3(CameraBlockPos) - CameraOffset;
    float gust = windGust(world.xz, time);
    float lean = rain > 0.5 ? RAIN_LEAN + RAIN_GUST_LEAN * gust : SNOW_LEAN + SNOW_GUST_LEAN * gust;
    // Drops come from upwind: the top of a column lies upwind of its foot.
    vec3 pos = Position - vec3(WIND.x, 0.0, WIND.y) * (lean * Position.y);

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    texCoord0 = UV0;
    weatherViewPos = pos;
    weatherRain = rain;

    // Vanilla's light for this column, split as terrain.vsh splits it: what the sky gives alone, and what
    // block light adds on top (sample_lightmap, with the column's whole light levels).
    vec3 total = sample_lightmap(Sampler2, UV2).rgb;
    vec3 skyOnly = sample_lightmap(Sampler2, ivec2(0, UV2.y)).rgb;
    vec3 blocks = max(total - skyOnly, 0.0);
    // The sky's part at vanilla's brightness, in the colour of Etna's skylight.
    vec3 sky = ETNA_SUN_WORLD > 0.5 && ETNA_SKY_MODEL ? ETNA_SKY_SKYLIGHT * dot(skyOnly, LUMA) : skyOnly;
    float blockLevel = clamp(float(UV2.x) / 240.0, 0.0, 1.0);
    vec3 light = sky + torchColor(blocks, blockLevel) * (rain > 0.5 ? RAIN_TORCH_GAIN : 1.0);
    vertexColor = vec4(Color.rgb * light, Color.a);

    // The sun reaches the drops where the column is open to the sky (the light is sampled at the top of
    // the ground there, or at the camera's height); gone at sky light 10 and below.
    float open = smoothstep(10.0 / 15.0, 1.0, float(UV2.y) / 240.0);
    weatherSun = sunColor() * (ETNA_LIGHT_STRENGTH * ETNA_SUN_WORLD * open);
}
