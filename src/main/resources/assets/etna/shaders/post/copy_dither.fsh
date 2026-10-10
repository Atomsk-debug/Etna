#version 330
#extension GL_ARB_separate_shader_objects : require

// Copies the world chain's result into Minecraft's own picture, fully opaque. The world passes work with
// more than 8 bits per channel and Minecraft's picture has 8: the slow gradients they add (the light
// shafts' glow, mist, the night haze over the sky) would band there, so the copy adds half a step of
// noise, as the final pass does (interleaved gradient noise, Jorge Jimenez, 2014). With anti-aliasing the
// pattern moves every frame; without, it stays put.

#include <etna:settings.glsl>

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 p = gl_FragCoord.xy + (Frame.w > 0.5 ? 5.588238 * mod(Frame.x, 64.0) : 0.0);
    float ign = fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
    vec3 color = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0).rgb;
    fragColor = vec4(color + (ign - 0.5) / 255.0, 1.0);
}
