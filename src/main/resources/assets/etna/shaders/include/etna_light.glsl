// Etna's light on a surface, shared by the terrain (core/terrain.fsh) and by entities, block entities and
// items (core/entity.fsh, core/item.fsh through entity_light.glsl): the colours of the sun and the sky, how
// strong the direct sun is, the sky's light on a face by the way it faces, the soft terminator, and the warm
// colour of torch light. The terrain's skyLighting puts them together for blocks, entity_light.glsl for
// models. Moved out of terrain.fsh unchanged in 0.9.0, so the terrain is lit exactly as before.
// The includer declares, before including this: LUMA, terrain_ubo.glsl's block and names, sky.glsl,
// terrain_touch.glsl, etnaViewPos (the camera-relative position) and etnaLightLevels (x: block light, 0 .. 1).

// The brightness vanilla baked into this face's vertices (1 on top, darker on the sides and below).
float vanillaFaceShade(vec3 n, bool plant) {
    vec4 shade = ETNA_FACE_SHADE;
    if (plant) {
        // Faces with "shade": false (crosses, crops) are shaded as if they pointed up.
        return shade.y;
    }
    vec3 n2 = n * n;
    return n2.x * shade.w + n2.z * shade.z + n2.y * (n.y > 0.0 ? shade.y : shade.x);
}

// Colour of direct light: white-gold at noon, gold low in the sky, a deep orange at the horizon, a cool
// blue from the moon. With Etna's block it is the sky model's sunlight (EtnaSkyModel.sunlight: what the
// air leaves of the sun's light), brought to 0.6.0's brightness: a little under 1 at noon, three
// quarters with the sun low or the moon up, so the balance of sun and shade stays as it was tuned.
// Without the block or the model (draws with improved transparency, Etna switched off, the model failed)
// 0.6.0's own colours.
vec3 sunColor() {
    vec3 c;
    #ifdef ETNA_SHADOWS
    if (ETNA_SUN_WORLD > 0.5 && ETNA_SKY_MODEL) {
        c = etnaSunlight() * (ETNA_MOON ? 0.73 : mix(0.75, 0.97, smoothstep(0.04, 0.45, ETNA_LIGHT_DIR.y)));
    } else
    #endif
    {
        if (ETNA_MOON) {
            return vec3(0.62, 0.74, 1.0);
        }
        vec3 noon = vec3(1.0, 0.97, 0.92);
        vec3 low = vec3(1.0, 0.68, 0.40);
        c = mix(low, noon, smoothstep(0.04, 0.45, ETNA_LIGHT_DIR.y));
    }
    // The warmth setting nudges it: grey stone stays grey.
    float w = ETNA_WARMTH;
    return c * vec3(1.0 + 0.04 * w, 1.0 - 0.01 * w, 1.0 - 0.10 * w);
}

// Colour of the light from the sky, luma about 1. With Etna's block, in a world with a sun, the sky
// model's skylight (EtnaSkyModel.skylight): a clear blue by day, warmer around sunset, navy at night,
// grey in rain. Elsewhere the hue of vanilla's fog colour (the horizon: blue by day, orange at sunset,
// the biome's own tint, the Nether's and the End's air) at low saturation.
vec3 skyColor() {
    #ifdef ETNA_SHADOWS
    if (ETNA_SUN_WORLD > 0.5 && ETNA_SKY_MODEL) {
        // Under an aurora the night's skylight takes on a little of its green, at the same brightness
        // (EtnaNight, EtnaSky.writeNight: 0 by day, in rain and with the Aurora setting off).
        vec3 sky = etnaSkylight();
        return mix(sky, EtnaNight.rgb * dot(sky, LUMA), 0.22 * EtnaNight.w);
    }
    #endif
    vec3 hue = FogColor.rgb / max(dot(FogColor.rgb, LUMA), 1e-3);
    return clamp(mix(vec3(1.0), hue, 0.18), 0.0, 1.5);
}

