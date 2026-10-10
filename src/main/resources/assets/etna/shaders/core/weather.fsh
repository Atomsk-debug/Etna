#version 330
#extension GL_ARB_separate_shader_objects : require

// Based on Minecraft's vanilla shader, (c) Mojang AB, not covered by Etna's MIT licence (CREDITS.md).
// Etna's rain and snow fragment shader, made from vanilla 26.4's particle shader and used only by Etna's
// copies of the weather pipelines while Etna is on (EtnaWeather.java), with and without Improved
// Transparency. Vanilla draws rain as fat blue streaks at nearly full opacity: close to the camera each
// texel of the rain texture is several pixels wide.
// * Rain is thinner and fainter: near the camera only the middle of each texel-wide streak is kept, never
//   less than about a pixel and a quarter wide (so a streak never breaks up into flickering bits), and
//   farther away, where a texel is a pixel or less, the texture is drawn as it is. The drops take most of
//   their colour from the light on them (weather.vsh) rather than from the texture's blue, and the whole
//   of the rain is drawn at about half vanilla's opacity.
// * Snow is a little softer: near the camera the flakes, little crosses of texels, are filtered smoothly
//   into soft round flakes instead of hard squares, and drawn a little more see-through.
// * Drops seen towards the sun (or the moon) catch its light: a soft forward glow, only where the column
//   is open to the sky and the light is out (a shower at sunset, rain by moonlight).
// Vanilla's alpha test, fog and Improved Transparency phases are kept as they are.

#define ETNA_WEATHER_LIGHT

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#include <etna:terrain_ubo.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;
layout(location = 4) in vec3 weatherSun;
layout(location = 5) in vec3 weatherViewPos;
layout(location = 6) flat in float weatherRain;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// Opacity of Etna's rain and snow against vanilla's.
const float RAIN_ALPHA = 0.5;
const float SNOW_ALPHA = 0.8;
// Half the width a rain streak keeps near the camera, in texels (vanilla's streaks are one texel wide),
// and the least half width on screen, in pixels.
const float RAIN_HALF_WIDTH = 0.18;
const float RAIN_MIN_HALF_PIXELS = 0.65;
// How far the drops' colour moves from the texture's own (vanilla's is a deep blue) to white, which the
// light then colours.
const float RAIN_PALE = 0.6;
// How much of the sun's light the drops seen right towards it send to the eye, and how tight that glow is.
const float SUN_GLOW = 0.6;
const float SUN_GLOW_G = 0.7;

// Henyey-Greenstein phase function (Henyey and Greenstein, 1941) divided by its value straight towards
// the light: 1 looking at the light, falling off with the angle.
float forwardLobe(float cosTheta, float g) {
    float x = 1.0 + g * g - 2.0 * g * clamp(cosTheta, -1.0, 1.0);
    float r = (1.0 - g) * (1.0 - g) / x;
    return r * sqrt(r);
}

// The rain texture with each streak narrowed to its middle: texels are kept within half width h of their
// centre across the streak (u). h is RAIN_HALF_WIDTH of a texel near the camera and never under
// RAIN_MIN_HALF_PIXELS on screen; once a texel is that narrow on screen the whole texel is kept. The edge
// is smoothed over one pixel, so it is anti-aliased and moves smoothly as the camera does.
vec4 rainTexture() {
    vec4 color = texture(Sampler0, texCoord0);
    float u = texCoord0.x * float(textureSize(Sampler0, 0).x);
    float pixel = max(fwidth(u), 1e-4);
    float halfWidth = max(RAIN_HALF_WIDTH, RAIN_MIN_HALF_PIXELS * pixel);
    float across = abs(fract(u) - 0.5);
    float keep = 1.0 - smoothstep(halfWidth - 0.5 * pixel, halfWidth + 0.5 * pixel, across);
    color.rgb = mix(color.rgb, vec3(1.0), RAIN_PALE);
    return vec4(color.rgb, color.a * keep);
}

// A texel of a texture that repeats, at any whole texel coordinate (GLSL's % is undefined below zero).
vec4 wrappedTexel(sampler2D source, ivec2 texel, ivec2 size) {
    return texelFetch(source, texel - size * ivec2(floor(vec2(texel) / vec2(size))), 0);
}

// The snow texture, filtered smoothly where a texel covers more than a pixel: the four texels around the
// point are blended bilinearly (by texelFetch, wrapping round as the texture repeats: the columns scroll through it), turning each blocky
// flake into a soft one. Where a texel is a pixel or less, vanilla's own nearest read; the two are mixed
// in between, so nothing switches at a distance.
vec4 snowTexture() {
    vec4 nearest = texture(Sampler0, texCoord0);
    ivec2 size = textureSize(Sampler0, 0);
    vec2 p = texCoord0 * vec2(size) - 0.5;
    float pixel = max(fwidth(p.x), fwidth(p.y));
    float smoothness = 1.0 - smoothstep(0.5, 1.0, pixel);
    if (smoothness <= 0.0) {
        return nearest;
    }
    vec2 cell = floor(p);
    vec2 f = p - cell;
    ivec2 c = ivec2(cell);
    vec4 a = wrappedTexel(Sampler0, c, size);
    vec4 b = wrappedTexel(Sampler0, c + ivec2(1, 0), size);
    vec4 d = wrappedTexel(Sampler0, c + ivec2(0, 1), size);
    vec4 e = wrappedTexel(Sampler0, c + ivec2(1, 1), size);
    // Premultiplied, so the colour of the empty texels around a flake doesn't bleed into it.
    vec4 smoothRead = mix(mix(vec4(a.rgb * a.a, a.a), vec4(b.rgb * b.a, b.a), f.x), mix(vec4(d.rgb * d.a, d.a), vec4(e.rgb * e.a, e.a), f.x), f.y);
    smoothRead.rgb /= max(smoothRead.a, 1e-4);
    return mix(nearest, smoothRead, smoothness);
}

vec4 calculateFinalColor(vec4 color) {
    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif

    return color;
}

void main() {
    bool rain = weatherRain > 0.5;
    vec4 tex = rain ? rainTexture() : snowTexture();
    // Vanilla's alpha test, before Etna's own fading: the faint ends of the streaks are kept, only fainter.
    if (tex.a * vertexColor.a * ColorModulator.a < 0.1) {
        discard;
    }
    vec4 color = tex * vertexColor * ColorModulator;
    color.a *= rain ? RAIN_ALPHA : SNOW_ALPHA;
    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    if (weatherSun.r + weatherSun.g + weatherSun.b > 0.0) {
        vec3 viewDir = weatherViewPos * inversesqrt(max(dot(weatherViewPos, weatherViewPos), 1e-6));
        // The light in the block is the sun's by day and the moon's at night: either way the one in the sky.
        color.rgb += tex.rgb * weatherSun * (SUN_GLOW * forwardLobe(dot(viewDir, ETNA_LIGHT_DIR), SUN_GLOW_G));
    }
    fragColor = calculateFinalColor(color);
    #endif
}
