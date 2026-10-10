// Water: the surface's own shading, the water around the camera, and the hooks the terrain lighting asks
// about them. terrain_hooks.glsl declares the hooks and says where terrain.fsh calls them.
// Shadow-receiving draws (ETNA_SHADOWS) get the water state from EtnaWater.java through the EtnaWater,
// EtnaWaterColor, EtnaWaterLook and EtnaSkyColor fields of the terrain block. Under water they draw
// their own fog (clear near the camera, the water's colour far away, reds fading first), light the
// ground under the water with sun that loses red then green on its way down, focused into caustics by
// the waves, and add shafts of light through the water. Every other draw (Etna off, improved
// transparency's translucent pass) keeps the look Etna had before: vanilla's fog and light under water.
// Everything here is in display gamma, like the rest of terrain.fsh.

#include <etna:water_waves.glsl>

const float WATER_IOR = 1.333;

#ifdef ETNA_SHADOWS
// ---- The water around the camera ------------------------------------------------------------------

// The camera is in water (EtnaWater.x: 0 air, 1 water, 2 lava, 3 powder snow).
bool cameraInWater() {
    return abs(EtnaWater.x - 1.0) < 0.5;
}

// The camera is in water and Etna's fog has replaced vanilla's this frame. Blindness, Darkness and a
// boss's world fog keep vanilla's: EtnaWater.java then sends a visibility (EtnaWaterColor.w) of 0.
bool etnaUnderwater() {
    return cameraInWater() && EtnaWaterColor.w > 0.5;
}

// Worked out once per fragment by etnaWaterPrepare, for the other hooks. All 0 unless etnaUnderwater().
float etnaWaterDepth = 0.0;   // blocks of water above this fragment, when it lies below the camera's water surface
float etnaWaterPath = 0.0;    // blocks of the view ray to this fragment that run through the water
float etnaSubmergedSky = 0.0; // how much of the open sky's light reaches this submerged fragment, 0 .. 1
float etnaSubmergedSun = 0.0; // gate on the sun for a submerged fragment: 1 when the shadow map decides
vec3 etnaSubmergedShare = vec3(1.0); // the sky's share a submerged fragment is lit with (etnaSkyShare, then etnaFinalSkyShare)

// Extinction of light in water per block, for the water clarity setting. Red goes first, blue last
// (ratios after Photon's underwater absorption, 0.20 / 0.08 / 0.04); the values are for display-gamma
// colour, so the 1/2.2 of the gamma is already in them.
vec3 waterExtinction(float clarity) {
    return vec3(2.1, 1.0, 0.64) * mix(0.16, 0.04, clamp(clarity, 0.0, 1.0));
}

// Extinction along the view for the underwater visibility V: green is down to 5 % at V.
vec3 viewExtinction() {
    return vec3(2.1, 1.0, 0.64) * (3.75 / max(EtnaWaterColor.w, 8.0));
}

// The water "thickness" the view fog counts for a distance d: like d^2 near the camera, so the first
// blocks stay clear, and like d - V/4 far away. waterPath(V) = 0.8 V.
float waterPath(float d) {
    return d * d / max(d + 0.25 * max(EtnaWaterColor.w, 8.0), 1e-3);
}

// How much of the view ray to a camera-relative point runs through the water: things above the
// surface, seen through it, count only the part below the surface (a flat surface at EtnaWater.z).
float viewInWater(vec3 viewPos) {
    float dist = length(viewPos);
    float up = viewPos.y / max(dist, 1e-4);
    if (up > 1e-3 && viewPos.y > EtnaWater.z) {
        return min(dist, max(EtnaWater.z, 0.0) / up);
    }
    return dist;
}

// What is left of a colour behind inWater blocks of water, plus the light the water adds on the way:
// the colour of the water itself (EtnaWaterColor, the fog colour vanilla's draws use too).
vec3 underwaterFog(vec3 color, float inWater) {
    vec3 transmittance = exp(-viewExtinction() * waterPath(inWater));
    return color * transmittance + EtnaWaterColor.rgb * (1.0 - transmittance);
}

// Direction of the light inside the water, pointing down: towardsSun refracted at a flat surface
// (Snell's law), so even a low sun shines down steeply under water.
vec3 sunInWater(vec3 towardsSun) {
    vec3 i = -towardsSun;
    const float eta = 1.0 / WATER_IOR;
    float k = 1.0 - eta * eta * (1.0 - i.y * i.y);
    return eta * i - (eta * i.y + sqrt(max(k, 0.0))) * vec3(0.0, 1.0, 0.0);
}

// Where the light reaching a point depth blocks under water came through the surface: back up along
// the refracted light. worldXZ: the point's horizontal position.
vec2 surfaceEntry(vec2 worldXZ, float depth, vec3 down) {
    return worldXZ - down.xz * (depth / max(-down.y, 0.2));
}

// ---- Caustics ---------------------------------------------------------------------------------------

// Eight waves of equal curvature (amplitude 1 / frequency^2), in spread-out directions, as two octaves:
// one octave alone draws stripes, two draw the cells of real caustics. xy: direction times frequency
// (radians per block), z: speed (radians per second), w: amplitude. Every speed is a whole multiple of
// 2 pi / 1200: GameTime * 1200 counts the seconds of a 20-minute day and starts again from 0 after it,
// and at those speeds the pattern carries on across that jump without a seam.
const vec4 CAUSTIC_WAVES[8] = vec4[8](
    vec4(2.5605, 0.4515, 1.29852, 0.14793),
    vec4(1.0603, 2.9130, -1.09956, 0.10406),
    vec4(-1.4784, 1.7619, 0.90059, 0.18904),
    vec4(-3.3829, -1.2313, 1.60221, 0.07716),
    vec4(4.1826, 3.5096, 2.72795, 0.01174),
    vec4(-1.1304, 6.4111, -2.30907, 0.00826),
    vec4(-4.5387, 1.6520, 1.89019, 0.01500),
    vec4(-4.8595, -5.7913, 3.36150, 0.00612)
);

// Sunlight the waves focus onto ground under water, as a factor that averages 1: bright lines around
// darker cells. A patch of surface sends its light onto a patch of ground whose area is the patch's
// own times det(I - k focus Hessian(height)), so the light per area there is 1 / det; the Hessian of a
// sum of sines is exact and takes one sine per wave. This is the idea of Photon's water caustics,
// which measure that area with screen-space derivatives in the shadow pass; here it is worked out
// analytically per fragment, with no extra pass, no derivatives and no noise. p: where the light came
// through the surface (world xz). focus: how far below the surface it lands, scaled by the setting.
// footprint: blocks per pixel, to fade lines thinner than a pixel. waves: 8, or 4 for the light shafts.
float causticLight(vec2 p, float t, float focus, float footprint, int waves) {
    vec3 h = vec3(0.0); // d2h/dx2, d2h/dz2, d2h/dxdz
    for (int i = 0; i < waves; i++) {
        vec4 w = CAUSTIC_WAVES[i];
        float c = -w.w * etnaSin(dot(p, w.xy) + t * w.z);
        h += c * vec3(w.x * w.x, w.y * w.y, w.x * w.y);
    }
    float q = 0.25 * (1.0 - 1.0 / WATER_IOR) * focus;
    float a = 1.0 - q * h.x;
    float b = 1.0 - q * h.y;
    float c = -q * h.z;
    float light = min(1.0 / max(abs(a * b - c * c), 0.15), 4.0);
    // Sharpen: thin bright lines, darker cells.
    light = light * light / (1.0 + 0.25 * light * light) * 1.25;
    // The pattern's average, measured offline, is about 1 + 0.016 focus^2 for focus up to 6: back to 1.
    light /= 1.0 + 0.016 * focus * focus;
    // The finest wave is about 0.8 blocks long: once a pixel covers a good part of it, lines would shimmer.
    return mix(light, 1.0, smoothstep(0.12, 0.35, footprint));
}

