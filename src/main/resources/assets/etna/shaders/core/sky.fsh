#version 330
#extension GL_ARB_separate_shader_objects : require

// Etna's sky, per pixel: the sky model (sky.glsl), the sun's disc, and at night stars in a few colours
// that twinkle a little, the Milky Way with its dust lanes, the aurora, shooting stars and a round moon
// with its phase; after a shower, a rainbow opposite a low sun. In the End, a violet nebula full of small
// stars instead (endSky).
//
// This triangle covers the whole screen before the land is drawn, so every line here runs for every
// pixel. Each wonder is behind a test that is the same for the whole frame (night, its setting, its
// strength) and then a cheap per-pixel one (above the horizon, inside the band, near the moon), so the
// day sky costs what it did before. The noise comes from a small tiling texture (sky_noise.png, made by
// tools/gen_sky_noise.py): one read stands in for a whole octave of noise arithmetic.
//
// Nothing here changes from frame to frame except through smooth functions of time, and every bright
// point is drawn at least a pixel wide with its light spread rather than lost: no flicker, with or
// without anti-aliasing.

#include <minecraft:dynamictransforms.glsl>
#include <etna:sky.glsl>
#include <etna:sky_ubo.glsl>

layout(location = 0) in vec3 viewDirection;
layout(location = 1) flat in vec4 skySunRain;
layout(location = 2) flat in vec4 skyMoonStorm;
layout(location = 3) flat in vec3 skySunLow;
layout(location = 4) flat in vec3 skySunHigh;
layout(location = 5) flat in vec4 skyMultiGlow;
layout(location = 6) flat in vec3 skyMoonLow;
layout(location = 7) flat in vec3 skyMoonHigh;
layout(location = 8) flat in vec3 skyOvercast;
layout(location = 9) flat in vec3 skySunDisc;    // the sunlight the air in front of the sun leaves
layout(location = 10) flat in vec4 skyShootHead; // the shooting star of the moment (sky.vsh)
layout(location = 11) flat in vec4 skyShootAxis;

// Four channels of smooth tiling noise: red and green 16 cells across, blue and alpha 32.
uniform sampler2D EtnaSkyNoise;

layout(location = 0) out vec4 fragColor;

// The sun's disc: its radius in radians (about 2.6 degrees), and how bright its light is, per channel,
// before it rolls off to white: white at noon, gold low in the sky, deep orange right at the horizon.
const float SUN_RADIUS = 0.045;
const float SUN_BRIGHTNESS = 20.0;
// Stars sit in the cells of a grid on the six faces of a cube around the camera, at most one per cell:
// STAR_GRID cells along each face's edge, a star in STAR_DENSITY of them (about 2900 above the horizon).
const float STAR_GRID = 180.0;
const float STAR_DENSITY = 0.03;
// The smallest star's radius, in cells: about a pixel at 1080p and a 70 degree field of view.
const float STAR_RADIUS = 0.12;
// In the Milky Way up to this many times more stars, all of them faint ones.
const float BAND_STARS = 2.5;

// The Milky Way's frame on the star sphere, whose x axis is the axis the sky turns about (it points to
// the celestial pole under the southern horizon): the galactic pole 27 degrees from the northern
// celestial pole and the galactic centre at 29 degrees south, as on Earth. The core rises in the
// south-east in the evening, stands about 35 degrees up in the south an hour after midnight and sets
// before dawn, and the band arches high across the sky around it.
const vec3 GALAXY_POLE = vec3(-0.4540, 0.0, 0.8910);
const vec3 GALAXY_CENTRE = vec3(0.4850, -0.8389, 0.2470);
const vec3 GALAXY_SIDE = vec3(0.7475, 0.5443, 0.3809);
// The band's half-width (Gaussian sigma, sine of galactic latitude), the core's, and the dust lanes'.
const float BAND_WIDTH = 0.13;
const float CORE_WIDTH = 0.16;
const float DUST_WIDTH = 0.045;
// Noise tiles round the band (a whole number, so the texture meets itself without a seam).
const float BAND_TILES = 4.0;
// How bright the band is at its brightest, added to the night sky on screen.
const float MILKY_WAY_BRIGHTNESS = 0.16;

