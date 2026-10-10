#version 330
#extension GL_ARB_separate_shader_objects : require
// Based on Minecraft's vanilla shader, (c) Mojang AB, not covered by Etna's MIT licence (CREDITS.md).
// The shadow filter's textureGather (include/terrain_shadow.glsl), enabled before any code as the GLSL spec
// wants; a compiler without it only warns here, and the include then reads one texel at a time.
#extension GL_ARB_texture_gather : enable

// Etna's terrain fragment shader, made from vanilla 26.4's. Only Etna's copies of the terrain pipelines
// use it (EtnaShadows), and only while Etna is on: with Etna off the game draws with its own shader.
// Lights sky-lit terrain with the sun and the sky (real cast shadows from Etna's shadow map, see
// EtnaShadows.java) instead of vanilla's fixed per-face shading, and adds animated, reflective water, which
// takes its texture's average colour: none of the texture's own pixels show on water.
// Block textures get texel-sized bumps worked out from the textures themselves, surfaces a soft sheen
// in the sun and a wet look in rain, leaves glow with the sun behind them and torches light warmly, the
// one in the player's hand too.
// ETNA_SHADOW_PASS is the depth-only draw into the shadow map.
// The code that reads the shadow map, the clouds' shadows, the water and the hooks for dark places live
// in includes (assets/etna/shaders/include/terrain_*.glsl); terrain_hooks.glsl lists the functions this
// file calls in them.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif
#include <etna:terrain_ubo.glsl>
#include <etna:terrain_touch.glsl>

uniform sampler2D Sampler0;
#ifdef IMPROVED_FOG
uniform sampler2D SkySampler;
uniform sampler2D CloudsSampler;
uniform sampler2D CloudsDepthSampler;
#endif

#ifdef ETNA_SHADOW_PASS
#ifdef ALPHA_CUTOUT
// The shadow map is drawn depth only: the alpha test needs nothing but the texture coordinates (xy) and
// the vertex alpha (z). Solid terrain has no alpha test and takes no inputs.
layout(location = 0) in vec3 etnaShadowCutout;
#endif
#else
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float chunkVisibility;
layout(location = 5) in vec3 etnaViewPos;
layout(location = 6) in vec3 etnaWorldPos;
layout(location = 7) in float etnaMaterial;
layout(location = 8) in float etnaSkyLight;  // 0 in caves .. 1 under open sky
layout(location = 9) in vec3 etnaLightmap;
layout(location = 10) in vec3 etnaTorchLight;  // the part of etnaLightmap that block light adds to the sky's
layout(location = 11) in float etnaDaylight;
layout(location = 12) in vec3 etnaLightLevels; // x: block light, y: sky light (0..1), z: share of the sky-side light that is real sky

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif
#endif

#ifndef ETNA_SHADOW_PASS
#ifdef ETNA_SHADOWS
uniform sampler2D EtnaShadowSampler;
// The other of the two maps, while the shadows fade from one to the other (terrain_shadow.glsl).
uniform sampler2D EtnaShadowSampler2;
#endif

#include <etna:terrain_hooks.glsl>
#ifdef ETNA_SHADOWS
#define ETNA_SKY_CURRENT
#endif
#include <etna:sky.glsl>

const float ENVIRONMENTAL_BACKGROUND_BLEND_START = 0.9;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

#ifdef ETNA_SHADOWS
// This frame's sky (sky.glsl), as EtnaSkyModel.java worked it out once for the whole frame. The light
// in the block is the moon's at night, which stands opposite the sun.
EtnaSkyFrame etnaSkyCurrent() {
    EtnaSkyFrame f;
    f.sun = ETNA_MOON ? -EtnaLightDir.xyz : EtnaLightDir.xyz;
    f.moon = -f.sun;
    f.sunLow = EtnaSkySunLow.rgb;
    f.sunHigh = EtnaSkySunHigh.rgb;
    f.multi = EtnaSkyMulti.rgb;
    f.moonLow = EtnaSkyMoonLow.rgb;
    f.moonHigh = EtnaSkyMoonHigh.rgb;
    f.overcast = EtnaSkyOvercast.rgb;
    f.rain = EtnaSkySunLow.w;
    f.storm = EtnaSkySunHigh.w;
    f.glow = EtnaSkyMulti.w;
    return f;
}
#endif

// The colour land fades into at the edge of the render distance (and while its chunk fades in): the
// sky right behind it. Etna's sky changes colour with the direction (orange towards a setting sun, blue
// away from it) where vanilla's fog has one colour for the whole screen, so while Etna draws the sky
// this is the sky model's horizon in this pixel's direction. Only the far band the fog covers asks for
// it. Otherwise (vanilla's sky, under water, the Nether) it is vanilla's fog colour.
vec3 etnaFogColor() {
    #ifdef ETNA_SHADOWS
    if (ETNA_SKY_DRAWN) {
        vec3 dir = vec3(etnaViewPos.x, max(etnaViewPos.y, 0.0), etnaViewPos.z);
        float len = length(dir);
        if (len > 1e-4) {
            return etnaSkyRadiance(dir / len);
        }
    }
    #endif
    return FogColor.rgb;
}

// The sky the water mirrors in the direction dir: the sky model's while Etna draws the sky, else a
// guess from vanilla's fog colour (0.6.0's). Below the horizon (the underside of the water, or a wave
// tipped far over) it is the horizon.
vec3 etnaWaterSky(vec3 dir) {
    #ifdef ETNA_SHADOWS
    if (ETNA_SKY_DRAWN) {
        return etnaSkyRadiance(normalize(vec3(dir.x, max(dir.y, 0.0), dir.z) + vec3(0.0, 1e-3, 0.0)));
    }
    #endif
    return mix(FogColor.rgb, vec3(0.42, 0.62, 0.95), 0.35);
}

// How many blocks one screen pixel spans on this surface, from etnaViewPos's derivatives taken before any
// discard. Set at the start of main (it stays 0 in the alpha-only phase of improved transparency).
float etnaPixelFootprint = 0.0;

