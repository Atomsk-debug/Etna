#version 330
#extension GL_ARB_separate_shader_objects : require

// Etna world pass 0, at half resolution: how much of the air in front of each pixel the sun reaches,
// for the light shafts and the sunlit mist that world_light.fsh adds to the picture. One texel stands for
// a 2x2 block of screen pixels; while anti-aliasing runs it takes a different one of the four every
// frame, so over four frames every pixel's own depth has been marched.
//
// The view ray is marched in 8 steps through Etna's shadow map (bound to this pass by PostPassMixin,
// with the terrain's EtnaShadow block), from the camera out to the pixel, or to the edge of the map if
// that comes first. The steps are spaced exponentially, each a fixed ratio longer than the one before
// (the slices of Bart Wronski's "Volumetric Fog", SIGGRAPH 2014), so the air near the camera, where a
// shaft's edge is widest on screen, gets the most of them; together they are shifted by interleaved
// gradient noise (Jorge Jimenez, 2014), a different shift in each pixel of a small block (interleaved
// sampling, Alexander Keller and Wolfgang Heidrich, 2001) and a new one every frame while anti-aliasing
// averages them. Each step weighs the density of the air there (the clear shaft air and the mist,
// air.glsl) by how much of it reaches the eye through the mist in front. Steps the shadow map shades
// count against the light, and the result is the lit share: 1 where the sun reaches all of the air
// along the ray, 0 where none of it.
//
// r: lit share (0 .. 1), g: the distance the ray ran to, in blocks (AIR_SKY_DISTANCE for the sky), which
// the world pass uses to blend only texels that lie at the same depth as its pixel.

#include <etna:settings.glsl>
// The terrain's EtnaShadow block: the shadow map's matrix and size.
#define ETNA_SHADOWS
#include <etna:terrain_ubo.glsl>
#include <etna:shadow_distortion.glsl>
#include <etna:air.glsl>

uniform sampler2D DepthSampler;
uniform sampler2D EtnaShadowSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 DepthSize;
    vec2 EtnaShadowSize;
};

layout(location = 0) out vec4 fragColor;

const int SHAFT_STEPS = 8;
// The last step is 2^SHAFT_CURVE / 2^(SHAFT_CURVE / SHAFT_STEPS) = 11.3 times longer than the first: with
// a 96-block map the first ends two and a half blocks from the camera and the last spans 30 blocks.
const float SHAFT_CURVE = 4.0;
// Each step's distance is (2^(SHAFT_CURVE * x) - 1) / SHAFT_SCALE of the ray, x running from 0 to 1, and
// stepping x by 1 / SHAFT_STEPS multiplies the power by SHAFT_RATIO.
const float SHAFT_SCALE = 15.0;
const float SHAFT_RATIO = 1.41421356;

// Which pixel of the 2x2 block a texel stands for, frame by frame: the two diagonals in turn (the same order
// as Photon Shaders' checkerboard.glsl).
const ivec2 SUBPIXEL[4] = ivec2[4](ivec2(0, 0), ivec2(1, 1), ivec2(1, 0), ivec2(0, 1));

// 1 where the sun reaches a point of the ray, given as a position in the shadow map's clip space, 0 where
// something nearer the light blocks it. The same test and bias as the terrain's unfiltered lookups
// (terrain_shadow.glsl), and outside the map, or fading towards its edge, the point counts as lit. How far
// out it lies is counted from the camera, as the terrain's etnaMapOut does, so the shafts end where the shadows do.
float sunAt(vec3 clip) {
    vec2 away = abs(clip.xy - EtnaShadowMat[3].xy);
    float edge = max(away.x, away.y) / (EtnaShadowInfo.w > 0.0 ? EtnaShadowInfo.w : 1.0);
    if (edge >= 1.0 || max(abs(clip.x), abs(clip.y)) >= 1.0) {
        return 1.0;
    }
    vec2 uv = clip.xy / etnaShadowFactor(clip.xy) * 0.5 + 0.5;
    #ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    float depth = clip.z;
    #else
    float depth = clip.z * 0.5 + 0.5;
    #endif
    // Beyond the far end of the map's depth range the cleared map holds nothing: lit, as in the terrain.
    if (depth <= 0.0) {
        return 1.0;
    }
    // Reversed depth: bigger is closer to the light.
    float stored = textureLod(EtnaShadowSampler, uv, 0.0).r;
    float lit = stored > depth + EtnaShadowInfo.z * 0.05 ? 0.0 : 1.0;
    return mix(lit, 1.0, smoothstep(0.85, 1.0, edge));
}