#ifdef ETNA_SHADOWS
// Ordered 4x4 dither; with temporal anti-aliasing on it shifts every frame, so a few steps per
// frame add up to a smooth result over a handful of frames instead of a fixed pattern.
float etnaDither() {
    ivec2 cell = ivec2(gl_FragCoord.xy) & 3;
    const float BAYER[16] = float[](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
    float d = (BAYER[cell.y * 4 + cell.x] + 0.5) / 16.0;
    return EtnaWorld.y > 0.5 ? fract(d + EtnaLook2.z * 0.618034) : d;
}
#endif

// Strength of the direct sun, in vanilla's units (1 = a top face in vanilla daylight). Whatever the
// shade loses to the shadow darkness setting, the sun makes up on sunlit ground.
float directSun() {
    float strength = ETNA_LIGHT_STRENGTH;
    float shade = mix(0.95, mix(0.85, 0.45, ETNA_SHADOW_DARKNESS), strength);
    return (1.08 - shade) / 0.9 * ETNA_SUNLIGHT * strength;
}

// The sky light on a face, before its brightness. In a sunlit world, shade is lit by the blue sky
// alone and reads cooler than sunlit ground; on faces the sun reaches, its light outshines the sky's
// blue, so grey stone in the sun stays grey (and takes the sun's warmth). How blue the shade gets
// follows the shadow darkness setting: from its middle up all of the sky's colour, down to the sunlit
// faces' share at 0. With the sun up, walls see more of the sky's bright, warm side when they face the
// sun and of its darker blue side when they face away: each wall direction gets its own light, and a
// low sun warms the sky on the walls that face it. At night the sky's navy is the only light.
vec3 skyLightOn(vec3 normal, float faceNdl, float shadow) {
    vec3 sky = skyColor();
    if (ETNA_MOON) {
        return sky;
    }
    float strength = ETNA_LIGHT_STRENGTH;
    vec2 flatSun = ETNA_LIGHT_DIR.xz;
    float flatLength = dot(flatSun, flatSun);
    if (flatLength > 1e-6) {
        float toward = dot(normal.xz, flatSun) * inversesqrt(flatLength);
        float low = 1.0 - smoothstep(0.1, 0.6, ETNA_LIGHT_DIR.y);
        vec3 sun = sunColor();
        sky = mix(sky, sun / max(dot(sun, LUMA), 1e-3), 0.30 * max(toward, 0.0) * low * strength);
        sky *= 1.0 + 0.14 * toward * strength;
    }
    float sunlit = shadow * smoothstep(0.0, 0.3, faceNdl) * strength;
    float keep = mix(mix(0.4, 1.0, smoothstep(0.0, 0.5, ETNA_SHADOW_DARKNESS)), 0.4, sunlit);
    return mix(vec3(dot(sky, LUMA)), sky, keep);
}

// A face lit by the sun at the cosine ndl, the terminator softened a little: a face the sun only grazes
// gets a little more than Lambert's cosine (wrap lighting), so walls don't snap from lit to dark as the
// sun crosses their plane. Faces turned away keep none: the shadow map can't speak for them.
float softSun(float ndl) {
    return ndl > 0.0 ? mix(ndl, (ndl + 0.15) / 1.15, smoothstep(0.0, 0.12, ndl)) : 0.0;
}

// How far torch light moves from vanilla's colour towards a flame's. The Nether and the End are lit
// by little else: a strong orange cast there would bury their own colours.
float torchWarmth() {
    return ETNA_TORCH_WARMTH * mix(0.35, 1.0, ETNA_SUN_WORLD);
}

// Torches, lanterns, lava, glowstone. vanilla is vanilla's block light at this spot: its curve, the
// brightness setting, night vision and the dimension's ambient light are all in it already, so only
// its colour changes, to a flame that is yellow-white close to the source and deepens to orange as
// it fades, and its far reach dims a little so the light pools around the source. The light held in
// the player's hand is in vanilla here already (terrain.vsh) and takes the same flame where it
// outshines the blocks' own light, tinted by what is held: blue for soul fire, green for copper.
vec3 torchLight(vec3 vanilla) {
    float warmth = torchWarmth();
    if (warmth <= 0.0) {
        return vanilla;
    }
    float level = etnaLightLevels.x;
    vec3 tint = vec3(1.0);
    #ifdef ETNA_SHADOWS
    if (EtnaHand.w > 0.0) {
        float held = etnaHeldLevel(etnaViewPos) / 15.0;
        // All the hand's colour two levels above the blocks' own light, none where the blocks' is brighter.
        tint = mix(vec3(1.0), EtnaHand.rgb, clamp((held - level) * 7.5, 0.0, 1.0));
        level = max(level, held);
    }
    #endif
    vec3 flame = mix(vec3(1.0, 0.60, 0.30), vec3(1.0, 0.86, 0.66), smoothstep(0.45, 0.95, level)) * tint;
    // Same brightness as vanilla's light (luma 1 times its luma): only the hue moves.
    flame /= dot(flame, LUMA);
    float falloff = mix(0.72, 1.0, smoothstep(0.3, 0.93, level));
    return mix(vanilla, flame * (dot(vanilla, LUMA) * falloff), warmth);
}
