#version 330
#extension GL_ARB_separate_shader_objects : require

// Etna final pass: sharpening, bloom, exposure, the mood (grade.glsl), colour grade, tone curve, vignette.
// Runs after the hand is drawn.

#include <etna:settings.glsl>
#include <etna:tone.glsl>
#include <etna:grade.glsl>

uniform sampler2D InSampler;
uniform sampler2D BloomSampler;
// The picture blurred at a quarter of the screen's size for the mood's dreamy glow (glow_combine.fsh).
uniform sampler2D GlowSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 BloomSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec3 fetch(ivec2 p) {
    return texelFetch(InSampler, clamp(p, ivec2(0), ivec2(InSize) - 1), 0).rgb;
}

// Contrast-adaptive sharpening, after AMD FidelityFX CAS (MIT licence): flat detail like block
// textures is sharpened more than edges that already have contrast, so it brings back the crispness
// the anti-aliasing softens without drawing halos around edges.
vec3 sharpen(ivec2 p, float amount) {
    vec3 c = fetch(p);
    vec3 n = fetch(p + ivec2(0, 1));
    vec3 s = fetch(p - ivec2(0, 1));
    vec3 e = fetch(p + ivec2(1, 0));
    vec3 w = fetch(p - ivec2(1, 0));
    vec3 mn = min(c, min(min(n, s), min(e, w)));
    vec3 mx = max(c, max(max(n, s), max(e, w)));
    vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(1e-4)), 0.0, 1.0));
    vec3 weight = -amp * mix(1.0 / 8.0, 1.0 / 5.0, amount);
    vec3 sharp = clamp((c + (n + s + e + w) * weight) / (1.0 + 4.0 * weight), 0.0, 1.0);
    return mix(c, sharp, clamp(amount * 2.0, 0.0, 1.0));
}

// Contrast as a soft S-curve around middle grey: blacks and whites are never clipped. Each channel takes
// one pow, of its distance to black or to white, whichever is nearer.
vec3 curve(vec3 x, float k) {
    vec3 low = vec3(lessThan(x, vec3(0.5)));
    vec3 p = 0.5 * pow(max(mix(2.0 - 2.0 * x, 2.0 * x, low), 0.0), vec3(k));
    return mix(1.0 - p, p, low);
}

// Visibility at full (Color2.y = 1): near black comes out VISIBILITY_LIFT more, a deep shade around
// VISIBILITY_KNEE (scene light; mid grey is 0.18) a little over half that, mid grey 28 % more.
const float VISIBILITY_LIFT = 1.2;
const float VISIBILITY_KNEE = 0.1;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 color = Color2.w > 0.005 ? sharpen(pixel, Color2.w) : fetch(pixel);
    // This pixel's brightness before the grade, which the dreamy glow compares the blurred picture with.
    float sharp = dot(color * color, ETNA_LUMA);

    // From here to the tone curve everything works in scene light (tone.glsl), which goes past white.
    vec3 light = etnaSceneLight(color);

    // Bloom is blended, not added: the bloom texture holds the blurred bright part of the picture, and
    // each pixel trades a little of its own bright part for it. A wide bright area like a cloud or the
    // sun's glow stays exactly as bright as it was, and only light spilling onto darker things around
    // it shows. Whatever is under the bloom threshold sends out no glow of its own.
    if (Glow.x > 0.005) {
        // The mood may tint the bloom (Mood5, luminance 1).
        // The tint colours only the light the bloom adds, never the bright thing itself.
        vec3 glow = texture(BloomSampler, texCoord).rgb;
        light += min(Glow.x * TONE_GLOW_MIX, 0.1) * (glow - etnaBrightPart(light)) * Mood5.rgb;
    }

    // Exposure: the brightness setting times the eyes' adaptation to the light around the camera
    // (EtnaEye.java: a little brighter in caves and at night, a little calmer at noon).
    light *= Color.x * Eye.y;
    // The mood's exposure, white balance, contrast and highlight softness, before Visibility so that its
    // lift still applies to the mood's darks.
    light = etnaMoodScene(light);

    // Visibility lifts the darkest tones and leaves the bright ones: L + a T x / (1 + x^2) with x = L / T
    // adds the most around T (a deep shade), up to 1 + a times the light in near black, and fades back to
    // nothing towards white (well under a hundredth of a stop at the picture's white). It only ever rises
    // (its slope stays above 1 - a / 8), so darks stay in order. On luminance, so hues stay.
    if (Color2.y > 0.005) {
        float y = dot(light, ETNA_LUMA);
        float x = y / VISIBILITY_KNEE;
        light *= 1.0 + VISIBILITY_LIFT * Color2.y / (1.0 + x * x);
    }

    // Colour grade.
    float warmth = Color2.x;
    light *= vec3(1.0 + 0.06 * warmth, 1.0 + 0.01 * warmth, 1.0 - 0.08 * warmth);
    float luma = dot(light, ETNA_LUMA);
    float peak = max(max(light.r, light.g), light.b);
    float chroma = (peak - min(min(light.r, light.g), light.b)) / max(peak, 1e-4);
    // Vibrance lifts dull colours more than vivid ones.
    float boost = Color.z + Color.w * (1.0 - chroma);
    light = max(mix(vec3(luma), light, boost), 0.0);

    // At night, out under the sky, the darkest colours fade towards the cool grey of night vision.
    if (Eye.z > 0.001) {
        light = etnaNightVision(light, Eye.z);
    }

    // Back to the screen: the highlights roll off towards white instead of clipping, and nothing leaves
    // the 0 .. 1 range the S-curve below is defined on (above 1 its pow() got a negative base and returned
    // NaN, which once turned the sky lime).
    vec3 shown = etnaToneMap(light);
    // The mood's dreamy glow and halation, then its colours (grade.glsl), in display light.
    if (Mood2.w > 0.001 || Mood4.w > 0.001) {
        shown = etnaMoodGlow(shown, texture(GlowSampler, texCoord), sharp);
    }
    color = sqrt(clamp(etnaMoodColor(shown), 0.0, 1.0));

    float k = Color.y;
    // The curve's toe pressed the darks down (at Vivid's contrast a 0.05 grey came out at 0.040, a
    // 0.02 one at 0.0145), so caves, rooms and nights sank into black. It comes in over the darkest
    // quarter instead: shadows keep their level, darks stay in order (the result still rises
    // everywhere, over the whole contrast range), and from 0.25 up the curve is as before.
    color += (curve(color, k) - color) * smoothstep(0.0, 0.25, color);
    color = etnaMoodLift(color);

    // The vignette darkens dark corners and leaves bright ones (sky, snow) as they are.
    vec2 centered = texCoord - 0.5;
    centered.x *= OutSize.x / OutSize.y;
    float vignette = max(Color2.z + Mood1.w, 0.0);
    color *= 1.0 - smoothstep(0.5, 1.3, length(centered)) * vignette * (1.0 - dot(color, ETNA_LUMA));

    // Half a step of 8-bit noise breaks up banding in smooth gradients (sky, fog, shade). It stays
    // still, so it never shimmers.
    float ign = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    color += (ign - 0.5) / 255.0;

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
