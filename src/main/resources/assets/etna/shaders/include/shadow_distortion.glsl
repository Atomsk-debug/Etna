// Shadow map distortion, after Photon (SixthSurge, Photon Shaders License, see CREDITS.md): the shadow
// pass (terrain.vsh) squeezes the map towards its centre, where the player stands, and the lookups
// (terrain_shadow.glsl) undo it with the same function. A shadow texel next to you covers about a
// seventh of what it did undistorted, so the stair-steps of a block's shadow edge are far too small to
// see crawl as the sun moves; far away the texels grow instead.
// terrain.vsh includes it in the shadow pass (ETNA_SHADOW_PASS), terrain.fsh in the shadow-receiving
// draws (ETNA_SHADOWS).
const float ETNA_SHADOW_DISTORTION = 0.85;

float etnaShadowFactor(vec2 p) {
    vec2 p2 = p * p;
    return sqrt(sqrt(p2.x * p2.x + p2.y * p2.y)) * ETNA_SHADOW_DISTORTION + (1.0 - ETNA_SHADOW_DISTORTION);
}