#ifdef IMPROVED_FOG
vec3 sampleBackgroundColor(ivec2 pixelCoords) {
    vec3 skyColor = texelFetch(SkySampler, pixelCoords, 0).rgb;
    vec4 cloudColor = texelFetch(CloudsSampler, pixelCoords, 0);
    float cloudDepth = texelFetch(CloudsDepthSampler, pixelCoords, 0).r;
    float cloudsBehind = step(cloudDepth, gl_FragCoord.z);
    return mix(skyColor, cloudColor.rgb / (cloudColor.a > 0.0 ? cloudColor.a : 1.0), cloudColor.a * cloudsBehind);
}
#endif

#ifndef OIT_ALPHA_ONLY
vec4 calculateFinalColor(vec4 color) {
    float environmentalValue = linear_fog_value(sphericalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd);
    environmentalValue *= etnaEnvironmentalFog();
    float renderDistanceValue = linear_fog_value(cylindricalVertexDistance, FogRenderDistanceStart, FogRenderDistanceEnd);

    float environmentalBackgroundValue = smoothstep(ENVIRONMENTAL_BACKGROUND_BLEND_START, 1.0, environmentalValue);

    float fogValue = max(environmentalValue, renderDistanceValue);

    float backgroundFogValue = max(environmentalBackgroundValue, renderDistanceValue);

    if (backgroundFogValue > 0.0 || chunkVisibility < 1.0) {
        vec3 air = etnaFogColor();
        #ifdef IMPROVED_FOG
        vec3 backgroundColor = sampleBackgroundColor(ivec2(gl_FragCoord.xy));
        vec3 targetColor = mix(air, backgroundColor, backgroundFogValue);
        #else
        vec3 backgroundColor = air;
        vec3 targetColor = backgroundColor;
        #endif
        vec3 foggedColor = mix(color.rgb, targetColor, fogValue);
        vec3 foggedFadedColor = mix(backgroundColor, foggedColor, chunkVisibility);
        color = vec4(foggedFadedColor, color.a);
    } else {
        color.rgb = mix(color.rgb, FogColor.rgb, fogValue);
    }
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif

    return color;
}

// posDx and posDy are etnaViewPos's screen-space derivatives, taken before any discard.
vec3 geometricNormal(vec3 posDx, vec3 posDy) {
    vec3 n = cross(posDx, posDy);
    float len2 = dot(n, n);
    // A pixel whose derivatives span no area has no normal to speak of: call it a top face rather
    // than let normalize() turn it into NaN.
    if (len2 < 1e-30) {
        return vec3(0.0, 1.0, 0.0);
    }
    n *= inversesqrt(len2);
    // Screen-space derivatives flip with the backend's y axis; a visible face always faces the camera.
    n = dot(n, etnaViewPos) > 0.0 ? -n : n;
    // Seen at a steep angle the derivatives get imprecise and the normal wobbles from pixel to pixel.
    // Block faces are axis aligned: snap anything close to an axis onto it.
    vec3 m = abs(n);
    float top = max(m.x, max(m.y, m.z));
    if (top > 0.9) {
        n = m.x == top ? vec3(sign(n.x), 0.0, 0.0) : m.y == top ? vec3(0.0, sign(n.y), 0.0) : vec3(0.0, 0.0, sign(n.z));
    }
    return n;
}

// What kind of surface this is: 0 other, 1 leaves, 2 plant, 3 water. Every vertex of leaves, plants
// and water carries its value (terrain.vsh), so it is the same all across a face.
float surfaceKind() {
    return etnaMaterial > 2.5 ? 3.0 : etnaMaterial > 1.5 ? 2.0 : etnaMaterial > 0.5 ? 1.0 : 0.0;
}

// What the block texture says about the surface under this fragment.
struct SurfaceDetail {
    vec3 normal;      // one normal per texel; the face's own where the texture is flat or too small to see
    float cavity;     // how far the texel sits below its neighbours (mortar, gaps between cobbles), 0 on a flat texture
    float smoothness; // 0 for a busy, rough-looking texture .. 1 for an even, polished-looking one
};

// A texel's height, taken from its brightness: on block textures the dark texels are mostly the
// cracks, gaps and grooves. Holes in cut-out textures (leaves, flowers) are not pits: they keep the
// height of the texel in the middle.
float texelHeight(ivec2 texel, float centre) {
    vec4 c = texelFetch(Sampler0, clamp(texel, ivec2(0), max(TextureSize - 1, ivec2(0))), 0);
    return c.a > 0.5 ? dot(c.rgb, LUMA) : centre;
}

// The whole axis, on this face's plane, that v points along most (zero if v has nothing there).
vec3 snapToFaceAxis(vec3 v, vec3 normal) {
    v -= normal * dot(v, normal);
    vec3 m = abs(v);
    if (m.x >= m.y && m.x >= m.z) {
        return vec3(sign(v.x), 0.0, 0.0);
    }
    return m.y >= m.z ? vec3(0.0, sign(v.y), 0.0) : vec3(0.0, 0.0, sign(v.z));
}

