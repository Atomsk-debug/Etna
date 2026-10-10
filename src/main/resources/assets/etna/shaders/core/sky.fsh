#version 330
#extension GL_ARB_separate_shader_objects : require

// Etna's sky, per pixel: the sky model (sky.glsl), the sun's disc, and stars that twinkle a little and
// come in a few colours. The moon is vanilla's (its phases are in its texture), drawn over this on the
// same tilted path as the light (EtnaSky.java).

#include <minecraft:dynamictransforms.glsl>
#include <etna:sky.glsl>
#include <etna:sky_ubo.glsl>

layout(location = 0) in vec3 viewDirection;
layout(location = 1) flat in vec4 skySunRain;
layout(location = 2) flat in vec4 skyMoonStorm;
layout(location = 3) flat in vec3 skySunLow;
layout(location = 4) flat in vec3 skySunHigh;
layout(location = 5) flat in vec4 skyMultiGlow;
layout(location = 6) flat in vec3 skyMoonLow;
layout(location = 7) flat in vec3 skyMoonHigh;
layout(location = 8) flat in vec3 skyOvercast;
layout(location = 9) flat in vec3 skySunDisc;    // the sunlight the air in front of the sun leaves

layout(location = 0) out vec4 fragColor;

// The sun's disc: its radius in radians (about 2.6 degrees), and how bright its light is, per channel,
// before it rolls off to white: white at noon, gold low in the sky, deep orange right at the horizon.
const float SUN_RADIUS = 0.045;
const float SUN_BRIGHTNESS = 20.0;
// Stars sit in the cells of a grid on the six faces of a cube around the camera, at most one per cell:
// STAR_GRID cells along each face's edge, a star in STAR_DENSITY of them (about 2900 above the horizon).
const float STAR_GRID = 180.0;
const float STAR_DENSITY = 0.03;
// The smallest star's radius, in cells: about a pixel at 1080p and a 70 degree field of view.
const float STAR_RADIUS = 0.12;

// Hash without Sine by David Hoskins (shadertoy.com/view/4djSRW, MIT licence, Copyright (c) 2014 David
// Hoskins; see CREDITS.md): three values in 0..1 from a cell.
vec3 hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

// The star of the cell dir points into, if it has one. pixelAngle is the size of a pixel in radians: a
// star is never drawn smaller than about a pixel, so it can't flicker in and out between pixels with
// anti-aliasing off, and a larger star gets dimmer by its area so its light stays the same.
vec3 star(vec3 dir, float pixelAngle, float seconds) {
    vec3 a = abs(dir);
    vec2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z) {
        uv = dir.zy / a.x;
        face = dir.x > 0.0 ? 0.0 : 1.0;
    } else if (a.y >= a.z) {
        uv = dir.xz / a.y;
        face = dir.y > 0.0 ? 2.0 : 3.0;
    } else {
        uv = dir.xy / a.z;
        face = dir.z > 0.0 ? 4.0 : 5.0;
    }
    vec2 grid = (uv * 0.5 + 0.5) * STAR_GRID;
    vec2 cell = floor(grid);
    vec3 h = hash33(vec3(cell, face * 131.0 + 7.0));
    if (h.x >= STAR_DENSITY) {
        return vec3(0.0);
    }
    // Somewhere in the middle of its cell, so its glow never reaches the cell's edge.
    vec2 centre = 0.3 + 0.4 * h.yz;
    // A cube face stretches towards its edges (up to twice its middle's scale in the corners).
    float pixel = pixelAngle * 0.5 * STAR_GRID * (1.0 + 0.5 * dot(uv, uv));
    // At least a pixel wide, so a star drifting across the pixel grid keeps its brightness (no flicker
    // without anti-aliasing), and never past its cell's edge (0.3 from the centre at least), which only a
    // very small window would ask for.
    float radius = min(max(pixel, STAR_RADIUS), 0.3);
    float d = length(grid - cell - centre) / radius;
    float shape = max(1.0 - d * d, 0.0);
    float area = STAR_RADIUS / radius;
    // Many faint stars and a few bright ones.
    float rank = h.x / STAR_DENSITY;
    float magnitude = 0.5 + 2.5 * pow(1.0 - rank, 5.0);
    float twinkle = 0.8 + 0.2 * sin(seconds * (1.5 + 3.0 * h.y) + 6.2832 * h.z);
    // From a warm orange-white to a cool blue-white, mostly white.
    vec3 tint = mix(vec3(1.0, 0.84, 0.68), vec3(0.78, 0.87, 1.0), h.y);
    return tint * (shape * shape * area * area * magnitude * twinkle);
}

void main() {
    vec3 dir = normalize(transpose(mat3(ModelViewMat)) * viewDirection);
    // Size of a pixel in radians, taken while every pixel of the quad is still running.
    float pixelAngle = length(fwidth(dir));

    EtnaSkyFrame f;
    f.sun = skySunRain.xyz;
    f.moon = skyMoonStorm.xyz;
    f.sunLow = skySunLow;
    f.sunHigh = skySunHigh;
    f.multi = skyMultiGlow.rgb;
    f.moonLow = skyMoonLow;
    f.moonHigh = skyMoonHigh;
    f.overcast = skyOvercast;
    f.rain = skySunRain.w;
    f.storm = skyMoonStorm.w;
    f.glow = skyMultiGlow.w;
    vec3 light = etnaSkyLinear(f, dir);

    vec3 color = etnaSkyGround(etnaSkyDisplay(light), dir.y);

    // The sun's disc, a little darker towards its rim, sharp to a pixel and cut by the horizon, added over
    // the sky like light: its colour is the sunlight the air in front of it leaves.
    float angle = sqrt(max(2.0 - 2.0 * dot(dir, f.sun), 0.0));
    float r = angle / SUN_RADIUS;
    float edge = max(pixelAngle / SUN_RADIUS, 0.02);
    float disc = (1.0 - smoothstep(1.0 - edge, 1.0 + edge, r)) * smoothstep(-0.003, 0.003, dir.y) * SkyLook.y;
    if (disc > 0.0) {
        float limb = 1.0 - 0.35 * (1.0 - sqrt(max(1.0 - r * r, 0.0)));
        vec3 sun = 1.0 - exp(-skySunDisc * (SUN_BRIGHTNESS * limb));
        color += sun * (1.0 - color) * disc;
    }

    // Stars, added to the dark sky like light: gone where the sky is bright, and fading into the haze
    // towards the horizon.
    if (SkyLook.x > 0.0) {
        vec3 starDir = transpose(mat3(SkyStarX.xyz, SkyStarY.xyz, SkyStarZ.xyz)) * dir;
        vec3 s = star(starDir, pixelAngle, SkyLook.z) * (SkyLook.x * smoothstep(0.0, 0.25, dir.y));
        color += s * (1.0 - color);
    }

    // Up to half an 8-bit step of noise either way, against banding in the sky's smooth gradients.
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    fragColor = vec4(color + (noise - 0.5) / 255.0, 1.0);
}
