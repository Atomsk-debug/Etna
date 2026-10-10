#version 330
#extension GL_ARB_separate_shader_objects : require

// Bloom, step 1: shrinks the picture into the first, largest bloom level. Each output pixel averages
// the picture under it with the 13-tap filter of Call of Duty: Advanced Warfare's bloom (Jimenez,
// SIGGRAPH 2014), and each of the filter's five boxes is weighted down by its own brightness (Karis
// average): one bright pixel moving across the screen can't make the glow pulse. Only the top of the
// range feeds it: each read is turned into scene light and keeps just the part above the bloom threshold
// (tone.glsl), so only bright things glow.

#include <etna:settings.glsl>
#include <etna:tone.glsl>

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec3 tap(vec2 offset) {
    // Half an output pixel per step covers the whole area under it at any screen size.
    return etnaBrightPart(etnaSceneLight(texture(InSampler, texCoord + offset * (0.5 / OutSize)).rgb));
}

vec4 box(vec3 a, vec3 b, vec3 c, vec3 d, float weight) {
    vec3 average = (a + b + c + d) * 0.25;
    return vec4(average, 1.0) * weight / (1.0 + dot(average, ETNA_LUMA));
}

void main() {
    vec3 a = tap(vec2(-2.0, -2.0));
    vec3 b = tap(vec2(0.0, -2.0));
    vec3 c = tap(vec2(2.0, -2.0));
    vec3 d = tap(vec2(-1.0, -1.0));
    vec3 e = tap(vec2(1.0, -1.0));
    vec3 f = tap(vec2(-2.0, 0.0));
    vec3 g = tap(vec2(0.0, 0.0));
    vec3 h = tap(vec2(2.0, 0.0));
    vec3 i = tap(vec2(-1.0, 1.0));
    vec3 j = tap(vec2(1.0, 1.0));
    vec3 k = tap(vec2(-2.0, 2.0));
    vec3 l = tap(vec2(0.0, 2.0));
    vec3 m = tap(vec2(2.0, 2.0));

    vec4 sum = box(d, e, i, j, 0.5)
        + box(a, b, f, g, 0.125)
        + box(b, c, g, h, 0.125)
        + box(f, g, k, l, 0.125)
        + box(g, h, l, m, 0.125);
    fragColor = vec4(sum.rgb / sum.a, 1.0);
}
