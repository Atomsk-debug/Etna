#version 330
#extension GL_ARB_separate_shader_objects : require

// Etna world pass, at half resolution: the world reflected in the water (screen-space reflections).
// One texel stands for a 2x2 block of screen pixels, always its first, with or without anti-aliasing.
// 0.8.0 traced a different pixel of the block each frame along steps shifted by noise, for the
// anti-aliasing to average, but where only some rays met a thin edge (leaves, a fence, a block's corner)
// the anti-aliasing kept them as specks, and the reflections read as dots. The same rays every frame give
// a steady image that only moves with the camera and the waves. Each ray starts from the point of the water
// the jittered pixel shows (up to half a pixel from its centre, a different way each frame), worked out
// without the jitter, and every read of the depth and the picture is moved by the jitter to where the
// picture holds the point it wants, so the rays stay true to the scene whatever the jitter.
//
// Even so, where a ray only just meets or misses an edge it can go either way from one frame to the next
// (its start moves by up to half a pixel, the waves move on), and a whole 2x2 block, then
// spread over its neighbours by the world pass, changes at once: too smooth for the anti-aliasing, which
// keeps the old frames only where they fit the new one's neighbouring pixels, to tell from a real change,
// so the reflection flickered. With anti-aliasing on, each texel is now blended with the last frames' own
// result for the same point of the water (SSR_BLEND, moved to where the point was on last frame's screen):
// a ray that goes either way gives the average.
//
// Without improved transparency the water is drawn into Minecraft's picture after everything opaque, and
// it writes the depth buffer: after the level the depth there is the water's surface, exactly where a
// reflected ray starts. Etna's water also marks its pixels: its pipelines (EtnaShadows.receiverFor) blend
// alpha with factors 0 and 1 - alpha, so the picture's alpha, 1 after the opaque pass, ends up below 1
// only where water (or glass, or ice) was drawn. Each water pixel seen from above has its ray reflected off
// a calmer copy of the swell the water shader drew (water_waves.glsl, TRACE_WAVES of its slope: the
// mirrored shore wobbles, it doesn't break up) and marched across the screen in STEPS fixed steps, the
// same for every pixel and every frame, so neighbouring pixels find the same things and the reflection
// keeps its shapes. Where the ray first passes behind what the depth buffer holds, the step is halved
// HALVINGS times down to the crossing; it is a hit when the surface there lies no nearer than the ray was
// just before (a hill, the shore, a tree it really ran into), not when the ray merely went behind
// something standing in front of it. Where it meets something (the shore, trees, hills, vanilla's
// clouds), the picture there is what the water mirrors in place of the sky the water shader drew; where it
// leaves the screen, meets nothing, or passes behind something it can't see round, the sky stays. Near
// the screen's edges the world's reflection fades into the sky's.
//
// The water shader drew the sky's reflection as reflection * F (terrain_water.glsl, waterFromAbove),
// before the distance haze and vanilla's render-distance fog. This pass works that same amount out again
// (the same waves, the same sky, the same Fresnel and water reflections setting) and writes how much the
// picture changes when the world's reflection takes its place: rgb, the change to add (it can be
// negative), a: the distance of the water surface in blocks, which the world pass uses to blend only
// texels at its pixel's own distance (water_reflection.glsl). A water pixel whose ray meets nothing still
// writes its distance with no change, so the upsampling averages it with its neighbours' hits rather than
// spreading one lone hit over them. 0 everywhere the pixel isn't water seen from above, with the camera
// in water, lava or powder snow, and while the reflections are off (WaterSsr.x 0: the setting at 0,
// improved transparency on).
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
// This pass's result from the frames before (the world chain's "water_ssr0"; the plain chain, without
// anti-aliasing, never reads it).
uniform sampler2D SsrHistorySampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 DepthSize;
};

layout(location = 0) out vec4 fragColor;

// Steps along the ray, and halvings of the step in which the ray first passes behind something. The same
// with and without anti-aliasing: the steps don't move from frame to frame, so there is nothing for the
// anti-aliasing to average and nothing to flicker.
const int STEPS = 24;
const int HALVINGS = 6;
// Step i lies (i / STEPS)^STEP_CURVE of the way along the ray: closer together near the water, where the
// shore's reflection is and where the depth changes fastest.
const float STEP_CURVE = 1.4;
// Share of the swell's slope the reflected rays follow. At full strength neighbouring rays scatter over
// the far shore and its reflection breaks into specks; at this much it wobbles as a whole.
const float TRACE_WAVES = 0.25;
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
// The ray has run into a surface, rather than gone behind something in front of it, when that surface
// lies no more than this much nearer than the ray was just before the crossing: this many blocks, or this
// share of its distance (far away one pixel of the depth buffer spans more).
const float THICKNESS = 1.5;
const float THICKNESS_SHARE = 0.05;
// Share of the screen, at each edge, over which the world's reflection fades into the sky's.
const float EDGE_FADE = 0.07;
// How much of this frame's result each texel takes, against the last frames' for the same point of the
// water: about the last four frames count. While the camera moves, what the water mirrors shifts against
// the water itself (things nearer and further away by different amounts), and this frame counts more.
const float SSR_BLEND = 0.3;
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