// Direct light reaching a point depth blocks under water, per colour, before the shadow map and the
// face's angle: absorbed on its way down (reds first) and focused into caustics by the waves.
vec3 underwaterSun(float depth, float footprint) {
    vec3 down = sunInWater(ETNA_LIGHT_DIR);
    float path = depth / max(-down.y, 0.2);
    vec3 transmittance = exp(-waterExtinction(EtnaWaterLook.x) * path);
    vec2 entry = surfaceEntry(etnaWorldPos.xz, depth, down);
    // No caustics right at the waterline; deep floors get softer ones.
    float focus = mix(2.0, 6.0, EtnaWaterLook.y) * smoothstep(0.0, 1.5, depth);
    float caustics = mix(1.0, causticLight(entry, GameTime * 1200.0, focus, footprint, 8), EtnaWaterLook.y * exp(-depth / 24.0));
    return transmittance * caustics;
}

// ---- Light shafts under water -----------------------------------------------------------------------

// Light shafts while the camera is under water, as a change to the light the water itself scatters
// (EtnaWaterColor, which the fog adds already, colour by colour): open sunlit water averages out to
// about nothing, with caustic streaks through it, and water that terrain shades from the sun goes
// darker, at most down to none of that light, never below what the fog lets through from behind, and
// keeping its hue. Where nothing is drawn the picture is the plain fog colour, the same average, so
// distant terrain doesn't stand out against it. In place of the air's light shafts: 8 shadow map reads
// and 32 sines while the anti-aliasing blends frames (the jitter moves the steps every frame, so a few
// frames add up to many more), 12 and 48 without it.
vec3 underwaterShafts(vec3 viewPos, float inWater, float jitter) {
    float march = min(inWater, 48.0);
    if (march < 0.5 || EtnaLook2.w < 0.5 || EtnaWaterLook.z <= 0.0 || ETNA_LIGHT_STRENGTH <= 0.0) {
        return vec3(0.0);
    }
    vec3 dir = viewPos / max(length(viewPos), 1e-3);
    vec3 extinction = viewExtinction();
    float sigma = extinction.g;
    vec3 down = sunInWater(ETNA_LIGHT_DIR);
    vec2 cameraXZ = etnaWorldPos.xz - etnaViewPos.xz;
    float t = GameTime * 1200.0;
    int steps = EtnaWorld.y > 0.5 ? 8 : 12;
    float sum = 0.0;
    float weights = 0.0;
    for (int i = 0; i < steps; i++) {
        float s = (float(i) + jitter) / float(steps) * march;
        vec3 p = dir * s;
        float depth = max(EtnaWater.z - p.y, 0.0);
        // First octave only: the shafts are soft anyway.
        float caustic = 0.35 + 0.65 * causticLight(surfaceEntry(cameraXZ + p.xz, depth, down), t, 4.0, 0.0, 4);
        float w = exp(-sigma * waterPath(s));
        sum += w * (shadowAtSample(p, fract(jitter + 0.618034 * float(i))) * caustic - 1.0);
        weights += w;
    }
    // What underwaterFog adds along the part of the view the shafts cover.
    vec3 covered = 1.0 - exp(-extinction * waterPath(march));
    // Water scatters mostly forwards: Henyey-Greenstein with g = 0.5 mixed with a third isotropic, as in
    // Photon's water. The base below is at least (1 - g)^2.
    const float g = 0.5;
    float cosTheta = dot(dir, -down);
    float hg = (1.0 - g * g) / pow(max(1.0 + g * g - 2.0 * g * cosTheta, 1e-3), 1.5);
    float gain = min(0.6 * (0.7 * hg + 0.3), 0.9) * EtnaWaterLook.z * ETNA_LIGHT_STRENGTH;
    return EtnaWaterColor.rgb * covered * max((sum / max(weights, 1e-4)) * gain, -1.0);
}
#endif

// ---- Waves and the sun's glint ----------------------------------------------------------------------

// Ripples on the swell, which break the sun's reflection up into glints as on real water: nine waves
// from 0.83 down to 0.17 blocks long, in three octaves of three directions 120 degrees apart, each octave
// turned against the others, with slopes of 0.045, 0.04 and 0.035 (steeper ones would strew glints all
// over the water). Laid out like SWELL_WAVES. Speeds 200 to 490 times 2 pi / 1200: the crests drift 0.07
// to 0.14 blocks a second, calm water whose glints stay put long enough to read as glints.
const vec4 RIPPLE_WAVES[9] = vec4[9](
    vec4(7.1136, 2.5891, 1.047198, 0.005944),
    vec4(-6.7792, 5.6884, 1.256637, 0.005085),
    vec4(-1.7598, -9.9802, 1.466077, 0.004440),
    vec4(3.7819, 14.1142, 1.570796, 0.002737),
    vec4(-16.4029, -4.3952, 1.780236, 0.002355),
    vec4(13.8840, -13.8840, 1.989675, 0.002037),
    vec4(17.5598, 20.9270, 2.146755, 0.001281),
    vec4(-30.9386, 5.4553, 2.356194, 0.001114),
    vec4(12.6410, -34.7310, 2.565634, 0.000947)
);

// How far each ripple's crests move per block of the swell's height, in radians: the swell bends the
// ripples, so where a pixel spans several of them they never line up into a regular lattice.
const float RIPPLE_WARP[9] = float[9](28.33, -21.67, 15.0, -31.67, 18.33, -11.67, 25.0, -16.67, 13.33);

// The ripples' slopes added up: the most they can tilt the swell.
const float RIPPLE_SLOPES = 0.36;
// GGX roughness (alpha) of the water itself, where the pixel resolves every wave: the smallest glint.
const float GLINT_ROUGHNESS = 0.015;
// How far in slope beyond what the ripples can tilt the swell by the glint is still worked out, seen
// from above: its lobe is down to well under 1 % of its peak there.
const float GLINT_REACH = 0.6;
// The sun's light in the glint in total: what the lobe before it held (pow(r.l, 220) * 2 adds up to
// 2 * 2 pi / 221), so the water mirrors as much sunlight as it did, only spread out differently.
const float GLINT_LIGHT = 0.0569;
// The brightest a glint gets in full light, below where the bloom blows a highlight up into a white
// shape. In weaker light (dusk, rain, the moon) this ceiling comes down with the square root of the
// light, so dim glints don't all end up at the same peak and moonlit ones stay dimmer than sunlit ones.
const float GLINT_PEAK = 0.7;

// The waves at a point of the surface, as a pixel sees them.
struct WaterWaves {
    vec2 seen;   // slope of the swell, as far as the pixel resolves it: what the sky's reflection follows
    vec2 slope;  // slope of swell and ripples together, as resolved: the mirror the glint comes off
    float rough; // GGX alpha squared of the glint: the water's own, the variance of the slopes the pixel
                 // averages over, and the spread of slopes across the pixel where the waves curve
    float reach; // 1 where the glint can show, falling to 0 where no ripple can tilt the surface enough
};

