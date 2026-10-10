#version 330
#extension GL_ARB_separate_shader_objects : require

// The mood's dreamy glow, last step, at a quarter of the screen's size: the one texture the final pass
// reads for it (grade.glsl, etnaMoodGlow). rgb is the wide blur, the two smallest levels enlarged
// smoothly and mixed by the mood's glow radius; alpha is the luminance of the narrower level, for the
// halation's tighter fringe. Doing this here, on a sixteenth of the pixels, leaves the final pass a single
// read.

#include <etna:settings.glsl>

uniform sampler2D NarrowSampler;
uniform sampler2D MidSampler;
uniform sampler2D WideSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 NarrowSize;
    vec2 MidSize;
    vec2 WideSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// A small level enlarged with a tent filter: four bilinear reads half a texel off each diagonal weigh
// the 3x3 texels around this point 1-2-1, which hides the level's texel grid.
vec3 enlarge(sampler2D level, vec2 size) {
    vec2 d = 0.5 / size;
    return 0.25 * (texture(level, texCoord + vec2(-d.x, -d.y)).rgb + texture(level, texCoord + vec2(d.x, -d.y)).rgb
        + texture(level, texCoord + vec2(-d.x, d.y)).rgb + texture(level, texCoord + vec2(d.x, d.y)).rgb);
}

void main() {
    vec3 wide = mix(enlarge(MidSampler, MidSize), enlarge(WideSampler, WideSize), Mood3.w);
    float narrow = dot(texture(NarrowSampler, texCoord).rgb, ETNA_LUMA);
    fragColor = vec4(wide, narrow);
}
