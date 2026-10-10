// Dark places, for terrain.vsh.

#ifdef ETNA_SHADOWS
// Light added to the sky's share of vanilla's light where the sky can't reach. terrain.vsh adds it to
// etnaLightmap after etnaTorchLight is worked out, in shadow-receiving draws only. uv2 is the vertex's
// light (block, sky; 0 .. 240), floorOnly vanilla's lightmap with no block or sky light at all (the
// dimension's ambient floor, or night vision).
//
// Caves are never pitch black: vanilla's light is topped up to a small minimum, EtnaDark.x (a quarter of
// the square root of the cave light setting, in lightmap units, so the looks clear the floor vanilla
// already has at the default Brightness; Visibility raises it, EtnaConfig.caveLightFloor), fading out
// towards the open sky as one minus the squared sky level (the idea and that fade after Photon's cave
// lighting, see CREDITS.md). Vanilla's own floor
// counts towards it, so a Bright gamma or night vision get nothing extra; the Darkness and Blindness
// effects take it away (EtnaDark.w), and dimensions without a sun (the Nether, the End) have none, so
// they get no blue cast. It joins the sky's share of the light, which terrain.fsh shades by face (tops
// brightest, then walls, then undersides) so every block edge in a dark cave shows. A cool blue-grey of
// luma about 1: torch-lit spots turn it grey and stay warm, the dark corners stay cool.
vec3 etnaCaveLift(ivec2 uv2, vec3 floorOnly) {
    const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
    float sky = clamp(float(uv2.y) / 240.0, 0.0, 1.0);
    float lift = max(EtnaDark.x - dot(floorOnly, LUMA), 0.0) * (1.0 - sky * sky) * EtnaDark.w;
    return lift * vec3(0.88, 0.97, 1.15);
}
#endif
