#ifndef ETNA_SKY_GLSL
#define ETNA_SKY_GLSL

// Etna's sky: one analytic model of the daylight, twilight and night sky, used by the sky itself
// (assets/etna/shaders/core/sky.*), by the terrain for the colour of the air in the distance and of the
// sky mirrored in water, and on the Java side (EtnaSkyModel.java, a line-for-line port) for the colour
// of the sunlight and the skylight on the land and for vanilla's fog colour. Everything in one place,
// so the land, the water and the fog always match the sky above them.
//
// Technique: single scattering in a uniform atmosphere with closed-form optical depths, from published
// sky models (see CREDITS.md): Rayleigh scattering by the air (blue, a ~1 / wavelength^4 colour) and Mie
// scattering by haze (white, thrown forward by a Henyey-Greenstein phase), light reddened on its way
// through the air by the air mass of a curved atmosphere, the ozone layer's absorption that keeps the
// twilight zenith blue, the Earth's shadow rising in the air after sunset, a little multiple scattering
// and the eye's adaptation to the fading light. The result is mapped to the screen with a soft
// roll-off. It costs no texture and no loop: a few exponentials per pixel.
//
// Directions are world-space unit vectors (y up). The per-frame part (EtnaSkyFrame: the light that
// reaches the air from the sun and the moon) depends only on where they are and on the weather; it is
// worked out once (Java for the terrain, the sky's vertex shader for the sky) and passed along.

const vec3 ETNA_SKY_LUMA = vec3(0.2126, 0.7152, 0.0722);
// Zenith optical depths: Rayleigh (sea-level scattering coefficients times its 8 km scale height), haze
// and the ozone layer's absorption (its Chappuis band takes red and green out of light that grazes it).
const vec3 ETNA_SKY_RAYLEIGH = vec3(0.0464, 0.1085, 0.2648);
const float ETNA_SKY_HAZE = 0.022;
const vec3 ETNA_SKY_OZONE = vec3(0.0120, 0.0220, 0.0013);
// Planet radius over each layer's scale height: air 8 km, haze 1.2 km, ozone about 25 km.
const float ETNA_SKY_K_AIR = 796.0;
const float ETNA_SKY_K_HAZE = 5300.0;
const float ETNA_SKY_K_OZONE = 255.0;
const float ETNA_SKY_EXPOSURE = 5.5;
// Night: starlight and airglow, a navy floor under everything, a little lighter towards the horizon.
const vec3 ETNA_SKY_NIGHT_ZENITH = vec3(0.0060, 0.0098, 0.0228);
const vec3 ETNA_SKY_NIGHT_HORIZON = vec3(0.0105, 0.0135, 0.0215);
// Moonlight is sunlight, but at night the eye sees blue best: the moon's glow in the air reads cooler.
const vec3 ETNA_SKY_MOON_TINT = vec3(0.80, 0.92, 1.15);

// The light of the sun and the moon as it reaches the air, the same for every direction of the sky in
// a frame. Linear units: the noon sun's light is about 1.
struct EtnaSkyFrame {
    vec3 sun;       // towards the sun (below the horizon at night)
    vec3 moon;      // towards the moon
    vec3 sunLow;    // sunlight reaching the low air (reddened, in the Earth's shadow after sunset), times the eye's adaptation
    vec3 sunHigh;   // sunlight reaching the high air, where it crossed little of the dense air
    vec3 multi;     // light scattered more than once, the sky lighting itself
    vec3 moonLow;   // the same two for the moon, times its brightness by phase
    vec3 moonHigh;
    vec3 overcast;  // the colour of a rain sky (its brightness by time of day)
    float rain;     // 0 clear .. 1 rain
    float storm;    // 0 .. 1 thunder
    float glow;     // strength of the haze's glow round the sun and over a sunset (the Sky Glow setting, 1 as tuned)
};

// Path through a spherical shell of the atmosphere (thickness 1 in scale heights) from the ground,
// relative to straight up; y is the sine of the elevation. Exact for a uniform shell, finite at the
// horizon (about 40 air masses for the air), unlike 1 / y.
float etnaAirMass(float y, float k) {
    return sqrt(k * k * y * y + 2.0 * k + 1.0) - k * y;
}

// Henyey-Greenstein phase function (Henyey and Greenstein, 1941), averaging 1 over the sphere.
float etnaPhaseHG(float mu, float g) {
    float x = 1.0 + g * g - 2.0 * g * mu;
    return (1.0 - g * g) / (x * sqrt(x));
}

