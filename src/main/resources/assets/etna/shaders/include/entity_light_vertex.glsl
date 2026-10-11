// Etna's light on entities, block entities, armour and items, vertex half: core/entity.vsh and core/item.vsh
// include it after terrain_ubo.glsl, terrain_touch.glsl, terrain_dark_vertex.glsl and minecraft:sample_lightmap.glsl,
// and after declaring Sampler2 (vanilla's lightmap). entity_light.glsl is the fragment half and reads what this
// writes. Only Etna's copies of vanilla's entity and item pipelines draw with these shaders (EtnaEntities.java),
// always with Etna's block bound (ETNA_SHADOWS), so the block is there whenever this runs.
//
// Vanilla multiplies its lightmap into the colour at every vertex. Etna splits it the way terrain.vsh does for
// blocks: the sky's share, which entity_light.glsl lights again with the sun, the sky and the shadow map, and the
// share torches and other glowing blocks add on top, which takes the torch colour. The vertex's light is the same
// pair of levels a block's is (block, sky; 0 .. 240), so a mob standing on the grass reads the light the grass
// reads.
// Locations 8 to 12 are Etna's own: vanilla's entity and item shaders use 0 to 7.

#ifndef OIT_ALPHA_ONLY
layout(location = 8) out vec3 etnaModelPos;    // camera-relative position, world-oriented (vanilla's Position)
layout(location = 9) out vec3 etnaLightmap;    // vanilla's light here, with the held light and the cave light
layout(location = 10) out vec3 etnaTorchLight; // the part of etnaLightmap that block light adds to the sky's
// x: block light level, y: sky light level (both 0 .. 1), z: how much of the light on the sky's side is really sky
// rather than the dimension's ambient floor or night vision (as terrain.vsh's).
layout(location = 11) out vec3 etnaLightLevels;
layout(location = 12) out vec3 etnaModelNormal; // vanilla's Normal: world-oriented in the world and in the hand

// The same split terrain.vsh makes for a block's vertex (see the comments there), for a vertex of a model at the
// camera-relative position pos, with the vertex's normal and light. The light held in the player's hand counts as
// block light here too, as it does on the blocks around.
void etnaModelLightVertex(vec3 pos, vec3 normal, ivec2 uv2) {
    const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
    etnaModelPos = pos;
    etnaModelNormal = normal;
    vec2 light = vec2(uv2);
    light.x = max(light.x, 16.0 * etnaHeldLevel(pos));
    etnaLightmap = texture(Sampler2, clamp(light / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
    vec3 skyOnly = sample_lightmap(Sampler2, ivec2(0, uv2.y)).rgb;
    vec3 floorOnly = sample_lightmap(Sampler2, ivec2(0, 0)).rgb;
    etnaTorchLight = max(etnaLightmap - skyOnly, 0.0);
    etnaLightmap += etnaCaveLift(uv2, floorOnly);
    float skyLuma = dot(skyOnly, LUMA);
    float realSky = clamp((skyLuma - dot(floorOnly, LUMA)) / max(skyLuma, 1e-3), 0.0, 1.0);
    etnaLightLevels = vec3(clamp(vec2(uv2) / 240.0, 0.0, 1.0), realSky);
}
#endif
