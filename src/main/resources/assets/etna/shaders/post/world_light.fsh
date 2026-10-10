#version 330
#extension GL_ARB_separate_shader_objects : require

// Etna world pass 1, before anti-aliasing: the light of the world itself, in three steps. All run in
// this one pass, so the picture is read and written once instead of three times.
//
// 1. Ambient occlusion. Corners, cracks and the ground under things get darker where part of the open
// air above them is blocked. Technique after Photon's SSAO (by sixthsurge, see CREDITS.md): sample
// points on a disc in the surface's own plane, each looked up in the depth buffer, and the surface found
// there darkens this pixel by how far it rises above the plane. Surfaces more than twice the radius away
// are ignored, so a tree in front of a far hill leaves no dark outline on the hill. The noise changes
// every frame; the anti-aliasing pass right after averages it into a smooth result.
//
// 2. Sky glow and atmosphere. Vanilla's sky is one flat colour that fades to the fog colour at the
// horizon, and land far away only fades into that fog at the very edge of the render distance. Here:
// * the sky (where nothing was drawn) gets a deeper blue towards the zenith, a brighter horizon, a
//   soft glow around the sun and, at sunrise and sunset, a warm band along the horizon towards it;
// * land far away gets a faint blue from the air in front of it and, towards a low sun, a warm haze,
//   and from a few chunks out it takes on the sky's own glow for its direction, so that land and
//   clouds vanilla fades into the sky at the edge of the render distance stay hidden in it.
// Everything is worked out from the view direction and the sun's, so it never changes from frame
// to frame on its own, and everything is blended gently into vanilla's picture: the sun, the moon,
// the stars and the clouds keep their own colours, and with both settings at 0 the picture passes
// through untouched. While Etna draws the sky itself (sky.glsl, EtnaSky.java) the sky already has its
// gradient, its glow round the sun and its sunset band: the sky and the land's hand-over to it are left
// alone then, and the air's blue takes the colour of that sky's light (Shade.yzw).
// All of it fades out at night, in rain and in dimensions without a sun, and this
// step stands down with the camera in water, lava or powder snow: the game says what the camera is in
// (Water.x), so the picture is never guessed at from the colour of the fog.
//
// 3. Mist and light shafts (air.glsl). The land and the sky fade into the mist in front of them: ground
// mist in the valleys in the morning and the evening, a haze at night and in rain, a faint murk over
// dark rock underground. Then the sun adds the light the mist and the air scatter towards the eye,
// golden with the sun low, strongest looking towards it and, where the shadow map reaches, only from
// the air the sun actually lights: the shafts pass before this one (shafts.fsh) has worked out that lit
// share at half resolution. This step does not depend on the time of day or the weather itself:
// EtnaAir.java sets how dense each layer is, so at noon in clear weather it adds next to nothing. It
// also stands down in water, lava and powder snow. In the Nether and the End the air is their own instead
// (air.glsl's etnaSunlessAir): a haze that glows in the colour of their fog, and a lava mist over the lava sea.

#include <etna:settings.glsl>
#include <etna:air.glsl>
#include <etna:ink.glsl>

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
uniform sampler2D ShaftsSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 DepthSize;
    vec2 ShaftsSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

#include <etna:water_reflection.glsl>

// ---------------------------------------------------------------------------------------------------
// Ambient occlusion

const int AO_SAMPLES = 8;
const float AO_RADIUS = 1.0; // blocks
const float TAU = 6.2831853;
const float SQRT_HALF = 0.70710678;

float depthAt(ivec2 p) {
    return texelFetch(DepthSampler, clamp(p, ivec2(0), ivec2(DepthSize) - 1), 0).r;
}

// Positions come from InvViewProj * (uv, depth, 1) divided by its w (etnaPosition). That product is
// linear in uv and depth, so it is kept undivided (h) and a neighbouring pixel's is this pixel's plus
// one column per pixel of uv and one per unit of depth: a few additions instead of a full matrix product.
vec3 divided(vec4 h) {
    return h.xyz / h.w;
}

