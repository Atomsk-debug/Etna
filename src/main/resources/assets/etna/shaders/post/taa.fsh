#version 330
#extension GL_ARB_separate_shader_objects : require

// Etna world pass 2: temporal anti-aliasing, the pass that keeps the picture steady, and that can also
// rebuild a full-size picture from a world drawn at a lower resolution (temporal upsampling).
//
// Every frame the camera is moved by a different fraction of a pixel (a Halton sequence, EtnaFrame.java;
// 8 steps at full size, 16 when the world is drawn at about 0.75 of the screen per axis), so over a few
// frames the pixels of the level see every part of their area. This pass keeps a running average of
// those frames, at the size of the screen:
//
//  - This frame's contribution is rebuilt from the 3x3 level pixels around the screen pixel, each
//    weighted by how close the point it saw (its centre moved by the jitter) lies to the screen pixel's
//    centre: a small Gaussian, so a sample right on the centre counts most. The weights also say how much
//    this frame knows about the pixel, and the running average adds them up (alpha keeps the total, in
//    frames' worth of samples, so a fresh history takes this frame's share as 1, 1/2, 1/3 ... down to
//    the settled share): a frame whose samples all landed far from the centre adds little and the
//    history carries the pixel. This follows Yang, Nehab, Sander, Sitthi-amorn, Lawrence and Hoppe,
//    "Amortized Supersampling" (2009), which weighs each new sample by its distance to the pixel centre,
//    and is how temporal upsampling rebuilds an output pixel from jittered samples of a smaller picture
//    (Unreal Engine's documentation of TAAU and screen percentage). At full size the centre sample
//    carries almost all the weight and this is plain temporal anti-aliasing.
//  - Last frame's result is found again through the camera's movement, with the depth of the nearest
//    surface around the pixel so the edges of near things move with them (Brian Karis, "High Quality
//    Temporal Supersampling", SIGGRAPH 2014), and read back with a Catmull-Rom spline from five bilinear
//    reads (Jorge Jimenez, "Filmic SMAA", SIGGRAPH 2016), which stays sharp where a bilinear read would
//    blur a little more every frame.
//  - Before the blend that history is pulled into the box of colours found around the pixel in this
//    frame, in YCoCg (Karis 2014), sized by the mean and standard deviation of those colours (Marco Salvi,
//    "An Excursion in Temporal Supersampling", GDC 2016), along the line towards the box's centre (Lasse
//    Jon Fuglsang Pedersen, "Temporal Reprojection Anti-Aliasing in INSIDE", GDC 2016). Moving things and
//    newly uncovered surfaces leave no trails, while the per-frame noise of the passes before (ambient
//    occlusion, light shafts, soft shadows) stays inside the box and averages out.
//
// Edges stop crawling and the noise settles, and with the world drawn smaller than the screen the jitter
// fills in the detail the smaller picture misses.

#include <etna:settings.glsl>

uniform sampler2D InSampler;
uniform sampler2D HistorySampler;
uniform sampler2D DepthSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 HistorySize;
    vec2 DepthSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// This frame's share of the result once the history is full, close to the camera and far away. Far off
// a pixel covers many texels and the noise of the passes before is strongest, so the history counts more
// there; close by the picture follows changes in light sooner.
const float SHARE_NEAR = 0.18;
const float SHARE_FAR = 0.08;
// How the share eases from near to far: 1 - exp2(-falloff * distance in blocks) mixes them.
const float SHARE_FALLOFF = 0.025;
// Spread (standard deviation) of the Gaussian that weighs this frame's samples, in screen pixels. Small
// enough that the result is about as sharp as a pixel-sized box, wide enough that a sample between two
// pixels counts for both.
const float SAMPLE_SPREAD = 0.35;
// Half the size of the colour box around the pixel, in standard deviations of the colours in it. The
// noise of the passes before widens the box by itself; at a smaller render the box is widened by the
// render scale, as its samples lie further apart and say less about the screen pixel.
const float BOX_SIZE = 1.5;
// How much less the history counts when it is read half way between its pixels (where even the
// Catmull-Rom spline softens it a little), so a moving picture keeps its edges.
const float BETWEEN_PIXELS_LOSS = 0.5;
// How much less the history counts for each box size it had to be pulled in by: where something moved
// or was uncovered, this frame takes over sooner.
const float CLIP_FORGET = 0.5;
// This frame's weight (1: an average frame at full size) from which the history is pulled all the way
// into the box. A frame whose samples all landed far from the pixel (only at a smaller render) pulls it
// in less: its box is a poor guess, and pulling the history towards it would blur the detail earlier
// frames found. At full size every frame weighs more than this, so the clip is always complete.
const float FULL_CLIP_WEIGHT = 1.0;