// The aurora: curtains hanging from AURORA_BASE to AURORA_TOP (in units of the base's height above you),
// in a belt of the northern sky from AURORA_NEAR to AURORA_FAR away (same units, to the north), fading
// in over the first unit. The belt keeps out of the high sky: there the rays climb so steeply that they
// only cross the noise's small closed loops, which would show as rings rather than curtains.
// AURORA_STEPS slices up the curtain, each step integrated exactly between its ends (see aurora()).
const float AURORA_BASE = 1.0;
const float AURORA_TOP = 2.8;
const float AURORA_NEAR = 1.0;
const float AURORA_FAR = 9.0;
const int AURORA_STEPS = 8;
// Noise tiles per unit of distance along the curtains (east-west, x) and across them (north-south, z):
// curtains run mostly east to west, as real ones do along the auroral oval.
const vec2 AURORA_SCALE = vec2(0.008, 0.04);
// Half-width of a curtain in noise units (how soft its foot and top are), and its brightness on screen.
const float AURORA_WIDTH = 0.06;
// Noise tiles round the horizon for the curtains' rays (a whole number per turn, so they meet without a
// seam), per radian: rays about two thirds of a degree wide.
const float AURORA_RAYS = 16.0 / 6.2832;
const float AURORA_BRIGHTNESS = 0.5;
// Colours by height: green at the foot (oxygen at 557.7 nm), violet in the thin air at the top.
const vec3 AURORA_GREEN = vec3(0.12, 1.0, 0.45);
const vec3 AURORA_VIOLET = vec3(0.55, 0.22, 1.0);

// The round moon's radius in radians (a little larger than the sun's disc, as the eye sees it).
const float MOON_RADIUS = 0.05;
const vec3 MOON_COLOR = vec3(0.93, 0.92, 0.88);
// The ice-crystal halo round the moon on cold clear nights: its radius (22 degrees) and width in radians.
const float HALO_RADIUS = 0.384;
const float HALO_WIDTH = 0.014;

// The End's nebula band: its half-width (sine of latitude) and noise tiles round it.
const float END_BAND_WIDTH = 0.3;
const float END_TILES = 1.0;

// Rainbows (angles from the point opposite the sun, in degrees): red, green and blue of the primary bow
// and of the fainter secondary one, whose colours run the other way, and how wide each colour's band is.
const vec3 BOW_PRIMARY = vec3(42.3, 41.5, 40.7);
const vec3 BOW_SECONDARY = vec3(50.6, 51.7, 52.8);
const float BOW_PRIMARY_WIDTH = 0.6;
const float BOW_SECONDARY_WIDTH = 1.1;
// How bright the primary bow is against the sky behind it, the secondary's share of that, how much
// Alexander's dark band between the two takes away and how much the sky inside the primary gains.
const float BOW_BRIGHTNESS = 0.3;
const float BOW_SECONDARY_SHARE = 0.38;
const float BOW_DARK_BAND = 0.035;
const float BOW_INSIDE = 0.03;

// Hash without Sine by David Hoskins (shadertoy.com/view/4djSRW, MIT licence, Copyright (c) 2014 David
// Hoskins; see CREDITS.md): three values in 0..1 from a cell.
vec3 hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

vec4 noise(vec2 uv) {
    return textureLod(EtnaSkyNoise, uv, 0.0);
}

float etnaLuma(vec3 c) {
    return dot(c, ETNA_SKY_LUMA);
}

// The galactic latitude's sine of a direction on the star sphere: 0 along the Milky Way.
float galacticLatitude(vec3 starDir) {
    return dot(starDir, GALAXY_POLE);
}

// How much of the band a latitude sine b lies in (1 in its middle).
float bandOf(float b) {
    return exp(-b * b * (0.5 / (BAND_WIDTH * BAND_WIDTH)));
}

