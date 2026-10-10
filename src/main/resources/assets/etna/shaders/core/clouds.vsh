#version 330
#extension GL_ARB_separate_shader_objects : require

// Based on Minecraft's vanilla shader, (c) Mojang AB, not covered by Etna's MIT licence (CREDITS.md).
// Etna's cloud vertex shader, made from vanilla 26.4's and used only by Etna's copies of the cloud
// pipelines while Etna is on (EtnaClouds.java). The geometry is vanilla's, untouched: the
// same cell buffer, the same faces in the same places. Only the shading changes.
//
// ETNA_CLOUD_LIGHT is set on Etna's copies of the cloud pipelines (EtnaClouds.java), which also bind
// Etna's light: clouds.fsh then lights each face of a cloud box by the sun, so this shader passes on
// which way the face looks, how high up the cell each vertex sits and where it is. Without it the
// clouds are vanilla's, one fixed grey per face baked into the vertex colour.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

const int FLAG_MASK_DIR = 7;
const int FLAG_INSIDE_FACE = 1 << 4;
const int FLAG_USE_TOP_COLOR = 1 << 5;
const int FLAG_EXTRA_Z = 1 << 6;
const int FLAG_EXTRA_X = 1 << 7;

layout(std140) uniform CloudInfo {
    vec4 CloudColor;
    vec3 CloudOffset;
    vec3 CellSize;
};

uniform isamplerBuffer CloudFaces;

layout(location = 0) out float vertexDistance;
layout(location = 1) out vec4 vertexColor;    // lit: vanilla's cloud colour (time of day, weather), without any face shading
#ifdef ETNA_CLOUD_LIGHT
layout(location = 2) out float cloudHeight;   // 0 at the bottom of the cloud layer, 1 at the top
layout(location = 3) out vec3 cloudNormal;    // the way the face looks
layout(location = 4) out vec3 cloudPosition;  // relative to the camera, in blocks
#endif

/*
// vertex function below should generate vec3s matching the indices in this array, but using less constants
const vec3[] vertices = vec3[](
    // Bottom face
    vec3(1, 0, 0),
    vec3(1, 0, 1),
    vec3(0, 0, 1),
    vec3(0, 0, 0),
    // Top face
    vec3(0, 1, 0),
    vec3(0, 1, 1),
    vec3(1, 1, 1),
    vec3(1, 1, 0),
    // North face
    vec3(0, 0, 0),
    vec3(0, 1, 0),
    vec3(1, 1, 0),
    vec3(1, 0, 0),
    // South face
    vec3(1, 0, 1),
    vec3(1, 1, 1),
    vec3(0, 1, 1),
    vec3(0, 0, 1),
    // West face
    vec3(0, 0, 1),
    vec3(0, 1, 1),
    vec3(0, 1, 0),
    vec3(0, 0, 0),
    // East face
    vec3(1, 0, 0),
    vec3(1, 1, 0),
    vec3(1, 1, 1),
    vec3(1, 0, 1)
);
*/

const int packedX = 0xF03CC3;
const int packedY = 0x6666F0;
const int packedZ = 0xC3F066;

vec3 vertex(int index) {
    vec3 pos = vec3(0);
    pos.x = float((packedX >> index) & 1);
    pos.y = float((packedY >> index) & 1);
    pos.z = float((packedZ >> index) & 1);
    return pos;
}

#ifdef ETNA_CLOUD_LIGHT
// Outward normal of each face, in the order of Minecraft's Direction: down, up, north, south, west, east.
const vec3[] faceNormals = vec3[](
    vec3(0.0, -1.0, 0.0),
    vec3(0.0, 1.0, 0.0),
    vec3(0.0, 0.0, -1.0),
    vec3(0.0, 0.0, 1.0),
    vec3(-1.0, 0.0, 0.0),
    vec3(1.0, 0.0, 0.0)
);
#else
const vec4[] faceColors = vec4[](
    // Bottom face
    vec4(0.7, 0.7, 0.7, 1.0),
    // Top face
    vec4(1.0, 1.0, 1.0, 1.0),
    // North face
    vec4(0.8, 0.8, 0.8, 1.0),
    // South face
    vec4(0.8, 0.8, 0.8, 1.0),
    // West face
    vec4(0.9, 0.9, 0.9, 1.0),
    // East face
    vec4(0.9, 0.9, 0.9, 1.0)
);
#endif

void main() {
    int quadVertex = gl_VertexIndex % 4;
    int index = (gl_VertexIndex / 4) * 3;

    int cellX = texelFetch(CloudFaces, index).r;
    int cellZ = texelFetch(CloudFaces, index + 1).r;
    int dirAndFlags = texelFetch(CloudFaces, index + 2).r;
    int direction = dirAndFlags & FLAG_MASK_DIR;
    bool isInsideFace = (dirAndFlags & FLAG_INSIDE_FACE) == FLAG_INSIDE_FACE;
    bool useTopColor = (dirAndFlags & FLAG_USE_TOP_COLOR) == FLAG_USE_TOP_COLOR;
    cellX = (cellX << 1) | ((dirAndFlags & FLAG_EXTRA_X) >> 7);
    cellZ = (cellZ << 1) | ((dirAndFlags & FLAG_EXTRA_Z) >> 6);
    vec3 faceVertex = vertex((direction * 4) + (isInsideFace ? 3 - quadVertex : quadVertex));
    vec3 pos = (faceVertex * CellSize) + (vec3(cellX, 0, cellZ) * CellSize) + CloudOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_spherical_distance(pos);
    #ifdef ETNA_CLOUD_LIGHT
    vertexColor = CloudColor;
    cloudPosition = pos;
    // Fast clouds are a single flat sheet that vanilla colours like a top face from either side.
    if (useTopColor) {
        cloudHeight = 1.0;
        cloudNormal = vec3(0.0, 1.0, 0.0);
    } else {
        cloudHeight = faceVertex.y;
        // The faces vanilla adds around a camera inside a cloud are seen from within: they look inwards.
        cloudNormal = isInsideFace ? -faceNormals[direction] : faceNormals[direction];
    }
    #else
    vertexColor = (useTopColor ? faceColors[1] : faceColors[direction]) * CloudColor;
    #endif
}