// The waves at world xz p and time t (GameTime * 1200, in seconds) for a pixel footprint blocks wide.
// target: the slope that would send the sun into the eye. The ripples are only worked out where they can
// bring the slope within range of it (in slope, past what they can tilt the swell by); reach fades out
// over the second half of that range, so the glint ends with no edge. Where reach is 0, slope and rough
// leave the ripples out.
WaterWaves waterWaves(vec2 p, float t, float footprint, vec2 target, float range) {
    float height = 0.0;     // of the swell, in blocks: it bends the ripples
    vec3 curve = vec3(0.0); // d2h/dx2, d2h/dz2 and d2h/dxdz of the waves the pixel resolves
    float unseen = 0.0;     // variance of the slopes the pixel averages over
    WaterWaves waves;
    waves.seen = vec2(0.0);
    for (int i = 0; i < 4; i++) {
        vec4 w = SWELL_WAVES[i];
        vec2 sc = etnaSinCos(dot(p, w.xy) + t * w.z);
        float k2 = dot(w.xy, w.xy);
        float r = waveSeen(sqrt(k2), footprint);
        height += w.w * sc.x;
        waves.seen += (r * w.w * sc.y) * w.xy;
        curve -= (r * w.w * sc.x) * vec3(w.x * w.x, w.y * w.y, w.x * w.y);
        // A sine's slope averages out to a variance of half its amplitude squared.
        unseen += (1.0 - r * r) * 0.5 * w.w * w.w * k2;
    }
    waves.slope = waves.seen;
    waves.reach = 1.0 - smoothstep(RIPPLE_SLOPES + 0.5 * range, RIPPLE_SLOPES + range, distance(waves.seen, target));
    if (waves.reach > 0.0) {
        for (int i = 0; i < 9; i++) {
            vec4 w = RIPPLE_WAVES[i];
            float k2 = dot(w.xy, w.xy);
            float r = waveSeen(sqrt(k2), footprint);
            if (r > 0.0) {
                vec2 sc = etnaSinCos(dot(p, w.xy) + t * w.z + RIPPLE_WARP[i] * height);
                waves.slope += (r * w.w * sc.y) * w.xy;
                curve -= (r * w.w * sc.x) * vec3(w.x * w.x, w.y * w.y, w.x * w.y);
            }
            unseen += (1.0 - r * r) * 0.5 * w.w * w.w * k2;
        }
    }
    // Across a pixel the resolved waves' curvature spreads the slopes by about the footprint times the
    // curvature (geometric specular anti-aliasing): a glint narrower than a pixel widens and dims instead
    // of flickering. Capped where it would smear a glint over the whole path of the sun.
    float spread = 2.0 * footprint * length(vec4(curve, curve.z));
    waves.rough = GLINT_ROUGHNESS * GLINT_ROUGHNESS + unseen + min(spread * spread, 0.05);
    return waves;
}

// GGX's distribution of facet normals (Trowbridge-Reitz), at cos theta nh for alpha squared a2.
float glintGgx(float nh, float a2) {
    float d = nh * nh * (a2 - 1.0) + 1.0;
    return a2 / (3.14159265 * d * d);
}

// A glint eased off towards its ceiling, which it nears but never reaches: bright glints keep their
// shape instead of flattening into a saturated patch.
float glintCap(float g, float ceiling) {
    return g * ceiling * inversesqrt(max(g * g + ceiling * ceiling, 1e-12));
}

// The sun's glint off the waves, seen from toEye (h: halfway between it and the sun): GLINT_LIGHT spread
// by GGX over the faces of the waves, eased below GLINT_PEAK. light: how strongly the sun shines on the
// water (open sky, time of day, the water reflections setting). The caller shades it from the shadow map,
// once for the glint and the glitter together.
float sunGlint(WaterWaves waves, vec3 toEye, vec3 h, float light) {
    if (waves.reach <= 0.0) {
        return 0.0;
    }
    vec3 facet = normalize(vec3(-waves.slope.x, 1.0, -waves.slope.y));
    if (dot(facet, ETNA_LIGHT_DIR) <= 0.0 || dot(facet, toEye) <= 0.0) {
        return 0.0;
    }
    float nh = clamp(dot(facet, h), 0.0, 1.0);
    // Facets around h, as reflected directions: D(h) (n.h) / (4 v.h) adds up to 1 over the sky.
    float g = GLINT_LIGHT * glintGgx(nh, waves.rough) * nh / (4.0 * max(dot(toEye, h), 0.1));
    return glintCap(g * light * waves.reach, GLINT_PEAK * sqrt(max(light, 0.0)));
}

// ---- Glitter ------------------------------------------------------------------------------------------
// Sparkles of the sun, and of the moon at night, along the path its glint takes across the water: tiny
// facets of the waves, each turned its own way, that flash for a moment as one of them happens to mirror
// the light into the eye. The water is cut into square cells fixed in the world; each cell holds one
// facet with a random tilt and one round spark at a random place inside it. A cell lights up where the
// swell's slope plus its facet's tilt comes close to the slope that mirrors the light into the eye
// (target), so the sparks gather along the glint's path, follow the camera's view and change as the
// waves roll. 2.2 times a second each cell draws a new facet, and the old one crossfades
// into it, so a spark fades in and out over a few frames instead of popping. Cells are never smaller than
// GLITTER_PIXELS pixels: further away they double in size, crossfading from one size to the next, so a
// spark is always more than a pixel wide and still lies in the same place frame after frame.

// Cell size near the camera, in blocks: two texels of a 16x16 texture.
const float GLITTER_CELL = 0.125;
// The fewest pixels a cell spans, so its spark (GLITTER_DOT of the cell across) stays over a pixel wide.
const float GLITTER_PIXELS = 6.0;
// Radius of a spark, as a share of its cell; its centre stays this far inside the cell's edges.
const float GLITTER_DOT = 0.22;
// The facets' tilts, as slopes: up to this far from the swell's. Wider spreads the sparks over more of
// the water, each less likely to light.
const float GLITTER_SPREAD = 0.25;
// How close in slope a facet must come to the target to flash: about 10 % of the cells along the path.
const float GLITTER_TOLERANCE = 0.08;
// New facets per 20-minute day (2.2 a second), a whole number so the slots carry on without a seam when
// the clock starts again from 0, and the share of each slot spent crossfading into the next.
const float GLITTER_SLOTS = 2640.0;
const float GLITTER_FADE = 0.35;
// The brightest a spark gets in full sunlight, at the glitter setting's full strength. Like the glint
// it comes down with the square root of the light, so the moon's sparks still show; the moon's get
// GLITTER_MOON times more on top of that.
const float GLITTER_PEAK = 1.2;
const float GLITTER_MOON = 2.2;

// Three well-mixed 32-bit numbers from a cell and a time slot: the pcg3d hash (Mark Jarzynski and Marc
// Olano, "Hash Functions for GPU Rendering", JCGT 2020).
uvec3 glitterHash(ivec3 v) {
    uvec3 u = uvec3(v) * 1664525u + 1013904223u;
    u.x += u.y * u.z;
    u.y += u.z * u.x;
    u.z += u.x * u.y;
    u ^= u >> 16u;
    u.x += u.y * u.z;
    u.y += u.z * u.x;
    u.z += u.x * u.y;
    return u;
}