// The star of the cell dir points into, if it has one. pixelAngle is the size of a pixel in radians: a
// star is never drawn smaller than about a pixel, so it can't flicker in and out between pixels with
// anti-aliasing off, and a larger star gets dimmer by its area so its light stays the same. grid and
// density are the cells per face edge and the share of them with a star; bandStars, how many times more
// faint stars the Milky Way holds (0: as many as anywhere), decided per cell from where its middle lies
// in the band, so a star is either wholly there or not at all.
vec3 star(vec3 dir, float pixelAngle, float seconds, float grid, float density, float bandStars) {
    vec3 a = abs(dir);
    vec2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z) {
        uv = dir.zy / a.x;
        face = dir.x > 0.0 ? 0.0 : 1.0;
    } else if (a.y >= a.z) {
        uv = dir.xz / a.y;
        face = dir.y > 0.0 ? 2.0 : 3.0;
    } else {
        uv = dir.xy / a.z;
        face = dir.z > 0.0 ? 4.0 : 5.0;
    }
    vec2 cells = (uv * 0.5 + 0.5) * grid;
    vec2 cell = floor(cells);
    vec3 h = hash33(vec3(cell, face * 131.0 + 7.0));
    float here = density;
    if (bandStars > 0.0) {
        // The cell's middle, back on the sphere (the face's axis is the larger component of dir).
        vec2 mid = (cell + 0.5) / grid * 2.0 - 1.0;
        vec3 centre = face < 2.0 ? vec3(sign(dir.x), mid.y, mid.x) : face < 4.0 ? vec3(mid.x, sign(dir.y), mid.y) : vec3(mid.x, mid.y, sign(dir.z));
        here *= 1.0 + bandStars * bandOf(galacticLatitude(normalize(centre)));
    }
    if (h.x >= here) {
        return vec3(0.0);
    }
    // Somewhere in the middle of its cell, so its glow never reaches the cell's edge.
    vec2 centre = 0.3 + 0.4 * h.yz;
    // A cube face stretches towards its edges (up to twice its middle's scale in the corners).
    float pixel = pixelAngle * 0.5 * grid * (1.0 + 0.5 * dot(uv, uv));
    // At least a pixel wide, so a star drifting across the pixel grid keeps its brightness (no flicker
    // without anti-aliasing), and never past its cell's edge (0.3 from the centre at least), which only a
    // very small window would ask for.
    float radius = min(max(pixel, STAR_RADIUS), 0.3);
    float d = length(cells - cell - centre) / radius;
    float shape = max(1.0 - d * d, 0.0);
    float area = STAR_RADIUS / radius;
    // Many faint stars and a few bright ones; the band's extra stars are all faint.
    float rank = h.x / density;
    float magnitude = rank < 1.0 ? 0.5 + 2.5 * pow(1.0 - rank, 5.0) : 0.25 + 0.2 * h.z;
    float twinkle = 0.8 + 0.2 * sin(seconds * (1.5 + 3.0 * h.y) + 6.2832 * h.z);
    // From a warm orange-white to a cool blue-white, mostly white.
    vec3 tint = mix(vec3(1.0, 0.84, 0.68), vec3(0.78, 0.87, 1.0), h.y);
    return tint * (shape * shape * area * area * magnitude * twinkle);
}

