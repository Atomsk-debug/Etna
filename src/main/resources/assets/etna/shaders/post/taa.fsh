#version 330
#extension GL_ARB_separate_shader_objects : require

// Etna world pass 2: temporal anti-aliasing, the pass that keeps the picture steady. Ported from
// Photon Shaders (by SixthSurge: program/c4_taa_exposure.fsh and include/utility/bicubic.glsl),
// under the Photon Shaders License (LICENSE-PHOTON.txt, see CREDITS.md), and adapted to Minecraft's reversed depth buffer and Etna's camera matrices.
// Every frame the camera is moved by a fraction of a pixel (EtnaFrame.java). Here last frame's
// result is found again through the camera's movement, read back with a sharp Catmull-Rom filter and
// blended with the new frame, after being pulled into the range of colours around the pixel in the
// new frame, so moving things leave no trails. Edges stop crawling, and the noise of the passes
// before (ambient occlusion, light shafts, soft shadows) averages out.

#include <etna:settings.glsl>

uniform sampler2D InSampler;
uniform sampler2D HistorySampler;
uniform sampler2D DepthSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 HistorySize;
    vec2 DepthSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// How much a sample far from a history pixel's centre is trusted less (less blur in motion).
const float OFFCENTER_REJECTION = 0.25;

vec3 minOf(vec3 a, vec3 b, vec3 c, vec3 d, vec3 f) {
    return min(a, min(b, min(c, min(d, f))));
}

vec3 maxOf(vec3 a, vec3 b, vec3 c, vec3 d, vec3 f) {
    return max(a, max(b, max(c, max(d, f))));
}

// Blending happens on a compressed scale, so bright pixels don't dominate the average.
vec3 reinhard(vec3 rgb) {
    return rgb / (rgb + 1.0);
}

vec3 reinhardInverse(vec3 rgb) {
    return rgb / (1.0 - rgb);
}

vec3 rgbToYcocg(vec3 rgb) {
    const mat3 cm = mat3(0.25, 0.5, 0.25, 0.5, 0.0, -0.5, -0.25, 0.5, -0.25);
    return rgb * cm;
}

vec3 ycocgToRgb(vec3 ycocg) {
    float tmp = ycocg.x - ycocg.z;
    return vec3(tmp + ycocg.y, ycocg.x + ycocg.z, tmp - ycocg.y);
}

// The nearest surface among five pixels in a cross: things in front reproject with their own
// movement, so their edges don't smear into what's behind them. Reversed depth: nearest is biggest.
vec3 closestFragment(ivec2 texel0) {
    ivec2 limit = ivec2(DepthSize) - 1;
    ivec2 texel1 = clamp(texel0 + ivec2(-2, -2), ivec2(0), limit);
    ivec2 texel2 = clamp(texel0 + ivec2(2, -2), ivec2(0), limit);
    ivec2 texel3 = clamp(texel0 + ivec2(-2, 2), ivec2(0), limit);
    ivec2 texel4 = clamp(texel0 + ivec2(2, 2), ivec2(0), limit);

    float depth0 = texelFetch(DepthSampler, texel0, 0).r;
    float depth1 = texelFetch(DepthSampler, texel1, 0).r;
    float depth2 = texelFetch(DepthSampler, texel2, 0).r;
    float depth3 = texelFetch(DepthSampler, texel3, 0).r;
    float depth4 = texelFetch(DepthSampler, texel4, 0).r;

    vec3 pos = depth0 > depth1 ? vec3(texel0, depth0) : vec3(texel1, depth1);
    vec3 pos1 = depth2 > depth3 ? vec3(texel2, depth2) : vec3(texel3, depth3);
    pos = pos.z > pos1.z ? pos : pos1;
    pos = pos.z > depth4 ? pos : vec3(texel4, depth4);

    return vec3((pos.xy + 0.5) / DepthSize, pos.z);
}

// AABB clipping from "Temporal Reprojection Anti-Aliasing in INSIDE".
vec3 clipAabb(vec3 historyColor, vec3 minColor, vec3 maxColor) {
    vec3 pClip = 0.5 * (maxColor + minColor);
    vec3 eClip = 0.5 * (maxColor - minColor);
    vec3 vClip = historyColor - pClip;
    vec3 vUnit = vClip / max(eClip, 1e-3);
    vec3 aUnit = abs(vUnit);
    float maUnit = max(aUnit.x, max(aUnit.y, aUnit.z));
    return maUnit > 1.0 ? pClip + vClip / maUnit : historyColor;
}

vec3 fetchYcocg(ivec2 texel) {
    texel = clamp(texel, ivec2(0), ivec2(InSize) - 1);
    return rgbToYcocg(reinhard(texelFetch(InSampler, texel, 0).rgb));
}