// The spark of one cell in one time slot at world xz p: 0 .. 1.
float glitterSpark(vec2 p, float size, int level, int slot, vec2 seen, vec2 target) {
    ivec2 cell = ivec2(floor(p / size));
    uvec3 h = glitterHash(ivec3(cell, slot * 8 + level));
    vec3 r = vec3(h) * (1.0 / 4294967296.0);
    // The facet's tilt, spread evenly over a disc of GLITTER_SPREAD.
    float angle = r.x * 6.2831853;
    vec2 tilt = sqrt(r.y) * GLITTER_SPREAD * vec2(cos(angle), sin(angle));
    vec2 miss = seen + tilt - target;
    float aligned = 1.0 - min(dot(miss, miss) / (GLITTER_TOLERANCE * GLITTER_TOLERANCE), 1.0);
    if (aligned <= 0.0) {
        return 0.0;
    }
    // Where the spark sits in its cell, from the hash's third number (two halves of it).
    vec2 at = vec2(h.z & 0xFFFFu, h.z >> 16u) * (1.0 / 65536.0);
    vec2 centre = (vec2(cell) + GLITTER_DOT + (1.0 - 2.0 * GLITTER_DOT) * at) * size;
    float spot = 1.0 - smoothstep(0.0, GLITTER_DOT * size, distance(p, centre));
    return aligned * aligned * spot;
}

// Glitter at world xz p and time t (seconds) for a pixel footprint blocks wide, before the shadow map.
// seen: the swell's slope as the pixel resolves it; target: the slope that mirrors the light into the eye;
// light: how strongly the light shines on the water (as for the glint). 0 with the setting at 0, without
// Etna's block, and away from the light's path.
float waterGlitter(vec2 p, float t, float footprint, vec2 seen, vec2 target, float light) {
    #ifdef ETNA_SHADOWS
    float amount = EtnaWaterSsr.y;
    if (amount <= 0.0 || light <= 0.0 || distance(seen, target) > GLITTER_SPREAD + GLITTER_TOLERANCE) {
        return 0.0;
    }
    // The cell size: GLITTER_CELL near the camera, doubling as the pixels grow, between two sizes.
    float lod = log2(max(footprint * GLITTER_PIXELS / GLITTER_CELL, 1.0));
    float level = floor(lod);
    float between = lod - level;
    // Each cell's slots start at their own time, so the water never changes all at once.
    float sum = 0.0;
    for (int i = 0; i < 2; i++) {
        float l = level + float(i);
        float size = GLITTER_CELL * exp2(l);
        float shift = float(glitterHash(ivec3(ivec2(floor(p / size)), 7 + int(l))).x & 1023u) / 1024.0;
        float clock = t * (GLITTER_SLOTS / 1200.0) + shift;
        float slot = floor(clock);
        float fade = smoothstep(0.5 - 0.5 * GLITTER_FADE, 0.5 + 0.5 * GLITTER_FADE, clock - slot);
        // Counted round the day, so the slots just before the clock wraps are the ones just after it.
        int s = int(mod(slot, GLITTER_SLOTS));
        int next = int(mod(slot + 1.0, GLITTER_SLOTS));
        float spark = mix(glitterSpark(p, size, int(l), s, seen, target), glitterSpark(p, size, int(l), next, seen, target), fade);
        sum += spark * (i == 0 ? 1.0 - between : between);
    }
    float peak = GLITTER_PEAK * sqrt(light) * (ETNA_MOON ? GLITTER_MOON : 1.0);
    return min(sum * peak * amount, 1.0);
    #else
    return 0.0;
    #endif
}

// Temporal anti-aliasing moves the level's picture by a fraction of a pixel every frame: EtnaFrame's
// 8-step Halton sequence (bases 2 and 3, less 0.5), in pixels, one step per frame. Keep the two in step.
const vec2 ETNA_JITTER[8] = vec2[8](
    vec2(0.0, -0.16666667), vec2(-0.25, 0.16666667), vec2(0.25, -0.38888889), vec2(-0.375, -0.05555556),
    vec2(0.125, 0.27777778), vec2(-0.125, -0.27777778), vec2(0.375, 0.05555556), vec2(-0.4375, 0.38888889)
);

// From this fragment to the point its pixel's centre would show without this frame's jitter, camera-
// relative. Detail drawn there holds still from frame to frame; drawn where the jitter puts it, small
// detail that moves (glints on moving waves) shimmers, because the anti-aliasing drops the old frames
// wherever the picture changes by more than about a tenth of a pixel. Such detail must then be smooth
// at the scale of a pixel by itself, as the glint is. 0 without anti-aliasing, and in draws without
// Etna's block, which can't tell the frame.
vec3 etnaUnjitter() {
    #ifdef ETNA_SHADOWS
    if (EtnaWorld.y > 0.5) {
        // EtnaLook2.z is the frame number modulo 4096, a multiple of the sequence's 8 steps.
        vec2 jitter = ETNA_JITTER[int(EtnaLook2.z + 0.5) & 7];
        // The jitter moves the picture by +jitter pixels in window space (x right, y down, as dFdx and
        // dFdy count), so the centre's own point lies +jitter pixels on along the surface.
        vec3 shift = dFdx(etnaViewPos) * jitter.x + dFdy(etnaViewPos) * jitter.y;
        // Derivatives are undefined next to a discarded pixel: never move by more than a pixel.
        return dot(shift, shift) <= etnaPixelFootprint * etnaPixelFootprint ? shift : vec3(0.0);
    }
    #endif
    return vec3(0.0);
}

#if defined(ETNA_SHADOWS) && defined(ETNA_TRANSLUCENT_LAYER)
// ---- The water surface ------------------------------------------------------------------------------
// Only the classic translucent pass blends what this writes into alpha. With improved transparency the
// water is drawn without Etna's block and keeps its look from before.

// Fresnel reflectance at the boundary between two clear media. n: the index of refraction of the far
// side over that of the near side (1 / 1.333 looking from the water up into the air). Returns 1 past
// the critical angle: total internal reflection.
// Ported from Photon Shaders by SixthSurge (include/lighting/bsdf.glsl, fresnel_dielectric_n), Photon
// Shaders License, see CREDITS.md and LICENSE-PHOTON.txt.
float fresnelDielectric(float cosTheta, float n) {
    float gSq = n * n + cosTheta * cosTheta - 1.0;
    if (gSq < 0.0) {
        return 1.0;
    }
    float g = sqrt(gSq);
    float a = g - cosTheta;
    float b = g + cosTheta;
    float x = (b * cosTheta - 1.0) / max(a * cosTheta + 1.0, 1e-4);
    float ab = a / max(b, 1e-6);
    return clamp(0.5 * ab * ab * (1.0 + x * x), 0.0, 1.0);
}

// A colour and alpha for the translucent blend, which can't take a colour above 1: alpha grows (the
// surface covers a little more of what is behind it) until the colour fits, so a bright glint still
// shows at full strength over clear water.
vec4 etnaFitAlpha(vec3 rgb, float alpha) {
    float peak = max(rgb.r, max(rgb.g, rgb.b));
    if (peak > 1.0) {
        float covered = min(alpha * peak, 1.0);
        rgb *= alpha / max(covered, 1e-4);
        alpha = covered;
    }
    return vec4(rgb, alpha);
}

