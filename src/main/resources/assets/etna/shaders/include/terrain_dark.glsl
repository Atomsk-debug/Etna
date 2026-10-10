// Dark places: how deep shade gets, torch light, the haze in the distance and light that blocks give off
// themselves. terrain_hooks.glsl declares these hooks and says where terrain.fsh calls them. The cave
// light that keeps unlit caves from going pitch black is in terrain_dark_vertex.glsl.

// How far the sky light is shaded where the sun can't reach (0: not at all, 1: as deep as the shadow
// darkness setting goes). Only light that really comes from the open sky is shaded: the open-sky gate
// (etnaSkyLight, 1 from sky level 13 up) times the larger of that gate and the share of vanilla's light
// that is real sky rather than the dimension's ambient floor or night vision (etnaLightLevels.z). That
// share alone is about 1 by day at any sky level above 0, so on its own (0.4.0 took the larger of it and
// the gate) a room lit through a window, or a sea bed a few blocks down, took the full shade of open
// ground where the sun can't reach (at Vivid only 0.59 of vanilla's light). Open ground keeps exactly the
// shade it had, whatever vanilla's floor: night vision or a bright gamma lift that floor, but vanilla's
// open ground is at full brightness anyway, so its shade must not ease off while the sun stays as strong.
// Under water the gate is the water's own openness (etnaShadeOpen in terrain_water.glsl), which fades
// steadily with depth instead of with vanilla's sky level.
float etnaShadeWeight() {
    float open = etnaShadeOpen();
    return ETNA_LIGHT_STRENGTH * open * max(open, etnaLightLevels.z);
}

// Brightness factor on the torch light, after its colour: a hot core. The block or two around a torch,
// lantern or other glowing block (block light 12 to 15) get up to 30 % more of its light, so light pools
// around its source instead of vanilla's flat wash. By day the sky alone already fills vanilla's
// lightmap and torch light adds next to nothing, so this shows only in the dark. It follows the torch
// warmth setting (0 also turns it off, as it is in draws without Etna's block).
float etnaTorchBoost() {
    return 1.0 + 0.3 * ETNA_TORCH_WARMTH * smoothstep(12.0 / 15.0, 1.0, etnaLightLevels.x);
}

// Distant terrain slowly takes the colour of the air before vanilla's fog finishes the job at the
// edge of the render distance, so the two meet without a seam. Underground the fog colour is still
// the bright daytime air, and hazing cave walls towards it turned ravines and big caves milky at noon:
// where the sky can't reach, only a seventh of the haze is left. In the Nether and the End the fog
// colour is their own air, not daylight, so they keep all of it.
vec3 aerialHaze(vec3 color) {
    float far = max(FogRenderDistanceEnd, 32.0);
    float t = clamp(length(etnaViewPos) / far, 0.0, 1.0);
    float open = max(etnaSkyLight, mix(1.0, 0.15, ETNA_SUN_WORLD));
    return mix(color, FogColor.rgb, pow(t, 1.6) * ETNA_HAZE * 0.6 * open);
}

// The last word on a lit terrain colour: lit is the colour shadeTerrain worked out, albedo the surface's
// own colour (vanilla's face shading and ambient occlusion divided out), kind what surfaceKind says.
//
// Glowing blocks. The main picture is 8-bit, so "brighter" can only mean closer to white, which is where
// the bloom pass picks light up. The tops of most light sources are near white already, but their sides
// and undersides took vanilla's face shading like any other block and lava stayed a deep orange, so they
// barely bloomed. A block that gives off light is drawn without ambient occlusion and with its own light
// level on every face, so block light 15 on every corner means a level-15 source: glowstone, sea
// lanterns, lanterns, lava, fire, campfires, shroomlight and froglights. Smooth lighting averages the
// light of the cells around each corner, so the faces next to a light (the ceiling over a hanging
// lantern, the wall beside one on the floor) top out at about 14.75 and stay as they are. Level-14
// sources (torches, end rods) don't glow either; their flames are near white already. With smooth
// lighting off, every face that touches a light's cell reads 15 too, so EtnaDark.y is 0 then
// (EtnaDark.java). Only bright texels glow (the flame and the glass, not the lantern's iron): a hotter,
// whiter version of their own colour is added on top, so the sides shine like the top and lava turns a
// hot yellow-orange. It is off under Darkness and Blindness, which keep the dark as vanilla has it.
// Water draws its own surface (shadeWater).
//
// Where daylight reaches the block (the sun up and the sky open) a glow added on top would only wash a
// sunlit lava pool out to white. There the glowing texels instead keep their own full colour on every
// face, the shaded sides and undersides too, as a light should, and a little of the hot colour is
// screened over them: it never takes a channel past white, and the brighter the sun already makes the
// texel, the less it adds. At the default glowing blocks setting (0.5) every glowing texel is fully
// self-lit by day.
vec3 etnaGlow(vec3 lit, vec3 albedo, float kind) {
    #ifdef ETNA_SHADOWS
    if (kind > 2.5) {
        return lit;
    }
    float peak = max(albedo.r, max(albedo.g, albedo.b));
    float glow = EtnaDark.y * EtnaDark.w
        * smoothstep(14.75 / 15.0, 14.95 / 15.0, etnaLightLevels.x) * smoothstep(0.6, 0.85, peak);
    if (glow > 0.0) {
        float daylight = smoothstep(0.0, 0.5, (ETNA_MOON ? 0.0 : ETNA_LIGHT_STRENGTH) * etnaSkyLight);
        vec3 hot = mix(albedo / max(peak, 0.05), vec3(1.0), 0.25);
        vec3 dark = lit + hot * (0.5 * glow);
        vec3 day = mix(lit, max(lit, albedo), min(2.0 * glow, 1.0));
        day += (1.0 - min(day, 1.0)) * hot * (0.3 * glow);
        return mix(dark, day, daylight);
    }
    #endif
    return lit;
}
