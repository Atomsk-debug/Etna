#ifndef ETNA_WATER_WAVES_GLSL
#define ETNA_WATER_WAVES_GLSL

// The water's swell, shared by the terrain shader (terrain_water.glsl, which draws the surface and the
// sky's reflection on it) and the water reflections pass (post/water_ssr.fsh, which traces the world's
// reflection off the same waves), so the two always tilt the surface the same way; and the water mask
// the reflections find the water by.

// Etna's water, glass and ice blend the picture's alpha down to 1 - their own alpha (at least 0.1, the
// translucent layer's alpha test), from the 1 everything opaque leaves there; whatever is drawn over them
// afterwards with vanilla's blend (clouds, rain) only pushes it back up. Below this the pixel is taken to
// show such a surface with nothing much in front of it: a cloud or thick rain over the water lifts it
// above, and that pixel keeps the sky's reflection.
const float WATER_MASK_LIMIT = 0.95;

// sin(x) as a parabola with one correction step, for the waves: within 0.0012 of sin, in a handful of
// multiply-adds where sin itself is a longer sequence on many GPUs. x / 2 pi is folded into one turn
// first, which costs large x no more precision than sin's own range reduction does.
float etnaSin(float x) {
    float y = fract(x * 0.15915494 + 0.5) * 2.0 - 1.0;
    float s = 4.0 * y * (1.0 - abs(y));
    return s + 0.225 * s * (abs(s) - 1.0);
}

// sin(x) and cos(x) the same way, in one go.
vec2 etnaSinCos(float x) {
    vec2 y = fract(x * 0.15915494 + vec2(0.5, 0.75)) * 2.0 - 1.0;
    vec2 s = 4.0 * y * (1.0 - abs(y));
    return s + 0.225 * s * (abs(s) - 1.0);
}

// The swell: the four long waves Etna's water has always had, which the sky's reflection, the world's
// reflection and Snell's window follow. xy: direction times frequency (radians per block), z: speed
// (radians per second), w: height in blocks. Every speed is a whole multiple of 2 pi / 1200 (210, 306, 439
// and 592 of them): the time the waves run on (GameTime * 1200 in the terrain, WaterSsr.y in the post
// passes) counts the seconds of a 20-minute day and starts again from 0 after it, and at those speeds the
// waves carry on across that jump without a seam.
const vec4 SWELL_WAVES[4] = vec4[4](
    vec4(1.040, 0.780, 1.09956, 0.0300),
    vec4(-0.945, 1.869, 1.60221, 0.0150),
    vec4(3.589, -0.888, 2.29860, 0.0072),
    vec4(-4.473, -4.410, 3.09970, 0.0036)
);

// How much of a wave of k radians per block a pixel footprint blocks wide resolves: all of it down to 18
// pixels per wavelength, none from 6 pixels down. The rest is averaged into the glint's roughness (the
// same light, spread wider) instead of being drawn as detail that would crawl and sparkle.
float waveSeen(float k, float footprint) {
    return 1.0 - smoothstep(0.35, 1.0, k * footprint);
}

// The swell's slope at world xz p and time t, every wave as it is: what Snell's window follows.
vec2 swellSlope(vec2 p, float t) {
    vec2 slope = vec2(0.0);
    for (int i = 0; i < 4; i++) {
        vec4 w = SWELL_WAVES[i];
        slope += (w.w * etnaSinCos(dot(p, w.xy) + t * w.z).y) * w.xy;
    }
    return slope;
}

// The swell's slope as a pixel footprint blocks wide resolves it: what the reflections follow (the same
// sum waterWaves makes for WaterWaves.seen in terrain_water.glsl).
vec2 swellSeen(vec2 p, float t, float footprint) {
    vec2 slope = vec2(0.0);
    for (int i = 0; i < 4; i++) {
        vec4 w = SWELL_WAVES[i];
        float r = waveSeen(length(w.xy), footprint);
        slope += (r * w.w * etnaSinCos(dot(p, w.xy) + t * w.z).y) * w.xy;
    }
    return slope;
}

// Far water seen nearly edge on. The waves a pixel no longer resolves (waveSeen) still tilt the facets it
// sees towards the eye, by about HIDDEN_TILT (the swell's and the ripples' slopes), which only matters when
// the view is about as flat as that: those facets mirror sky from above the horizon, and less of it than a
// flat mirror would. Without them, water far away seen from a block or two above it mirrored almost only
// the whitish horizon and looked white. How far the facets tilt, for a pixel footprint blocks wide.
const float HIDDEN_TILT = 0.08;
float waterHiddenTilt(float footprint) {
    return HIDDEN_TILT * (1.0 - waveSeen(length(SWELL_WAVES[0].xy), footprint));
}
// The cosine between the view and the facets the pixel sees, from cosView, the one to the resolved surface.
float waterSeenCos(float cosView, float footprint) {
    float tilt = waterHiddenTilt(footprint);
    return sqrt(cosView * cosView + tilt * tilt);
}
// Where those facets mirror the sky from, for `mirrored`, the reflection off the resolved surface.
vec3 waterSeenMirror(vec3 mirrored, float footprint) {
    float lift = 2.0 * waterHiddenTilt(footprint);
    float up = max(mirrored.y, 0.0);
    return normalize(vec3(mirrored.x, sqrt(up * up + lift * lift), mirrored.z));
}
// Schlick's Fresnel for water seen from the air, at the cosine between the view and the surface's normal:
// the share of the light the surface mirrors (4 % straight down, all of it at grazing angles).
float waterFresnel(float cosView) {
    return 0.04 + 0.96 * pow(1.0 - clamp(cosView, 0.0, 1.0), 5.0);
}

#endif