// Surface normal from the depth buffer. Of the two neighbours on each axis the closer one is taken,
// since it lies on the same face: at the edge of a block the normal stays the face's own instead of
// pointing half way between two faces.
vec3 normalAt(ivec2 p, vec3 center, vec4 h, float depth) {
    vec4 stepX = InvViewProj[0] / DepthSize.x;
    vec4 stepY = InvViewProj[1] / DepthSize.y;
    vec4 stepDepth = InvViewProj[2];
    vec3 l = divided(h - stepX + stepDepth * (depthAt(p - ivec2(1, 0)) - depth));
    vec3 r = divided(h + stepX + stepDepth * (depthAt(p + ivec2(1, 0)) - depth));
    vec3 d = divided(h - stepY + stepDepth * (depthAt(p - ivec2(0, 1)) - depth));
    vec3 u = divided(h + stepY + stepDepth * (depthAt(p + ivec2(0, 1)) - depth));
    vec3 dx = distance(r, center) < distance(l, center) ? r - center : center - l;
    vec3 dy = distance(u, center) < distance(d, center) ? u - center : center - d;
    vec3 n = normalize(cross(dx, dy));
    // A visible surface faces the camera, which sits at the origin.
    return dot(n, center) > 0.0 ? -n : n;
}

float ambientOcclusion(ivec2 pixel, vec3 position, vec4 h, float depth) {
    vec3 normal = normalAt(pixel, position, h, depth);
    vec3 t = normalize(cross(normal, abs(normal.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3 b = cross(normal, t);
    float angleNoise = etnaNoise(gl_FragCoord.xy);
    float radiusNoise = etnaNoise(gl_FragCoord.xy + vec2(47.0, 17.0));
    // Lift the disc off the surface a little, so the surface itself never counts as an occluder.
    vec3 origin = position + normal * (0.02 + 0.002 * length(position));

    // Sample i lies at the angle (i + angleNoise) / 8 of a turn. Turning by an eighth, a quarter or a
    // half of a turn needs no sine: sample 1 is sample 0 turned by an eighth, samples 2 and 3 are 0 and
    // 1 turned by a quarter, and samples 4 to 7 are 0 to 3 turned by a half, which is the same direction
    // with the radius negated. One sine and one cosine instead of eight of each.
    vec2 turn0 = vec2(cos(angleNoise * (TAU / float(AO_SAMPLES))), sin(angleNoise * (TAU / float(AO_SAMPLES))));
    vec2 turn1 = vec2(turn0.x - turn0.y, turn0.x + turn0.y) * SQRT_HALF;
    vec3 dirs[4] = vec3[4](t * turn0.x + b * turn0.y, t * turn1.x + b * turn1.y,
                           b * turn0.x - t * turn0.y, b * turn1.x - t * turn1.y);

    // The projection is linear too: a sample point's clip position is the disc centre's plus the
    // direction's times the radius. Only x, y and w are used.
    vec4 clipOrigin = ViewProj * vec4(origin, 1.0);
    vec4 clipDirs[4] = vec4[4](ViewProj * vec4(dirs[0], 0.0), ViewProj * vec4(dirs[1], 0.0),
                               ViewProj * vec4(dirs[2], 0.0), ViewProj * vec4(dirs[3], 0.0));

    // The surface the depth buffer holds where a sample point lands lies on the line from the eye
    // through that point, and it is the point itself moved along that line by the ratio of the two
    // depths: for any projection, InvViewProj * (uv, depth, 1) has a w of 1 / clip w at the point's own
    // depth, and with the eye at InvViewProj's depth column divided by its w, the surface is
    // eye + (point - eye) / (clip w * w at the depth found). This replaces a matrix product and a
    // divide per sample with a dot product and a multiply. The eye is the camera, moved by view bobbing.
    vec3 eye = InvViewProj[2].xyz / InvViewProj[2].w;
    vec4 rowW = vec4(InvViewProj[0].w, InvViewProj[1].w, InvViewProj[2].w, InvViewProj[3].w);
    vec3 originFromEye = origin - eye;
    vec3 eyeFromPosition = eye - position;

    float occlusion = 0.0;
    for (int i = 0; i < AO_SAMPLES; i++) {
        float r = AO_RADIUS * sqrt(fract(radiusNoise + float(i) * 0.618034) * 0.9 + 0.1);
        r = i < 4 ? r : -r;
        int k = i % 4;
        vec4 clip = clipOrigin + clipDirs[k] * r;
        vec2 screen = clip.xy / clip.w;
        if (any(lessThan(screen, vec2(0.0))) || any(greaterThan(screen, vec2(1.0)))) {
            continue;
        }
        float found = depthAt(ivec2(screen * DepthSize));
        if (etnaIsSky(found)) {
            continue;
        }
        vec3 offset = (originFromEye + dirs[k] * r) / (clip.w * dot(rowW, vec4(screen, found, 1.0))) + eyeFromPosition;
        float len = length(offset);
        float rise = clamp(dot(offset, normal) / max(len, 1e-4) - 0.05, 0.0, 1.0);
        float range = 1.0 - smoothstep(AO_RADIUS, 2.0 * AO_RADIUS, len);
        occlusion += rise * range;
    }
    float ao = 1.0 - occlusion / float(AO_SAMPLES);
    return ao * ao;
}

// ---------------------------------------------------------------------------------------------------
// Sky glow and atmosphere

// Land closer than this gets no atmosphere at all.
const float AIR_START = 16.0; // blocks
// Distance over which the air's blue builds up, and the warm haze towards the sun.
const float AIR_BLUE_DISTANCE = 420.0;
const float AIR_WARM_DISTANCE = 300.0;
// The air thins upwards with this scale height: looking down into a valley crosses denser air than
// looking up at a peak or at clouds.
const float AIR_SCALE_HEIGHT = 150.0;
// Land and clouds take on the sky's glow for their direction: none of it up to the first distance,
// all of it from the second, measured like vanilla's render distance fog (the larger of the
// horizontal and the vertical distance). Vanilla fades the last blocks of the render distance, chunks
// that are still loading in and far clouds into the colour of the sky behind them; to stay hidden
// they must get exactly what the sky next to them gets. This pass cannot know the render distance,
// so the hand-over is complete at 10 chunks: from there up the edge of the world disappears as it
// does in vanilla, and at 8 chunks only a faint trace of it is left.
const float HORIZON_START = 64.0; // blocks
const float HORIZON_END = 160.0;

// Adds light the way a glow over a picture does: from 0..1 inputs the result stays in 0..1, and a
// colour already near white (the sun, a cloud in full light) barely changes.
vec3 screenBlend(vec3 base, vec3 added) {
    return base + clamp(added, 0.0, 1.0) * (1.0 - base);
}

// Henyey-Greenstein phase function (Henyey and Greenstein, 1941) divided by its own value straight
// towards the sun: 1 looking at the sun, falling off with the angle. g close to 1 gives a tight glow.
// The base 1 + g^2 - 2g cos is at least (1 - g)^2, so for g < 1 nothing here can reach 0.
float forwardLobe(float cosTheta, float g) {
    float x = 1.0 + g * g - 2.0 * g * clamp(cosTheta, -1.0, 1.0);
    float r = (1.0 - g) * (1.0 - g) / x;
    return r * sqrt(r);
}

// Sky values shared by both effects, from the sun's height: how much daylight blue there is, how
// much of the sun's glow (it lasts a little past sunset, into twilight), how low the sun is, and
// how clear the weather is. All 0 in dimensions without a sun.
struct SkyState {
    float day;
    float glow;
    float low;
    float clear;
};

SkyState skyState() {
    SkyState l;
    float sunWorld = Air.w > 0.5 ? 1.0 : 0.0;
    float clear = clamp(1.0 - Air.z, 0.0, 1.0);
    l.clear = clear * clear * sunWorld;
    l.day = smoothstep(0.02, 0.30, Sun.y);
    l.glow = smoothstep(-0.12, 0.04, Sun.y);
    l.low = 1.0 - smoothstep(0.05, 0.45, Sun.y);
    return l;
}

// The colour of the glow around the sun, already scaled by how strong it is in this direction:
// a tight bright core and a wide soft halo, pale gold at noon and deep orange with the sun low.
vec3 sunHalo(float cosSun, SkyState l) {
    float halo = 0.5 * forwardLobe(cosSun, 0.85) + 0.5 * forwardLobe(cosSun, 0.6);
    vec3 tint = mix(vec3(1.0, 0.94, 0.84), vec3(1.0, 0.60, 0.30), l.low);
    return tint * halo * l.glow * (0.18 + 0.30 * l.low);
}

// Light the air adds can only show where the sky lights the scene: none on black (blindness,
// darkness) or on the rock of an unlit cave, all of it from a dim grey up, which daylit land reaches
// even in a dark forest once a few pixels are averaged (see surroundings).
float daylit(vec3 c) {
    return smoothstep(0.03, 0.12, dot(c, ETNA_LUMA));
}

// How much a pixel where nothing was drawn looks like the open sky. The camera is in air here (this
// step does not run in water, lava or powder snow), but blindness and darkness turn the sky black, and
// a data pack can give a biome's sky any colour, so only a colour that could be a daylit sky takes the
// glow. The open sky, even the orange band of a sunset, always keeps its weakest channel above a fifth
// of its strongest, and is never black while the sun is up.
float skyLikeness(vec3 c) {
    float peak = max(c.r, max(c.g, c.b));
    float weakest = min(c.r, min(c.g, c.b));
    float spread = weakest / max(peak, 1e-3);
    return daylit(c) * smoothstep(0.12, 0.18, spread);
}

// The sky's glow in the direction dir, on the colour c, at the given strength (0 leaves c as it is).
// The sky takes it in full; land and clouds take more of it the farther away they are (landAir).
vec3 skyGlow(vec3 c, vec3 dir, SkyState l, float strength) {
    if (strength < 0.002) {
        return c;
    }
    float up = dir.y;
    float cosSun = dot(dir, Sun.xyz);

    // Deeper, purer blue towards the zenith. Only clearly blue pixels take it: white and grey ones
    // (clouds, even their cool grey-blue undersides, the sun, the moon by day) are left as they are.
    float blue = smoothstep(0.20, 0.40, (c.b - c.r) / max(c.b, 1e-3));
    float zenith = smoothstep(0.05, 0.85, up);
    c *= mix(vec3(1.0), vec3(0.62, 0.80, 1.0), 0.75 * strength * l.day * blue * zenith);

    // A brighter, paler horizon, fading out a little over 20 degrees up. It reaches all the way
    // down to the horizon and below it: the land vanilla fades into the sky there gets the same
    // light, so where the two meet there is no seam, and far land seen from above (looking down into
    // a valley) takes it as a haze rather than being cut off at the horizon line.
    float rim = 1.0 - smoothstep(0.06, 0.40, up);
    c = screenBlend(c, vec3(0.86, 0.92, 1.0) * (0.24 * strength * l.day * rim));

    // Sunrise and sunset: a warm band along the horizon, orange towards the sun and fading to a
    // faint pink all around, from a little before the sun touches the horizon until twilight ends.
    // The horizontal direction towards the sun, without normalising vectors that can be zero
    // (looking straight up, or the sun at the zenith).
    float flatLength = dot(dir.xz, dir.xz) * dot(Sun.xz, Sun.xz);
    float towards = flatLength > 1e-6 ? clamp(dot(dir.xz, Sun.xz) * inversesqrt(flatLength), -1.0, 1.0) : 0.0;
    float side = 0.5 + 0.5 * towards;
    float sunset = smoothstep(-0.14, -0.02, Sun.y) * (1.0 - smoothstep(0.08, 0.40, Sun.y));
    float band = 1.0 - smoothstep(0.05, 0.34, up);
    vec3 bandColor = mix(vec3(1.0, 0.66, 0.60), vec3(1.0, 0.52, 0.22), side);
    c = screenBlend(c, bandColor * (0.5 * strength * sunset * band * (0.15 + 0.85 * side)));

    // The glow around the sun: forward scattering, stronger and more orange with the sun low.
    c = screenBlend(c, sunHalo(cosSun, l) * strength);
    return c;
}

// The colour around a pixel: the pixel and four neighbours three pixels away diagonally, averaged.
// The tests that tell daylit land from caves and from fog look at this rather than at one texel, so
// the detail of a texture cannot switch the haze on and off texel by texel: bright and dark leaves of
// a far forest get the same haze, and the anti-aliasing jitter, which brings a different texel under
// the pixel every frame, cannot turn that difference into shimmer. The neighbours are read from the
// picture as the level left it, before ambient occlusion: only pixels 16 to 96 blocks away have both
// air and occlusion, and there occlusion moves these dim-light tests by too little to show.
vec3 surroundings(vec3 c, ivec2 pixel) {
    ivec2 last = ivec2(InSize) - 1;
    vec3 sum = c;
    sum += clamp(texelFetch(InSampler, clamp(pixel + ivec2(3, 3), ivec2(0), last), 0).rgb, 0.0, 1.0);
    sum += clamp(texelFetch(InSampler, clamp(pixel + ivec2(-3, 3), ivec2(0), last), 0).rgb, 0.0, 1.0);
    sum += clamp(texelFetch(InSampler, clamp(pixel + ivec2(3, -3), ivec2(0), last), 0).rgb, 0.0, 1.0);
    sum += clamp(texelFetch(InSampler, clamp(pixel + ivec2(-3, -3), ivec2(0), last), 0).rgb, 0.0, 1.0);
    return sum * 0.2;
}

// Whether Etna drew the sky this frame: Shade.yzw holds the colour of its skylight then, 0 otherwise.
bool etnaSkyDrawn() {
    return Shade.y + Shade.z + Shade.w > 0.0;
}

// How far vanilla's render-distance fog has faded the land at this camera-relative position into the
// sky behind it (0 .. 1): vanilla's own band, from a tenth of the render distance (4 to 64 blocks) short
// of its end, by the larger of the horizontal and the vertical distance. Land faded all the way is the
// sky's colour already, so the air treats it as it treats the sky beside it. 0 without the mist's data.
float renderFog(vec3 position) {
    float end = MistColor.w;
    if (end <= 0.0) {
        return 0.0;
    }
    float span = clamp(end / 10.0, 4.0, 64.0);
    return clamp((max(length(position.xz), abs(position.y)) - (end - span)) / span, 0.0, 1.0);
}

vec3 landAir(vec3 c, vec3 position, ivec2 pixel, SkyState l) {
    // Etna's sky needs no hand-over: vanilla's fog fades the land into it already.
    float glowStrength = etnaSkyDrawn() ? 0.0 : Air.x * l.clear;
    float airStrength = Air.y * l.clear;
    if (max(glowStrength, airStrength) < 0.002) {
        return c;
    }
    float dist = length(position);
    if (dist <= AIR_START) {
        return c;
    }
    vec3 dir = position / dist;

    // Light the air adds can only show over land the sky lights: in caves (dark rock, even far
    // away) and with blindness or darkness nothing is added. The camera is in air (main), so there is
    // no medium's fog here to tell apart from land.
    vec3 around = surroundings(c, pixel);
    float lit = daylit(around);

    // The sky's own glow, handed over with distance (see HORIZON_START). Land at the edge of the
    // render distance, faded by vanilla into the colour of the sky, gets exactly what the sky beside
    // it gets, and so do far clouds, which vanilla fades out while they still hide the sky.
    float horizon = smoothstep(HORIZON_START, HORIZON_END, max(length(position.xz), abs(position.y)));
    c = skyGlow(c, dir, l, glowStrength * lit * horizon);

    // Air between the camera and this point: none for the first blocks, then thinner the higher the
    // point is above the camera. Average density along the ray in air that thins exponentially with
    // height (as in Inigo Quilez's "Better Fog"), kept within bounds and with its limit near level.
    float rise = clamp(position.y / AIR_SCALE_HEIGHT, -1.5, 4.0);
    float density = abs(rise) < 1e-3 ? 1.0 - 0.5 * rise : (1.0 - exp(-rise)) / rise;
    float air = max(dist - AIR_START, 0.0) * density;
    float blueAir = 1.0 - exp(-air / AIR_BLUE_DISTANCE);
    float warmAir = 1.0 - exp(-air / AIR_WARM_DISTANCE);

    // Blue aerial perspective: the air takes a little red out of what is behind it and adds pale
    // blue skylight in front of it. Etna's sky has its own aerial perspective, and vanilla's fog fades
    // the farthest land into it: that land gets none, as the sky beside it gets none.
    float blue = airStrength * l.day * blueAir * (etnaSkyDrawn() ? 1.0 - renderFog(position) : 1.0);
    c *= mix(vec3(1.0), vec3(0.84, 0.92, 1.0), 0.5 * blue);
    // The pale blue of the sky's light, or Etna's sky model's own (brought to the same brightness).
    vec3 airColor = etnaSkyDrawn() ? Shade.yzw * 0.74 : vec3(0.62, 0.76, 1.0);
    c = screenBlend(c, airColor * (0.42 * blue * lit));

    // Warm haze towards a low sun: the same glow the sky has around the sun, in the air in front of
    // the nearer land. Farther out the sky's glow above has taken over, and this gives way to it so
    // the glow is not counted twice.
    c = screenBlend(c, sunHalo(dot(dir, Sun.xyz), l) * (0.75 * airStrength * warmAir * lit * (1.0 - horizon)));
    return c;
}

// ---------------------------------------------------------------------------------------------------
// Mist and light shafts

// How strongly the scattered sunlight shows: the light added is 1 - exp(-AIR_SUN_GAIN * phase * scattered),
// which grows with the air in front like the in-scattering itself while the air is thin, and levels off
// towards the sun's own colour instead of burning out to white where a lot of lit mist lies in front of
// a low sun.
const float AIR_SUN_GAIN = 0.32;

// The lit share of the air in front of this pixel, from the half-resolution shafts pass. Each of its
// texels holds the share along one pixel's ray and how far that ray ran, so only texels whose ray ended
// at about this pixel's own distance are blended (joint bilateral upsampling, after Johannes Kopf,
// Michael Cohen, Dani Lischinski and Matt Uyttendaele, 2007): a shaft never bleeds from a far hill onto
// the tree in front of it. With anti-aliasing the four nearest texels, weighted as a bilinear filter
// would weigh them, are enough: their noise changes every frame and the anti-aliasing averages it.
// Without it the noise stays put, and the sixteen nearest under a tent twice as wide blur it instead.
// Where none of them lies at this pixel's distance (a thin branch against the sky), the one nearest in
// distance is taken.
float litShare(ivec2 pixel, float dist) {
    int radius = Frame.w > 0.5 ? 1 : 2;
    float spread = float(radius);
    // shafts.fsh's texel c holds pixel 2c + sub: with anti-aliasing sub cycles over the 2x2 block, so on
    // average the block's centre; without, its first pixel.
    vec2 p = vec2(pixel) * 0.5 - (Frame.w > 0.5 ? 0.25 : 0.0);
    vec2 corner = floor(p);
    vec2 f = p - corner;
    ivec2 base = ivec2(corner);
    ivec2 last = ivec2(ShaftsSize) - 1;
    float sum = 0.0;
    float weights = 0.0;
    float nearest = 1.0;
    float nearestGap = 1e9;
    for (int y = 1 - radius; y <= radius; y++) {
        float wy = max(1.0 - abs(float(y) - f.y) / spread, 0.0);
        for (int x = 1 - radius; x <= radius; x++) {
            float wx = max(1.0 - abs(float(x) - f.x) / spread, 0.0);
            vec2 texel = texelFetch(ShaftsSampler, clamp(base + ivec2(x, y), ivec2(0), last), 0).rg;
            float gap = abs(texel.y - dist) / max(dist, 1.0);
            float w = wx * wy * exp(-8.0 * gap);
            sum += w * texel.x;
            weights += w;
            if (gap < nearestGap) {
                nearestGap = gap;
                nearest = texel.x;
            }
        }
    }
    // The tent's weights add up to radius squared.
    return weights > 0.01 * spread * spread ? clamp(sum / weights, 0.0, 1.0) : nearest;
}

// True while Etna paints the sky itself from its sky model, which already holds the air's haze and glow
// in every direction.
bool airSkyPainted() {
    return etnaSkyDrawn();
}

// The picture behind the air (c) seen through it, at the camera-relative position of what was drawn here
// or, for the sky, in the direction of this uv. Single scattering: c fades into the mist's own colour by
// the mist in front of it, then the sun adds what the air scatters towards the eye, where it reaches
// the air. That scattered light is the sum, along the ray, of the air's density times how much of it
// reaches the eye: inside the shadow map's reach the clear shaft air and the mist, times the lit share
// the shafts pass found there; beyond it the mist alone, all of it lit. Without the map, the mist is
// lit as far as the sky above the camera is open. A sky Etna paints itself (airSkyPainted) gets the mist
// in front of it like land at the end of the render distance, but of the clear shaft air only the part
// the sky above the camera adds by being covered (trees, a cliff): in the open that air's glow is already
// in the painted sky, and adding it again would double the glow round the sun. The farthest land, which
// vanilla's fog has faded into that sky, slides into the sky's share with the same fog, so the edge of
// the render distance stays hidden.
vec3 airLight(vec3 c, ivec2 pixel, bool sky, vec2 uv, vec3 position) {
    if (Mist.x + Mist.y + Mist.z + Shafts.x <= 0.0) {
        return c;
    }
    vec3 dir;
    float dist;
    if (sky) {
        // As for the sky glow: only the direction matters. The sky counts as lying at the end of the
        // render distance, where vanilla's own fog ends.
        vec4 farPoint = InvViewProj * vec4(uv, 1e-6, 1.0);
        vec3 ray = farPoint.w < 0.0 ? -farPoint.xyz : farPoint.xyz;
        dir = ray / max(length(ray), 1e-6);
        dist = MistColor.w;
    } else {
        dist = length(position);
        dir = position / max(dist, 1e-4);
    }

    // The mist in front. Underground the murk lies only over dark rock: lit rooms, torches and lava keep
    // their colour, and the test looks at the pixel's surroundings so texture detail can't switch it.
    float mistDepth = etnaMistDepth(dir.y, dist);
    float murk = 0.0;
    if (Mist.z > 0.0 && !sky) {
        float dark = 1.0 - smoothstep(0.10, 0.45, dot(surroundings(c, pixel), ETNA_LUMA));
        murk = Mist.z * dark * max(dist - AIR_MIST_START, 0.0);
    }
    vec3 sun;
    vec3 ambient;
    etnaAirLight(sun, ambient);
    c = mix(ambient, c, exp(-(mistDepth + murk)));
    if (Sun.w < 0.003) {
        return c;
    }

    // Integrated over the mist alone, density times transmittance sums to 1 - transmittance.
    float behind = exp(-mistDepth);
    float scattered;
    if (Shafts.w > 0.0) {
        float reach = min(dist, Shafts.w);
        float reachDepth = etnaMistDepth(dir.y, reach);
        float reachThrough = exp(-reachDepth);
        // The clear air within reach, seen through the mist: its length times the mist's average
        // transmittance over it, (1 - e^-depth) / depth.
        float average = reachDepth < 1e-3 ? 1.0 - 0.5 * reachDepth : (1.0 - reachThrough) / reachDepth;
        // EtnaAir's shaft air is 0.15 of its full density in the open, all of it under full cover: a painted
        // sky takes only the covered part, and the farthest land slides into it with vanilla's fog.
        float shaftAir = Shafts.x + (sky ? 0.0 : Mist.z);
        if (airSkyPainted()) {
            float covered = Shafts.x * (0.85 * Shafts.y / (0.15 + 0.85 * Shafts.y));
            shaftAir = mix(shaftAir, covered, sky ? 1.0 : renderFog(position));
        }
        float inReach = shaftAir * reach * average + (1.0 - reachThrough);
        // Open sky with no mist and no cover gets nothing: skip the reads.
        float share = inReach > 1e-4 ? litShare(pixel, sky ? AIR_SKY_DISTANCE : dist) : 1.0;
        scattered = share * inReach + max(reachThrough - behind, 0.0);
    } else {
        scattered = (1.0 - behind) * (1.0 - Shafts.y);
    }
    float amount = 1.0 - exp(-AIR_SUN_GAIN * etnaAirPhase(dot(dir, etnaAirLightDir())) * scattered);
    return screenBlend(c, sun * amount);
}

// ---------------------------------------------------------------------------------------------------

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 color = texelFetch(InSampler, pixel, 0).rgb;
    float depth = depthAt(pixel);
    bool sky = etnaIsSky(depth);
    vec2 uv = (vec2(pixel) + 0.5) / DepthSize;
    // Where something was drawn, the camera-relative position both steps need (etnaPosition, kept
    // undivided for the neighbours' positions).
    vec4 h = sky ? vec4(0.0, 0.0, 0.0, 1.0) : InvViewProj * vec4(uv, depth, 1.0);
    vec3 position = divided(h);

    // 0. The world reflected in the water (water_reflection.glsl), before everything that lies in front of it.
    color = etnaWaterReflection(color, pixel, sky ? 0.0 : length(position));

    // 1. Ambient occlusion.
    if (Shade.x > 0.005 && !sky) {
        // Far away the disc shrinks under a pixel and only adds noise.
        float fade = 1.0 - smoothstep(48.0, 96.0, length(position));
        if (fade > 0.0) {
            color *= mix(1.0, ambientOcclusion(pixel, position, h, depth), Shade.x * fade);
        }
    }

    // The Ink & Colour mood's outlines (ink.glsl), before the air so the distance softens them too. Not on the
    // screen's outermost pixels, which lack a neighbour on one side and would trace the screen's border.
    if (Mood5.w > 0.005 && !sky && all(greaterThan(pixel, ivec2(0))) && all(lessThan(pixel, ivec2(DepthSize) - 1))) {
        color *= etnaInk(depth, depthAt(pixel - ivec2(1, 0)), depthAt(pixel + ivec2(1, 0)), depthAt(pixel - ivec2(0, 1)),
            depthAt(pixel + ivec2(0, 1)), length(position));
    }

    // 2. Sky glow and atmosphere. With the camera under water, in lava or in powder snow (Water.x)
    // the picture is that medium's fog, and it passes through unchanged.
    color = clamp(color, 0.0, 1.0);
    if (Water.x > 0.5) {
        fragColor = vec4(color, 1.0);
        return;
    }
    // Nothing to add with both settings off, without a sun (Air.w), in full rain (Air.z), or once the
    // sun is far enough below the horizon that every glow has faded (sunset band at -0.14).
    if ((Air.x > 0.005 || Air.y > 0.005) && Air.w > 0.5 && Air.z < 0.999 && Sun.y > -0.14) {
        SkyState l = skyState();
        if (!sky) {
            color = landAir(color, position, pixel, l);
        } else if (!etnaSkyDrawn()) {
            // A sky Etna paints itself already has its glow; vanilla's gets it here. Only the direction
            // matters. A point a hair in front of the far plane, in homogeneous form and turned to face
            // away from the camera, gives it without dividing by w.
            vec4 farPoint = InvViewProj * vec4(uv, 1e-6, 1.0);
            vec3 ray = farPoint.w < 0.0 ? -farPoint.xyz : farPoint.xyz;
            float len = length(ray);
            if (len > 1e-6) {
                color = skyGlow(color, ray / len, l, Air.x * l.clear * skyLikeness(color));
            }
        }
    }

    // 3. Mist and light shafts; without a sun, the Nether's or the End's own air (air.glsl).
    color = Air.w > 0.5 ? airLight(color, pixel, sky, uv, position) : etnaSunlessAir(color, sky, uv, position);
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
