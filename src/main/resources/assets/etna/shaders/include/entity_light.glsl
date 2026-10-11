// Etna's light on entities, block entities, armour and items, fragment half: core/entity.fsh and core/item.fsh
// include it after minecraft:fog.glsl, minecraft:globals.glsl, terrain_ubo.glsl and terrain_touch.glsl.
// entity_light_vertex.glsl writes what it reads. Only Etna's copies of vanilla's entity and item pipelines draw
// with these shaders (EtnaEntities.java), always with Etna's block, its shadow maps and the cloud texture bound
// (ETNA_SHADOWS), so all of them are there whenever this runs.
//
// Vanilla lights a model with two fixed lights and its lightmap. Here a model is lit the way the terrain lights a
// block (terrain.fsh, shadeTerrain and skyLighting), with the same functions (etna_light.glsl): the sky's share of
// vanilla's light is lit again with the sky, brightest from above and tinted by the way the face turns to the sun,
// and with the sun where the shadow map and the clouds let it through, and the share that torches add takes their
// warm colour. A model's surface is a plain one: no bumps from its texture, no sheen, no glowing texels. So a white
// cube standing on white ground comes out as bright as the ground's own top and sides in the same light.
//
// ETNA_HAND is the first-person hand and what it holds: drawn after the level, close to the eye, with the hand's
// own projection. It is lit as if it stood where the camera is: it takes the shade of the tree or the roof over the
// player, as the land around does, rather than the shadow map's tiny texels at the hand's own place, which would
// flicker over it as the player walks.

#ifndef OIT_ALPHA_ONLY
layout(location = 8) in vec3 etnaModelPos;
layout(location = 9) in vec3 etnaLightmap;
layout(location = 10) in vec3 etnaTorchLight;
layout(location = 11) in vec3 etnaLightLevels;
layout(location = 12) in vec3 etnaModelNormal;

uniform sampler2D EtnaShadowSampler;
// The other of the two maps, while the shadows fade from one to the other (terrain_shadow.glsl).
uniform sampler2D EtnaShadowSampler2;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

// The names the terrain's includes read, as terrain.fsh has them: set by etnaModelBegin, before any lighting.
// etnaViewPos is the camera-relative position the shadow map, the clouds and the haze are read at, etnaWorldPos
// the same in the world (only its height is read, by the Nether's lava light), etnaSkyLight the open-sky gate
// terrain.vsh works out per vertex (0 in caves .. 1 under open sky).
vec3 etnaViewPos = vec3(0.0);
vec3 etnaWorldPos = vec3(0.0);
float etnaSkyLight = 0.0;

#include <etna:sky.glsl>
#include <etna:etna_light.glsl>
#include <etna:shadow_distortion.glsl>
#include <etna:terrain_shadow.glsl>
#include <etna:terrain_clouds.glsl>

// How open this place is to the light from above, for how deep the shade goes (terrain_dark.glsl's
// etnaShadeWeight). On land the terrain takes the open-sky gate; its water hooks, which change that under water,
// are the terrain's own, and a model under water keeps the land's.
float etnaShadeOpen() {
    return etnaSkyLight;
}

#include <etna:terrain_dark.glsl>

// Sets the names above for this fragment.
void etnaModelBegin() {
    #ifdef ETNA_HAND
    etnaViewPos = vec3(0.0);
    #else
    etnaViewPos = etnaModelPos;
    #endif
    // Globals: the camera stands at CameraBlockPos - CameraOffset.
    etnaWorldPos = etnaViewPos + vec3(CameraBlockPos) - CameraOffset;
    etnaSkyLight = smoothstep(7.0 / 15.0, 13.0 / 15.0, etnaLightLevels.y);
}

#ifdef ETNA_HAND
// The sun at the camera, for the hand: the shadow map read at the camera and at eight points on a ring 0.4 blocks
// round it, each blended over the four texels round it (shadowSoftAt). Walking out from under a tree, the hand
// brightens over about a block in small steps rather than at once, and it never changes from frame to frame
// while the player stands still. No dither: the hand is drawn after the anti-aliasing, which can't smooth it.
float etnaHandShadow() {
    float lit = shadowSoftAt(vec3(0.0));
    for (int i = 0; i < 8; i++) {
        float angle = float(i) * (6.2831853 / 8.0);
        lit += shadowSoftAt(vec3(cos(angle), 0.0, sin(angle)) * 0.4);
    }
    return lit / 9.0;
}
#endif