// What is left of a light at elevation sine lightY after its way to the air where it scatters: the
// ground's air (high 0) or the high air (high 1), whose light crossed less of the dense air and haze but
// all of the ozone. With the light under the horizon, the air it still reaches lies above the Earth's
// shadow and its light grazed the atmosphere higher still: less air in the way.
vec3 etnaSkyTransmittance(float lightY, float high) {
    float y = max(lightY, 0.0);
    float below = mix(1.0, 0.3, smoothstep(0.0, -0.1, lightY));
    float air = mix(1.0, 0.15, high) * below;
    float haze = mix(1.0, 0.10, high) * below;
    vec3 depth = ETNA_SKY_RAYLEIGH * (etnaAirMass(y, ETNA_SKY_K_AIR) * air)
        + vec3(ETNA_SKY_HAZE * 1.11 * etnaAirMass(y, ETNA_SKY_K_HAZE) * haze)
        + ETNA_SKY_OZONE * etnaAirMass(y, ETNA_SKY_K_OZONE);
    return exp(-depth);
}

// Share of a layer still lit with the light under the horizon: the air below the shadow's edge, about
// R theta^2 / 2 high, is dark. depth is the layer's height relative to the low air's.
float etnaEarthShadow(float lightY, float depth) {
    float y = min(lightY, 0.0);
    return exp(-398.0 / depth * y * y);
}

// As the light fades the eye opens up, so dawn and dusk stay bright and colourful.
float etnaEyeAdapt(float sunY) {
    return 1.0 + 2.0 * (1.0 - smoothstep(-0.14, 0.14, sunY));
}

// The per-frame part of the sky. weather: 0 clear, 1 rain, 2 thunder. moonLight: the moon's brightness
// by its phase (0 new .. 1 full). glow: the haze's glow, 1 as tuned.
EtnaSkyFrame etnaSkyFrame(vec3 sun, vec3 moon, float weather, float moonLight, float glow) {
    EtnaSkyFrame f;
    f.sun = sun;
    f.moon = moon;
    float adapt = etnaEyeAdapt(sun.y);
    f.sunLow = etnaSkyTransmittance(sun.y, 0.45) * (etnaEarthShadow(sun.y, 1.0) * adapt);
    f.sunHigh = etnaSkyTransmittance(sun.y, 1.0) * (etnaEarthShadow(sun.y, 2.0) * adapt);
    f.multi = etnaSkyTransmittance(sun.y, 0.6) * (etnaEarthShadow(sun.y, 2.0) * adapt) * vec3(0.85, 0.92, 1.08);
    vec3 moonStrength = ETNA_SKY_MOON_TINT * (0.025 * moonLight * smoothstep(-0.05, 0.2, moon.y));
    f.moonLow = etnaSkyTransmittance(moon.y, 0.45) * (etnaEarthShadow(moon.y, 1.0) * moonStrength);
    f.moonHigh = etnaSkyTransmittance(moon.y, 1.0) * (etnaEarthShadow(moon.y, 2.0) * moonStrength);
    float day = smoothstep(-0.20, 0.35, sun.y);
    f.overcast = mix(0.010, 0.125, day) * mix(vec3(0.82, 0.88, 1.00), vec3(0.95, 0.99, 1.04), day);
    f.rain = clamp(weather, 0.0, 1.0);
    f.storm = clamp(weather - 1.0, 0.0, 1.0);
    f.glow = glow;
    return f;
}

// Single scattering of one light along a view ray, per unit of the light. A ray that climbs steeply
// (rise near 1) scatters mostly high up, where the light stayed white; a flat one in the low air, where
// it is reddened and, after sunset, in the Earth's shadow. The two are mixed as colours, so the sky
// between them never turns a grey average of the two. Haze lies low and scatters the low air's light
// only: a tight glow round the sun, and the wide warm glow over a setting sun.
vec3 etnaSkyScatter(float mu, vec3 low, vec3 high, float rise, vec3 viewAir, float viewHaze, vec3 depth, vec3 fade, float glow) {
    float phaseAir = 0.9 + 0.3 * mu * mu;
    float phaseHaze = (0.10 * etnaPhaseHG(mu, 0.80) + 0.25 * etnaPhaseHG(mu, 0.55) + 0.15) * glow;
    vec3 light = mix(low, high, rise);
    return (light * viewAir * phaseAir + low * (viewHaze * phaseHaze)) / depth * fade;
}

