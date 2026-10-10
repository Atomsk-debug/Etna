#ifndef ETNA_GRADE_GLSL
#define ETNA_GRADE_GLSL

// The mood filter (EtnaMood.java), in the final pass. Include after settings.glsl and tone.glsl.
//
// A mood is a colour grade matched to a family of resource packs, or a purely magic one, laid over the
// Look. Its steps sit where each works best in the final pass:
// * in scene light, before the Look's own grade: exposure, white balance, contrast around mid grey and a
//   softer roll-off of the highlights (etnaMoodScene);
// * in display light, after the tone curve: the dreamy glow and the halation, which only ever add light
//   (etnaMoodGlow), then saturation, vibrance and hue shifts by colour band in OkLab, and split toning
//   (etnaMoodColor);
// * in display gamma, last: the black lift (etnaMoodLift).
// Java works out every number it can (white balance gains, tint normalisation, strength blending), so each
// step is a few multiplies here, and each is skipped when the mood leaves it neutral. With Mood Strength
// at 0 every step is neutral and the picture is the Look's own.

// Mid grey in scene light, the pivot of the mood's contrast.
const float MOOD_MID = 0.18;
// Scene light above which the highlight softness starts to compress (a display grey of about 0.6), and how
// far it reaches: at full softness the picture's white (TONE_TOP_IN) comes out around display 0.96.
const float MOOD_SOFT_KNEE = 1.0;
const float MOOD_SOFT_RANGE = 8.0;

// The dreamy glow (Orton effect, a Pro-Mist filter): light from the blurred picture spreads into darker
// surroundings, never the other way, so the sharp picture is never blurred. Only blurred light at least
// this bright spreads (display light: the sky, clouds, sunlit snow and sand, lamps), eased in up to the
// second value, and a difference under the floor is ignored, so the texture of a lit wall stays as it is.
const float MOOD_GLOW_FROM = 0.12;
const float MOOD_GLOW_TO = 0.6;
const float MOOD_GLOW_FLOOR = 0.02;
// Halation, the red-orange fringe film shows where a bright area meets a dark one (light scattering back
// through the film's base): only from the brightest light, with this hue.
const float MOOD_HALATION_FROM = 0.35;
const vec3 MOOD_HALATION_TINT = vec3(1.0, 0.35, 0.12);

// The three colour bands of the hue shifts, in OkLab hue (radians; Minecraft's grass and leaves sit
// near 138 degrees, the sky and water near 260, wood, dirt, torchlight and skin near 65), stored as the
// band's direction in the a/b plane, and how wide each is: a band's weight is exp(K (cos d - 1)) for a
// hue d away from its centre, about a Gaussian of width 1 / sqrt(K) radians (here about 28 degrees).
const vec2 MOOD_GREEN = vec2(-0.7431, 0.6691);
const vec2 MOOD_BLUE = vec2(-0.1392, -0.9903);
const vec2 MOOD_WARM = vec2(0.4226, 0.9063);
const float MOOD_BAND_K = 4.2;
// Chroma (OkLab) under which colours count as grey and keep their hue, and the chroma vibrance treats as
// fully vivid (it lifts duller colours more).
const vec2 MOOD_GREY_CHROMA = vec2(0.01, 0.04);
const float MOOD_VIVID_CHROMA = 0.2;

// Split toning's shadows reach up to this display grey and its highlights start from this one.
const float MOOD_SHADOWS_TO = 0.45;
const float MOOD_HIGHLIGHTS_FROM = 0.55;

// Exposure, white balance, contrast and highlight softness, on scene light right after Etna's own
// exposure. The contrast scales log luminance around mid grey, fading out towards black: a contrasty mood
// gives the mid tones and highlights their punch without sinking caves and nights into black (Visibility
// asks for the opposite), and a soft one leaves the black lift to lift the darks. Like the softness it
// works on luminance, so hues stay.
vec3 etnaMoodScene(vec3 light) {
    light *= Mood0.xyz;
    if (abs(Mood0.w) > 0.001 || Mood1.z > 0.001) {
        float y = max(dot(light, ETNA_LUMA), 1e-6);
        float shown = y * mix(1.0, pow(y / MOOD_MID, Mood0.w), smoothstep(0.0, MOOD_MID, y));
        float over = max(shown - MOOD_SOFT_KNEE, 0.0);
        shown -= over - over / (1.0 + Mood1.z * over / MOOD_SOFT_RANGE);
        light *= shown / y;
    }
    return light;
}