// The Milky Way on the star sphere: a soft band brightest towards its core, mottled with star clouds and
// cut lengthwise by dark dust lanes; bluish along the arms, warm in the core. nebula (0 .. 1) adds faint
// rose and teal gas clouds along it. Coordinates are galactic longitude and latitude, so the noise
// stretches along the band and never meets a seam; nothing in it is smaller than a few pixels.
vec3 milkyWay(vec3 starDir, float b, float nebula) {
    float along = atan(dot(starDir, GALAXY_SIDE), dot(starDir, GALAXY_CENTRE));
    float toCentre = dot(starDir, GALAXY_CENTRE);
    vec2 uv = vec2(along, b) * (BAND_TILES / 6.2832);
    vec4 big = noise(uv);
    vec4 fine = noise(uv * 3.0 + vec2(0.37, 0.61));
    // 0.75 so the dust tiles a whole number of times round the band (BAND_TILES * 0.75 = 3), with no seam
    // where the band's coordinate wraps.
    vec4 dustNoise = noise(vec2(uv.x * 0.75, uv.y * 3.0) + vec2(0.71, 0.13));

    float band = bandOf(b) * (0.55 + 0.45 * smoothstep(-0.6, 1.0, toCentre));
    float core = exp(-(1.0 - toCentre) * 7.0) * exp(-b * b * (0.5 / (CORE_WIDTH * CORE_WIDTH)));
    float clouds = 0.7 * big.r + 0.3 * fine.b;
    float light = band * (0.4 + 0.75 * smoothstep(0.25, 0.8, clouds)) + 1.5 * core * (0.7 + 0.45 * fine.a);
    // The dust lanes: a long, wavering dark rift down the middle of the band, denser in some places than
    // others and widest over the core.
    float offset = (dustNoise.g - 0.5) * 0.07;
    float width = DUST_WIDTH * (1.0 + 0.8 * core) * (0.7 + 0.6 * dustNoise.b);
    float rift = exp(-(b - offset) * (b - offset) * (0.5 / (width * width)));
    float lanes = rift * (0.3 + 0.7 * smoothstep(0.35, 0.68, dustNoise.r));
    light *= 1.0 - 0.72 * lanes;
    vec3 color = mix(vec3(0.62, 0.70, 1.0), vec3(1.0, 0.88, 0.74), min(core * 1.5, 1.0)) * light;
    if (nebula > 0.0) {
        float rose = smoothstep(0.5, 0.85, 0.6 * big.g + 0.4 * dustNoise.a) * band;
        float teal = smoothstep(0.6, 0.85, fine.a) * band * (1.0 - rose);
        color += (vec3(1.0, 0.35, 0.72) * rose * 0.9 + vec3(0.2, 0.85, 0.9) * teal * 0.55) * nebula * (1.0 - 0.6 * lanes);
    }
    return color * MILKY_WAY_BRIGHTNESS;
}

// Pixels of the band's latitude beyond this see none of it (and skip it).
const float BAND_REACH = 0.45;

// The shooting star of the moment (sky.vsh) in direction dir: a hot head and a trail fading behind it
// along its great circle, never thinner than about a pixel and as bright in total whatever its width.
vec3 shootingStar(vec3 dir, float pixelAngle) {
    vec3 axis = skyShootAxis.xyz;
    float off = dot(dir, axis);
    float width = max(0.8 * pixelAngle, 0.0006);
    if (abs(off) > 4.0 * width) {
        return vec3(0.0);
    }
    vec3 head = skyShootHead.xyz;
    vec3 onCircle = dir - axis * off;
    // How far behind the head along its path (radians): positive along the trail.
    float behind = -atan(dot(onCircle, cross(axis, head)), dot(onCircle, head));
    float trail = skyShootAxis.w;
    float along = behind < 0.0 ? exp(-behind * behind / (2.0 * width * width)) : max(1.0 - behind / max(trail, 1e-4), 0.0);
    along *= along;
    float across = exp(-off * off / (2.0 * width * width)) * (0.0006 / width);
    vec3 color = mix(vec3(0.65, 0.8, 1.0), vec3(1.0, 0.95, 0.85), along);
    return color * (3.0 * skyShootHead.w * along * across);
}