vec3 toYCoCg(vec3 c) {
    return vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b, 0.5 * (c.r - c.b), 0.5 * c.g - 0.25 * (c.r + c.b));
}

vec3 fromYCoCg(vec3 c) {
    float t = c.x - c.z;
    return vec3(t + c.y, c.x + c.z, t - c.y);
}

// Last frame's result at uv through a Catmull-Rom spline over its 4x4 nearest pixels. The spline's
// weights along each axis, for a point t (0..1) of the way from pixel 1 to pixel 2, are
//   w0 = (-t + 2t^2 - t^3) / 2, w1 = (2 - 5t^2 + 3t^3) / 2, w2 = (t + 4t^2 - 3t^3) / 2, w3 = (t^3 - t^2) / 2.
// The two middle pixels are both positive and are read together by one bilinear read placed between them
// in the ratio w2 : w1, which leaves a 3x3 grid of reads; the four corners (each the product of two small
// outer weights) are left out and the rest renormalised, so five reads stand for sixteen (Jimenez 2016).
// between: how far the point lies from a history pixel's centre, 0 on a centre, 1 half way to the next.
vec4 historyAt(vec2 uv, out float between) {
    vec2 pos = uv * HistorySize - 0.5;
    vec2 first = floor(pos);
    vec2 t = pos - first;
    vec2 t2 = t * t;
    vec2 t3 = t2 * t;
    vec2 w0 = 0.5 * (-t + 2.0 * t2 - t3);
    vec2 w1 = 0.5 * (2.0 - 5.0 * t2 + 3.0 * t3);
    vec2 w2 = 0.5 * (t + 4.0 * t2 - 3.0 * t3);
    vec2 w3 = 0.5 * (t3 - t2);
    vec2 w12 = w1 + w2;
    vec2 texel = 1.0 / HistorySize;
    // Pixel n's centre is at n + 0.5: pixel `first` is spline point 1.
    vec2 at0 = (first - 0.5) * texel;
    vec2 at12 = (first + 0.5 + w2 / w12) * texel;
    vec2 at3 = (first + 2.5) * texel;
    float wUp = w12.x * w0.y;
    float wLeft = w0.x * w12.y;
    float wMid = w12.x * w12.y;
    float wRight = w3.x * w12.y;
    float wDown = w12.x * w3.y;
    vec4 sum = texture(HistorySampler, vec2(at12.x, at0.y)) * wUp
             + texture(HistorySampler, vec2(at0.x, at12.y)) * wLeft
             + texture(HistorySampler, at12) * wMid
             + texture(HistorySampler, vec2(at3.x, at12.y)) * wRight
             + texture(HistorySampler, vec2(at12.x, at3.y)) * wDown;
    vec2 off = 4.0 * t * (1.0 - t);
    between = 0.5 * (off.x + off.y);
    return sum / (wUp + wLeft + wMid + wRight + wDown);
}