// The dreamy glow and the halation, on display light after the tone curve. glow is the final chain's
// blurred picture (glow_combine.fsh: rgb the wide blur, a the luminance of a narrow one) and sharp the
// luminance of this pixel in the same picture, both before the grade. Each adds light only where the
// blurred picture is brighter than the sharp one, never more than its own share of the blurred light.
vec3 etnaMoodGlow(vec3 c, vec4 glow, float sharp) {
    float wide = dot(glow.rgb, ETNA_LUMA);
    float spread = max(wide - sharp - MOOD_GLOW_FLOOR, 0.0) * smoothstep(MOOD_GLOW_FROM, MOOD_GLOW_TO, wide);
    c += Mood2.w * Mood5.rgb * glow.rgb * (spread / max(wide, 1e-4));
    float fringe = max(glow.a - sharp - MOOD_GLOW_FLOOR, 0.0) * smoothstep(MOOD_HALATION_FROM, 1.0, glow.a);
    return c + Mood4.w * fringe * MOOD_HALATION_TINT;
}

float etnaMoodBand(vec2 ab, float chroma, vec2 centre) {
    return exp(MOOD_BAND_K * (dot(ab, centre) / chroma - 1.0));
}

// Saturation, vibrance and the colour bands in OkLab (Bjorn Ottosson, 2020, public domain), then split
// toning, on display light (0 .. 1).
vec3 etnaMoodColor(vec3 c) {
    if (Mood7.z > 0.5) {
        vec3 lms = vec3(
            dot(c, vec3(0.4122214708, 0.5363325363, 0.0514459929)),
            dot(c, vec3(0.2119034982, 0.6806995451, 0.1073969566)),
            dot(c, vec3(0.0883024619, 0.2817188376, 0.6299787005)));
        lms = pow(max(lms, 0.0), vec3(1.0 / 3.0));
        float lightness = dot(lms, vec3(0.2104542553, 0.7936177850, -0.0040720468));
        vec2 ab = vec2(dot(lms, vec3(1.9779984951, -2.4285922050, 0.4505937099)),
            dot(lms, vec3(0.0259040371, 0.7827717662, -0.8086757660)));
        float chroma = length(ab);
        // Greys have no hue to shift.
        float coloured = smoothstep(MOOD_GREY_CHROMA.x, MOOD_GREY_CHROMA.y, chroma);
        float safe = max(chroma, 1e-5);
        float green = etnaMoodBand(ab, safe, MOOD_GREEN) * coloured;
        float blue = etnaMoodBand(ab, safe, MOOD_BLUE) * coloured;
        float warm = etnaMoodBand(ab, safe, MOOD_WARM) * coloured;
        float turn = green * Mood6.x + blue * Mood6.z + warm * Mood7.x;
        float scale = Mood1.x * (1.0 + Mood1.y * max(1.0 - chroma / MOOD_VIVID_CHROMA, 0.0))
            * max(1.0 + green * Mood6.y + blue * Mood6.w + warm * Mood7.y, 0.0);
        float cs = cos(turn);
        float sn = sin(turn);
        ab = mat2(cs, sn, -sn, cs) * ab * scale;
        lms = vec3(lightness + 0.3963377774 * ab.x + 0.2158037573 * ab.y,
            lightness - 0.1055613458 * ab.x - 0.0638541728 * ab.y,
            lightness - 0.0894841775 * ab.x - 1.2914855480 * ab.y);
        lms = lms * lms * lms;
        c = vec3(dot(lms, vec3(4.0767416621, -3.3077115913, 0.2309699292)),
            dot(lms, vec3(-1.2684380046, 2.6097574011, -0.3413193965)),
            dot(lms, vec3(-0.0041960863, -0.7034186147, 1.7076147010)));
        // A colour pushed past what the screen shows is dimmed back to it, keeping its hue.
        c = max(c, 0.0);
        c /= max(max(max(c.r, c.g), c.b), 1.0);
    }
    // Split toning: shadows and highlights each lean towards their own tint (luminance 1, so the
    // brightness stays), by the display grey.
    float grey = sqrt(dot(c, ETNA_LUMA));
    c *= 1.0 + Mood2.xyz * (1.0 - smoothstep(0.0, MOOD_SHADOWS_TO, grey))
        + Mood3.xyz * smoothstep(MOOD_HIGHLIGHTS_FROM, 1.0, grey);
    return c;
}

// The black lift (matte), on display gamma: black comes out at Mood4.rgb (tinted like the shadows) and
// white stays white.
vec3 etnaMoodLift(vec3 color) {
    return Mood4.rgb + (1.0 - Mood4.rgb) * color;
}

#endif
