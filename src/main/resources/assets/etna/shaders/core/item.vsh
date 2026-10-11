#version 330
#extension GL_ARB_separate_shader_objects : require

// Based on Minecraft's vanilla shader, (c) Mojang AB, not covered by Etna's MIT licence (CREDITS.md).
// Etna's item vertex shader, made from vanilla 26.4's: dropped items, items in frames and held items, in the
// world and in the hand. Only Etna's copies of vanilla's item pipelines use it (EtnaEntities.java), and only
// while Etna is on and outside the menus: with Etna off, and for every item in the inventory and the other
// menus, the game draws with its own shader. Everything is vanilla's but the light: vanilla's two fixed lights
// and its lightmap colour make way for the light split of entity_light_vertex.glsl, which item.fsh lights with
// the sun, the sky and the shadow map as the terrain is.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <etna:terrain_ubo.glsl>
#include <etna:terrain_touch.glsl>
#include <etna:terrain_dark_vertex.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
#ifdef GLINT_SPECIAL
layout(location = 5) in vec2 UV3;
#endif
layout(location = 6) in vec3 Normal;

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif
layout(location = 2) out vec4 vertexColor;
#ifndef OIT_ALPHA_ONLY
layout(location = 4) out vec4 overlayColor;
#endif

layout(location = 5) out vec2 texCoord0;
#ifdef GLINT
layout(location = 6) out vec2 texCoordGlint;
#endif

#ifndef OIT_ALPHA_ONLY
#include <etna:entity_light_vertex.glsl>
#endif

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    #ifndef OIT_ALPHA_ONLY
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    #endif
    vertexColor = Color;
    #ifndef OIT_ALPHA_ONLY
    etnaModelLightVertex(Position, Normal, UV2);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    #endif

    texCoord0 = UV0;
    #ifdef GLINT
    #ifdef GLINT_SPECIAL
    texCoordGlint = (TextureMat * vec4(UV3, 0.0, 1.0)).xy;
    #else
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    #endif
    #endif
}
