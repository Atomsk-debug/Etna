#version 330
#extension GL_ARB_separate_shader_objects : require

// Etna world pass, at half resolution: the world reflected in the water (screen-space reflections).
// One texel stands for a 2x2 block of screen pixels; while anti-aliasing runs it takes a different one of
// the four every frame, as the light shafts pass does (shafts.fsh), so over four frames every pixel's own
// reflection has been traced.
//
// Without improved transparency the water is drawn into Minecraft's picture after everything opaque, and
// it writes the depth buffer: after the level the depth there is the water's surface, exactly where a
// reflected ray starts. Etna's water also marks its pixels: its pipelines (EtnaShadows.receiverFor) blend
// alpha with factors 0 and 1 - alpha, so the picture's alpha, 1 after the opaque pass, ends up below 1
// only where water (or glass, or ice) was drawn. Each water pixel seen from above has its ray reflected off
// the same swell the water shader drew (water_waves.glsl) and marched across the screen, 12 steps and 3
// halvings with anti-aliasing (the steps shift every frame and the anti-aliasing averages them), 20 and 4
// without (fixed steps, no noise to flicker). Where the ray meets something the depth buffer holds (the
// shore, trees, hills, vanilla's clouds), the picture there is what the water mirrors in place of the
// sky the water shader drew; where it leaves the screen, meets nothing, or passes behind something it
// can't see round, the sky stays. Near the screen's edges the world's reflection fades into the sky's.
//
// The water shader drew the sky's reflection as reflection * F (terrain_water.glsl, waterFromAbove),
// before the distance haze and vanilla's render-distance fog. This pass works that same amount out again
// (the same waves, the same sky, the same Fresnel and water reflections setting) and writes how much the
// picture changes when the world's reflection takes its place: rgb, the change to add (it can be
// negative), a: the distance of the water surface in blocks, which the world pass uses to blend only
// texels at its pixel's own distance (water_reflection.glsl). 0 everywhere the pixel isn't water seen from
// above, with the camera in water, lava or powder snow, and while the reflections are off (WaterSsr.x 0:
// the setting at 0, improved transparency on).
//
// How much sky the water mirrored also depends on how open its surface is to the sky (none in a cave),
// which the picture doesn't say. It is taken as fully open unless the camera is underground, and never
// as more than the pixel's own colour could hold: a cave lake loses at most the light it really has.

#include <etna:settings.glsl>
// The terrain's EtnaShadow block, which PostPassMixin hands this pass: this frame's sky (the reflection
// the water drew) and the distance haze setting.
#define ETNA_SHADOWS
#include <etna:terrain_ubo.glsl>
#define ETNA_SKY_CURRENT
#include <etna:sky.glsl>
#include <etna:water_waves.glsl>

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 DepthSize;
};

layout(location = 0) out vec4 fragColor;

// Which pixel of the 2x2 block a texel stands for, frame by frame (shafts.fsh's order).
const ivec2 SUBPIXEL[4] = ivec2[4](ivec2(0, 0), ivec2(1, 1), ivec2(1, 0), ivec2(0, 1));

// Steps along the ray and halvings of the step that found something, with and without anti-aliasing.
const int STEPS_TAA = 12;
const int REFINE_TAA = 3;
const int STEPS_PLAIN = 20;
const int REFINE_PLAIN = 4;
// Step i lies ((i + shift) / steps)^STEP_CURVE of the way along the ray: closer together near the water,
// where the shore's reflection is and where the depth changes fastest.
const float STEP_CURVE = 1.6;
// How far the ray runs before the screen's edges cut it off, in blocks.
const float RAY_LENGTH = 512.0;
// The ray starts this far above the surface, in blocks, so it can't find the water itself.
const float RAY_LIFT = 0.02;
// What a ray finds must stand at least this far above the water, in blocks. A ray leaving the water
// rises, so anything it meets lies above the surface; the water itself only seems to be met when the ray,
// within the first pixel or two, passes behind the depth the pixel it started from holds (far out a pixel
// of the water spans many blocks at a grazing view), and mirroring it would darken the far water.
const float RAY_ABOVE = 0.05;
// The nearest a ray coming back towards the camera may get, in blocks of view depth.
const float RAY_NEAR = 0.1;
// A surface the ray has passed behind counts as hit only within this depth of it, in blocks, or this
// share of its distance (far away a step of the depth buffer spans more): behind more, the ray has
// gone round the back of something it can't see.
const float THICKNESS = 1.0;
const float THICKNESS_SHARE = 0.02;
// Share of the screen, at each edge, over which the world's reflection fades into the sky's.
const float EDGE_FADE = 0.07;
// Only faces turned within about 25 degrees of straight up reflect: the water's top (flowing water tilts
// a little), not glass panes or the sides of a waterfall.
const float LEVEL = 0.9;
// How close to a whole block a surface's height may lie and still count as a block's top (ice, glass)
// rather than water: wide enough for the depth buffer's error at a distance.
const float BLOCK_TOP = 0.03;