// The aurora in direction dir (above the horizon): thin curtains hanging straight down along the 0.5
// line of a noise field over the northern sky, broken into arcs by a second noise and warped once per
// pixel so they fold and wander. A view ray crosses the curtains' height range along a straight line on
// the ground plan; the field is read at AURORA_STEPS + 1 heights along it. Each time the field passes 0.5
// the ray goes through a curtain, and smoothstep(-w, w, field) climbs by exactly 1 over that crossing,
// whichever steps it falls between: the climb within each step is that step's share of the curtain's
// light. Every crossing counts once, smoothed over w at the curtains' foot and top, with no gaps between
// steps even close to the horizon where one step jumps far, and no per-pixel noise needing anti-aliasing.
vec3 aurora(vec3 dir, float amount, float seconds, float pixelAngle) {
    float y = dir.y;
    if (y < 0.04) {
        return vec3(0.0);
    }
    // Where the ray is at the curtains' foot, per unit of height, on the ground plan.
    vec2 plan = dir.xz / y;
    float north = -plan.y * mix(AURORA_BASE, AURORA_TOP, 0.4);
    float region = smoothstep(AURORA_NEAR, AURORA_NEAR + 1.0, north) * (1.0 - smoothstep(AURORA_FAR * 0.6, AURORA_FAR, north));
    if (region <= 0.0) {
        return vec3(0.0);
    }
    // Fades: into the haze near the horizon, and where the curtains would crowd closer than a few pixels
    // (a step of the field per pixel, radially, is about pixelAngle * height / y^2 in distance).
    float footprint = pixelAngle * AURORA_TOP / (y * y) * AURORA_SCALE.y * 16.0;
    float fade = region * smoothstep(0.04, 0.16, y) * (1.0 - smoothstep(0.06, 0.2, footprint));
    if (fade <= 0.0) {
        return vec3(0.0);
    }
    // Slow drift along the curtains, a slower fold of the warp, and bright patches that come and go: whole
    // turns of the texture every hour, so the motion repeats exactly where the clock wraps.
    float t = seconds / 3600.0;
    vec2 mid = plan * mix(AURORA_BASE, AURORA_TOP, 0.3);
    vec4 warpNoise = noise(mid * vec2(0.03, 0.08) + vec2(t * 2.0, -t));
    vec2 warp = (warpNoise.gb - 0.5) * vec2(0.05, 0.035);
    float activity = smoothstep(0.3, 0.75, warpNoise.r);
    vec2 drift = vec2(t * 2.0, 0.0);

    float azimuth = atan(dir.x, -dir.z);
    // Rays: fine streaks up the curtains, by the direction round the horizon (so they stand upright and
    // meet overhead, as real ones do), slowly changing across the sky. Read once per pixel, at the
    // curtains' middle height, rather than at each crossing.
    float rays = 0.6 + 0.6 * noise(vec2(azimuth * AURORA_RAYS, mid.y * 0.05) + drift * 0.5).a;
    vec3 light = vec3(0.0);
    float lastCrossed = 0.0;
    float lastArc = 0.0;
    float lastField = 0.0;
    for (int i = 0; i <= AURORA_STEPS; i++) {
        float s = float(i) / float(AURORA_STEPS);
        float height = mix(AURORA_BASE, AURORA_TOP, s);
        vec4 noises = noise(plan * height * AURORA_SCALE + warp + drift);
        float field = noises.r - 0.5;
        float crossed = smoothstep(-AURORA_WIDTH, AURORA_WIDTH, field);
        // The curtains run in arcs: only where the second channel is high.
        float arc = smoothstep(0.42, 0.68, noises.g);
        if (i > 0) {
            float share = abs(crossed - lastCrossed) * 0.5 * (arc + lastArc);
            if (share > 0.0) {
                // Where the ray meets the curtain within this step: its height.
                float u = clamp(lastField / (lastField - field), 0.0, 1.0);
                float sHit = (float(i) - 1.0 + u) / float(AURORA_STEPS);
                // A bright foot, fading upwards, green below and violet in the thin air at the top.
                float rise = exp(-2.0 * sHit);
                vec3 color = mix(AURORA_GREEN, AURORA_VIOLET, smoothstep(0.3, 0.95, sHit));
                light += color * (share * rise);
            }
        }
        lastCrossed = crossed;
        lastArc = arc;
        lastField = field;
    }
    return light * rays * AURORA_BRIGHTNESS * amount * fade * (0.35 + 0.65 * activity);
}