vec3 neighborhoodClipping(ivec2 texel, vec3 currentColor, vec3 historyColor, float distanceFactor) {
    // 3x3 neighbourhood
    // a b c
    // d e f
    // g h i
    vec3 a = fetchYcocg(texel + ivec2(-1, 1));
    vec3 b = fetchYcocg(texel + ivec2(0, 1));
    vec3 c = fetchYcocg(texel + ivec2(1, 1));
    vec3 d = fetchYcocg(texel + ivec2(-1, 0));
    vec3 e = rgbToYcocg(currentColor);
    vec3 f = fetchYcocg(texel + ivec2(1, 0));
    vec3 g = fetchYcocg(texel + ivec2(-1, -1));
    vec3 h = fetchYcocg(texel + ivec2(0, -1));
    vec3 i = fetchYcocg(texel + ivec2(1, -1));

    // Soft minimum and maximum ("Hybrid Reconstruction Antialiasing")
    vec3 minColor = minOf(b, d, e, f, h);
    minColor += minOf(minColor, a, c, g, i);
    minColor *= 0.5;
    vec3 maxColor = maxOf(b, d, e, f, h);
    maxColor += maxOf(maxColor, a, c, g, i);
    maxColor *= 0.5;

    // Variance clipping ("An Excursion in Temporal Supersampling"). Higher gamma: steadier but more
    // ghosting, so it is only relaxed far from the camera.
    vec3 mu = (a + b + c + d + e + f + g + h + i) * (1.0 / 9.0);
    vec3 m2 = (a * a + b * b + c * c + d * d + e * e + f * f + g * g + h * h + i * i) * (1.0 / 9.0);
    vec3 sigma = sqrt(max(m2 - mu * mu, 0.0));
    float gamma = mix(0.75, 1.25, clamp((distanceFactor - 0.25) / 0.75, 0.0, 1.0));
    minColor = max(minColor, mu - gamma * sigma);
    maxColor = min(maxColor, mu + gamma * sigma);

    return ycocgToRgb(clipAabb(rgbToYcocg(historyColor), minColor, maxColor));
}

// Catmull-Rom from five bilinear taps (Siggraph 2016 SMAA presentation), so the history stays sharp
// however many frames it is carried.
vec3 catmullRomFast(sampler2D tex, vec2 coord, const float sharpness) {
    vec2 res = HistorySize;
    vec2 pixelSize = 1.0 / res;
    vec2 position = res * coord;
    vec2 centerPosition = floor(position - 0.5) + 0.5;
    vec2 f = position - centerPosition;
    vec2 f2 = f * f;
    vec2 f3 = f * f2;

    vec2 w0 = -sharpness * f3 + 2.0 * sharpness * f2 - sharpness * f;
    vec2 w1 = (2.0 - sharpness) * f3 - (3.0 - sharpness) * f2 + 1.0;
    vec2 w2 = -(2.0 - sharpness) * f3 + (3.0 - 2.0 * sharpness) * f2 + sharpness * f;
    vec2 w3 = sharpness * f3 - sharpness * f2;

    vec2 w12 = w1 + w2;
    vec2 tc12 = pixelSize * (centerPosition + w2 / w12);
    vec3 centerColor = texture(tex, tc12).rgb;
    vec2 tc0 = pixelSize * (centerPosition - 1.0);
    vec2 tc3 = pixelSize * (centerPosition + 2.0);

    vec4 color = vec4(texture(tex, vec2(tc12.x, tc0.y)).rgb, 1.0) * (w12.x * w0.y)
        + vec4(texture(tex, vec2(tc0.x, tc12.y)).rgb, 1.0) * (w0.x * w12.y)
        + vec4(centerColor, 1.0) * (w12.x * w12.y)
        + vec4(texture(tex, vec2(tc3.x, tc12.y)).rgb, 1.0) * (w3.x * w12.y)
        + vec4(texture(tex, vec2(tc12.x, tc3.y)).rgb, 1.0) * (w12.x * w3.y);
    return color.rgb / color.a;
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec3 currentColor = texelFetch(InSampler, texel, 0).rgb;

    vec3 closest = closestFragment(texel);
    // The sky sits on the far plane; a hair in front of it keeps the maths finite.
    vec3 position = etnaPosition(closest.xy, max(closest.z, 1e-6));
    vec2 velocity = closest.xy - etnaScreen(PrevViewProj, position + Motion.xyz).xy;
    vec2 previousUv = texCoord - velocity;

    vec3 historyColor = max(catmullRomFast(HistorySampler, previousUv, 0.6), 0.0);
    bool inside = all(greaterThanEqual(previousUv, vec2(0.0))) && all(lessThanEqual(previousUv, vec2(1.0)));
    float age = texelFetch(HistorySampler, clamp(ivec2(previousUv * HistorySize), ivec2(0), ivec2(HistorySize) - 1), 0).a;
    // A fresh history (first frame, resized window, teleport) starts over from this frame alone.
    age = Motion.w > 0.5 && inside ? max(age, 0.0) + 1.0 : 1.0;

    // Favour responsiveness close to the camera and stability further away. Photon starts at 0.35
    // next to the camera; Etna's ambient occlusion, light shafts and soft shadows change their noise
    // every frame and count on this pass alone to average it, and at 0.35 a fifth of that noise still
    // showed as shimmer in the shade around the player. The clipping above keeps the lower weight
    // from leaving trails.
    float distanceFactor = 1.0 - exp2(-0.025 * length(position));
    float blendWeight = mix(0.18, 0.08, distanceFactor);
    float alpha = max(1.0 / age, blendWeight);

    currentColor = reinhard(currentColor);
    historyColor = neighborhoodClipping(texel, currentColor, reinhard(historyColor), distanceFactor);

    // Offcenter rejection (Jessie, originally Zombye): reduces blur in motion.
    vec2 pixelOffset = 1.0 - abs(2.0 * fract(HistorySize * previousUv) - 1.0);
    float offcenter = sqrt(pixelOffset.x * pixelOffset.y) * OFFCENTER_REJECTION + (1.0 - OFFCENTER_REJECTION);
    alpha = 1.0 - (1.0 - alpha) * offcenter;

    vec3 result = reinhardInverse(min(mix(historyColor, currentColor, alpha), vec3(0.999)));
    fragColor = vec4(result, age * offcenter);
}
