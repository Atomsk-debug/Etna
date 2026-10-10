#ifndef ETNA_TONE_GLSL
#define ETNA_TONE_GLSL

// Etna's tone curve, shared by the bloom and the colour grade. Include after settings.glsl.
//
// Minecraft draws an 8-bit picture in display gamma with 1 as white, and whatever was brighter than white
// stops there. The final chain reads that picture as if a filmic tone curve had made it: etnaSceneLight
// undoes the curve and gives back light that goes past white (mid grey stays 0.18, white stands for
// TONE_TOP_IN), where bloom, exposure and saturation can work without clipping. etnaToneMap then brings
// the light back through the same curve with a softer top (TONE_TOP_OUT): darks and mid tones come back as
// they went in, the brightest tenth (sunlit sand, snow, clouds, the sky by the sun) rolls off smoothly a
// little under white and loses a little of its colour on the way, as film does, and whatever exposure or
// saturation pushed past white is rolled off the same way instead of clipping.
//
// The curve is N(h) = h / (B h + C), the rational shoulder of Lottes' tone curve ("Advanced Techniques and
// Optimization of HDR Color Pipelines", GDC 2016) with contrast and shoulder at 1, which is Reinhard's
// extended operator (Reinhard et al., "Photographic Tone Reproduction for Digital Images", 2002) pinned at
// mid grey: B and C are solved so that N(0.18) = 0.18 and N(top) = 1. It is applied to luminance, so hues
// stay. Display light is the square of the display colour (gamma 2, close to the screen's own 2.2 and
// undone exactly by a square root), which keeps the bloom's thirteen reads per pixel cheap.

const float TONE_MID = 0.18;
// The scene light the picture's white stands for, about five and a half stops over mid grey.
const float TONE_TOP_IN = 8.0;
// The scene light the output reaches white at: the picture's own white comes out at 0.966 (display 0.983).
const float TONE_TOP_OUT = 12.0;
const float TONE_IN_B = (TONE_TOP_IN - 1.0) / (TONE_TOP_IN - TONE_MID);
const float TONE_IN_C = 1.0 - TONE_IN_B * TONE_MID;
const float TONE_OUT_B = (TONE_TOP_OUT - 1.0) / (TONE_TOP_OUT - TONE_MID);
const float TONE_OUT_C = 1.0 - TONE_OUT_B * TONE_MID;
// Path to white: how much colour the very brightest light loses, fading in from display light 0.7 up.
const float TONE_WHITE_PATH = 0.2;

// Bloom takes only the light above this (a display grey of 0.76, about the blue sky overhead), eased in
// over a soft knee of this half-width (display greys 0.62 to 0.83). The picture's white is eight times
// as much light, so the sun, clouds, snow, torch flames and glowing blocks bloom, sunlit sand and the pale
// sky by the horizon add a trace, and grass, stone and shade nothing.
const float TONE_GLOW_FROM = 1.0;
const float TONE_GLOW_KNEE = 0.5;
// How much of the bloom texture a bloom strength of 1 mixes in.
const float TONE_GLOW_MIX = 0.02;

// Night vision: the light rods respond to (most to blue-green, hardly to red; a grey keeps its value)
// and the slightly blue grey they are seen as (luminance 1).
const vec3 TONE_ROD_WEIGHTS = vec3(0.05, 0.55, 0.40);
const vec3 TONE_NIGHT_TINT = vec3(0.83, 1.01, 1.40);
// Scene light below which colours start to fade at night (the moonlit land, not the torchlight), and how
// much of their colour the darkest ones lose once the eyes have fully adapted.
const float TONE_NIGHT_FROM = 0.12;
const float TONE_NIGHT_FADE = 0.5;

// The picture's colour as scene light, by its luminance so hues stay.
vec3 etnaSceneLight(vec3 display) {
    vec3 linear = display * display;
    float y = min(dot(linear, ETNA_LUMA), 1.0);
    return linear * (TONE_IN_C / (1.0 - TONE_IN_B * y));
}

// The part of scene light that feeds the bloom: what lies above TONE_GLOW_FROM, eased in over the knee
// (quadratic soft threshold), with the light's own hue.
vec3 etnaBrightPart(vec3 light) {
    float y = dot(light, ETNA_LUMA);
    float x = clamp(y - TONE_GLOW_FROM + TONE_GLOW_KNEE, 0.0, 2.0 * TONE_GLOW_KNEE);
    float over = max(x * x * (0.25 / TONE_GLOW_KNEE), y - TONE_GLOW_FROM);
    return light * (over / max(y, 1e-4));
}

// Purkinje shift: in dim light the eye sees with its rods, which see no colour, so at night the darkest
// colours fade towards a cool grey of the brightness the rods see (reds a little darker). Blues keep
// their own brightness rather than the rods' brighter one: Etna's night sky and moonlight are already
// drawn as the night-adapted eye sees them (sky.glsl), and lifting them again would turn the navy sky
// a pale slate. amount is how far the eyes have adapted to the night (EtnaSettings' Eye.z); torchlight
// and the bright parts of the picture keep their colour.
vec3 etnaNightVision(vec3 light, float amount) {
    float y = dot(light, ETNA_LUMA);
    float dark = 1.0 - smoothstep(0.0, TONE_NIGHT_FROM, y);
    float rods = min(dot(light, TONE_ROD_WEIGHTS), y);
    return mix(light, rods * TONE_NIGHT_TINT, amount * TONE_NIGHT_FADE * dark);
}

// Scene light back to display light (linear, 0 .. 1): the curve with the softer top, a path to white for
// the brightest colours, and a colour that still has a channel past white (a saturated light at full
// brightness) dimmed until it fits, keeping its hue and saturation, where clipping would have shifted
// its hue.
vec3 etnaToneMap(vec3 light) {
    float y = dot(light, ETNA_LUMA);
    float scale = 1.0 / (TONE_OUT_B * y + TONE_OUT_C);
    vec3 c = light * scale;
    float shown = y * scale;
    c = mix(c, vec3(shown), TONE_WHITE_PATH * smoothstep(0.7, 1.0, shown));
    return c / max(max(max(c.r, c.g), c.b), 1.0);
}

#endif