// Where the point itself lies in the shade of something over the water (a tree, a cliff, the bank on the
// sun's side, leaves), the map holds that instead of the bottom, but around the shade it still holds the
// bottom: these points on the water's plane, in blocks (x, z) with their weight, stand in for it. Twelve
// in three rings: 0.8 blocks away, 2 blocks away on the diagonals and 4 blocks away, nearer ones counting
// more. Fixed in the world, so the result never crawls as the camera moves.
const vec3 THICKNESS_RING[12] = vec3[12](
    vec3(0.8, 0.0, 1.0), vec3(-0.8, 0.0, 1.0), vec3(0.0, 0.8, 1.0), vec3(0.0, -0.8, 1.0),
    vec3(1.414, 1.414, 0.7), vec3(-1.414, 1.414, 0.7), vec3(1.414, -1.414, 0.7), vec3(-1.414, -1.414, 0.7),
    vec3(4.0, 0.0, 0.5), vec3(-4.0, 0.0, 0.5), vec3(0.0, 4.0, 0.5), vec3(0.0, -4.0, 0.5)
);

// Blocks of water under this point of the surface, along the light, read from the shadow map: it holds
// only opaque terrain, so under open water it holds the bottom. known says how far that can be trusted,
// 0 .. 1: nothing without the map or outside it. Where something opaque hangs over the water the map
// holds that instead, and the bottom around the shade (THICKNESS_RING) stands in: dappled shade under
// leaves and narrow shade along a bank keep the clear water around them, and only the middle of a wide
// shade, with no bottom in sight, goes back to the surface as it was. known also fades out towards the
// map's edge, so no ring shows where it ends, and as the light fades at dusk or in rain, so the water
// doesn't change at once when the map stops being drawn. The four texels around the point are weighed
// bilinearly, so shorelines don't step texel by texel.
float waterThickness(vec3 surfacePos, out float known) {
    known = 0.0;
    if (EtnaLook2.w < 0.5) {
        return 0.0;
    }
    // The map's projection is orthographic: a point moved on the water's plane moves its clip position
    // by the matrix's x and z columns, with no second trip through the matrix.
    vec3 clip = (EtnaShadowMat * vec4(surfacePos, 1.0)).xyz;
    vec4 map = etnaShadowMapPoint(clip);
    if (map.w >= 1.0) {
        return 0.0;
    }
    float size = 1.0 / max(EtnaShadowInfo.x, 1e-6);
    vec2 texel = map.xy * size - 0.5;
    ivec2 first = ivec2(floor(texel));
    vec2 f = texel - vec2(first);
    ivec2 last = ivec2(size) - 1;
    float blocksPerUnit = 1.0 / max(EtnaShadowInfo.z, 1e-6);
    float sum = 0.0;
    float weight = 0.0;
    for (int i = 0; i < 4; i++) {
        ivec2 o = ivec2(i & 1, i >> 1);
        float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
        float stored = texelFetch(EtnaShadowSampler, clamp(first + o, ivec2(0), last), 0).r;
        // Reversed depth: the bottom lies further from the light than the surface, so it stores less.
        float blocks = (map.z - stored) * blocksPerUnit;
        if (blocks > 0.02) {
            sum += w * blocks;
            weight += w;
        }
    }
    float found = weight;
    if (weight < 0.999) {
        vec3 alongX = EtnaShadowMat[0].xyz;
        vec3 alongZ = EtnaShadowMat[2].xyz;
        float ringSum = 0.0;
        float ringWeight = 0.0;
        for (int i = 0; i < 12; i++) {
            vec3 tap = THICKNESS_RING[i];
            vec4 p = etnaShadowMapPoint(clip + alongX * tap.x + alongZ * tap.y);
            if (p.w < 1.0) {
                float stored = texelFetch(EtnaShadowSampler, clamp(ivec2(p.xy * size), ivec2(0), last), 0).r;
                // Land beside the water stands above its plane and shade holds what casts it: neither counts.
                float blocks = (p.z - stored) * blocksPerUnit;
                if (blocks > 0.02) {
                    ringSum += tap.z * blocks;
                    ringWeight += tap.z;
                }
            }
        }
        // Bottom found at two of the nearest points, or three of the furthest, is enough to go by.
        float share = (1.0 - weight) * min(ringWeight / 1.5, 1.0);
        sum += share * ringSum / max(ringWeight, 1e-4);
        found = weight + share;
    }
    known = found * (1.0 - smoothstep(0.85, 1.0, map.w)) * smoothstep(0.002, 0.03, ETNA_LIGHT_STRENGTH);
    return sum / max(found, 1e-4);
}

// ---- Shoreline foam -----------------------------------------------------------------------------------
// A thin, faint, broken line of foam where still water meets the shore. Minecraft's shores run along the
// edges of blocks, so the line is measured from those edges: the shadow map, which holds opaque terrain
// only, says which of the eight blocks around this one are land, from one read at each block's middle on
// the water's plane, and the line follows the nearest land block's edge exactly, however far away and
// whatever the map's resolution. Smooth noise fixed in the world wobbles its edge, breaks it into patches
// and drifts slowly round, fine bubbles open it up close by, and the line breathes a little as if the
// water lapped at the shore. It is lit like a pale top face in the same place (sun, shade, sky, torches),
// never brighter than the sand beside it. The map can't tell a block of land from water lying in that
// block's shade, so where the light comes from behind a neighbouring block, only land standing barely
// above the water (a beach, where shade can't reach that far) counts: banks get their foam on the side the
// light comes from. Without the map (shadows off, dusk, rain) there is none. 0.8.0 also foamed every floor
// lying just under the surface, which turned thin sheets of flowing water down a bank into blotches and
// shallow water into a milky haze: shallow water now stays clear, and water flowing down a slope has no
// foam.

// Width of the line, in blocks, before the noise wobbles it.
const float FOAM_WIDTH = 0.2;
// How much of the water the line covers where it is thickest, at the setting's full strength.
const float FOAM_OPACITY = 0.7;
// Land counts only up to this height above the water's plane (along the light, as the map measures it):
// higher, it is more likely the shade of a tree or a cliff top than a shore.
const float FOAM_BANK = 1.3;
// Land behind the block, as the light sees it, counts only up to this height: a beach's top.
const float FOAM_BEACH = 0.2;
// The foam's own colour: a pale grey-white with a breath of blue, so in full sun it reads as foam and
// doesn't glow.
const vec3 FOAM_ALBEDO = vec3(0.66, 0.70, 0.72);
// Bubbles per block, along each axis.
const float FOAM_BUBBLES = 6.0;

// Smooth value noise, 0 .. 1, on a lattice of one block: fixed in the world, no detail finer than a block.
float foamNoise(vec2 p) {
    vec2 c = floor(p);
    vec2 f = p - c;
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    ivec2 i = ivec2(c);
    float a = float(glitterHash(ivec3(i, 911)).x);
    float b = float(glitterHash(ivec3(i + ivec2(1, 0), 911)).x);
    float d = float(glitterHash(ivec3(i + ivec2(0, 1), 911)).x);
    float e = float(glitterHash(ivec3(i + ivec2(1, 1), 911)).x);
    return mix(mix(a, b, u.x), mix(d, e, u.x), u.y) * (1.0 / 4294967296.0);
}

