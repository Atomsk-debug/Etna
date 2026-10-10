#version 330
#extension GL_ARB_separate_shader_objects : require

// Etna's sky, drawn in place of vanilla's sky disc, sunrise band, sun and stars (EtnaSky.java): one
// triangle over the whole screen. The view direction is worked out as vanilla's sky occluder does it.
// Everything about the sun and the moon that is the same for the whole sky (the light reaching the air,
// sky.glsl's EtnaSkyFrame) is worked out here, three times a frame instead of once per pixel, and
// handed to every pixel unchanged. So is the shooting star of the moment, if there is one: its place
// follows from the time alone, so every pixel agrees on it.

#include <minecraft:projection.glsl>
#include <minecraft:fullscreen_triangle.glsl>
#include <etna:sky.glsl>
#include <etna:sky_ubo.glsl>

layout(location = 0) out vec3 viewDirection;
layout(location = 1) flat out vec4 skySunRain;    // xyz: towards the sun, w: rain
layout(location = 2) flat out vec4 skyMoonStorm;  // xyz: towards the moon, w: thunder
layout(location = 3) flat out vec3 skySunLow;
layout(location = 4) flat out vec3 skySunHigh;
layout(location = 5) flat out vec4 skyMultiGlow;   // rgb: multi, w: glow
layout(location = 6) flat out vec3 skyMoonLow;
layout(location = 7) flat out vec3 skyMoonHigh;
layout(location = 8) flat out vec3 skyOvercast;
layout(location = 9) flat out vec3 skySunDisc;     // the sun's own light, reddened by the air in front of it
layout(location = 10) flat out vec4 skyShootHead;  // xyz: towards the shooting star's head, w: its brightness (0: none now)
layout(location = 11) flat out vec4 skyShootAxis;  // xyz: the axis of the great circle it travels along, w: its trail's length in radians

const float NEAR_PLANE_DEPTH = 1.0;
const float FAR_PLANE_DEPTH = 0.0;
// A shooting star: how long it burns (seconds, plus up to the extra), how far it travels across the sky
// (radians, plus up to the extra) and the longest trail it leaves behind it (radians).
const float SHOOT_DURATION = 0.55;
const float SHOOT_DURATION_EXTRA = 0.45;
const float SHOOT_ARC = 0.16;
const float SHOOT_ARC_EXTRA = 0.20;
const float SHOOT_TRAIL = 0.18;

// Hash without Sine by David Hoskins (shadertoy.com/view/4djSRW, MIT licence, Copyright (c) 2014 David
// Hoskins; see CREDITS.md): four values in 0..1 from one number.
vec4 hash41(float p) {
    vec4 p4 = fract(vec4(p) * vec4(0.1031, 0.1030, 0.0973, 0.1099));
    p4 += dot(p4, p4.wzxy + 33.33);
    return fract((p4.xxyz + p4.yzzw) * p4.zywx);
}

// The shooting star burning at this moment, if any: the time is cut into slots of SkyShoot.x seconds,
// and each slot's hash decides whether it has one (by the chance SkyShoot.y), when in the slot it
// flares, where it starts (20 to 65 degrees up, any way round) and which way it falls. It brightens and
// fades smoothly over its short life, so it never pops in or out.
void shootingStar(float seconds) {
    skyShootHead = vec4(0.0);
    skyShootAxis = vec4(0.0, 1.0, 0.0, 0.0);
    float period = SkyShoot.x;
    if (period <= 0.0) {
        return;
    }
    float slot = floor(seconds / period);
    vec4 r = hash41(slot + 17.0);
    if (r.x >= SkyShoot.y) {
        return;
    }
    vec4 q = hash41(slot * 1.618 + 3.0);
    float duration = SHOOT_DURATION + SHOOT_DURATION_EXTRA * q.x;
    float start = r.y * max(period - duration, 0.0);
    float phase = (seconds - slot * period - start) / duration;
    if (phase <= 0.0 || phase >= 1.0) {
        return;
    }
    float azimuth = 6.2832 * r.z;
    float elevation = radians(20.0 + 45.0 * r.w);
    vec3 from = vec3(cos(azimuth) * cos(elevation), sin(elevation), sin(azimuth) * cos(elevation));
    // Falling: down the sky and to one side, never upwards.
    vec3 side = normalize(cross(from, vec3(0.0, 1.0, 0.0)));
    vec3 down = cross(side, from);
    vec3 heading = normalize(-down * (0.4 + 0.6 * q.y) + side * (q.z * 2.0 - 1.0));
    vec3 axis = normalize(cross(from, heading));
    float arc = SHOOT_ARC + SHOOT_ARC_EXTRA * q.w;
    float travelled = phase * arc;
    // Rodrigues' rotation of from about axis (perpendicular to it) by the arc travelled so far.
    vec3 head = from * cos(travelled) + cross(axis, from) * sin(travelled);
    float glow = sin(3.14159 * phase);
    skyShootHead = vec4(head, glow * glow);
    skyShootAxis = vec4(axis, min(travelled, SHOOT_TRAIL));
}

vec3 unproject(mat4 inverseProjection, vec4 position) {
    vec4 unprojected = inverseProjection * position;
    return unprojected.xyz / unprojected.w;
}

void main() {
    vec2 position = fullscreen_triangle_position();
    gl_Position = vec4(position, NEAR_PLANE_DEPTH, 1.0);
    mat4 inverseProjection = inverse(ProjMat);
    // Two depths and their difference: the translation view bobbing puts in the projection drops out.
    viewDirection = unproject(inverseProjection, vec4(position, FAR_PLANE_DEPTH, 1.0)) - unproject(inverseProjection, vec4(position, NEAR_PLANE_DEPTH, 1.0));

    EtnaSkyFrame f = etnaSkyFrame(SkySun.xyz, SkyMoon.xyz, SkySun.w, SkyMoon.w, SkyLook.w);
    // The biome's tint on the sunlit air, as EtnaSky.java tints the same frame for the land and the fog.
    f.sunLow *= SkyTint.rgb;
    f.sunHigh *= SkyTint.rgb;
    f.multi *= SkyTint.rgb;
    skySunRain = vec4(f.sun, f.rain);
    skyMoonStorm = vec4(f.moon, f.storm);
    skySunLow = f.sunLow;
    skySunHigh = f.sunHigh;
    skyMultiGlow = vec4(f.multi, f.glow);
    skyMoonLow = f.moonLow;
    skyMoonHigh = f.moonHigh;
    skyOvercast = f.overcast;
    skySunDisc = etnaSkyTransmittance(SkySun.y, 0.2);
    shootingStar(SkyLook.z);
}
