#version 330
#extension GL_ARB_separate_shader_objects : require

// Based on Minecraft's vanilla shader, (c) Mojang AB, not covered by Etna's MIT licence (CREDITS.md).
// Etna's cloud fragment shader, made from vanilla 26.4's and used only by Etna's copies of the cloud
// pipelines while Etna is on (EtnaClouds.java). Vanilla paints each face of a cloud box a
// flat grey (top 1.0, sides 0.8 or 0.9, underside 0.7), so clouds read as white boxes that look the
// same from every side and at every time of day.
//
// With Etna's light bound (ETNA_CLOUD_LIGHT, see EtnaClouds.java) each box is lit like a cloud by the
// sun and the sky:
// * light from the sky on every face: bright tops, sides that darken smoothly towards the base
//   instead of in one flat panel, undersides that stay brighter than the blue sky behind them;
// * the sun on top of that, wrapped round each face (a cloud scatters light deep inside it, so a face
//   turned a little away still gets some): the side towards the sun is the brighter one;
// * looking towards the sun, a glow through the cloud, brightest at the edges (silver lining);
// * a low sun lights the clouds gold against a sky that has dimmed more than the sun, and just after
//   sunset its last, red light reaches under them and turns the undersides pink;
// * moonlight at night on clouds that stay dark and cool; flat, darker grey in rain;
// * far clouds low over the horizon melt into the colour of the air there.
// Vanilla's cloud colour (dimmer at night, grey in rain) sets the light from the sky, and the alpha
// keeps vanilla's fade with distance. The colours come from Etna's sky model (sky.glsl), the same that
// paints the sky and lights the land, so the clouds match both at every hour. Without Etna's light
// (ETNA_CLOUD_LIGHT not set) this is vanilla's shader, with vanilla's per-face greys from clouds.vsh.

#include <minecraft:fog.glsl>
#ifdef ETNA_CLOUD_LIGHT
#include <etna:terrain_ubo.glsl>
#define ETNA_SKY_CURRENT
#endif
#include <etna:sky.glsl>

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
#ifdef ETNA_CLOUD_LIGHT
layout(location = 2) in float cloudHeight;
layout(location = 3) in vec3 cloudNormal;
layout(location = 4) in vec3 cloudPosition;
#endif

layout(location = 0) out vec4 fragColor;

#ifdef ETNA_CLOUD_LIGHT
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

// This frame's sky (sky.glsl), from the terrain block, as terrain.fsh's etnaSkyCurrent: the light in the
// block is the moon's at night, which stands opposite the sun.
EtnaSkyFrame etnaSkyCurrent() {
    EtnaSkyFrame f;
    f.sun = ETNA_MOON ? -EtnaLightDir.xyz : EtnaLightDir.xyz;
    f.moon = -f.sun;
    f.sunLow = EtnaSkySunLow.rgb;
    f.sunHigh = EtnaSkySunHigh.rgb;
    f.multi = EtnaSkyMulti.rgb;
    f.moonLow = EtnaSkyMoonLow.rgb;
    f.moonHigh = EtnaSkyMoonHigh.rgb;
    f.overcast = EtnaSkyOvercast.rgb;
    f.rain = EtnaSkySunLow.w;
    f.storm = EtnaSkySunHigh.w;
    f.glow = EtnaSkyMulti.w;
    return f;
}

// The light on the clouds. Every colour the clouds take is looked up here. With the sky model (in the
// overworld while Etna's block has it) the sun's and the sky's colours are the ones the land gets
// (EtnaSkyModel.sunlight and skylight), and the air is the model's sky right behind the cloud; without
// it, 0.6.0's colours (as terrain.fsh's sunColor) and vanilla's fog colour.
struct CloudLight {
    vec3 sunDir; // towards the sun, below the horizon at night
    vec3 sun;    // sunlight reaching the cloud layer, colour times strength: 0 at night and in rain
    vec3 moon;   // moonlight, the same way: 0 by day
    vec3 sky;    // light from the sky on a face looking up, at vanilla's cloud brightness
};