// How strongly foam covers the water surface at world xz p and time t (seconds), 0 .. 1, before the
// setting.
float shoreFoam(vec2 p, float t) {
    if (EtnaWaterSsr.x <= 0.0 || EtnaLook2.w < 0.5) {
        return 0.0;
    }
    vec3 clip = (EtnaShadowMat * vec4(etnaViewPos, 1.0)).xyz;
    vec4 here = etnaShadowMapPoint(clip);
    if (here.w >= 1.0) {
        return 0.0;
    }
    float size = 1.0 / max(EtnaShadowInfo.x, 1e-6);
    ivec2 last = ivec2(size) - 1;
    float blocksPerUnit = 1.0 / max(EtnaShadowInfo.z, 1e-6);
    vec3 alongX = EtnaShadowMat[0].xyz;
    vec3 alongZ = EtnaShadowMat[2].xyz;
    vec2 inBlock = fract(p);
    vec2 toMiddle = 0.5 - inBlock;
    float flatLight = length(ETNA_LIGHT_DIR.xz);
    vec2 towardLight = flatLight > 1e-3 ? ETNA_LIGHT_DIR.xz / flatLight : vec2(0.0);
    float nearest = 2.0;
    for (int i = 0; i < 9; i++) {
        vec2 o = vec2(float(i % 3) - 1.0, float(i / 3) - 1.0);
        if (i == 4) {
            continue;
        }
        vec4 m = etnaShadowMapPoint(clip + alongX * (toMiddle.x + o.x) + alongZ * (toMiddle.y + o.y));
        if (m.w >= 1.0) {
            continue;
        }
        float stored = texelFetch(EtnaShadowSampler, clamp(ivec2(m.xy * size), ivec2(0), last), 0).r;
        // Reversed depth: what the light meets first stores more. Its height above the water's plane, had
        // the light come straight down.
        float above = (stored - m.z) * blocksPerUnit * max(ETNA_LIGHT_DIR.y, 0.05);
        // The light reaches that block's middle from behind it (seen from here): its own shade can't be told
        // from land there, so only a beach counts.
        float behind = dot(normalize(o), towardLight) * flatLight;
        float limit = behind > 0.35 ? FOAM_BEACH : FOAM_BANK;
        if (above > 0.02 && above < limit) {
            // From here to the nearest point of that block.
            vec2 gap = max(mix(inBlock, 1.0 - inBlock, step(0.0, o)) * abs(o), 0.0);
            nearest = min(nearest, length(gap));
        }
    }
    // Slow, seamless drift: circles whose periods divide the 20-minute clock (2 and 1.25 minutes).
    float turn = t * (6.2831853 / 120.0);
    float turn2 = t * (6.2831853 / 75.0);
    float wobble = foamNoise(p * 1.6 + 0.6 * vec2(cos(turn), sin(turn))) - 0.5;
    float patches = smoothstep(0.25, 0.7, foamNoise(p * 3.1 + vec2(17.3, 5.1) + 0.4 * vec2(sin(turn2), cos(turn2))));
    // Bubbles, merging into their average where a pixel spans several of them, so far away they never
    // crawl.
    float bubbles = smoothstep(0.3, 0.65, foamNoise(p * FOAM_BUBBLES + vec2(3.7, 11.9) + 0.5 * vec2(cos(turn2), sin(turn))));
    bubbles = mix(bubbles, 0.5, smoothstep(0.25, 1.0, etnaPixelFootprint * FOAM_BUBBLES));
    // The water lapping at the shore: the line breathes every 8 seconds, at its own moment along the shore.
    float lap = 0.85 + 0.15 * etnaSin(t * (6.2831853 / 8.0) + 6.0 * wobble);
    // Never thinner than a pixel and a half: wider and fainter instead, so it never breaks up into crawling dots.
    float width = FOAM_WIDTH * lap;
    float shown = max(width, 1.5 * etnaPixelFootprint);
    float line = (1.0 - smoothstep(0.0, shown, nearest + 0.08 * wobble)) * (width / shown);
    float edge = 1.0 - smoothstep(0.85, 1.0, here.w);
    return line * (0.2 + 0.8 * patches) * (0.35 + 0.65 * bubbles) * edge;
}

// The light on foam at this point: what a white top face here would get (shadeTerrain's light for it).
vec3 foamLight() {
    float shadow = EtnaLook2.w > 0.5 ? shadowAt(etnaViewPos) : 1.0;
    shadow *= etnaCloudShadow();
    vec3 up = vec3(0.0, 1.0, 0.0);
    vec3 sky = skyLighting(up, SurfaceDetail(up, 0.0, 0.5), false, false, shadow) * max(etnaLightmap - etnaTorchLight, 0.0);
    return sky + torchLight(etnaTorchLight) * etnaTorchBoost();
}

// The water surface seen from above: shallow water clear, deep water the colour of the water, foam along
// the shore. vanillaAlpha: the water texture's alpha; lit: the surface as shadeTerrain lit it, whose
// brightness (shade of trees and cliffs, time of day) the water's body takes, with the hue of the water's
// own tint. fresnel and reflection: the sky's reflection, glint: the sun's, with the glitter. p and t:
// where (world xz) and when the foam is worked out; level: whether the surface lies flat (no foam on water
// flowing down a slope). The blend then gives
// behind * (1 - a)(1 - F) + body * (1 - T)(1 - F) + reflection * F + glint, with T what the water lets
// through on the way to the bottom and back up to the eye, and a its average. Where the depth is unknown
// the body is the lit texture's colour and a its alpha: the look water had before it had depth, with what
// it mirrors taken out of what it lets through, so the sky's reflection is always exactly reflection * F
// (the water reflections pass, post/water_ssr.fsh, takes that out again where it finds the world instead).
vec4 waterFromAbove(float vanillaAlpha, vec3 lit, vec3 viewDir, vec3 reflection, float fresnel, vec3 glint, vec2 p, float t, bool level) {
    float plainAlpha = 1.0 - (1.0 - vanillaAlpha) * (1.0 - fresnel);
    vec4 surface = etnaFitAlpha((lit * (vanillaAlpha * (1.0 - fresnel)) + reflection * fresnel + glint) / max(plainAlpha, 0.05), plainAlpha);
    float known;
    float alongLight = waterThickness(etnaViewPos, known);
    float vertical = alongLight * max(ETNA_LIGHT_DIR.y, 0.05);
    // Mixed as the blend sees them (colour times alpha), so every hand-over stays smooth.
    vec3 covered = surface.rgb * surface.a;
    float outAlpha = surface.a;
    if (known > 0.0) {
        float path = min(vertical / max(-viewDir.y, 0.1), 64.0);
        vec3 transmittance = exp(-waterExtinction(EtnaWaterLook.x) * path);
        float a = clamp(1.0 - dot(transmittance, vec3(0.25, 0.5, 0.25)), 0.12, 1.0);
        vec3 hue = vertexColor.rgb / max(dot(vertexColor.rgb, LUMA), 0.02);
        vec3 body = hue * (0.6 * dot(lit, LUMA));
        float alpha = 1.0 - (1.0 - a) * (1.0 - fresnel);
        vec4 deep = etnaFitAlpha((body * (1.0 - transmittance) * (1.0 - fresnel) + reflection * fresnel + glint) / max(alpha, 0.05), alpha);
        covered = mix(covered, deep.rgb * deep.a, known);
        outAlpha = mix(outAlpha, deep.a, known);
    }
    float foam = level ? shoreFoam(p, t) * EtnaWaterSsr.x * FOAM_OPACITY : 0.0;
    if (foam > 0.003) {
        // A layer over the water, which covers what the water shows by its own share.
        covered = mix(covered, FOAM_ALBEDO * foamLight(), foam);
        outAlpha = 1.0 - (1.0 - outAlpha) * (1.0 - foam);
    }
    return etnaFitAlpha(covered / max(outAlpha, 1e-4), outAlpha);
}

