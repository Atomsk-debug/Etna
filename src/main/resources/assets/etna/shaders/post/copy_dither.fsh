#version 330
#extension GL_ARB_separate_shader_objects : require

// Copies the world chain's result into Minecraft's own picture, fully opaque. The world passes work with
// more than 8 bits per channel and Minecraft's picture has 8: the slow gradients they add (the light
// shafts' glow, mist, the night haze over the sky) would band there, so the copy adds half a step of
// noise, as the final pass does (interleaved gradient noise, Jorge Jimenez, 2014). With anti-aliasing the
// pattern moves every frame; without, it stays put.
//
// Fireflies and motes (EtnaMotes.java) are added here, after the anti-aliasing: they move every frame and
// would leave trails in its history, and as the last world pass this one sees every surface that can hide
// them. The game places them and works out where each one is on screen; each is a small bright Gaussian core,
// never narrower than a pixel, in a faint wide halo that falls smoothly to nothing at its edge. The screen is cut into tiles and each tile
// lists the motes whose halo reaches into it, so a pixel only weighs the few near it, and the pass does no
// work for them at all while there are none (by day, or with the setting off). A mote shows where the
// surface at the pixel lies behind it, fading out as a surface comes in front: grass in front of a firefly
// hides it, a wall between a firefly and the camera hides all of it. Light is added on the squared
// display colour (about linear light), so a halo over a dark night shows and a core over a bright sky
// still saturates smoothly instead of clipping.

#include <etna:settings.glsl>

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
};

// Most motes drawn at once, and the tiles the screen is cut into for them (EtnaMotes.java has the same).
const int MOTES_MAX = 32;
const int MOTE_TILES_X = 32;
const int MOTE_TILES_Y = 18;

// Written every frame by EtnaMotes.java, only the first Motes.x entries hold motes. "Pixels" are this
// pass's own.
layout(std140) uniform EtnaMotes {
    vec4 MotePos[MOTES_MAX];   // xy: centre on screen (uv), z: distance from the camera in blocks, w: how far in front of it, in blocks, a surface may come before it hides the mote completely
    vec4 MoteColor[MOTES_MAX]; // rgb: light of the core at its centre (squared display colour), w: light of the halo at its centre, as a share of rgb
    vec4 MoteShape[MOTES_MAX]; // x: the core's falloff, 1 / (2 sigma^2) per square pixel, y: 1 / the halo's radius squared, in pixels (the mote adds nothing past it), zw: 0
    uvec4 MoteTiles[MOTE_TILES_X * MOTE_TILES_Y / 4]; // one 32-bit mask per tile, row by row from uv (0, 0): bit i set where mote i's halo reaches into the tile
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// The light the motes add at this pixel (squared display colour).
vec3 motes(ivec2 pixel) {
    ivec2 tile = min(ivec2(texCoord * vec2(MOTE_TILES_X, MOTE_TILES_Y)), ivec2(MOTE_TILES_X - 1, MOTE_TILES_Y - 1));
    int t = tile.y * MOTE_TILES_X + tile.x;
    uint mask = MoteTiles[t >> 2][t & 3];
    if (mask == 0u) {
        return vec3(0.0);
    }
    float depth = texelFetch(DepthSampler, pixel, 0).r;
    float surface = etnaIsSky(depth) ? 1.0e6 : length(etnaPosition(texCoord, depth));
    vec3 light = vec3(0.0);
    for (int i = 0; mask != 0u; i++, mask >>= 1u) {
        if ((mask & 1u) == 0u) {
            continue;
        }
        vec2 d = (texCoord - MotePos[i].xy) * OutSize;
        float r2 = dot(d, d);
        vec2 shape = MoteShape[i].xy;
        // The halo is (1 - r^2 / R^2)^6: close to a Gaussian, but it reaches 0 at R with a flat slope, so
        // even over black its edge never shows as a ring.
        float halo = 1.0 - r2 * shape.y;
        if (halo <= 0.0) {
            continue;
        }
        halo *= halo * halo;
        float shown = clamp((surface - MotePos[i].z) / MotePos[i].w + 1.0, 0.0, 1.0);
        light += MoteColor[i].rgb * (exp(-r2 * shape.x) + MoteColor[i].w * halo * halo) * shown;
    }
    return light;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec2 p = gl_FragCoord.xy + (Frame.w > 0.5 ? 5.588238 * mod(Frame.x, 64.0) : 0.0);
    float ign = fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
    vec3 color = texelFetch(InSampler, pixel, 0).rgb;
    // Motes.x is the same for every pixel: with no motes, the pass is the plain copy it always was.
    if (Motes.x > 0.5) {
        color = sqrt(max(color, 0.0) * max(color, 0.0) + motes(pixel));
    }
    fragColor = vec4(color + (ign - 0.5) / 255.0, 1.0);
}