CloudLight cloudLight(vec3 cloud) {
    CloudLight l;
    bool moon = ETNA_MOON;
    bool model = ETNA_SUN_WORLD > 0.5 && ETNA_SKY_MODEL;
    l.sunDir = moon ? -ETNA_LIGHT_DIR : ETNA_LIGHT_DIR;
    float y = l.sunDir.y;
    float rain = clamp(ETNA_RAIN, 0.0, 1.0);
    float clear = 1.0 - smoothstep(0.0, 0.8, rain);
    // Clouds stand high above the land: they keep the sun for a while after it has set on the ground.
    float sunUp = smoothstep(-0.12, 0.06, y) * clear * ETNA_SUN_WORLD;
    // White-gold at noon, deep gold low down. With the sun under the horizon the model's sunlight is the
    // moon's: the clouds keep the colour it had at the horizon. The brightest channel is 1, so the gain
    // in directLight keeps its meaning whatever the hue. Below the horizon the last light that reaches the
    // clouds has crossed the most air: red, which the blue sky light turns pink.
    vec3 sun;
    if (model) {
        sun = !moon && y > 0.0 ? etnaSunlight() : vec3(1.0, 0.55, 0.52);
        sun /= max(max(sun.r, sun.g), max(sun.b, 1e-3));
        // The clouds stand above the densest, haziest air, so with the sun well up their light is a little
        // whiter than the land's: white clouds at noon, gold ones only as the sun goes down.
        sun = mix(vec3(1.0), sun, mix(1.0, 0.7, smoothstep(0.1, 0.5, y)));
    } else {
        sun = mix(vec3(1.0, 0.68, 0.40), vec3(1.0, 0.97, 0.92), smoothstep(0.04, 0.45, y));
    }
    sun = mix(sun, vec3(1.0, 0.44, 0.42), smoothstep(0.04, -0.06, y));
    // The warmth setting only nudges it, as on the land.
    float w = ETNA_WARMTH;
    l.sun = sun * vec3(1.0 + 0.04 * w, 1.0 - 0.01 * w, 1.0 - 0.10 * w) * sunUp;
    // The terrain's moonlight (the model's sunlight while the moon is the light), already scaled by the
    // moon's height, its phase and the weather.
    vec3 moonColor = model ? etnaSunlight() / max(etnaSunlight().b, 1e-3) : vec3(0.62, 0.74, 1.0);
    l.moon = moon ? moonColor * ETNA_LIGHT_STRENGTH : vec3(0.0);
    // Sky light at vanilla's brightness. With the model, half way from white to the land's skylight: a
    // clear blue by day, warm around sunset, navy at night, grey in rain; a cloud is bright enough that
    // its shaded sides read pale blue rather than the blue of the shade on the land. Without it 0.6.0's
    // tints: blue at night, a softer violet blue at twilight, pale blue by day, neutral grey in rain.
    // Around sunset the sky has dimmed more than the low sun, so the clouds take less of it and more of
    // the sun's colour.
    vec3 tint;
    if (model) {
        tint = mix(vec3(1.0), etnaSkylight(), 0.5);
    } else {
        tint = mix(vec3(0.90, 0.95, 1.12), vec3(0.96, 0.94, 1.04), smoothstep(-0.30, -0.02, y));
        tint = mix(tint, vec3(0.97, 0.99, 1.04), smoothstep(0.02, 0.30, y));
        tint = mix(tint, vec3(1.0), smoothstep(0.0, 0.7, rain));
    }
    float dim = 0.28 * smoothstep(-0.25, 0.0, y) * (1.0 - smoothstep(0.0, 0.35, y)) * (1.0 - smoothstep(0.0, 0.7, rain));
    l.sky = cloud * tint * ((1.0 - dim) / dot(tint, LUMA));
    return l;
}

// The colour of the air a far cloud melts into, in the direction viewDir. While Etna draws the sky, the
// sky model's colour right behind the cloud (a cloud low over the horizon then fades into the sky around
// it, orange towards a setting sun, blue away from it). Otherwise vanilla's fog colour, which only matches
// the sky right at the horizon.
vec3 cloudAir(vec3 viewDir) {
    if (ETNA_SKY_DRAWN) {
        return etnaSkyRadiance(normalize(vec3(viewDir.x, max(viewDir.y, 0.0) + 1e-3, viewDir.z)));
    }
    return clamp(FogColor.rgb, 0.0, 1.0);
}

// Henyey-Greenstein phase function (Henyey and Greenstein, 1941) divided by its value straight
// towards the light, as in world_light.fsh: 1 looking at the light, falling off with the angle.
float forwardLobe(float cosTheta, float g) {
    float x = 1.0 + g * g - 2.0 * g * clamp(cosTheta, -1.0, 1.0);
    float r = (1.0 - g) * (1.0 - g) / x;
    return r * sqrt(r);
}

// The face's dot product with the light, wrapped round towards the back by w.
float wrapped(vec3 n, vec3 l, float w) {
    return clamp((dot(n, l) + w) / (1.0 + w), 0.0, 1.0);
}