// Bumps worked out from the albedo texture itself, so they work with any resource pack: each
// texel's height is its brightness, compared with the four texels around it. Five texel fetches.
// The derivatives are etnaViewPos's and texCoord0's, taken before any discard.
SurfaceDetail surfaceDetail(vec3 normal, float kind, vec3 posDx, vec3 posDy, vec2 uvDx, vec2 uvDy) {
    SurfaceDetail detail = SurfaceDetail(normal, 0.0, 0.5);
    // Water has its own waves.
    if (kind > 2.5 || max(ETNA_SURFACE_DETAIL, ETNA_SHINE) <= 0.0) {
        return detail;
    }
    // Texels per screen pixel. Once a texel shrinks below a pixel, neighbouring pixels land on
    // unrelated texels and per-texel detail turns into shimmer: it is gone by then.
    vec2 atlas = vec2(TextureSize);
    float footprint = max(length(uvDx * atlas), length(uvDy * atlas));
    float fade = 1.0 - smoothstep(0.4, 1.0, footprint);
    if (fade <= 0.0) {
        return detail;
    }
    // On a whole block face, the world directions in which the texture's u and v grow, from how
    // position and texture coordinates change across the pixel: the 2x2 system solved up to its
    // determinant, since only the directions matter, and the determinant's sign keeps mirrored
    // textures the right way round. They snap onto the block's axes, so the frame can't wobble where
    // screen-space derivatives get imprecise. Plants and rotated model parts have no such frame.
    vec3 m = abs(normal);
    bool blockFace = max(m.x, max(m.y, m.z)) >= 0.999;
    vec3 alongU = vec3(0.0);
    vec3 alongV = vec3(0.0);
    if (blockFace) {
        float det = uvDx.x * uvDy.y - uvDx.y * uvDy.x;
        alongU = snapToFaceAxis((posDx * uvDy.y - posDy * uvDx.y) * sign(det), normal);
        alongV = snapToFaceAxis((posDy * uvDx.x - posDx * uvDy.x) * sign(det), normal);
        // Derivatives too degenerate to tell the two directions apart: no frame.
        blockFace = dot(alongU, alongU) > 0.5 && dot(alongV, alongV) > 0.5 && abs(dot(alongU, alongV)) < 0.5;
    }

    // Which of the texture's 16x16 texels this is. The atlas can't say: in 26.x its textures may sit
    // inside a border of padding (for mipmaps and anisotropic filtering), off any 16-texel grid. On
    // a block face the texture lines up with the block's edges (vanilla models take their texture
    // coordinates from where each face sits in the block), so the world position counts it instead.
    // This pixel's place inside its texel comes off first, so the count lands on a whole texel and
    // small errors in the world position can't tip it into the next one. Elsewhere it falls back to
    // the atlas grid, right when there is no padding. Where either guess is wrong, the worst left is
    // a texel compared on one side only, or with the padding beside it.
    vec2 texelPos = texCoord0 * atlas;
    ivec2 texel = clamp(ivec2(floor(texelPos)), ivec2(0), max(TextureSize - 1, ivec2(0)));
    ivec2 local = texel & 15;
    if (blockFace) {
        vec2 fromEdge = fract(vec2(dot(etnaWorldPos, alongU), dot(etnaWorldPos, alongV))) * 16.0;
        local = ivec2(round(fromEdge - fract(texelPos))) & 15;
    }
    // Neighbours are read only inside the texture: past its edge lies padding or another texture.
    // Edge texels compare with the neighbours they have (inside: left, right, up, down).
    vec4 inside = vec4(step(0.5, float(local.x)), step(float(local.x), 14.5), step(0.5, float(local.y)), step(float(local.y), 14.5));
    float h = dot(texelFetch(Sampler0, texel, 0).rgb, LUMA);
    float left = inside.x > 0.5 ? texelHeight(texel - ivec2(1, 0), h) : h;
    float right = inside.y > 0.5 ? texelHeight(texel + ivec2(1, 0), h) : h;
    float up = inside.z > 0.5 ? texelHeight(texel - ivec2(0, 1), h) : h;
    float down = inside.w > 0.5 ? texelHeight(texel + ivec2(0, 1), h) : h;
    // Every texel has at least one neighbour on each axis, so this is 2 to 4.
    float count = max(inside.x + inside.y + inside.z + inside.w, 1.0);

    // Smooth-looking: the texel hardly differs from its neighbours. Where the texels are too small
    // to tell apart, every surface counts as middling.
    float contrast = (abs(left - h) + abs(right - h) + abs(up - h) + abs(down - h)) / count;
    detail.smoothness = mix(0.5, 1.0 - smoothstep(0.015, 0.09, contrast), fade);

    float amount = ETNA_SURFACE_DETAIL * fade;
    if (amount <= 0.0) {
        return detail;
    }
    // A missing neighbour stands in at the texel's own height, so it adds nothing to the sum.
    detail.cavity = clamp(((left + right + up + down - (4.0 - count) * h) / count - h) * 4.0, 0.0, 1.0) * amount;

    // Tilt the normal down the slope of the heights, one normal per texel so pixel art stays pixel
    // art. Only whole block faces get it; plants and rotated model parts keep their flat normal.
    if (!blockFace) {
        return detail;
    }
    // Central differences, or one-sided ones at the texture's edge.
    vec2 slope = vec2(right - left, down - up) / max(vec2(inside.x + inside.y, inside.z + inside.w), 1.0);
    vec3 tilt = (alongU * slope.x + alongV * slope.y) * (9.0 * amount);
    float tilt2 = dot(tilt, tilt);
    if (tilt2 > 0.36) {
        // No steeper than about 31 degrees, however contrasty the texture.
        tilt *= 0.6 * inversesqrt(tilt2);
    }
    if (tilt2 > 0.0) {
        // The tilt lies in the face's plane, so this is never shorter than the unit normal.
        detail.normal = normalize(normal - tilt);
    }
    return detail;
}

// The brightness vanilla baked into this face's vertices (1 on top, darker on the sides and below).
float vanillaFaceShade(vec3 n, bool plant) {
    vec4 shade = ETNA_FACE_SHADE;
    if (plant) {
        // Faces with "shade": false (crosses, crops) are shaded as if they pointed up.
        return shade.y;
    }
    vec3 n2 = n * n;
    return n2.x * shade.w + n2.z * shade.z + n2.y * (n.y > 0.0 ? shade.y : shade.x);
}

// Colour of direct light: white-gold at noon, gold low in the sky, a deep orange at the horizon, a cool
// blue from the moon. With Etna's block it is the sky model's sunlight (EtnaSkyModel.sunlight: what the
// air leaves of the sun's light), brought to 0.6.0's brightness: a little under 1 at noon, three
// quarters with the sun low or the moon up, so the balance of sun and shade stays as it was tuned.
// Without the block or the model (draws with improved transparency, Etna switched off, the model failed)
// 0.6.0's own colours.
vec3 sunColor() {
    vec3 c;
    #ifdef ETNA_SHADOWS
    if (ETNA_SUN_WORLD > 0.5 && ETNA_SKY_MODEL) {
        c = etnaSunlight() * (ETNA_MOON ? 0.73 : mix(0.75, 0.97, smoothstep(0.04, 0.45, ETNA_LIGHT_DIR.y)));
    } else
    #endif
    {
        if (ETNA_MOON) {
            return vec3(0.62, 0.74, 1.0);
        }
        vec3 noon = vec3(1.0, 0.97, 0.92);
        vec3 low = vec3(1.0, 0.68, 0.40);
        c = mix(low, noon, smoothstep(0.04, 0.45, ETNA_LIGHT_DIR.y));
    }
    // The warmth setting nudges it: grey stone stays grey.
    float w = ETNA_WARMTH;
    return c * vec3(1.0 + 0.04 * w, 1.0 - 0.01 * w, 1.0 - 0.10 * w);
}