// Linear light of the sky in direction dir, before the screen mapping: no sun disc and no stars.
vec3 etnaSkyLinear(EtnaSkyFrame f, vec3 dir) {
    float y = max(dir.y, 0.0) + 0.002;
    // Optical depth along the view ray, and how much of the light the ray gathers before it is all
    // scattered away: saturating towards the horizon, where the sky turns pale.
    vec3 viewAir = ETNA_SKY_RAYLEIGH * etnaAirMass(y, ETNA_SKY_K_AIR);
    float viewHaze = ETNA_SKY_HAZE * etnaAirMass(y, ETNA_SKY_K_HAZE);
    vec3 depth = viewAir + viewHaze * 1.11;
    vec3 fade = 1.0 - exp(-depth);
    float steep = min(y, 1.0);
    float up = pow(steep, 0.35);
    float rise = pow(steep, 0.7);

    float mu = clamp(dot(dir, f.sun), -1.0, 1.0);
    vec3 sky = etnaSkyScatter(mu, f.sunLow, f.sunHigh, rise, viewAir, viewHaze, depth, fade, f.glow);
    // Light scattered more than once: a soft glow of the sky's own light, stronger towards the sun.
    float toward = 0.5 + 0.5 * mu;
    sky += f.multi * ((0.04 + 0.12 * toward * toward * toward) * fade);
    // The moon lights the night sky the same way, much more faintly. Skipped while it adds nothing
    // (the same for every pixel of a frame, so the branch costs nothing).
    if (f.moonLow.b + f.moonHigh.b > 1e-6) {
        float muMoon = clamp(dot(dir, f.moon), -1.0, 1.0);
        sky += etnaSkyScatter(muMoon, f.moonLow, f.moonHigh, rise, viewAir, viewHaze, depth, fade, f.glow);
    }
    float low = 1.0 - up;
    sky += mix(ETNA_SKY_NIGHT_ZENITH, ETNA_SKY_NIGHT_HORIZON, low * low);
    // Yellowed light scattered blue turns slightly green near the horizon. A sky is never green: green
    // never rises above the larger of red and blue, which leaves blue, cyan, white, yellow and orange.
    sky.g = min(sky.g, max(sky.r, sky.b));

    // Rain: an even grey, a little brighter at the horizon; thunder darkens it further.
    vec3 overcast = f.overcast * mix(1.12, 0.92, sqrt(steep));
    return mix(sky, overcast, f.rain) * (1.0 - 0.45 * f.storm);
}

// From the sky's linear light to the screen: linear in the dark, rolling off smoothly without ever
// clipping. The roll-off works mostly on the brightest channel, so an orange horizon stays orange
// instead of washing out to white; a part per channel still lets the glow right around the sun go white.
vec3 etnaSkyDisplay(vec3 light) {
    float peak = max(max(light.r, light.g), max(light.b, 1e-6));
    vec3 keep = light * ((1.0 - exp(-peak * ETNA_SKY_EXPOSURE)) / peak);
    vec3 each = 1.0 - exp(-light * ETNA_SKY_EXPOSURE);
    return mix(keep, each, 0.35);
}

// Below the horizon (seen from up high, over the edge of the world): a darker, greyer horizon.
vec3 etnaSkyGround(vec3 color, float y) {
    float below = smoothstep(0.0, -0.35, y);
    vec3 grey = vec3(dot(color, ETNA_SKY_LUMA)) * vec3(0.92, 0.95, 1.0);
    return mix(color, mix(color, grey, 0.35) * 0.62, below);
}

// The sky's colour on screen in direction dir, without the sun disc and the stars.
vec3 etnaSkyColor(EtnaSkyFrame f, vec3 dir) {
    return etnaSkyGround(etnaSkyDisplay(etnaSkyLinear(f, dir)), dir.y);
}

// The shortcuts for the shaders that know this frame's sky: one that defines ETNA_SKY_CURRENT before
// including this file implements etnaSkyCurrent() (this frame's EtnaSkyFrame) anywhere after it, and gets
// etnaSkyRadiance. One that defines ETNA_SKY_SUNLIGHT and ETNA_SKY_SKYLIGHT (the colours
// EtnaSkyModel.java works out once a frame) gets etnaSunlight and etnaSkylight.
#ifdef ETNA_SKY_CURRENT
EtnaSkyFrame etnaSkyCurrent();

// The sky's colour on screen in the world direction worldDir (a unit vector).
vec3 etnaSkyRadiance(vec3 worldDir) {
    return etnaSkyColor(etnaSkyCurrent(), worldDir);
}
#endif
#ifdef ETNA_SKY_SUNLIGHT
// The colour of direct light (the sun's, or the moon's at night), with a luma of 1.
vec3 etnaSunlight() {
    return ETNA_SKY_SUNLIGHT;
}
#endif
#ifdef ETNA_SKY_SKYLIGHT
// The colour of the light from the whole sky, with a luma of 1.
vec3 etnaSkylight() {
    return ETNA_SKY_SKYLIGHT;
}
#endif

#endif