// How much of a light from direction l a face gets, in units of that light.
float directLight(vec3 n, float rise, vec3 l) {
    // A low light lights the clouds against a sky that has dimmed more than it has: it counts for more
    // than at noon.
    float gain = mix(0.85, 0.30, smoothstep(0.05, 0.5, l.y));
    if (n.y < -0.5) {
        // Undersides are bumpy in a real cloud: a light near the horizon reaches under them (wrapped all
        // the way round), and a high one shines through the cloud onto them a little.
        return gain * wrapped(n, l, 1.0) * (1.0 - smoothstep(0.05, 0.4, l.y)) + 0.08 * clamp(l.y, 0.0, 1.0);
    }
    float lit = gain * wrapped(n, l, 0.35);
    // Light reaches the sides from above through the cloud too: with the light high, more near the top.
    return abs(n.y) < 0.5 ? lit * mix(1.0, 0.55 + 0.45 * rise, clamp(l.y, 0.0, 1.0)) : lit;
}

// Light seen through the cloud looking towards a light whose direction makes cosine c with the view:
// a tight lobe that is strongest on faces seen edge-on (the outline of a box, where a cloud is
// thinnest), and a wide, soft one.
float throughLight(float c, float rim) {
    return 0.40 * forwardLobe(c, 0.85) * rim + 0.25 * forwardLobe(c, 0.55);
}
#endif

void main() {
    #ifdef ETNA_CLOUD_LIGHT
    vec4 cloud = clamp(vertexColor, 0.0, 1.0);
    float height = clamp(cloudHeight, 0.0, 1.0);
    float rise = height * height * (3.0 - 2.0 * height);
    vec3 n = normalize(cloudNormal);
    vec3 viewDir = cloudPosition * inversesqrt(max(dot(cloudPosition, cloudPosition), 1e-6));
    CloudLight light = cloudLight(cloud.rgb);
    float rain = clamp(ETNA_RAIN, 0.0, 1.0);

    // Light from the sky. Undersides are in the cloud's own shadow, lit by the sky around and the land
    // below. Sides run from the shaded base up to nearly the brightness of the top, so the edge where a
    // side meets the top or the underside is a soft step rather than a hard one; the lower part of a
    // side is a little thinner, which softens the base of the box against the sky. In rain the lower
    // part of the cloud darkens most: heavy, grey rain clouds.
    float shape;
    float density = 1.0;
    if (n.y < -0.5) {
        shape = 0.76 * (1.0 - 0.18 * rain);
    } else if (n.y > 0.5) {
        shape = 0.84;
    } else {
        // North and south faces a touch darker than west and east, as in vanilla, so neighbouring sides
        // of one box still read apart while the sun is over the east-west line.
        shape = mix(0.70, 0.84, rise) * (abs(n.z) > 0.5 ? 0.96 : 1.0) * (1.0 - 0.18 * rain * (1.0 - rise));
        density = mix(0.88, 1.0, rise);
    }
    vec3 color = light.sky * shape;

    // The sun, and at night the moon, opposite it.
    color += light.sun * directLight(n, rise, light.sunDir);
    color += light.moon * directLight(n, rise, -light.sunDir);

    // Silver lining: looking towards the sun, its light comes through the cloud. The faces turned away
    // from the sun show it most; on the sunlit ones it would only add to the light already there.
    float edge = 1.0 - abs(dot(n, viewDir));
    float rim = 0.35 + 0.65 * edge * edge;
    float towards = dot(viewDir, light.sunDir);
    color += light.sun * (throughLight(towards, rim) * (1.0 - 0.6 * wrapped(n, light.sunDir, 0.35)));
    color += light.moon * (0.5 * throughLight(-towards, rim));

    // Where the light adds up past white, keep its hue: a gold face stays gold at full brightness.
    color /= max(max(color.r, max(color.g, color.b)), 1.0);

    // Far clouds low over the horizon melt into the air there (cloudAir): clouds higher up keep their own
    // colour, and only the low, far ones take it. Near ones skip the sky model's colour.
    float low = 1.0 - smoothstep(0.04, 0.30, viewDir.y);
    float haze = (1.0 - exp(-vertexDistance / 900.0)) * low;
    if (haze > 0.004) {
        color = mix(color, cloudAir(viewDir), 0.75 * haze);
    }

    float alpha = cloud.a * density;
    alpha *= 1.0 - linear_fog_value(vertexDistance, 0.0, FogCloudsEnd);
    fragColor = vec4(clamp(color, 0.0, 1.0), clamp(alpha, 0.0, 1.0));
    #else
    vec4 color = vertexColor;
    color.a *= 1.0f - linear_fog_value(vertexDistance, 0, FogCloudsEnd);
    fragColor = color;
    #endif
}
