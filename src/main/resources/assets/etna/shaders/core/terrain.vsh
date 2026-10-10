#version 330
#extension GL_ARB_separate_shader_objects : require

// Based on Minecraft's vanilla shader, (c) Mojang AB, not covered by Etna's MIT licence (CREDITS.md).
// Etna's terrain vertex shader, made from vanilla 26.4's. Only Etna's copies of the terrain pipelines use it
// (EtnaShadows), and only while Etna is on: with Etna off the game draws with its own shader.
// Adds leaves and plants waving in a gusty wind, lets the light held in the player's hand into vanilla's
// light, and passes world data to terrain.fsh for water, sun and torch light.
// Leaves and plants are recognised by a tag Etna puts in as chunks are built (EtnaFoliage.java): the alpha
// of their vertex colour, which is always 1 in vanilla's chunks.
// ETNA_SHADOW_PASS is the depth-only draw into the shadow map: it only needs the position, and for the
// alpha test of cutout terrain the texture coordinates and the vertex alpha, so it writes nothing else.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif
#include <etna:terrain_ubo.glsl>
#include <etna:terrain_touch.glsl>
#ifdef ETNA_SHADOW_PASS
#include <etna:shadow_distortion.glsl>
#endif
#include <etna:terrain_water_vertex.glsl>
#include <etna:terrain_dark_vertex.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
layout(location = 4) in ivec3 ChunkPosition;
layout(location = 5) in float ChunkVisibility;
#endif

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler2;
#endif

#ifdef ETNA_SHADOW_PASS
#ifdef ALPHA_CUTOUT
// xy: the texture coordinates, z: the vertex alpha, for the alpha test. Solid terrain has no alpha test
// and passes nothing at all.
layout(location = 0) out vec3 etnaShadowCutout;
#endif
#else
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out float chunkVisibility;
layout(location = 5) out vec3 etnaViewPos;
layout(location = 6) out vec3 etnaWorldPos;
layout(location = 7) out float etnaMaterial; // 0 other, 1 leaves, 2 plant (2.25 on faces vanilla shades), 3 water
layout(location = 8) out float etnaSkyLight;
layout(location = 9) out vec3 etnaLightmap;   // vanilla's light for this vertex (sky and block, time of day, gamma)
layout(location = 10) out vec3 etnaTorchLight; // the part of etnaLightmap that torches, lava and other glowing blocks add to the sky's
layout(location = 11) out float etnaDaylight; // brightness of the open sky right now (time of day, weather, dimension)
// x: block light level, y: sky light level (both 0..1), z: how much of the light on the sky's side is
// really sky rather than the dimension's ambient floor or night vision (0 in caves, 1 in the open).
layout(location = 12) out vec3 etnaLightLevels;
#endif

// The tags in the vertex alpha, as EtnaFoliage puts them in (out of 255): leaves, and a plant's vertex with
// how far it sways in sixteenths, from TAG_PLANT (its roots, which stay) to TAG_PLANT + 16 (free), on a
// face vanilla shades as if it faced up; from TAG_PLANT_SHADED on one it shades by the way it faces.
const int TAG_LEAVES = 254;
const int TAG_PLANT = 237;
const int TAG_PLANT_SHADED = 220;
// Leaves sway 7/16 as far as a plant's free end, which EtnaFoliage gives the edge of a plant hanging from leaves.
const float PLANT_SWAY = 0.11;
const float LEAF_SWAY = PLANT_SWAY * 7.0 / 16.0;

// GameTime starts again every 24000 ticks (20 minutes). Every motion below repeats a whole number of times
// in that span (its rate a multiple of LOOP, in radians a second), so foliage never jumps when it does.
const float LOOP = 6.2831853 / 1200.0;

// The wind goes towards WIND (east by a little south: it comes from the west-north-west), and it blows in
// gusts: patches of stronger and weaker wind, about 16 blocks deep and 28 wide, which it carries across
// the land at 4 blocks a second, so a meadow ripples as each one passes and a forest sways in waves rather
// than all at once. Along the wind the pattern repeats every GUST_CELLS patches (4800 blocks), the
// distance the wind covers in 20 minutes, so it carries on unbroken when GameTime starts again. Only
// GameTime and the world position go in: the shadow pass, which has no Etna block, moves its leaves
// exactly like the main pass.
const vec2 WIND = vec2(0.9285, 0.3714);
const vec2 GUST_SIZE = vec2(16.0, 28.0);
const float GUST_SPEED = 4.0;
const float GUST_CELLS = 300.0;

// A random value 0 .. 1 for each cell of the gust pattern (the integer hash "lowbias32", after Chris
// Wellons). The cell along the wind, below GUST_CELLS, takes the key's low 9 bits.
float gustCell(vec2 cell) {
    uint h = uint(int(mod(cell.x, GUST_CELLS))) | (uint(int(cell.y)) << 9);
    h ^= h >> 16;
    h *= 0x7feb352du;
    h ^= h >> 15;
    h *= 0x846ca68bu;
    h ^= h >> 16;
    return float(h) * (1.0 / 4294967296.0);
}

// How hard the wind blows here and now: 0 (calm) .. 1 (the strongest gust), changing smoothly from cell
// to cell (value noise). A slow wave bends the rows of cells, so they don't line up across the land.
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