// This frame's sky (sky.glsl), as terrain.fsh's etnaSkyCurrent puts it together from the same block.
EtnaSkyFrame etnaSkyCurrent() {
    EtnaSkyFrame f;
    f.sun = ETNA_MOON ? -EtnaLightDir.xyz : EtnaLightDir.xyz;
    f.moon = -f.sun;
    f.sunLow = EtnaSkySunLow.rgb;
    f.sunHigh = EtnaSkySunHigh.rgb;
    f.multi = EtnaSkyMulti.rgb;
    f.moonLow = EtnaSkyMoonLow.rgb;
    f.moonHigh = EtnaSkyMoonHigh.rgb;
    f.overcast = EtnaSkyOvercast.rgb;
    f.rain = EtnaSkySunLow.w;
    f.storm = EtnaSkySunHigh.w;
    f.glow = EtnaSkyMulti.w;
    return f;
}

// The sky the water mirrors in direction dir, as terrain.fsh's etnaWaterSky has it: the sky model's while
// Etna draws the sky, else a guess from vanilla's fog colour (MistColor holds it then).
vec3 waterSky(vec3 dir) {
    if (ETNA_SKY_DRAWN) {
        return etnaSkyRadiance(normalize(vec3(dir.x, max(dir.y, 0.0), dir.z) + vec3(0.0, 1e-3, 0.0)));
    }
    return mix(MistColor.rgb, vec3(0.42, 0.62, 0.95), 0.35);
}

float depthAt(vec2 uv) {
    return texelFetch(DepthSampler, clamp(ivec2(uv * DepthSize), ivec2(0), ivec2(DepthSize) - 1), 0).r;
}

float depthAtPixel(ivec2 p) {
    return texelFetch(DepthSampler, clamp(p, ivec2(0), ivec2(DepthSize) - 1), 0).r;
}

// The view depth (distance along the camera's axis, in blocks) of a screen point (uv, depth): InvViewProj
// gives it a w of 1 / view depth (world_light.fsh's ambient occlusion uses the same).
float viewDepth(vec3 screen) {
    return 1.0 / dot(vec4(InvViewProj[0].w, InvViewProj[1].w, InvViewProj[2].w, InvViewProj[3].w), vec4(screen, 1.0));
}

// Nothing to change here.
void nothing() {
    fragColor = vec4(0.0);
}

