#version 330
#extension GL_ARB_separate_shader_objects : require

// Based on Minecraft's vanilla shader, (c) Mojang AB, not covered by Etna's MIT licence (CREDITS.md).
// Etna's entity vertex shader, made from vanilla 26.4's: mobs, block entities (chests, banners, shulker boxes,
// skulls...), armour, item frames and the arm. Only Etna's copies of vanilla's entity pipelines use it
// (EtnaEntities.java), and only while Etna is on: with Etna off the game draws with its own shader.
// Everything is vanilla's but the light: vanilla's two fixed lights and its lightmap colour make way for the
// light split of entity_light_vertex.glsl, which entity.fsh lights with the sun, the sky and the shadow map as
// the terrain is. The vertex colour goes on unlit, so the per-face colours of PER_FACE_LIGHTING are one colour
// here and entity.fsh turns the normal round on the back of the face instead.

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
layout(location = 5) in vec3 Normal;

#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler1;
#endif

#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;

#ifndef NO_OVERLAY
layout(location = 5) out vec4 overlayColor;
#endif

layout(location = 6) out vec2 texCoord0;
#ifdef GLINT
layout(location = 7) out vec2 texCoordGlint;
#endif

#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
#include <etna:entity_light_vertex.glsl>
#endif

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

    vertexColor = Color;

#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
    etnaModelLightVertex(Position, Normal, UV2);
#endif

#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
    overlayColor = texelFetch(Sampler1, UV1, 0);
#endif

    texCoord0 = UV0;

#ifdef APPLY_TEXTURE_MATRIX
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif

#ifdef GLINT
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
}
