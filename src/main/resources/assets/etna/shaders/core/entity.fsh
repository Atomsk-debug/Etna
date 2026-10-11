#version 330
#extension GL_ARB_separate_shader_objects : require
// Based on Minecraft's vanilla shader, (c) Mojang AB, not covered by Etna's MIT licence (CREDITS.md).
// The shadow filter's textureGather (include/terrain_shadow.glsl), enabled before any code as the GLSL spec
// wants; a compiler without it only warns here, and the include then reads one texel at a time.
#extension GL_ARB_texture_gather : enable

// Etna's entity fragment shader, made from vanilla 26.4's. Only Etna's copies of vanilla's entity pipelines use
// it (EtnaEntities.java), and only while Etna is on: with Etna off the game draws with its own shader.
// Everything is vanilla's (the texture, the alpha test, the hurt flash, the dissolve, ColorModulator, the
// enchantment glint, the fog, the improved transparency stages) but the light: where vanilla multiplies by its
// lightmap colour, Etna multiplies by the light the terrain would have in the same place (entity_light.glsl), and
// adds the terrain's distance haze before the fog.

#include <minecraft:globals.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#include <etna:terrain_ubo.glsl>
#include <etna:terrain_touch.glsl>

uniform sampler2D Sampler0;

#ifdef DISSOLVE
uniform sampler2D DissolveMaskSampler;
#endif

#ifdef GLINT
uniform sampler2D GlintSampler;
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;

#ifndef NO_OVERLAY
layout(location = 5) in vec4 overlayColor;
#endif

layout(location = 6) in vec2 texCoord0;
#ifdef GLINT
layout(location = 7) in vec2 texCoordGlint;
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
#include <etna:entity_light.glsl>
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    #endif

    #if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
    color.rgb *= etnaModelLight(etnaModelNormalHere());
    #endif

    #ifdef GLINT
    vec4 glintColor = GlintAlpha * texture(GlintSampler, texCoordGlint);
    // Matches BlendFuntion.GLINT
    color.rgb += glintColor.rgb * glintColor.rgb;
    #endif

    #if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
    color.rgb = etnaModelHaze(color.rgb);
    #endif

    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif

    return color;
}

void main() {
    #if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
    etnaModelBegin();
    #endif

    vec4 color = texture(Sampler0, texCoord0);

    #ifdef OIT_ADDITIVE
    color.a = min(0.99, color.a);
    #endif

    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    vec4 faceVertexColor = vertexColor;

    #ifdef DISSOLVE
    if (faceVertexColor.a < texture(DissolveMaskSampler, texCoord0).a) {
        discard;
    }
    // The dissolve effect entirely replaces translucency
    faceVertexColor.a = 1.0;
    #endif

    color *= faceVertexColor * ColorModulator;

    #ifdef GLINT
    color.a = max(color.a, GlintAlpha);
    #endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