// The round moon, centred on SkyMoon.xyz: its share of the pixel (the disc's edge smoothed over a pixel)
// and its light. It is laid out as vanilla's moon quad is (one axis along the axis the sky turns about),
// lit by its phase (SkyMoonLook.y: the sun's direction seen from the moon, turned about that axis), with
// darker seas from the noise and a faint earthshine on its dark side. The moon of a resource pack shows
// when Round Moon is off.
float moonDisc(vec3 dir, float pixelAngle, out vec3 light) {
    light = vec3(0.0);
    vec3 centre = SkyMoon.xyz;
    if (dot(dir, centre) < cos(MOON_RADIUS * 1.25)) {
        return 0.0;
    }
    vec3 across = SkyStarX.xyz;
    vec3 along = cross(across, centre);
    vec2 q = vec2(dot(dir, across), dot(dir, along)) / MOON_RADIUS;
    float r = length(q);
    float edge = max(pixelAngle / MOON_RADIUS, 0.02);
    float cover = 1.0 - smoothstep(1.0 - edge, 1.0 + edge, r);
    vec3 normal = vec3(q, sqrt(max(1.0 - r * r, 0.0)));
    float phase = SkyMoonLook.y;
    vec3 toSun = vec3(0.0, -sin(phase), cos(phase));
    // The moon's surface scatters light back almost evenly up to its terminator.
    float lit = smoothstep(-0.06, 0.1, dot(normal, toSun));
    vec4 seas = noise(q * 0.09 + vec2(0.31, 0.77));
    float mare = smoothstep(0.42, 0.74, 0.7 * seas.r + 0.3 * noise(q * 0.21 + vec2(0.5, 0.1)).b);
    float albedo = mix(1.0, 0.72, mare) * (1.0 - 0.12 * r * r);
    float earthshine = 0.035 * (1.0 - SkyMoon.w);
    light = MOON_COLOR * albedo * (lit + earthshine);
    // The new moon is lost in the sky (vanilla's new moon is an empty texture too) instead of showing as
    // a dark disc over the stars.
    return cover * smoothstep(0.1, 0.3, SkyMoon.w);
}

// Light around the moon: a soft glow close to it, and on cold clear nights (SkyAurora.y) the 22 degree
// halo of ice crystals, red on its inner edge.
vec3 moonGlow(vec3 dir) {
    float c = dot(dir, SkyMoon.xyz);
    if (c < 0.85) {
        return vec3(0.0);
    }
    float angle = acos(min(c, 1.0));
    float strength = SkyMoon.w * SkyLook.y * smoothstep(-0.05, 0.1, SkyMoon.y);
    vec3 glow = vec3(0.55, 0.65, 0.85) * (0.05 * exp(-max(angle - MOON_RADIUS, 0.0) / 0.04));
    float cold = SkyAurora.y;
    if (cold > 0.0) {
        // Squared by hand: pow() of a negative base is undefined (NaN on some drivers).
        float halo = (angle - HALO_RADIUS) / HALO_WIDTH;
        float ring = exp(-halo * halo);
        vec3 tint = mix(vec3(1.0, 0.72, 0.55), vec3(0.85, 0.92, 1.0), smoothstep(HALO_RADIUS - HALO_WIDTH, HALO_RADIUS + HALO_WIDTH, angle));
        glow += tint * (0.045 * ring * cold);
    }
    return glow * strength;
}