// Colour of the light from the sky, luma about 1. With Etna's block, in a world with a sun, the sky
// model's skylight (EtnaSkyModel.skylight): a clear blue by day, warmer around sunset, navy at night,
// grey in rain. Elsewhere the hue of vanilla's fog colour (the horizon: blue by day, orange at sunset,
// the biome's own tint, the Nether's and the End's air) at low saturation.
vec3 skyColor() {
    #ifdef ETNA_SHADOWS
    if (ETNA_SUN_WORLD > 0.5 && ETNA_SKY_MODEL) {
        return etnaSkylight();
    }
    #endif
    vec3 hue = FogColor.rgb / max(dot(FogColor.rgb, LUMA), 1e-3);
    return clamp(mix(vec3(1.0), hue, 0.18), 0.0, 1.5);
}

#ifdef ETNA_SHADOWS
// Ordered 4x4 dither; with temporal anti-aliasing on it shifts every frame, so a few steps per
// frame add up to a smooth result over a handful of frames instead of a fixed pattern.
float etnaDither() {
    ivec2 cell = ivec2(gl_FragCoord.xy) & 3;
    const float BAYER[16] = float[](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
    float d = (BAYER[cell.y * 4 + cell.x] + 0.5) / 16.0;
    return EtnaWorld.y > 0.5 ? fract(d + EtnaLook2.z * 0.618034) : d;
}
#endif

// Strength of the direct sun, in vanilla's units (1 = a top face in vanilla daylight). Whatever the
// shade loses to the shadow darkness setting, the sun makes up on sunlit ground.
float directSun() {
    float strength = ETNA_LIGHT_STRENGTH;
    float shade = mix(0.95, mix(0.85, 0.45, ETNA_SHADOW_DARKNESS), strength);
    return (1.08 - shade) / 0.9 * ETNA_SUNLIGHT * strength;
}

// The sky light on a face, before its brightness. In a sunlit world, shade is lit by the blue sky
// alone and reads cooler than sunlit ground; on faces the sun reaches, its light outshines the sky's
// blue, so grey stone in the sun stays grey (and takes the sun's warmth). How blue the shade gets
// follows the shadow darkness setting: from its middle up all of the sky's colour, down to the sunlit
// faces' share at 0. With the sun up, walls see more of the sky's bright, warm side when they face the
// sun and of its darker blue side when they face away: each wall direction gets its own light, and a
// low sun warms the sky on the walls that face it. At night the sky's navy is the only light.
vec3 skyLightOn(vec3 normal, float faceNdl, float shadow) {
    vec3 sky = skyColor();
    if (ETNA_MOON) {
        return sky;
    }
    float strength = ETNA_LIGHT_STRENGTH;
    vec2 flatSun = ETNA_LIGHT_DIR.xz;
    float flatLength = dot(flatSun, flatSun);
    if (flatLength > 1e-6) {
        float toward = dot(normal.xz, flatSun) * inversesqrt(flatLength);
        float low = 1.0 - smoothstep(0.1, 0.6, ETNA_LIGHT_DIR.y);
        vec3 sun = sunColor();
        sky = mix(sky, sun / max(dot(sun, LUMA), 1e-3), 0.30 * max(toward, 0.0) * low * strength);
        sky *= 1.0 + 0.14 * toward * strength;
    }
    float sunlit = shadow * smoothstep(0.0, 0.3, faceNdl) * strength;
    float keep = mix(mix(0.4, 1.0, smoothstep(0.0, 0.5, ETNA_SHADOW_DARKNESS)), 0.4, sunlit);
    return mix(vec3(dot(sky, LUMA)), sky, keep);
}

// A face lit by the sun at the cosine ndl, the terminator softened a little: a face the sun only grazes
// gets a little more than Lambert's cosine (wrap lighting), so walls don't snap from lit to dark as the
// sun crosses their plane. Faces turned away keep none: the shadow map can't speak for them.
float softSun(float ndl) {
    return ndl > 0.0 ? mix(ndl, (ndl + 0.15) / 1.15, smoothstep(0.0, 0.12, ndl)) : 0.0;
}

// Light reaching a sky-lit surface, in vanilla's units (1 = a top face in vanilla daylight): a soft
// sky light from above plus direct sun where the shadow map lets it through, and the warm sunlight the
// lit ground throws back up onto walls and undersides.
vec3 skyLighting(vec3 normal, SurfaceDetail detail, bool thin, bool plant, float shadow) {
    // Shade the sun can't reach. Without direct sun (night, rain) the sky is the only light and
    // surfaces keep vanilla's brightness. Only light that really comes from the sky is shaded:
    // underground the sky's share is the dimension's ambient floor or night vision, which keep
    // vanilla's level whatever the time of day.
    float ambient = mix(0.95, mix(0.85, 0.45, ETNA_SHADOW_DARKNESS), etnaShadeWeight());
    float sun = directSun();
    // The sky is brightest straight up: top faces get all of it, walls most, undersides half. Bumps
    // turn a little towards it or away, and dips in the texture see less of it. Leaves, lit from all
    // round inside a canopy, get a fixed share of sky and sun. Grass and flowers stand in the open
    // and vanilla lights them like the ground they grow from: they get all of the sky, and from the
    // sun half its full light when it stands low, rising to all of it at noon. A lawn is then as
    // bright as its ground at noon and catches a low sun on its upright blades. East and west walls get
    // a few percent less sky than north and south ones (vanilla shades them more, much more strongly),
    // so the faces of a block stay apart under an overcast sky.
    float up = normal.y + (detail.normal.y - normal.y) * 0.5;
    float dome = (plant ? 1.0 : thin ? 0.9 : (0.78 + 0.22 * up) * (1.0 - 0.06 * normal.x * normal.x)) * (1.0 - 0.25 * detail.cavity);
    float faceNdl = plant ? 0.5 + 0.5 * max(ETNA_LIGHT_DIR.y, 0.0) : thin ? 0.65 : max(dot(normal, ETNA_LIGHT_DIR), 0.0);
    float ndl = faceNdl;
    if (!thin) {
        // Bumps catch the sun or turn from it, but only on faces that face it already: where the sun
        // merely grazes a face the shadow map is least sure, so the face keeps its plain light.
        float bumped = max(dot(detail.normal, ETNA_LIGHT_DIR), 0.0);
        ndl += (bumped - ndl) * smoothstep(0.0, 0.1, ndl);
        ndl = softSun(ndl);
    }
    // Dips in the texture lie in the shadow of the texels around them.
    ndl *= 1.0 - 0.5 * detail.cavity;
    vec3 sky = thin ? skyColor() : skyLightOn(normal, faceNdl, shadow);
    vec3 sunlight = sunColor() * sun;
    // Sunlit ground around a wall lights it from below: a fifth of the light on the ground, warmed by the
    // ground's own colour, on the half of the view a wall has of it (all of it for an underside). Only
    // by day, and only where the sun reaches this place at all (etnaSunReach: dimmed under water too).
    float bounce = ETNA_MOON || thin ? 0.0 : 0.20 * max(ETNA_LIGHT_DIR.y, 0.0) * (0.5 - 0.5 * up);
    return sky * ambient * dome + sunlight * etnaSunReach() * (ndl * shadow) + sunlight * vec3(1.0, 0.86, 0.68) * (bounce * etnaSunReach());
}

// How far torch light moves from vanilla's colour towards a flame's. The Nether and the End are lit
// by little else: a strong orange cast there would bury their own colours.
float torchWarmth() {
    return ETNA_TORCH_WARMTH * mix(0.35, 1.0, ETNA_SUN_WORLD);
}

// Torches, lanterns, lava, glowstone. vanilla is vanilla's block light at this spot: its curve, the
// brightness setting, night vision and the dimension's ambient light are all in it already, so only
// its colour changes, to a flame that is yellow-white close to the source and deepens to orange as
// it fades, and its far reach dims a little so the light pools around the source. The light held in
// the player's hand is in vanilla here already (terrain.vsh) and takes the same flame where it
// outshines the blocks' own light, tinted by what is held: blue for soul fire, green for copper.
vec3 torchLight(vec3 vanilla) {
    float warmth = torchWarmth();
    if (warmth <= 0.0) {
        return vanilla;
    }
    float level = etnaLightLevels.x;
    vec3 tint = vec3(1.0);
    #ifdef ETNA_SHADOWS
    if (EtnaHand.w > 0.0) {
        float held = etnaHeldLevel(etnaViewPos) / 15.0;
        // All the hand's colour two levels above the blocks' own light, none where the blocks' is brighter.
        tint = mix(vec3(1.0), EtnaHand.rgb, clamp((held - level) * 7.5, 0.0, 1.0));
        level = max(level, held);
    }
    #endif
    vec3 flame = mix(vec3(1.0, 0.60, 0.30), vec3(1.0, 0.86, 0.66), smoothstep(0.45, 0.95, level)) * tint;
    // Same brightness as vanilla's light (luma 1 times its luma): only the hue moves.
    flame /= dot(flame, LUMA);
    float falloff = mix(0.72, 1.0, smoothstep(0.3, 0.93, level));
    return mix(vanilla, flame * (dot(vanilla, LUMA) * falloff), warmth);
}

// Rain soaks ground lying straight under the open sky, which stays wet for a minute or two after the
// rain stops (ETNA_WETNESS, worked out by EtnaTouch.java). Only full sky light counts: a block of water
// or leaves overhead already dims it a level, so sea beds and the ground under trees stay dry. Very
// bright ground is most likely snow or desert sand, where rain falls as snow or not at all; base is
// the colour before vanilla's ambient occlusion and face shading, so snow and sand stay pale along
// walls and in corners. Glowing blocks (lava, magma, glowstone, sea lanterns, froglights) report
// full block light on their own faces and stay as they are: a wet film greyed a lava pool into stone.
// Ground next to them reads a level less and still gets wet.
float wetness(vec3 normal, vec3 base, float kind) {
    float soaked = ETNA_WETNESS * min(ETNA_SHINE * 1.6, 1.0);
    if (soaked <= 0.0 || kind > 0.5) {
        return 0.0;
    }
    float open = smoothstep(0.94, 0.995, etnaLightLevels.y);
    float up = smoothstep(0.7, 0.95, normal.y);
    float pale = smoothstep(0.7, 0.88, dot(base, LUMA));
    float glowing = smoothstep(0.95, 1.0, etnaLightLevels.x);
    return soaked * open * up * (1.0 - pale) * (1.0 - glowing) * cameraInAir();
}

// The sun on surfaces that aren't perfectly matte: a broad, soft sheen (normalised Blinn-Phong with
// Schlick's Fresnel), a little tighter and stronger on smooth-looking textures. As on real stone,
// wood and leaves, it shows most looking across the ground towards a low sun.
vec3 sunSheen(vec3 normal, SurfaceDetail detail, bool thin, float shadow, vec3 viewDir) {
    // Only straight under the sky: water overhead (a sea bed) dims the sky light a level or more.
    float amount = ETNA_SHINE * 3.0 * shadow * etnaSkyLight * smoothstep(0.85, 0.97, etnaLightLevels.y) * cameraInAir();
    float sun = directSun();
    if (amount * sun <= 0.0) {
        return vec3(0.0);
    }
    vec3 l = ETNA_LIGHT_DIR;
    float ndl = max(dot(normal, l), 0.0);
    // Mostly the face's own normal: the per-texel ones alone would make the sheen glitter.
    vec3 n = normal + (detail.normal - normal) * 0.3;
    n *= inversesqrt(max(dot(n, n), 1e-6));
    vec3 v = -viewDir;
    vec3 h = l + v;
    h *= inversesqrt(max(dot(h, h), 1e-6));
    float exponent = mix(8.0, 28.0, detail.smoothness);
    float ndh = max(dot(n, h), 0.0);
    float edge = clamp(1.0 - dot(v, h), 0.0, 1.0);
    float edge2 = edge * edge;
    float fresnel = 0.04 + 0.96 * edge2 * edge2 * edge;
    float spec = (exponent + 8.0) / 8.0 * pow(ndh, exponent) * fresnel * ndl;
    // Leaves and grass blades are too small and ragged to hold much of it.
    spec *= mix(0.4, 1.0, detail.smoothness) * (thin ? 0.5 : 1.0);
    return sunColor() * (spec * sun * amount);
}

// Sunlight shining through leaves and plants towards the camera: strongest looking towards the sun,
// tinted by the leaf's own colour, and only as much as the shadow map lets reach the leaf (through
// the leaves in front of it, for leaves). Gone by dusk, at night and in rain (the light strength
// carries the weather).
vec3 leafGlow(vec3 albedo, float kind, float shadow, vec3 viewDir) {
    #ifdef ETNA_SHADOWS
    if (kind < 0.5 || kind > 2.5 || ETNA_MOON) {
        return vec3(0.0);
    }
    float forward = max(dot(viewDir, ETNA_LIGHT_DIR), 0.0);
    forward *= forward;
    float amount = ETNA_LEAF_GLOW * forward * forward * directSun() * smoothstep(0.05, 0.3, ETNA_LIGHT_DIR.y)
        * etnaSkyLight * smoothstep(0.85, 0.97, etnaLightLevels.y) * cameraInAir();
    // Below this the glow stays under one step of an 8-bit colour: skip the shadow map reads.
    if (amount < 1e-3) {
        return vec3(0.0);
    }
    float through;
    if (EtnaLook2.w > 0.5) {
        through = kind < 1.5 ? leafBacklight() : shadow;
    } else {
        // No shadow map: leaves and plants right under the open sky are the ones the sun reaches.
        through = 0.6 * smoothstep(0.94, 0.995, etnaLightLevels.y);
    }
    // Light that came through a leaf takes on a little more of its colour, but mostly keeps the
    // leaf's own hue: tinted by the full colour once more, a green leaf glowed neon chartreuse.
    vec3 tint = mix(vec3(1.0), albedo / max(max(albedo.r, max(albedo.g, albedo.b)), 0.05), 0.3);
    return albedo * tint * sunColor() * (2.4 * amount * through);
    #else
    // Leaves and plants are never drawn without the block.
    return vec3(0.0);
    #endif
}

vec3 shadeTerrain(vec3 color, vec3 normal, SurfaceDetail detail, float kind) {
    bool thin = kind > 0.5 && kind < 2.5;
    bool plant = kind > 1.5 && kind < 2.5;
    // Undo vanilla's fixed shading (tops bright, sides 0.6-0.8, undersides 0.5): on top of the sun
    // shading it darkened walls twice. Faces vanilla left unshaded (torches, flat models) can't be
    // told apart, so the result never goes above full brightness. Plants say which of their faces
    // vanilla shaded as if they faced up (etnaMaterial 2) and which by the way they face (2.25).
    float faceShade = vanillaFaceShade(normal, plant && etnaMaterial < 2.125);
    float vertexPeak = max(max(vertexColor.r, vertexColor.g), vertexColor.b);
    float unshade = min(1.0 / faceShade, 1.0 / max(vertexPeak, 1e-3));

    float shadow = 1.0;
    #ifdef ETNA_SHADOWS
    // Faces turned away from the sun get no sun whatever the map says (every use of the shadow is
    // multiplied by the face's own angle to the sun), so they skip the lookup.
    if (EtnaLook2.w > 0.5 && ETNA_LIGHT_STRENGTH * etnaSunOpen() > 0.01 && (thin || dot(normal, ETNA_LIGHT_DIR) > 0.0)) {
        shadow = shadowVisibility(normal, thin);
    }
    // Clouds drifting over the sun, with or without the shadow map.
    if (ETNA_LIGHT_STRENGTH * etnaSunOpen() > 0.01 && (thin || dot(normal, ETNA_LIGHT_DIR) > 0.0)) {
        shadow *= etnaCloudShadow();
    }
    #endif
    vec3 viewDir = etnaViewPos * inversesqrt(max(dot(etnaViewPos, etnaViewPos), 1e-8));

    // Vanilla's light here, in two parts: what the sky gives, which is relit with the sun and the sky,
    // and what torches and other glowing blocks add on top, which keeps vanilla's per-face shading
    // and takes the torch colour. In daylight the sky alone is already at full brightness, as in
    // vanilla; in the Nether and the End the sky's part is only the dimension's ambient floor.
    vec3 skyPart = etnaSkyShare(max(etnaLightmap - etnaTorchLight, 0.0));
    vec3 torch = torchLight(etnaTorchLight) * etnaTorchBoost();
    // Where a torch outshines the sky (a lit doorway at night) the eye adapts to its warmth: the
    // moonlight's blue fades there instead of mixing with the orange into pink.
    float torchLuma = dot(etnaTorchLight, LUMA);
    float skyLuma = dot(skyPart, LUMA);
    skyPart = mix(skyPart, vec3(skyLuma), torchWarmth() * torchLuma / max(torchLuma + skyLuma, 1e-4));
    etnaFinalSkyShare(skyPart);

    // The vertex colour's brightest channel is vanilla's ambient occlusion times its face shading
    // (the biome tint's own brightest channel too, on grass and leaves): dividing it out leaves the
    // surface's own colour.
    float wet = wetness(normal, color / max(vertexPeak, 1e-3), kind);
    // Water fills a wet surface's pores, so less light scatters back out of it.
    color *= (1.0 - 0.2 * wet) * unshade;
    vec3 lit = color * (skyLighting(normal, detail, thin, plant, shadow) * skyPart + faceShade * torch);
    if (wet > 0.0) {
        // The film of water mirrors the sky, by Schlick's Fresnel: mostly at grazing angles, more on
        // smooth stone than on a grass block. The horizon's colour is the best guess at what it sees.
        float edge = clamp(1.0 + dot(normal, viewDir), 0.0, 1.0);
        float edge2 = edge * edge;
        float fresnel = 0.02 + 0.98 * edge2 * edge2 * edge;
        lit = mix(lit, FogColor.rgb, fresnel * wet * mix(0.35, 0.75, detail.smoothness));
    }
    // Highlights go on last, screened over the lit colour so they never push it past white. Like the
    // sun light itself they follow vanilla's sky light here (time of day, caves). Water has its own
    // waves, reflection and sharp glint (shadeWater): a broad sheen on its flat face would only
    // spread a milky film over lakes.
    vec3 highlight = kind > 2.5 ? vec3(0.0) : (sunSheen(normal, detail, thin, shadow, viewDir) + leafGlow(color, kind, shadow, viewDir)) * skyPart;
    // color is the surface's own colour here, with vanilla's face shading and ambient occlusion undone.
    return etnaGlow(lit + (1.0 - min(lit, 1.0)) * min(highlight, 1.0), color, kind);
}

#ifdef ETNA_SHADOWS
#include <etna:shadow_distortion.glsl>
#endif
#include <etna:terrain_shadow.glsl>
#include <etna:terrain_clouds.glsl>
#include <etna:terrain_water.glsl>
#include <etna:terrain_dark.glsl>
#endif

// Vanilla's sampleRGSS (minecraft:texture_sampling.glsl) with its blend factor worked out first. Where a
// texel of the block texture covers a screen pixel or more (everything near the camera) that factor is
// 0, and vanilla mixes its eight extra texture reads in at a weight of nothing: the nearest sample is
// returned on its own then, which is exactly what the mix gave. Elsewhere it is vanilla's arithmetic.
// du and dv are uv's screen-space derivatives (vanilla takes them here), taken before any discard.
vec4 etnaSampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv) {
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

    float minPixelSize = min(pixelSize.x, pixelSize.y);

    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);

    // Explicit gradients (textureGrad inside), so it is safe past the branch below.
    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
    if (blendFactor <= 0.0) {
        return nearestColor;
    }

    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);

    float effectiveDerivative = sqrt(minDerivative * maxDerivative);

    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    float mipLevelLow = floor(mipLevelExact);
    float mipLevelHigh = mipLevelLow + 1.0;
    float mipBlend = fract(mipLevelExact);

    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );

    vec4 rgssColorLow = vec4(0.0);
    vec4 rgssColorHigh = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColorLow += textureLod(source, sampleUV, mipLevelLow);
        rgssColorHigh += textureLod(source, sampleUV, mipLevelHigh);
    }
    rgssColorLow *= 0.25;
    rgssColorHigh *= 0.25;

    vec4 rgssColor = mix(rgssColorLow, rgssColorHigh, mipBlend);

    return mix(nearestColor, rgssColor, blendFactor);
}