void main() {
    // Level pixel k shows the point that, without the jitter, would sit at k + 0.5 - jitter (in level
    // pixels): the jitter moves the picture by +jitter. scale turns level pixels into screen pixels
    // (1 at full size, above 1 when the world is drawn smaller).
    vec2 jitter = Frame.yz;
    vec2 scale = OutSize / InSize;
    vec2 here = texCoord * InSize;
    ivec2 last = ivec2(InSize) - 1;
    // At the screen's edges, with the world drawn smaller, the jitter can carry the nearest level pixel a
    // pixel past the picture.
    ivec2 centre = clamp(ivec2(floor(here + jitter)), ivec2(0), last);

    // The Gaussian is normalised so the weights of one frame add up to 1 on average: at full size one
    // sample falls in each pixel, at a smaller render fewer, and a frame then adds less than one frame's
    // worth to the history.
    float falloff = 0.5 / (SAMPLE_SPREAD * SAMPLE_SPREAD * 0.6931472);
    float norm = 1.0 / (6.2831853 * SAMPLE_SPREAD * SAMPLE_SPREAD);

    vec3 current = vec3(0.0);
    float weight = 0.0;
    vec3 sum = vec3(0.0);
    vec3 sumSq = vec3(0.0);
    vec3 low = vec3(1e9);
    vec3 high = vec3(-1e9);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 pixel = clamp(centre + ivec2(x, y), ivec2(0), last);
            vec3 c = texelFetch(InSampler, pixel, 0).rgb;
            vec2 d = (vec2(pixel) + 0.5 - jitter - here) * scale;
            float w = norm * exp2(-falloff * dot(d, d));
            current += c * w;
            weight += w;
            vec3 ycocg = toYCoCg(c);
            sum += ycocg;
            sumSq += ycocg * ycocg;
            low = min(low, ycocg);
            high = max(high, ycocg);
        }
    }
    current /= max(weight, 1e-6);

    // Fetches: 9 level pixels, 5 depths, 5 history reads.
    // The nearest surface among the centre and its diagonal neighbours (reversed depth: the largest).
    float depth = texelFetch(DepthSampler, centre, 0).r;
    depth = max(depth, texelFetch(DepthSampler, clamp(centre + ivec2(-1, -1), ivec2(0), last), 0).r);
    depth = max(depth, texelFetch(DepthSampler, clamp(centre + ivec2(1, -1), ivec2(0), last), 0).r);
    depth = max(depth, texelFetch(DepthSampler, clamp(centre + ivec2(-1, 1), ivec2(0), last), 0).r);
    depth = max(depth, texelFetch(DepthSampler, clamp(centre + ivec2(1, 1), ivec2(0), last), 0).r);
    // The sky (depth 0) is taken as very far away, which keeps the maths finite: it moves only as the
    // camera turns.
    vec3 position = etnaPosition(texCoord, max(depth, 1e-6));
    vec4 previous = PrevViewProj * vec4(position + Motion.xyz, 1.0);
    vec2 previousUv = previous.xy / previous.w;

    float share = mix(SHARE_NEAR, SHARE_FAR, 1.0 - exp2(-SHARE_FALLOFF * length(position)));
    // The history's weight at which this frame, with an average frame's weight of 1, gets that share.
    float cap = 1.0 / share - 1.0;

    bool reusable = Motion.w > 0.5 && previous.w > 0.0
        && all(greaterThanEqual(previousUv, vec2(0.0))) && all(lessThanEqual(previousUv, vec2(1.0)));
    vec3 result = current;
    float total = weight;
    if (reusable) {
        float between;
        vec4 history = historyAt(previousUv, between);
        float historyWeight = clamp(history.a, 0.0, cap) * (1.0 - BETWEEN_PIXELS_LOSS * between);

        // Clip the history into this frame's colour box, along the line to this frame's own colour (kept
        // inside the box; Pedersen clips towards the box's centre, this frame's colour is sharper and is
        // the box's centre on average), so a colour pulled in keeps its hue as far as it can.
        vec3 mean = sum / 9.0;
        vec3 deviation = sqrt(max(sumSq / 9.0 - mean * mean, 0.0));
        float boxSize = BOX_SIZE * max(scale.x, 1.0);
        vec3 boxLow = max(mean - boxSize * deviation, low);
        vec3 boxHigh = min(mean + boxSize * deviation, high);
        vec3 target = clamp(toYCoCg(current), boxLow, boxHigh);
        vec3 offset = toYCoCg(max(history.rgb, 0.0)) - target;
        vec3 room = mix(target - boxLow, boxHigh - target, step(0.0, offset)) + 1e-5;
        vec3 reach = abs(offset) / room;
        float outside = max(reach.x, max(reach.y, reach.z));
        float pull = mix(1.0, max(outside, 1.0), min(weight / FULL_CLIP_WEIGHT, 1.0));
        vec3 clipped = fromYCoCg(target + offset / pull);

        historyWeight /= 1.0 + CLIP_FORGET * max(outside - 1.0, 0.0);
        // Each side counts less the brighter it is (Karis 2014): the picture holds more than white where
        // the sun glints on water or glitter sparkles, and one such pixel in a single frame would otherwise
        // flash through the average for many frames.
        float historyBlend = historyWeight / (1.0 + dot(clipped, ETNA_LUMA));
        float currentBlend = weight / (1.0 + dot(current, ETNA_LUMA));
        result = (clipped * historyBlend + current * currentBlend) / (historyBlend + currentBlend);
        total = historyWeight + weight;
    }
    fragColor = vec4(max(result, 0.0), total);
}