// A rainbow opposite the sun, in direction dir: the primary bow (red outside), the fainter secondary
// (red inside), the darker sky between them and the brighter sky inside the primary, scaled by the sky's
// own brightness so it stays a soft veil of colour, gone at dusk. Each colour is a soft band, as the sun's
// disc and the spread of drop sizes blur a real rainbow's colours into each other.
vec3 rainbow(vec3 dir, vec3 sky, float amount) {
    float c = dot(dir, -SkySun.xyz);
    // Only between about 26 and 58 degrees from the point opposite the sun: the brighter sky inside the
    // primary fades in from 26 degrees, so nothing ends in a ring.
    if (c > 0.9 || c < 0.53) {
        return sky;
    }
    float angle = degrees(acos(c));
    // Each band squared by hand: pow() of a negative base is undefined (NaN on some drivers).
    vec3 p = (vec3(angle) - BOW_PRIMARY) / BOW_PRIMARY_WIDTH;
    vec3 primary = exp(-p * p);
    // Violet: the blue band's inner edge with a touch of red.
    float v = (angle - 40.1) / BOW_PRIMARY_WIDTH;
    primary.r += 0.25 * exp(-v * v);
    vec3 q = (vec3(angle) - BOW_SECONDARY) / BOW_SECONDARY_WIDTH;
    vec3 secondary = exp(-q * q);
    float between = smoothstep(42.0, 44.0, angle) * (1.0 - smoothstep(49.0, 51.0, angle));
    float inside = smoothstep(26.0, 33.0, angle) * (1.0 - smoothstep(36.0, 41.0, angle));
    float horizon = smoothstep(-0.01, 0.05, dir.y);
    float brightness = etnaLuma(sky);
    vec3 bow = (primary + BOW_SECONDARY_SHARE * secondary) * (BOW_BRIGHTNESS * brightness);
    vec3 color = sky * (1.0 - BOW_DARK_BAND * between * amount * horizon) * (1.0 + BOW_INSIDE * inside * amount * horizon);
    return color + bow * (1.0 - 0.6 * color) * amount * horizon;
}

// The End: a near-black violet sky crossed by a broad, slowly drifting band of nebula (on the same kind of
// longitude and latitude as the Milky Way, about a tilted axis, fading out long before the poles where
// that mapping would pinch), dense small stars and the End's flash.
vec3 endSky(vec3 dir, float pixelAngle) {
    vec3 color = mix(vec3(0.050, 0.032, 0.068), vec3(0.022, 0.012, 0.040), smoothstep(-0.1, 0.8, dir.y));
    vec3 s = vec3(dir.x, 0.8 * dir.y + 0.6 * dir.z, 0.8 * dir.z - 0.6 * dir.y);
    float b = s.y;
    float band = exp(-b * b * (0.5 / (END_BAND_WIDTH * END_BAND_WIDTH)));
    float body = 0.0;
    if (abs(b) < 0.8) {
        float t = SkyLook.z / 3600.0;
        vec2 uv = vec2(atan(s.z, s.x), b) * (END_TILES / 6.2832) + vec2(t, 0.0);
        vec4 big = noise(uv);
        vec4 fine = noise(uv * 3.0 + vec2(0.3, 0.8) - vec2(t, 0.0));
        float clouds = 0.65 * big.r + 0.35 * fine.r;
        body = smoothstep(0.38, 0.8, clouds) * band;
        vec3 gas = mix(vec3(0.45, 0.16, 0.70), vec3(0.85, 0.25, 0.60), smoothstep(0.3, 0.7, big.g));
        gas += vec3(0.10, 0.45, 0.55) * smoothstep(0.55, 0.8, big.b) * 0.7;
        float lanes = smoothstep(0.5, 0.72, fine.g);
        color += gas * (body * (1.0 - 0.55 * lanes) + 0.2 * band) * 0.22;
    }
    // Small dense stars that hardly twinkle, fewer where the gas is thick.
    vec3 stars = star(dir, pixelAngle, SkyLook.z * 0.3, 240.0, 0.045, 0.0);
    color += stars * (0.75 - 0.35 * body) * (1.0 - color);
    // The flash: a bright violet-white glow with a wide halo.
    if (SkyEnd.w > 0.0) {
        float angle = acos(clamp(dot(dir, SkyEnd.xyz), -1.0, 1.0));
        vec3 flash = vec3(0.85, 0.7, 1.0) * exp(-angle / 0.03) + vec3(0.55, 0.3, 0.85) * (0.35 * exp(-angle / 0.25));
        color += flash * SkyEnd.w * (1.0 - color);
    }
    return color;
}