// The block texture under this pixel as vanilla reads it: RGSS or the nearest texel, as the Texture
// Filtering option says. uvDx and uvDy are texCoord0's derivatives, taken before any discard; every read
// has explicit gradients, so this is safe inside a branch.
vec4 etnaSampleBlock(vec2 uvDx, vec2 uvDy) {
    vec2 pixelSize = 1.0 / vec2(TextureSize);
    if (UseRgss == 1) {
        return etnaSampleRGSS(Sampler0, texCoord0, pixelSize, uvDx, uvDy);
    }
    return sampleNearest(Sampler0, texCoord0, pixelSize, uvDx, uvDy, sqrt(uvDx * uvDx + uvDy * uvDy));
}

// The water's own colour is the average of the water texture, so a resource pack that recolours the water
// still recolours it, and the waves (shadeWater) draw its surface. Vanilla's 16x16 water texture drawn as
// it is speckles the whole sea with its brightest texels, in streaks where its texels shrink towards a
// pixel, and further out, where those average away, its brighter and darker rows (two of the brightest are
// its first and last) still line up into stripes at every block's edge. 0.7.0 still let a faint trace of
// the pattern show near the camera; over Etna's waves it read as vanilla's texture laid on top of them.

// The water texture on the top and the underside of water, before the vertex colour: its average. The
// derivatives are texCoord0's and etnaViewPos's, taken before any discard. Both phases of improved
// transparency read the same alpha here.
vec4 etnaWaterTexture(vec2 uvDx, vec2 uvDy, vec3 posDx, vec3 posDy) {
    vec2 atlas = vec2(TextureSize);
    // How the texture lies on the face: atlas texels per block along world x (first column) and z (second),
    // from how position and texture coordinates change across the pixel.
    mat2 perBlock = mat2(uvDx * atlas, uvDy * atlas) * inverse(mat2(posDx.xz, posDy.xz));
    // Vanilla's liquid renderer puts the middle of the texture on the middle of every block: the whole of
    // still water's texture, and the middle half of flowing water's, turned along the flow. Where the pixel
    // lies in its block comes from the camera-relative position, precise to about a hundred-thousandth of a
    // block even at the far end of the render distance: only a pixel that close to a block's edge (a few in
    // a hundred thousand) can take the neighbouring block for its own and read a texture's width off, for
    // a frame, which with Mipmap Levels at 4 lands in the padding round the texture. The texture
    // coordinate can't settle such a pixel either: it is rounded about as coarsely.
    vec2 texel = texCoord0 * atlas;
    vec2 centre = texel + perBlock * (0.5 - fract(etnaViewPos.xz - CameraOffset.xz));
    float size = sqrt(abs(determinant(perBlock)));
    // Where the texture runs along the atlas's own axes (still water, and flowing water running along x or
    // z), the block covers n whole texels each way and its middle lies on a whole texel or a half: put it
    // there, so the small errors in the derivatives don't blend in texels beside it. Only where the
    // derivatives give n to within a quarter of a texel; right in front of the camera they are too coarse
    // for a texture of many texels.
    vec2 major = max(abs(perBlock[0]), abs(perBlock[1]));
    vec2 n = round(major);
    if (all(lessThan(min(abs(perBlock[0]), abs(perBlock[1])), vec2(0.25))) && all(lessThan(abs(major - n), vec2(0.25)))
            && all(greaterThan(n, vec2(0.5)))) {
        centre = round(centre - 0.5 * n) + 0.5 * n;
        size = sqrt(n.x * n.y);
    }
    // One read at the block's middle, at the mip level where a single texel would cover the whole block:
    // the texture's average, the same on every block, and as steady as the animation itself. The atlas puts
    // every texture on a multiple of 2^levels texels, with at least that much padding round it, and makes
    // each level from the texture alone, every frame of the animation too. So with Mipmap Levels at 3 or
    // more (4 by default) a 16x16 texture's read is its exact average: at 4 a single texel of the last
    // level, at 3 the bilinear sampler's blend of the four round the middle. With fewer levels it blends
    // the texels round the middle of the finest level there is: for vanilla's water a few per cent darker
    // than the average and up to about 4% from one animation frame to the next (the true average moves
    // 1.4%), still the same on every block. A texture of 64x64 or more gives the average of its middle half.
    // Four reads, one per quarter, would be exact from 2 levels, but one is exact at the default.
    return textureLod(Sampler0, centre / atlas, round(log2(size)));
}