// Nothing to change here: not water seen from above.
void nothing() {
    fragColor = vec4(0.0);
}

// How far, in blocks, the distance from the camera changes across the few pixels between a point and the
// history texels round it (main sets it from the pixel's size on the water): on water seen at a grazing
// angle far away, several blocks a pixel.
float historySlack = 0.0;

// The last frames' change for the water point at camera-relative `position`: rgb, and in a how much of it can
// be gone by (0 none: no history, the point off last frame's screen, or no water at its distance there).
vec4 lastChange(vec3 position) {
    if (Frame.w < 0.5 || Motion.w < 0.5) {
        return vec4(0.0);
    }
    // Last frame's camera was Motion.xyz behind this one's.
    vec3 before = position + Motion.xyz;
    vec4 h = PrevViewProj * vec4(before, 1.0);
    if (h.w <= 0.0) {
        return vec4(0.0);
    }
    vec2 uv = h.xy / h.w;
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {
        return vec4(0.0);
    }
    // Texel c held pixel 2c: blend the four round the point, each only if it held water at the point's
    // distance from last frame's camera, give or take how much that distance changes across them.
    vec2 c = (uv * DepthSize - 0.5) * 0.5;
    ivec2 base = ivec2(floor(c));
    vec2 f = c - vec2(base);
    ivec2 last = textureSize(SsrHistorySampler, 0) - 1;
    float dist = length(before);
    vec3 sum = vec3(0.0);
    float weights = 0.0;
    for (int i = 0; i < 4; i++) {
        ivec2 o = ivec2(i & 1, i >> 1);
        vec4 texel = texelFetch(SsrHistorySampler, clamp(base + o, ivec2(0), last), 0);
        float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
        if (texel.a > 0.0 && abs(texel.a - dist) < 0.3 + 0.03 * dist + historySlack) {
            sum += w * texel.rgb;
            weights += w;
        }
    }
    return weights > 0.01 ? vec4(sum / weights, weights) : vec4(0.0);
}

// This frame's change for the water point at `position`, dist blocks away, blended with the last frames'.
void result(vec3 change, vec3 position, float dist) {
    vec4 before = lastChange(position);
    float moving = smoothstep(0.03, 0.3, length(Motion.xyz));
    float blend = mix(1.0, mix(SSR_BLEND, 1.0, moving), smoothstep(0.25, 1.0, before.a));
    fragColor = vec4(mix(before.rgb, change, blend), dist);
}

// Water whose reflection stays the sky the water shader drew this frame, dist blocks away.
void unchanged(vec3 position, float dist) {
    result(vec3(0.0), position, dist);
}