// The water surface seen from below: a window to the sky within about 48.6 degrees of straight up, a
// mirror of the water itself beyond it (total internal reflection). normalDown: the swell's normal,
// turned down towards the camera. Inside the window some of what lies above the water (the sky, trees,
// clouds) shows through, unbent, and so does the sun: the glint the waves give from above, its light
// bent through the rippled surface instead of mirrored, as strong as the water reflections setting
// makes the glints from above. steady: the camera-relative point this pixel's centre shows without the
// anti-aliasing jitter; p and t: where (world xz) and when its waves are worked out, which happens only
// when there is light to glare (the ripples cost most of this). Only where the sky is over this patch of
// the surface: the water's top face, from below too, has the sky light of its own block and the one
// above it, so in a cave or an air pocket the window shows no sky.
vec4 waterFromBelow(vec3 viewDir, vec3 normalDown, vec3 steady, vec2 p, float t) {
    float cosI = clamp(dot(-viewDir, normalDown), 0.0, 1.0);
    float reflectance = fresnelDielectric(cosI, 1.0 / WATER_IOR);
    vec3 mirror = EtnaWaterColor.rgb * 0.85;
    vec3 window = EtnaSkyColor.rgb * etnaSkyLight;
    vec3 rgb = mix(window, mirror, reflectance);
    float alpha = mix(0.35, 1.0, reflectance);
    float light = ETNA_LIGHT_STRENGTH * etnaSkyLight * ETNA_WATER_REFLECTIONS;
    if (light > 0.0) {
        // Seen through the window the glint is wide in slope (a tilt turns the ray leaving the water
        // by only a third of itself), so the ripples are worked out all over the underside.
        WaterWaves waves = waterWaves(p, t, etnaPixelFootprint, vec2(0.0), 1e4);
        // Where the view leaves the water through the ripples' face, by Snell's law (0 past the
        // critical angle, where the face mirrors it back down).
        vec3 up = normalize(vec3(-waves.slope.x, 1.0, -waves.slope.y));
        vec3 leaving = refract(normalize(steady), -up, WATER_IOR);
        float cosSun = dot(leaving, ETNA_LIGHT_DIR);
        if (cosSun > 0.0) {
            // A tilt of the surface turns the ray it lets through by about (n - 1) = a third of the tilt,
            // and the ray it mirrors by twice the tilt: the slopes the pixel averages over spread the
            // glint a sixth as wide from below, while the smallest glint keeps the size it has from
            // above (twice the water's own roughness, as an angle).
            float a2 = (WATER_IOR - 1.0) * (WATER_IOR - 1.0) * (waves.rough - GLINT_ROUGHNESS * GLINT_ROUGHNESS)
                + 4.0 * GLINT_ROUGHNESS * GLINT_ROUGHNESS;
            float glare = glintCap(GLINT_LIGHT * glintGgx(cosSun, a2) * cosSun * light, GLINT_PEAK * sqrt(light));
            if (glare > 1e-3 && EtnaLook2.w > 0.5) {
                glare *= shadowAt(steady);
            }
            // Light the surface adds, like the glint from above (waterFromAbove): divided by alpha so
            // the blend, which multiplies by it, doesn't dim it.
            rgb += sunColor() * (glare * (1.0 - reflectance) / alpha);
        }
    }
    return etnaFitAlpha(rgb, alpha);
}
#endif

// ---- Hooks ----------------------------------------------------------------------------------------

// Whether the camera is out of the water, where a sheen, wet ground and glowing leaves make sense.
// Shadow-receiving draws know it from EtnaWater.java (lava and powder snow count as not in the air).
// Other draws guess it from vanilla's fog: under water it starts behind the camera (a negative start)
// and ends within 96 blocks. Rain also moves the start behind the camera, by up to 160 blocks once the
// camera sees enough sky, but its fog still reaches 768 blocks or more: the short end gives water away.
float cameraInAir() {
    #ifdef ETNA_SHADOWS
    return EtnaWater.x < 0.5 ? 1.0 : 0.0;
    #else
    return FogEnvironmentalStart < -1.0 && FogEnvironmentalEnd < 200.0 ? 0.0 : 1.0;
    #endif
}

// Runs once per fragment, after the alpha test and before any lighting: works out what the other
// water hooks share. Under water: how deep below the camera's water surface the fragment lies, how
// much of the view to it runs through water, and how open it is to the light from above.
void etnaWaterPrepare() {
    #ifdef ETNA_SHADOWS
    if (!etnaUnderwater()) {
        return;
    }
    etnaWaterPath = viewInWater(etnaViewPos);
    etnaWaterDepth = max(EtnaWater.z - etnaViewPos.y, 0.0);
    if (etnaWaterDepth <= 0.0) {
        return;
    }
    // Under water the game's sky light drops about a level per block, so vanilla's light goes dark a few
    // blocks down. Its sky level plus the levels the water above took away says how open the fragment
    // is to the sky, but only while some sky light is left, so flooded caves stay dark. Deeper than about
    // 14 blocks the sky level is 0 everywhere, open or not, and the shadow map can only add whether the
    // sun reaches the water above the fragment: one point, which any wall or kelp beside it shades.
    // There the game can't tell open sea from a flooded cave, so the camera's own water decides: how open
    // the surface above the camera is (EtnaSkyColor.w, 1 in the open sea, 0 in a flooded cave). Out at
    // sea the floor then keeps the sky's light below 15 blocks too, shade included, as it has above
    // that; seen from a flooded cave, the water outside goes by the shadow map as before.
    float sky = etnaLightLevels.y;
    float restored = sky + etnaWaterDepth / 15.0;
    float reached = max(step(0.5 / 15.0, sky), EtnaSkyColor.w);
    bool mapped = EtnaLook2.w > 0.5;
    float lightAbove = mapped ? shadowAt(etnaViewPos + vec3(0.0, 1.0, 0.0)) : 0.0;
    etnaSubmergedSky = max(smoothstep(0.75, 0.95, restored) * reached, lightAbove);
    // Without the map the sun goes by the restored sky level instead, like etnaSkyLight does on land.
    etnaSubmergedSun = mapped ? 1.0 : smoothstep(7.0 / 15.0, 13.0 / 15.0, restored) * reached;
    #endif
}

// Whether the distance haze, an effect of the air, applies to this fragment: not while the camera is in
// water, lava or powder snow. (The light shafts above water are the world pass's now, post/shafts.fsh.)
bool etnaAirEffects() {
    #ifdef ETNA_SHADOWS
    return cameraInAir() > 0.5;
    #else
    return true;
    #endif
}

// How open this fragment is to the sun, for the decision to look it up in the shadow map at all.
// Under water every submerged fragment asks the map: the sky light there says little about the sun.
float etnaSunOpen() {
    #ifdef ETNA_SHADOWS
    if (etnaWaterDepth > 0.0) {
        return 1.0;
    }
    #endif
    return etnaSkyLight;
}