// Leaves and plants in the wind: a quick flutter, a third as lively in a lull as in the strongest gust and
// on average as lively as before 0.7.0, and the gust itself bending them the way the wind goes.
vec3 waveOffset(vec3 world, float time, float amplitude) {
    float gust = windGust(world.xz, time);
    float phase = dot(world, vec3(0.63, 0.31, 0.77));
    float x = sin(time * (363.0 * LOOP) + phase) + 0.4 * sin(time * (707.0 * LOOP) + phase * 1.7);
    float z = cos(time * (306.0 * LOOP) + phase * 1.3) + 0.4 * cos(time * (783.0 * LOOP) + phase * 0.9);
    // A little up and down too, from the two sways: no third wave to work out.
    float y = 0.12 * x * z;
    vec3 flutter = vec3(x, y, z) * (0.3 + 0.6 * gust);
    vec3 lean = vec3(WIND.x, 0.0, WIND.y) * (0.7 * gust * gust);
    return (flutter + lean) * amplitude;
}

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    vec3 world = Position + vec3(ChunkPosition);
    // GameTime is the time of day from 0 to 1 over 20 minutes; convert to seconds.
    float time = GameTime * 1200.0;

    float material = 0.0;
    // The tag goes back to an alpha of 1, as vanilla has it, for the alpha test and everything after.
    vec4 color = Color;
    int tag = int(Color.a * 255.0 + 0.5);
    if (tag == TAG_LEAVES) {
        material = 1.0;
        color.a = 1.0;
        pos += waveOffset(world, time, LEAF_SWAY);
    } else if (tag >= TAG_PLANT_SHADED && tag < TAG_LEAVES) {
        // 2 on a face vanilla shaded as if it faced up, 2.25 on one it shaded by the way it faces (terrain.fsh).
        bool asUp = tag >= TAG_PLANT;
        material = asUp ? 2.0 : 2.25;
        color.a = 1.0;
        float sway = float(tag - (asUp ? TAG_PLANT : TAG_PLANT_SHADED)) / 16.0;
        if (sway > 0.0) {
            pos += waveOffset(world, time, PLANT_SWAY * sway);
        }
    } else if (etnaIsWater(Color)) {
        material = 3.0;
    }

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    #ifdef ETNA_SHADOW_PASS
    // Orthographic, so w is 1.
    gl_Position.xy /= etnaShadowFactor(gl_Position.xy);
    #ifdef ALPHA_CUTOUT
    // The alpha test is all the shadow map needs: no fog distances, light or world data.
    etnaShadowCutout = vec3(UV0, color.a);
    #endif
    #else
    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    // Vanilla multiplies the lightmap into the vertex colour here. Etna keeps the two apart: the
    // fragment shader undoes vanilla's fixed per-face shading and lights sky-lit surfaces itself.
    vertexColor = color;
    texCoord0 = UV0;

    const float chunkFullyVisibleRange = 16.0;
    float dist = length(pos);
    chunkVisibility = mix(1.0, ChunkVisibility, clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0, 1.0));

    etnaViewPos = pos;
    etnaWorldPos = world;
    etnaMaterial = material;
    #ifndef OIT_ALPHA_ONLY
    const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
    // The light held in the player's hand counts as one more light from a block: where it is brighter than
    // the blocks' own light it takes its place, so it never adds up to more than a torch placed in the hand
    // would give. Vanilla's lightmap then makes of it what it makes of any block light (its curve, the
    // brightness setting, night vision, Darkness), and by day under the open sky it adds next to nothing,
    // as a torch does. etnaLightLevels.x keeps the blocks' own level, which says where blocks glow.
    vec2 light = vec2(UV2);
    #ifdef ETNA_SHADOWS
    light.x = max(light.x, 16.0 * etnaHeldLevel(pos));
    #endif
    // sample_lightmap with a level between two whole ones allowed: the lightmap is filtered linearly.
    etnaLightmap = texture(Sampler2, clamp(light / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
    vec3 skyOnly = sample_lightmap(Sampler2, ivec2(0, UV2.y)).rgb;
    vec3 floorOnly = sample_lightmap(Sampler2, ivec2(0, 0)).rgb;
    // What block light adds on top of the sky light alone, read from vanilla's own lightmap so its
    // curve, the brightness setting, night vision and the dimension's ambient light all carry over.
    // In daylight the sky alone already reaches full brightness and torches add next to nothing, as
    // in vanilla. The rest of etnaLightmap (never below zero, since this never exceeds it) is the
    // sky's share, which terrain.fsh relights with the sun.
    etnaTorchLight = max(etnaLightmap - skyOnly, 0.0);
    #ifdef ETNA_SHADOWS
    // Light where the sky can't reach goes into the sky's share, which terrain.fsh relights.
    etnaLightmap += etnaCaveLift(UV2, floorOnly);
    #endif
    float skyLuma = dot(skyOnly, LUMA);
    float realSky = clamp((skyLuma - dot(floorOnly, LUMA)) / max(skyLuma, 1e-3), 0.0, 1.0);
    etnaLightLevels = vec3(clamp(vec2(UV2) / 240.0, 0.0, 1.0), realSky);
    etnaDaylight = dot(sample_lightmap(Sampler2, ivec2(0, 240)).rgb, LUMA);
    // How open to the sky this vertex is. The lightmap curve is steep, so feeding the vertex's own
    // level straight in made a corner vertex one level darker shade half of a sunlit face: a dark
    // triangle on every step top. The gate stays at 1 down to level 13 and only closes in caves and
    // deep overhangs.
    etnaSkyLight = smoothstep(7.0 / 15.0, 13.0 / 15.0, float(UV2.y) / 240.0);
    #else
    etnaLightmap = vec3(1.0);
    etnaTorchLight = vec3(0.0);
    etnaLightLevels = vec3(0.0);
    etnaDaylight = 0.0;
    etnaSkyLight = 0.0;
    #endif
    #endif
}
