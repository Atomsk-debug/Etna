#version 330
#extension GL_ARB_separate_shader_objects : require

// Bloom, step 1: shrinks the picture into the first, largest bloom level. Each output pixel averages
// the picture under it with the 13-tap filter of Call of Duty: Advanced Warfare's bloom (Jimenez,
// SIGGRAPH 2014), and each of the filter's five boxes is weighted down by its own brightness (Karis
// average): one bright pixel moving across the screen can't make the glow pulse. Only the top of the
// range feeds it: each read is turned into scene light and keeps just the part above the bloom threshold
// (tone.glsl), so only bright things glow.
//
// Bloom fog: at night, in rain, in caves, in the Nether and the End, damp or smoky air scatters a little of
// the light of everything far away, so distant lights and lit scenery glow softly in it. Distant scenery
// feeds a little of all its light into the bloom too, whatever its brightness, more the farther away it is
// (1 - e^(-distance / reach)), at the strength EtnaAir works out for the air this frame (Glow.y, with
// Glow.w one over the reach). Each read is compressed (as the Karis average weighs it) so a lone bright
// pixel can't flare, and dark scenery feeds next to nothing (fogFeed): the glow shows around what is lit,
// and the picture never turns milky. The hand, near land and the player's surroundings feed none. Under water and
// in lava the medium's own fog has the picture, and nothing is fed.

#include <etna:settings.glsl>
#include <etna:tone.glsl>

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
// Main's own depth. With a render scale (EtnaScale) the level's depth comes from the smaller picture
// (DepthSampler) and main's holds only the hand, drawn after the world at full size: there it says where the
// hand covers the land. Without one both are the same texture, with the hand already merged in.
uniform sampler2D HandDepthSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 DepthSize;
    vec2 HandDepthSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// How strongly the bloom fog's compressed light is fed in at full distance with Glow.y at 1: the glow it
// adds is the bloom's own small share of the bloom texture (tone.glsl's TONE_GLOW_MIX), so the feed has to
// stand up to the bright part of a flame (about 7) for a distant lit window to glow at all.
const float BLOOM_FOG_GAIN = 14.0;
// Scene luminance from which a read starts to feed the bloom fog, and from which it feeds all of itself:
// the night sky (about 0.08) feeds a twentieth, torch-lit stone and a rainy sky (0.3) about half.
const float BLOOM_FOG_FROM = 0.03;
const float BLOOM_FOG_FULL = 0.6;

vec3 sceneTap(vec2 offset) {
    // Half an output pixel per step covers the whole area under it at any screen size.
    return etnaSceneLight(texture(InSampler, texCoord + offset * (0.5 / OutSize)).rgb);
}

// How much of the bloom fog this output pixel takes: the four depth texels under it, each turned into its
// share by its distance and averaged, so an edge between near and far land feeds smoothly as the
// anti-aliasing jitter moves it. The sky (nothing drawn) is as far as it gets. The hand feeds none.
float fogShare() {
    ivec2 base = ivec2(texCoord * DepthSize - 0.5);
    ivec2 last = ivec2(DepthSize) - 1;
    bool separateHand = HandDepthSize != DepthSize;
    float share = 0.0;
    for (int i = 0; i < 4; i++) {
        ivec2 p = clamp(base + ivec2(i & 1, i >> 1), ivec2(0), last);
        vec2 uv = (vec2(p) + 0.5) / DepthSize;
        if (separateHand && !etnaIsSky(texelFetch(HandDepthSampler, ivec2(uv * HandDepthSize), 0).r)) {
            continue;
        }
        float depth = texelFetch(DepthSampler, p, 0).r;
        float dist = etnaIsSky(depth) ? 1e4 : length(etnaPosition(uv, depth));
        share += 1.0 - exp(-dist * Glow.w);
    }
    return 0.25 * share;
}

vec4 box(vec3 a, vec3 b, vec3 c, vec3 d, float weight) {
    vec3 average = (a + b + c + d) * 0.25;
    return vec4(average, 1.0) * weight / (1.0 + dot(average, ETNA_LUMA));
}

// A read as the bloom fog takes it: compressed as the Karis average weighs it, and eased in from dim light
// up (BLOOM_FOG_FROM to BLOOM_FOG_FULL, scene luminance), so lit things feed it and dark scenery and the
// night sky next to nothing, while the bright grey sky of a rainy day still does.
vec3 fogFeed(vec3 light) {
    float y = dot(light, ETNA_LUMA);
    return light * (smoothstep(BLOOM_FOG_FROM, BLOOM_FOG_FULL, y) / (1.0 + y));
}

void main() {
    // The four inner reads in scene light: the bloom fog's feed comes from them before the threshold.
    vec3 sd = sceneTap(vec2(-1.0, -1.0));
    vec3 se = sceneTap(vec2(1.0, -1.0));
    vec3 si = sceneTap(vec2(-1.0, 1.0));
    vec3 sj = sceneTap(vec2(1.0, 1.0));

    vec3 a = etnaBrightPart(sceneTap(vec2(-2.0, -2.0)));
    vec3 b = etnaBrightPart(sceneTap(vec2(0.0, -2.0)));
    vec3 c = etnaBrightPart(sceneTap(vec2(2.0, -2.0)));
    vec3 d = etnaBrightPart(sd);
    vec3 e = etnaBrightPart(se);
    vec3 f = etnaBrightPart(sceneTap(vec2(-2.0, 0.0)));
    vec3 g = etnaBrightPart(sceneTap(vec2(0.0, 0.0)));
    vec3 h = etnaBrightPart(sceneTap(vec2(2.0, 0.0)));
    vec3 i = etnaBrightPart(si);
    vec3 j = etnaBrightPart(sj);
    vec3 k = etnaBrightPart(sceneTap(vec2(-2.0, 2.0)));
    vec3 l = etnaBrightPart(sceneTap(vec2(0.0, 2.0)));
    vec3 m = etnaBrightPart(sceneTap(vec2(2.0, 2.0)));

    vec4 sum = box(d, e, i, j, 0.5)
        + box(a, b, f, g, 0.125)
        + box(b, c, g, h, 0.125)
        + box(f, g, k, l, 0.125)
        + box(g, h, l, m, 0.125);
    vec3 bloom = sum.rgb / sum.a;

    // Bloom fog: a uniform branch, skipped when the air has none this frame or the camera is in a medium.
    if (Glow.y > 0.001 && Water.x < 0.5) {
        vec3 inner = (fogFeed(sd) + fogFeed(se) + fogFeed(si) + fogFeed(sj)) * 0.25;
        bloom += inner * (BLOOM_FOG_GAIN * Glow.y * fogShare());
    }
    fragColor = vec4(bloom, 1.0);
}
