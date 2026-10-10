#version 330
#extension GL_ARB_separate_shader_objects : require

// Etna's sky, drawn in place of vanilla's sky disc, sunrise band, sun and stars (EtnaSky.java): one
// triangle over the whole screen. The view direction is worked out as vanilla's sky occluder does it.
// Everything about the sun and the moon that is the same for the whole sky (the light reaching the air,
// sky.glsl's EtnaSkyFrame) is worked out here, three times a frame instead of once per pixel, and
// handed to every pixel unchanged.

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

const float NEAR_PLANE_DEPTH = 1.0;
const float FAR_PLANE_DEPTH = 0.0;

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
    skySunRain = vec4(f.sun, f.rain);
    skyMoonStorm = vec4(f.moon, f.storm);
    skySunLow = f.sunLow;
    skySunHigh = f.sunHigh;
    skyMultiGlow = vec4(f.multi, f.glow);
    skyMoonLow = f.moonLow;
    skyMoonHigh = f.moonHigh;
    skyOvercast = f.overcast;
    skySunDisc = etnaSkyTransmittance(SkySun.y, 0.2);
}