// The water texture on the sides of water (waterfalls, a pool's edge seen from the side), before the vertex
// colour: the average of what the face shows, from eight reads spread over the block at the second mip
// level, so it has no pixel pattern either. The caller passes the face's two axes in the world (z or x
// across, y up) of the steps across the pixel and of where it lies in its block; the derivatives are as for
// etnaWaterTexture. Vanilla's liquid renderer maps the block's square on
// a side to a quarter of the flowing texture that starts at the texture's corner, so reads 0.2 blocks or
// more inside the square, blurred by a mip level two texels wide, stay inside the texture.
vec4 etnaWaterSide(vec2 uvDx, vec2 uvDy, vec2 posDx, vec2 posDy, vec2 inBlock) {
    vec2 atlas = vec2(TextureSize);
    mat2 perBlock = mat2(uvDx * atlas, uvDy * atlas) * inverse(mat2(posDx, posDy));
    vec2 texel = texCoord0 * atlas;
    vec4 sum = vec4(0.0);
    for (int i = 0; i < 8; i++) {
        vec2 at = vec2(0.2 + 0.2 * float(i % 4), i < 4 ? 0.3 : 0.7);
        sum += textureLod(Sampler0, (texel + perBlock * (at - inBlock)) / atlas, 1.0);
    }
    return sum * 0.125;
}