// A model's normal for lighting: vanilla's vertex normal, unit length, turned round on the back of faces drawn
// from both sides (PER_FACE_LIGHTING, where vanilla lights the back with the normal turned round too).
vec3 etnaModelNormalHere() {
    vec3 n = etnaModelNormal;
    float len2 = dot(n, n);
    // A model without normals: light it as a top face rather than let normalize() make NaN of it.
    n = len2 > 1e-8 ? n * inversesqrt(len2) : vec3(0.0, 1.0, 0.0);
    #ifdef PER_FACE_LIGHTING
    if (!gl_FrontFacing) {
        n = -n;
    }
    #endif
    return n;
}

// The light on a model's surface, in vanilla's units (1 = a top face in vanilla daylight): what Etna multiplies
// the model's colour by where vanilla multiplies it by its lightmap. shadeTerrain and skyLighting in terrain.fsh
// for a flat surface that is neither leaves nor a plant, in the same order. The model's colour has no face
// shading or ambient occlusion baked in to undo, so none is divided out.
vec3 etnaModelLight(vec3 normal) {
    float facing = dot(normal, ETNA_LIGHT_DIR);
    float shadow = 1.0;
    // Faces turned away from the sun get no sun whatever the map says, so they skip the lookups.
    if (ETNA_LIGHT_STRENGTH * etnaSkyLight > 0.01 && facing > 0.0) {
        if (EtnaLook2.w > 0.5) {
            #ifdef ETNA_HAND
            shadow = etnaHandShadow();
            #else
            shadow = shadowVisibility(normal, false);
            #endif
        }
        // Clouds drifting over the sun, with or without the shadow map.
        shadow *= etnaCloudShadow();
    }

    // Vanilla's light here, in two parts, as on the terrain: the sky's, relit below, and what torches add,
    // which keeps vanilla's per-face shading and takes the torch colour. Next to a torch the sky's part greys.
    vec3 skyPart = max(etnaLightmap - etnaTorchLight, 0.0);
    vec3 torch = torchLight(etnaTorchLight) * etnaTorchBoost();
    float torchLuma = dot(etnaTorchLight, LUMA);
    float skyLuma = dot(skyPart, LUMA);
    skyPart = mix(skyPart, vec3(skyLuma), torchWarmth() * torchLuma / max(torchLuma + skyLuma, 1e-4));

    // skyLighting: the sky brightest from above (tops all of it, sides most, undersides about half; east and
    // west a few percent less than north and south), the sun with the soft terminator where the shadow map lets
    // it through, and the sunlit ground's warm light thrown back up onto sides and undersides. The sky's up and
    // down and its warmer side towards the sun keep a mob's faces apart in the shade and under an overcast sky,
    // as vanilla's two lights do.
    float ambient = mix(0.95, mix(0.85, 0.45, ETNA_SHADOW_DARKNESS), etnaShadeWeight());
    float up = normal.y;
    float dome = (0.78 + 0.22 * up) * (1.0 - 0.06 * normal.x * normal.x);
    float faceNdl = max(facing, 0.0);
    float ndl = softSun(faceNdl);
    vec3 sky = skyLightOn(normal, faceNdl, shadow);
    vec3 sunlight = sunColor() * directSun();
    float bounce = ETNA_MOON ? 0.0 : 0.20 * max(ETNA_LIGHT_DIR.y, 0.0) * (0.5 - 0.5 * up);
    vec3 skyLight = sky * ambient * dome + sunlight * etnaSkyLight * (ndl * shadow)
        + sunlight * vec3(1.0, 0.86, 0.68) * (bounce * etnaSkyLight);
    return skyLight * skyPart + vanillaFaceShade(normal, false) * torch + etnaLavaLight(normal);
}

// The distance haze, as on the terrain: only while the camera is out of the water (EtnaWater.x 0), where the
// terrain draws it too.
vec3 etnaModelHaze(vec3 color) {
    return EtnaWater.x < 0.5 ? aerialHaze(color) : color;
}
#endif
