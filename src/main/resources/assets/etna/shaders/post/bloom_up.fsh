#version 330
#extension GL_ARB_separate_shader_objects : require

// Bloom, steps 7-11: from the smallest level back up, each level is blended with the one below it
// enlarged by a 3x3 tent filter. The result is a soft glow with a bright core and wide falloff, built
// the way Photon builds its bloom (by sixthsurge, see CREDITS.md); the bloom size setting decides how
// much the wide levels count.

#include <etna:settings.glsl>

uniform sampler2D DstSampler;
uniform sampler2D SrcSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 DstSize;
    vec2 SrcSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec3 tap(vec2 offset) {
    return texture(SrcSampler, texCoord + offset / SrcSize).rgb;
}

void main() {
    vec3 enlarged = (tap(vec2(-1.0, -1.0)) + tap(vec2(1.0, -1.0)) + tap(vec2(-1.0, 1.0)) + tap(vec2(1.0, 1.0))
        + 2.0 * (tap(vec2(0.0, -1.0)) + tap(vec2(-1.0, 0.0)) + tap(vec2(1.0, 0.0)) + tap(vec2(0.0, 1.0)))
        + 4.0 * tap(vec2(0.0))) * (1.0 / 16.0);
    float spread = clamp(0.25 + 0.3 * Glow.z, 0.25, 0.9);
    fragColor = vec4(mix(texture(DstSampler, texCoord).rgb, enlarged, spread), 1.0);
}