void main() {
    if (WaterSsr.x <= 0.0 || Water.x > 0.5) {
        nothing();
        return;
    }
    ivec2 cell = ivec2(gl_FragCoord.xy);
    ivec2 pixel = min(cell * 2, ivec2(DepthSize) - 1);
    vec4 picture = texelFetch(InSampler, pixel, 0);
    float depth = depthAtPixel(pixel);
    if (picture.a >= WATER_MASK_LIMIT || etnaIsSky(depth)) {
        nothing();
        return;
    }
    // The picture is moved by +jitter pixels: the pixel shows the point that would sit at its centre less
    // the jitter, and a point at uv lies at uv + jitter in the picture (0 without anti-aliasing).
    vec2 jitter = Frame.yz / DepthSize;
    vec2 uv = (vec2(pixel) + 0.5) / DepthSize - jitter;
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
    // A history texel's point lies up to a texel (two pixels) from this one, and last frame's jitter put it
    // up to half a pixel further: four pixels' change in distance covers both. Capped, so a pixel at the
    // water's edge whose neighbour lies on something else can't let in any history at all.
    vec3 toPoint = normalize(position);
    historySlack = min(4.0 * max(abs(dot(dx, toPoint)), abs(dot(dy, toPoint))), 0.25 * length(position));

    // The swell where this pixel lies, as the water shader drew it (shadeWater): the same waves, as far as
    // the pixel resolves them.
    vec2 slope = swellSeen(WaterSsr.zw + position.xz, WaterSsr.y, footprint);
    vec3 n = normalize(vec3(-slope.x, 1.0, -slope.y));
    float dist = length(position);
    vec3 toEye = -position / dist;
    // The sky's reflection the water drew, and how strong, came off the full swell; the ray follows a
    // calmer one.
    vec3 dir = reflect(-toEye, n);
    vec3 ray = reflect(-toEye, normalize(vec3(-slope.x * TRACE_WAVES, 1.0, -slope.y * TRACE_WAVES)));
    float mirror = waterFresnel(dot(toEye, n)) * WaterSsr.x;
    // The haze and the render-distance fog the water shader's colour went through (aerialHaze in
    // terrain_dark.glsl, calculateFinalColor in terrain.fsh), for the light the reflection adds or takes away.
    float openSky = Air.w > 0.5 ? 1.0 - Shafts.z : 0.0;
    float far = max(MistColor.w, 32.0);
    float hazed = pow(clamp(dist / far, 0.0, 1.0), 1.6) * ETNA_HAZE * 0.6 * openSky;
    float span = clamp(far / 10.0, 4.0, 64.0);
    float fogged = clamp((max(length(position.xz), abs(position.y)) - (far - span)) / span, 0.0, 1.0);
    float keep = (1.0 - hazed) * (1.0 - fogged);
    if (ray.y < 0.02 || mirror * keep < 0.004) {
        // A ray that skims the water, or a reflection too faint to change the picture.
        unchanged(position, dist);
        return;
    }

    // The ray in screen space (uv, depth): a straight line there too, and depth changes linearly along it.
    // It ends RAY_LENGTH blocks out, sooner if it comes back towards the camera, and at the screen's edges.
    vec3 start = position + vec3(0.0, RAY_LIFT, 0.0);
    vec4 hStart = ViewProj * vec4(start, 1.0);
    vec4 hDir = ViewProj * vec4(ray, 0.0);
    float len = RAY_LENGTH;
    if (hDir.w < 0.0) {
        len = min(len, (hStart.w - RAY_NEAR) / -hDir.w);
    }
    vec3 s0 = hStart.xyz / hStart.w;
    vec3 s1 = etnaScreen(ViewProj, start + ray * len);
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
        unchanged(position, dist);
        return;
    }

    float before = 0.0;
    // Whether the ray lay in front of what the depth buffer holds at the last step: it can only run into
    // something where it goes from in front to behind.
    bool ahead = true;
    float found = -1.0;
    for (int i = 1; i <= STEPS; i++) {
        float t = exit * pow(float(i) / float(STEPS), STEP_CURVE);
        vec3 s = s0 + span3 * t;
        // Reversed depth: the ray lies behind what the depth buffer holds where its depth is smaller.
        bool behind = s.z < depthAt(s.xy + jitter);
        if (behind && ahead) {
            // Halve the step down to the point where the ray passes behind that surface.
            float lo = before;
            float hi = t;
            for (int k = 0; k < HALVINGS; k++) {
                float mid = 0.5 * (lo + hi);
                vec3 m = s0 + span3 * mid;
                if (m.z < depthAt(m.xy + jitter)) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            vec3 h = s0 + span3 * hi;
            vec3 there3 = vec3(h.xy, depthAt(h.xy + jitter));
            float surface = viewDepth(there3);
            if (surface > viewDepth(s0 + span3 * lo) - max(THICKNESS, THICKNESS_SHARE * surface)
                    && etnaPosition(there3.xy, there3.z).y > position.y + RAY_ABOVE) {
                found = hi;
                break;
            }
        }
        ahead = !behind;
        before = t;
    }
    if (found < 0.0) {
        unchanged(position, dist);
        return;
    }
    vec3 hit = s0 + span3 * found;
    vec2 edge = min(hit.xy, 1.0 - hit.xy);
    float trust = smoothstep(0.0, EDGE_FADE, min(edge.x, edge.y));
    vec3 world = texelFetch(InSampler, clamp(ivec2((hit.xy + jitter) * InSize), ivec2(0), ivec2(InSize) - 1), 0).rgb;

    // The sky's reflection the water drew, as far as this pixel's colour could hold it.
    // The terrain dims it with the open sky's light (etnaDaylight: low at night and in storms), which
    // EtnaWaterSsr.z estimates.
    vec3 sky = waterSky(dir) * (mirror * keep * EtnaWaterSsr.z);
    vec3 held = picture.rgb / max(sky, vec3(1e-4));
    float open = clamp(min(openSky, min(held.r, min(held.g, held.b))), 0.0, 1.0);
    vec3 change = (world * (mirror * keep) - sky * open) * trust;
    result(change, position, dist);
}