void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy);
    ivec2 sub = Frame.w > 0.5 ? SUBPIXEL[int(Frame.x) & 3] : ivec2(0);
    ivec2 pixel = min(cell * 2 + sub, ivec2(DepthSize) - 1);
    float depth = texelFetch(DepthSampler, pixel, 0).r;
    vec2 uv = (vec2(pixel) + 0.5) / DepthSize;
    vec3 dir;
    float dist;
    if (etnaIsSky(depth)) {
        // Only the direction matters: a point a hair in front of the far plane, in homogeneous form and
        // turned to face away from the camera, as world_light.fsh finds it.
        vec4 farPoint = InvViewProj * vec4(uv, 1e-6, 1.0);
        vec3 ray = farPoint.w < 0.0 ? -farPoint.xyz : farPoint.xyz;
        dir = ray / max(length(ray), 1e-6);
        dist = AIR_SKY_DISTANCE;
    } else {
        vec3 position = etnaPosition(uv, depth);
        dist = length(position);
        dir = position / max(dist, 1e-4);
    }

    // Nothing to march: no map this frame (Shafts.w; also with shadows, light rays and mist all off), the
    // camera in water, lava or powder snow, or no light to cast shafts (night's first and last moments,
    // full rain). The air then counts as fully lit, which the world pass reads as no shadow in it.
    float clearAir = Shafts.x + Mist.z;
    if (Shafts.w <= 0.0 || Water.x > 0.5 || Air.w < 0.5 || Sun.w < 0.003 || clearAir + Mist.x + Mist.y <= 0.0) {
        fragColor = vec4(1.0, dist, 0.0, 1.0);
        return;
    }

    float march = min(dist, Shafts.w);
    // The map's projection is orthographic, so the ray is a straight line in its clip space as well: the
    // two ends go through the matrix once (the near end is the camera, the matrix's last column) and each
    // step only slides between them. Only the distortion is worked out per step.
    vec3 clipStart = EtnaShadowMat[3].xyz;
    vec3 clipEnd = (EtnaShadowMat * vec4(dir * march, 1.0)).xyz;
    float jitter = etnaNoise(gl_FragCoord.xy);

    float grow = exp2(SHAFT_CURVE * jitter / float(SHAFT_STEPS));
    float edgePower = 1.0;
    float depthSoFar = 0.0;
    float total = 0.0;
    float shaded = 0.0;
    for (int i = 0; i < SHAFT_STEPS; i++) {
        // The step covers the slice from (edgePower - 1) / SHAFT_SCALE to (edgePower * SHAFT_RATIO - 1) /
        // SHAFT_SCALE of the ray and is sampled at a point inside it, shifted by the jitter.
        float slice = march * edgePower * (SHAFT_RATIO - 1.0) / SHAFT_SCALE;
        float along = (grow - 1.0) / SHAFT_SCALE;
        float s = march * along;
        float mist = etnaMistDensity(Mist.w + dir.y * s, s);
        // Light scattered in this slice reaches the eye through the mist in front of it, half the slice's
        // own included.
        float weight = (clearAir + mist) * exp(-(depthSoFar + 0.5 * mist * slice)) * slice;
        depthSoFar += mist * slice;
        total += weight;
        shaded += weight * (1.0 - sunAt(mix(clipStart, clipEnd, along)));
        edgePower *= SHAFT_RATIO;
        grow *= SHAFT_RATIO;
    }
    fragColor = vec4(total > 0.0 ? 1.0 - shaded / total : 1.0, dist, 0.0, 1.0);
}