void main() {
    vec3 dir = normalize(transpose(mat3(ModelViewMat)) * viewDirection);
    // Size of a pixel in radians, taken while every pixel of the quad is still running.
    float pixelAngle = length(fwidth(dir));
    // Up to half an 8-bit step of noise either way, against banding in the sky's smooth gradients.
    float dither = (fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5) / 255.0;

    if (SkyTint.w > 0.5) {
        fragColor = vec4(endSky(dir, pixelAngle) + dither, 1.0);
        return;
    }

    EtnaSkyFrame f;
    f.sun = skySunRain.xyz;
    f.moon = skyMoonStorm.xyz;
    f.sunLow = skySunLow;
    f.sunHigh = skySunHigh;
    f.multi = skyMultiGlow.rgb;
    f.moonLow = skyMoonLow;
    f.moonHigh = skyMoonHigh;
    f.overcast = skyOvercast;
    f.rain = skySunRain.w;
    f.storm = skyMoonStorm.w;
    f.glow = skyMultiGlow.w;
    vec3 light = etnaSkyLinear(f, dir);

    vec3 sky = etnaSkyGround(etnaSkyDisplay(light), dir.y);
    vec3 color = sky;

    // The sun's disc, a little darker towards its rim, sharp to a pixel and cut by the horizon, added over
    // the sky like light: its colour is the sunlight the air in front of it leaves.
    float angle = sqrt(max(2.0 - 2.0 * dot(dir, f.sun), 0.0));
    float r = angle / SUN_RADIUS;
    float edge = max(pixelAngle / SUN_RADIUS, 0.02);
    float disc = (1.0 - smoothstep(1.0 - edge, 1.0 + edge, r)) * smoothstep(-0.003, 0.003, dir.y) * SkyLook.y;
    if (disc > 0.0) {
        float limb = 1.0 - 0.35 * (1.0 - sqrt(max(1.0 - r * r, 0.0)));
        vec3 sun = 1.0 - exp(-skySunDisc * (SUN_BRIGHTNESS * limb));
        color += sun * (1.0 - color) * disc;
    }

    // The night sky, added to the dark sky like light: gone where the sky is bright, and fading into the
    // haze towards the horizon. All of it turns with the stars.
    if (SkyLook.x > 0.0 && dir.y > -0.02) {
        float fade = SkyLook.x * smoothstep(0.0, 0.25, dir.y);
        vec3 starDir = transpose(mat3(SkyStarX.xyz, SkyStarY.xyz, SkyStarZ.xyz)) * dir;
        float b = galacticLatitude(starDir);
        // A bright moon up in the sky washes out the faint Milky Way, and a little the faintest stars.
        float moonlit = SkyMoon.w * smoothstep(-0.05, 0.2, SkyMoon.y);
        vec3 night = vec3(0.0);
        if (SkyNight.y > 0.0 && abs(b) < BAND_REACH) {
            night += milkyWay(starDir, b, SkyNight.z) * (SkyNight.y * (1.0 - 0.55 * moonlit));
        }
        if (SkyNight.x > 0.0) {
            night += star(starDir, pixelAngle, SkyLook.z, STAR_GRID, STAR_DENSITY, BAND_STARS * SkyNight.y) * (1.0 - 0.2 * moonlit);
        }
        if (skyShootHead.w > 0.0) {
            night += shootingStar(dir, pixelAngle);
        }
        color += night * fade * (1.0 - color);
    }

    // The moon covers the stars behind it (the sky's own glow stays in front), and its light is added
    // over that like the sun's.
    if (SkyMoonLook.x > 0.5) {
        vec3 moonLight;
        float cover = moonDisc(dir, pixelAngle, moonLight) * SkyLook.y * smoothstep(-0.003, 0.003, dir.y);
        if (cover > 0.0) {
            color = mix(color, sky + moonLight * (1.0 - sky), cover);
        }
        color += moonGlow(dir) * (1.0 - color);
    }

    if (SkyAurora.x > 0.0) {
        color += aurora(dir, SkyAurora.x, SkyLook.z, pixelAngle) * (1.0 - color);
    }

    if (SkyRainbow.x > 0.0) {
        color = rainbow(dir, color, SkyRainbow.x);
    }

    fragColor = vec4(color + dither, 1.0);
}
