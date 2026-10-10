#version 330
#extension GL_ARB_separate_shader_objects : require

// Bloom, steps 2-6: each bloom level is the one before at half the size, with the same 13-tap filter
// as the first step (Call of Duty: Advanced Warfare, Jimenez 2014), which doesn't flicker as things
// move the way a plain 2x2 average does.

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec3 tap(vec2 offset) {
    return texture(InSampler, texCoord + offset * (0.5 / OutSize)).rgb;
}

void main() {
    vec3 color = (tap(vec2(-1.0, -1.0)) + tap(vec2(1.0, -1.0)) + tap(vec2(-1.0, 1.0)) + tap(vec2(1.0, 1.0))) * 0.125;
    color += (tap(vec2(0.0, -2.0)) + tap(vec2(-2.0, 0.0)) + tap(vec2(2.0, 0.0)) + tap(vec2(0.0, 2.0))) * 0.0625;
    color += (tap(vec2(-2.0, -2.0)) + tap(vec2(2.0, -2.0)) + tap(vec2(-2.0, 2.0)) + tap(vec2(2.0, 2.0))) * 0.03125;
    color += tap(vec2(0.0)) * 0.125;
    fragColor = vec4(color, 1.0);
}