// How much of the direct sun reaches this fragment, per colour channel, before the shadow map and the
// face's angle: the open-sky gate on the sun term of the lighting. Under water, the sun that makes it
// down through the water, with its caustics. shadeTerrain multiplies the whole sky-side light, sun
// included, by the sky's share (etnaSkyShare, which it asks first): on land that share carries the time
// of day, but under water it is already dimmed by the water, which underwaterSun accounts for its own
// way. So the share comes back out here and the open sky's brightness goes in instead.
vec3 etnaSunReach() {
    #ifdef ETNA_SHADOWS
    if (etnaWaterDepth > 0.0) {
        return underwaterSun(etnaWaterDepth, etnaPixelFootprint) * (etnaSubmergedSun * etnaDaylight) / max(etnaSubmergedShare, vec3(0.02));
    }
    #endif
    return vec3(etnaSkyLight);
}

// The share of vanilla's light that comes from the sky, as the lighting will use it. Under water, the
// open sky's light, tinted and dimmed by the water above (half the depth: it arrives from all round),
// where that is brighter than vanilla's: torches and sea lanterns keep their own light.
vec3 etnaSkyShare(vec3 skyPart) {
    #ifdef ETNA_SHADOWS
    if (etnaWaterDepth > 0.0) {
        vec3 water = exp(-0.5 * waterExtinction(EtnaWaterLook.x) * etnaWaterDepth);
        etnaSubmergedShare = max(skyPart, vec3(etnaDaylight) * water * etnaSubmergedSky);
        return etnaSubmergedShare;
    }
    #endif
    return skyPart;
}

// The sky's share once shadeTerrain has finished with it (next to a torch it greys it): the share the
// sun term is multiplied with, so etnaSunReach takes out exactly that one and adds no tint of its own.
void etnaFinalSkyShare(vec3 skyPart) {
    #ifdef ETNA_SHADOWS
    if (etnaWaterDepth > 0.0) {
        etnaSubmergedShare = skyPart;
    }
    #endif
}

// How open this fragment is to the light from above, for how deep the shade goes (etnaShadeWeight).
// On land, the open-sky gate. Under water, the game's sky level drops a level per block, which would
// ease the shade off from full to none between 2 and 8 blocks down, faster than the water dims the
// sun, so deeper shade came out brighter. There it is how open the water above is (etnaSubmergedSky)
// times what the water leaves of the light on the way down: the shade eases off steadily with depth,
// as the sun's part of the light fades.
float etnaShadeOpen() {
    #ifdef ETNA_SHADOWS
    if (etnaWaterDepth > 0.0) {
        return etnaSubmergedSky * exp(-waterExtinction(EtnaWaterLook.x).g * etnaWaterDepth);
    }
    #endif
    return etnaSkyLight;
}

// Animated waves, a reflection of the sky, the sun's glint and its glitter on the top and the underside of
// the water. In the classic translucent pass the surface also gets its depth and its foam (seen from
// above) and Snell's window (seen from below, with the camera under water); elsewhere alpha stays
// vanilla's: with improved transparency the alpha-only phase has measured it already.
vec4 shadeWater(vec4 color, vec3 normal) {
    vec3 viewDir = normalize(etnaViewPos);
    if (normal.y < 0.8 && normal.y > -0.8) {
        return color;
    }
    float t = GameTime * 1200.0;
    // The waves are worked out where this pixel's centre lies without the anti-aliasing jitter, so their
    // glints hold still from frame to frame (etnaUnjitter).
    vec3 steady = etnaViewPos + etnaUnjitter();
    vec2 p = etnaWorldPos.xz + (steady.xz - etnaViewPos.xz);
    vec3 toEye = -normalize(steady);
    #if defined(ETNA_SHADOWS) && defined(ETNA_TRANSLUCENT_LAYER)
    if (normal.y < -0.8 && etnaUnderwater()) {
        // Snell's window follows the swell where this frame draws the pixel, as it always has, so the
        // anti-aliasing smooths its sharp edge. Far away the waves go flat: the edge would sparkle on
        // pixels wider than a wave.
        vec2 swell = swellSlope(etnaWorldPos.xz, t);
        vec3 n = normalize(vec3(-swell.x, 1.0, -swell.y));
        n = normalize(mix(n, vec3(0.0, 1.0, 0.0), smoothstep(0.15, 0.6, etnaPixelFootprint)));
        return waterFromBelow(viewDir, -n, steady, p, t);
    }
    #endif
    float open = etnaSkyLight * etnaDaylight;
    float light = open * ETNA_LIGHT_STRENGTH * ETNA_WATER_REFLECTIONS;
    vec3 h = normalize(ETNA_LIGHT_DIR + toEye);
    // The slope that would mirror the sun into the eye here. Only top faces glint and glitter.
    vec2 target = normal.y > 0.8 && light > 0.0 ? -h.xz / max(h.y, 0.05) : vec2(1e4);
    WaterWaves waves = waterWaves(p, t, etnaPixelFootprint, target, GLINT_REACH);
    // The sky's reflection follows the swell as far as the pixel resolves it: further away its waves
    // would only shimmer.
    vec3 n = normalize(vec3(-waves.seen.x, 1.0, -waves.seen.y));
    n.y *= sign(normal.y);
    float fresnel = waterFresnel(dot(toEye, n));
    vec3 skyReflection = etnaWaterSky(reflect(-toEye, n)) * open;
    float sun = sunGlint(waves, toEye, h, light) + waterGlitter(p, t, etnaPixelFootprint, waves.seen, target, light);
    #ifdef ETNA_SHADOWS
    // No glint and no glitter in the shade of trees and cliffs.
    if (sun > 1e-3 && EtnaLook2.w > 0.5) {
        sun *= shadowAt(steady);
    }
    #endif
    vec3 glint = sunColor() * sun;
    #if defined(ETNA_SHADOWS) && defined(ETNA_TRANSLUCENT_LAYER)
    if (normal.y > 0.8 && cameraInAir() > 0.5) {
        return waterFromAbove(color.a, color.rgb, viewDir, skyReflection, fresnel * ETNA_WATER_REFLECTIONS, glint, p, t, etnaWaterLevel);
    }
    #endif
    return vec4(mix(color.rgb, skyReflection, fresnel * ETNA_WATER_REFLECTIONS) + glint, color.a);
}

// The medium between the camera and this fragment, applied after the distance haze and before vanilla's
// fog. Under water: Etna's water fog and the light shafts. The shafts darken at most the light the fog
// adds; the result is kept at or above 0 all the same.
vec3 etnaMediumFog(vec3 color) {
    #ifdef ETNA_SHADOWS
    if (etnaUnderwater()) {
        return max(underwaterFog(color, etnaWaterPath) + underwaterShafts(etnaViewPos, etnaWaterPath, etnaDither()), 0.0);
    }
    #endif
    return color;
}

// Scales vanilla's environmental fog (the fog of water, lava, blindness and the like): 1 keeps it. Under
// water Etna's own fog (etnaMediumFog) takes its place; the fog at the edge of the render distance stays.
float etnaEnvironmentalFog() {
    #ifdef ETNA_SHADOWS
    return etnaUnderwater() ? 0.0 : 1.0;
    #else
    return 1.0;
    #endif
}