void main() {
    if (WaterSsr.x <= 0.0 || Water.x > 0.5) {
        nothing();
        return;
    }
    ivec2 cell = ivec2(gl_FragCoord.xy);
    ivec2 sub = Frame.w > 0.5 ? SUBPIXEL[int(Frame.x) & 3] : ivec2(0);
    ivec2 pixel = min(cell * 2 + sub, ivec2(DepthSize) - 1);
    vec4 picture = texelFetch(InSampler, pixel, 0);
    float depth = depthAtPixel(pixel);
    if (picture.a >= WATER_MASK_LIMIT || etnaIsSky(depth)) {
        nothing();
        return;
    }
    vec2 uv = (vec2(pixel) + 0.5) / DepthSize;
    vec3 position = etnaPosition(uv, depth);
    // Water seen from above only.
    if (position.y > -RAY_LIFT) {
        nothing();
        return;
    }
    // Water's top never lies on a whole block (a source block's at 0.89, flowing water's lower), while the
    // tops of ice, glass and slime, which leave the same mark, do: those keep their own reflection.
    float level = fract(position.y + EtnaWaterSsr.w);
    if (level < BLOCK_TOP || level > 1.0 - BLOCK_TOP) {
        nothing();
        return;
    }

    // The face's normal from the depth buffer, taking the nearer neighbour on each axis so a pixel at the
    // edge of the water keeps the water's own face (world_light.fsh's normalAt). The same steps give the
    // size of a pixel on the surface, for how much of the swell it resolves.
    vec2 texel = 1.0 / DepthSize;
    vec3 l = etnaPosition(uv - vec2(texel.x, 0.0), depthAtPixel(pixel - ivec2(1, 0)));
    vec3 r = etnaPosition(uv + vec2(texel.x, 0.0), depthAtPixel(pixel + ivec2(1, 0)));
    vec3 d = etnaPosition(uv - vec2(0.0, texel.y), depthAtPixel(pixel - ivec2(0, 1)));
    vec3 u = etnaPosition(uv + vec2(0.0, texel.y), depthAtPixel(pixel + ivec2(0, 1)));
    vec3 dx = distance(r, position) < distance(l, position) ? r - position : position - l;
    vec3 dy = distance(u, position) < distance(d, position) ? u - position : position - d;
    vec3 face = normalize(cross(dx, dy));
    // Written so a face that came out NaN (a one-pixel sliver of water with sky around it) is left too.
    if (!(abs(face.y) >= LEVEL)) {
        nothing();
        return;
    }
    float footprint = max(length(dx), length(dy));

    // The swell where this pixel lies, as the water shader drew it (shadeWater): the same waves, as far as
    // the pixel resolves them.
    vec2 slope = swellSeen(WaterSsr.zw + position.xz, WaterSsr.y, footprint);
    vec3 n = normalize(vec3(-slope.x, 1.0, -slope.y));
    float dist = length(position);
    vec3 toEye = -position / dist;
    vec3 dir = reflect(-toEye, n);
    float mirror = waterFresnel(dot(toEye, n)) * WaterSsr.x;
    // The haze and the render-distance fog the water shader's colour went through (aerialHaze in
    // terrain_dark.glsl, calculateFinalColor in terrain.fsh), for the light the reflection adds or takes away.
    float openSky = Air.w > 0.5 ? 1.0 - Shafts.z : 0.0;
    float far = max(MistColor.w, 32.0);
    float hazed = pow(clamp(dist / far, 0.0, 1.0), 1.6) * ETNA_HAZE * 0.6 * openSky;
    float span = clamp(far / 10.0, 4.0, 64.0);
    float fogged = clamp((max(length(position.xz), abs(position.y)) - (far - span)) / span, 0.0, 1.0);
    float keep = (1.0 - hazed) * (1.0 - fogged);
    if (dir.y < 0.02 || mirror * keep < 0.004) {
        // A ray that skims the water, or a reflection too faint to change the picture.
        nothing();
        return;
    }

    // The ray in screen space (uv, depth): a straight line there too, and depth changes linearly along it.
    // It ends RAY_LENGTH blocks out, sooner if it comes back towards the camera, and at the screen's edges.
    vec3 start = position + vec3(0.0, RAY_LIFT, 0.0);
    vec4 hStart = ViewProj * vec4(start, 1.0);
    vec4 hDir = ViewProj * vec4(dir, 0.0);
    float len = RAY_LENGTH;
    if (hDir.w < 0.0) {
        len = min(len, (hStart.w - RAY_NEAR) / -hDir.w);
    }
    vec3 s0 = hStart.xyz / hStart.w;
    vec3 s1 = etnaScreen(ViewProj, start + dir * len);
    vec3 span3 = s1 - s0;
    float exit = 1.0;
    for (int i = 0; i < 2; i++) {
        if (span3[i] > 1e-6) {
            exit = min(exit, (1.0 - s0[i]) / span3[i]);
        } else if (span3[i] < -1e-6) {
            exit = min(exit, -s0[i] / span3[i]);
        }
    }
    if (span3.z < -1e-9) {
        exit = min(exit, -s0.z / span3.z);
    }
    if (exit <= 0.0) {
        nothing();
        return;
    }

    bool temporal = Frame.w > 0.5;
    int steps = temporal ? STEPS_TAA : STEPS_PLAIN;
    int halvings = temporal ? REFINE_TAA : REFINE_PLAIN;
    float shift = temporal ? etnaNoise(gl_FragCoord.xy) : 0.5;
    float before = 0.0;
    float found = -1.0;
    for (int i = 0; i < steps; i++) {
        float t = exit * pow((float(i) + shift) / float(steps), STEP_CURVE);
        vec3 s = s0 + span3 * t;
        float there = depthAt(s.xy);
        // Reversed depth: the ray lies behind what the depth buffer holds where its depth is smaller.
        if (s.z < there) {
            // Halve the step down to the point where the ray passes behind that surface.
            float lo = before;
            float hi = t;
            for (int k = 0; k < halvings; k++) {
                float mid = 0.5 * (lo + hi);
                vec3 m = s0 + span3 * mid;
                if (m.z < depthAt(m.xy)) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            vec3 h = s0 + span3 * hi;
            vec3 there3 = vec3(h.xy, depthAt(h.xy));
            float surface = viewDepth(there3);
            if (viewDepth(h) - surface < max(THICKNESS, THICKNESS_SHARE * surface)
                    && etnaPosition(there3.xy, there3.z).y > position.y + RAY_ABOVE) {
                found = hi;
                break;
            }
        }
        before = t;
    }
    if (found < 0.0) {
        nothing();
        return;
    }
    vec3 hit = s0 + span3 * found;
    vec2 edge = min(hit.xy, 1.0 - hit.xy);
    float trust = smoothstep(0.0, EDGE_FADE, min(edge.x, edge.y));
    vec3 world = texelFetch(InSampler, clamp(ivec2(hit.xy * InSize), ivec2(0), ivec2(InSize) - 1), 0).rgb;

    // The sky's reflection the water drew, as far as this pixel's colour could hold it.
    // The terrain dims it with the open sky's light (etnaDaylight: low at night and in storms), which
    // EtnaWaterSsr.z estimates.
    vec3 sky = waterSky(dir) * (mirror * keep * EtnaWaterSsr.z);
    vec3 held = picture.rgb / max(sky, vec3(1e-4));
    float open = clamp(min(openSky, min(held.r, min(held.g, held.b))), 0.0, 1.0);
    vec3 change = (world * (mirror * keep) - sky * open) * trust;
    fragColor = vec4(change, dist);
}
