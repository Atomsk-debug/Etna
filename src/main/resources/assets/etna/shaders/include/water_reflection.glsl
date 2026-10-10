#ifndef ETNA_WATER_REFLECTION_GLSL
#define ETNA_WATER_REFLECTION_GLSL

// The world reflected in the water, for the world pass (world_light.fsh, which declares InSampler, the
// picture, before including this). The reflections pass (water_ssr.fsh) has worked out, at half
// resolution, how much each water pixel changes when the world's reflection takes the place of the sky's;
// here that change is added at full resolution. Each of its texels holds the change for one pixel and
// that pixel's distance, so only texels at about this pixel's own distance are blended (joint bilateral
// upsampling, as world_light.fsh's litShare does for the light shafts): a lake's reflection never bleeds
// onto the shore in front of it. With anti-aliasing the four nearest texels are enough (the pass traces a
// different pixel of each 2x2 block every frame and the anti-aliasing averages them), without it the
// sixteen nearest under a tent twice as wide. Only pixels the water marked (the picture's alpha, see
// WATER_MASK_LIMIT) take it.

#include <etna:water_waves.glsl>

uniform sampler2D WaterSsrSampler;

// color: this pixel of the picture, pixel: where it is, dist: how far away what it shows is, in blocks (0
// for the sky).
vec3 etnaWaterReflection(vec3 color, ivec2 pixel, float dist) {
    if (WaterSsr.x <= 0.0 || Water.x > 0.5 || dist <= 0.0 || texelFetch(InSampler, pixel, 0).a >= WATER_MASK_LIMIT) {
        return color;
    }
    int radius = Frame.w > 0.5 ? 1 : 2;
    float spread = float(radius);
    // Texel c holds pixel 2c + sub: with anti-aliasing sub cycles over the 2x2 block, so on average the
    // block's centre; without, its first pixel.
    vec2 p = vec2(pixel) * 0.5 - (Frame.w > 0.5 ? 0.25 : 0.0);
    vec2 corner = floor(p);
    vec2 f = p - corner;
    ivec2 base = ivec2(corner);
    ivec2 last = textureSize(WaterSsrSampler, 0) - 1;
    vec3 sum = vec3(0.0);
    float weights = 0.0;
    vec3 nearest = vec3(0.0);
    float nearestGap = 1e9;
    for (int y = 1 - radius; y <= radius; y++) {
        float wy = max(1.0 - abs(float(y) - f.y) / spread, 0.0);
        for (int x = 1 - radius; x <= radius; x++) {
            float wx = max(1.0 - abs(float(x) - f.x) / spread, 0.0);
            vec4 texel = texelFetch(WaterSsrSampler, clamp(base + ivec2(x, y), ivec2(0), last), 0);
            // Texels that aren't water (distance 0) hold no change and don't count.
            if (texel.a <= 0.0) {
                continue;
            }
            float gap = abs(texel.a - dist) / max(dist, 1.0);
            float w = wx * wy * exp(-8.0 * gap);
            sum += w * texel.rgb;
            weights += w;
            if (gap < nearestGap) {
                nearestGap = gap;
                nearest = texel.rgb;
            }
        }
    }
    // The tent's weights add up to radius squared. Where no texel nearby lies at this distance (a thin
    // strip of water), the one nearest in distance stands in, if it is at all close.
    vec3 change = weights > 0.01 * spread * spread ? sum / weights : nearestGap < 0.1 ? nearest : vec3(0.0);
    return max(color + change, 0.0);
}

#endif
