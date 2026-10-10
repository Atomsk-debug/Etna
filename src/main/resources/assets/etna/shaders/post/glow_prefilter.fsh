#version 330
#extension GL_ARB_separate_shader_objects : require

// The mood's dreamy glow, step 1: shrinks the whole picture (not only its bright part, as the bloom does)
// to a quarter of the screen's size, in display light. Each output pixel is the plain average of the
// pixels under it: four bilinear reads, each in the middle of a 2x2 block, cover its 4x4 block exactly,
// so every pixel of the picture counts once and a thin bright line moving across the screen can't make
// the glow pulse. The levels after it (bloom_down.fsh) blur it further.

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec3 tap(vec2 offset) {
    vec3 c = texture(InSampler, texCoord + offset).rgb;
    return c * c;
}

void main() {
    // A quarter of an output pixel each way: one input pixel on a 1920x1080 screen, where the target is
    // a quarter of the screen (gen_post_effects.py), so each read lands where four input pixels meet.
    vec2 quarter = 0.25 / OutSize;
    vec3 sum = tap(vec2(-quarter.x, -quarter.y)) + tap(vec2(quarter.x, -quarter.y)) + tap(vec2(-quarter.x, quarter.y)) + tap(vec2(quarter.x, quarter.y));
    fragColor = vec4(sum * 0.25, 1.0);
}