void main() {
    // Screen-space derivatives first, while the whole 2x2 block of pixels is still running: after a
    // discard they are undefined next to cut-out edges (leaves, plants).
    vec3 posDx = dFdx(etnaViewPos);
    vec3 posDy = dFdy(etnaViewPos);
    vec2 uvDx = dFdx(texCoord0);
    vec2 uvDy = dFdy(texCoord0);
    #ifndef OIT_ALPHA_ONLY
    etnaPixelFootprint = max(length(posDx), length(posDy));
    #endif
    // Water takes its texture's average: the top and the underside, the faces with waves (shadeWater),
    // through etnaWaterTexture, the sides through etnaWaterSide; everything else the texture as it is. So
    // does water seen almost exactly edge on, where the pixel's two steps across the face nearly line up and
    // can't tell its two axes apart, as both need (the normal's component along the face's own axis is their
    // determinant, up to sign). Water is drawn only by the translucent pipelines, which define ALPHA_CUTOUT,
    // so solid terrain doesn't carry any of this.
    vec4 color;
    bool sampled = false;
    #ifdef ALPHA_CUTOUT
    if (etnaMaterial > 2.5) {
        // Each face by its normal's largest component, so the steep tops at a waterfall's rim and beside a
        // falling column, tilted towards x or z, still get an average.
        vec3 faceNormal = abs(cross(posDx, posDy));
        vec3 inBlock = fract(etnaViewPos - CameraOffset);
        if (faceNormal.y >= max(faceNormal.x, faceNormal.z)) {
            if (faceNormal.y > 1e-3 * length(posDx.xz) * length(posDy.xz)) {
                color = etnaWaterTexture(uvDx, uvDy, posDx, posDy);
                sampled = true;
            }
        } else if (faceNormal.x >= faceNormal.z) {
            if (faceNormal.x > 1e-3 * length(posDx.zy) * length(posDy.zy)) {
                color = etnaWaterSide(uvDx, uvDy, posDx.zy, posDy.zy, inBlock.zy);
                sampled = true;
            }
        } else if (faceNormal.z > 1e-3 * length(posDx.xy) * length(posDy.xy)) {
            color = etnaWaterSide(uvDx, uvDy, posDx.xy, posDy.xy, inBlock.xy);
            sampled = true;
        }
    }
    #endif
    if (!sampled) {
        color = etnaSampleBlock(uvDx, uvDy);
    }
    color *= vertexColor;
    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    vec3 normal = geometricNormal(posDx, posDy);
    float kind = surfaceKind();
    etnaWaterPrepare();
    color.rgb = shadeTerrain(color.rgb, normal, surfaceDetail(normal, kind, posDx, posDy, uvDx, uvDy), kind);
    // The light shafts in the air are a world pass since 0.7.0 (post/shafts.fsh): the sky gets them too,
    // at half resolution for less than half the cost. Under water they stay here (underwaterShafts).
    // Water is drawn by the translucent pipelines, which also define ALPHA_CUTOUT (at 0.1).
    if (etnaMaterial > 2.5) {
        color = shadeWater(color, normal);
    }
    if (etnaAirEffects()) {
        color.rgb = aerialHaze(color.rgb);
    }
    color.rgb = etnaMediumFog(color.rgb);
    fragColor = calculateFinalColor(color);
    #endif
}
#else
// Depth-only draw into the shadow map, with no colour target: only the alpha test matters, so leaves
// cast leafy shadows. Solid terrain has no alpha test and reads no texture.
void main() {
    #ifdef ALPHA_CUTOUT
    if (texture(Sampler0, etnaShadowCutout.xy).a * etnaShadowCutout.z < 0.5) {
        discard;
    }
    #endif
}
#endif
