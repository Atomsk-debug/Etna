#ifndef ETNA_AIR_GLSL
#define ETNA_AIR_GLSL

// The air between the camera and what it sees: mist and light shafts, shared by the half-resolution
// shafts pass (post/shafts.fsh) and the world pass that blends them into the picture
// (post/world_light.fsh). Include after settings.glsl. EtnaAir.java fills the Mist, MistColor and
// Shafts fields of the settings block once per frame.
//
// One model for both, single scattering in a thin medium:
// * the mist is height fog in two layers, a shallow ground mist a little above sea level that fills the
//   valleys in the morning and the evening, and a deeper, thinner haze for night and rain; underground a
//   faint murk replaces both. The picture fades into the air's own colour with the mist in front of it;
// * the sun lights the mist and, where the light rays are on, a clear "shaft air" within the shadow
//   map's reach. That light is the in-scattering integral along the view ray, sum of density times
//   transmittance, times the share of it the sun reaches: the shafts pass marches that share through the
//   shadow map, the world pass works out the rest in closed form.
// The mist's density along a ray integrates exactly (exponential height fog, after Inigo Quilez, "Better
// Fog", 2010), so the mist itself needs no samples and has no noise: only the shadowed share is marched.

// Clear air in front of the camera: the player's own surroundings are never fogged.
const float AIR_MIST_START = 6.0; // blocks
// The ground mist thins by e over this many blocks above its base, which sits this far above sea level.
const float AIR_GROUND_SCALE = 12.0;
const float AIR_GROUND_BASE = 2.0;
// The night and rain haze thins by e over this many blocks above sea level.
const float AIR_HAZE_SCALE = 48.0;
// Distance the shafts pass stores for the sky (where nothing was drawn).
const float AIR_SKY_DISTANCE = 4096.0;

// Optical depth of one exponential layer, exp(-max(h, 0) / scale), along a ray that starts at height h0
// above the layer's base, climbs dy per block and runs for t blocks: all of the ray below the base counts
// at full density, the rest by the closed-form average of the exponential between its two end heights.
float etnaLayerDepth(float h0, float dy, float t, float scale) {
    float h1 = h0 + dy * t;
    float lo = min(h0, h1);
    float hi = max(h0, h1);
    float span = hi - lo;
    float below = span > 1e-3 ? t * clamp(-lo / span, 0.0, 1.0) : (lo < 0.0 ? t : 0.0);
    float a = max(lo, 0.0);
    float x = (max(hi, 0.0) - a) / scale;
    // (1 - e^-x) / x loses its digits as x goes to 0, where it tends to 1 - x / 2.
    float average = x < 1e-3 ? 1.0 - 0.5 * x : (1.0 - exp(-x)) / x;
    return below + (t - below) * exp(-a / scale) * average;
}

// Optical depth of the ground mist and the haze along a view ray climbing dy per block (the y of its
// unit direction), from where the mist starts out to t blocks from the camera. Mist.w is the camera's
// height above sea level.
float etnaMistDepth(float dy, float t) {
    float reach = max(t - AIR_MIST_START, 0.0);
    float h0 = Mist.w + dy * AIR_MIST_START;
    return Mist.x * etnaLayerDepth(h0 - AIR_GROUND_BASE, dy, reach, AIR_GROUND_SCALE)
        + Mist.y * etnaLayerDepth(h0, dy, reach, AIR_HAZE_SCALE);
}

// Density of the ground mist and the haze at height h above sea level, s blocks from the camera: the same
// layers etnaMistDepth integrates, eased in around where the mist starts.
float etnaMistDensity(float h, float s) {
    float on = smoothstep(AIR_MIST_START - 2.0, AIR_MIST_START + 2.0, s);
    return on * (Mist.x * exp(-max(h - AIR_GROUND_BASE, 0.0) / AIR_GROUND_SCALE)
        + Mist.y * exp(-max(h, 0.0) / AIR_HAZE_SCALE));
}

// Direction towards the light that casts the shadows: the sun, or the moon at night (Air.w 2).
vec3 etnaAirLightDir() {
    return Air.w > 1.5 ? -Sun.xyz : Sun.xyz;
}

// How the air scatters the light towards the eye: half evenly in every direction, half forwards
// (Henyey-Greenstein, g = 0.55; L. G. Henyey and J. L. Greenstein, 1941), so it averages 1 over the
// sphere. Mostly forwards makes the shafts and the mist glow towards a low sun; the even half keeps the
// mist lit when the sun is behind the camera. The base below is at least (1 - g)^2.
float etnaAirPhase(float cosTheta) {
    const float g = 0.55;
    float hg = (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * clamp(cosTheta, -1.0, 1.0), 1.5);
    return 0.5 + 0.5 * hg;
}

// The colours of the air's light, and the only place they are looked up. sun: the light the sun (or
// the moon) shines into the air, scaled by how strong it is now (Sun.w: none at the horizon, less in
// rain). With Etna's sky model (AirSun.w) it is the light the land gets, golden when low and pale by day,
// cool and faint from the moon, with its brightest channel at 1; without it, a fixed gold-to-pale ramp.
// ambient: the colour the mist itself has (MistColor: the model's horizon averaged all round while Etna
// draws the sky, else vanilla's fog colour; navy at night, grey in rain); deep underground a dark slate
// instead, and a little darker where the sky above the camera is covered.
void etnaAirLight(out vec3 sun, out vec3 ambient) {
    if (AirSun.w > 0.5) {
        sun = AirSun.rgb / max(max(AirSun.r, AirSun.g), max(AirSun.b, 1e-3));
        sun *= Air.w > 1.5 ? 0.45 : 1.0;
    } else if (Air.w > 1.5) {
        sun = vec3(0.50, 0.62, 1.0) * 0.45;
    } else {
        sun = mix(vec3(1.0, 0.56, 0.26), vec3(1.0, 0.94, 0.84), smoothstep(0.02, 0.5, Sun.y));
    }
    sun *= Sun.w;
    ambient = mix(MistColor.rgb * 0.94, vec3(0.050, 0.056, 0.068), Shafts.z) * (1.0 - 0.3 * Shafts.y);
}

#endif
