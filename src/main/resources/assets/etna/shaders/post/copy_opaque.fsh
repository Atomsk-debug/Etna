#version 330
#extension GL_ARB_separate_shader_objects : require

// Copies a picture pixel for pixel into Minecraft's own, fully opaque.

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0).rgb, 1.0);
}
