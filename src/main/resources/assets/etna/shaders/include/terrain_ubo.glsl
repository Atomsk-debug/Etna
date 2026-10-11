// Etna's terrain uniform block, and the names the terrain shaders read the light and the settings by.
// terrain.vsh and terrain.fsh both include it, before any other Etna terrain include, so both stages
// see the same block. EtnaShadows.java fills it for every shadow-receiving terrain draw (define
// ETNA_SHADOWS) and binds it to the lit clouds (define ETNA_CLOUD_LIGHT, clouds.fsh) and to Etna's rain
// and snow (ETNA_WEATHER_LIGHT, weather.vsh and weather.fsh) too: std140,
// 576 bytes (EtnaShadows.UBO_SIZE), written in this order by EtnaShadows.writeUbo. The first eight
// fields take bytes 0 to 175; each later one starts at the byte offset its comment begins with.
#if defined(ETNA_SHADOWS) || defined(ETNA_CLOUD_LIGHT) || defined(ETNA_WEATHER_LIGHT)
layout(std140) uniform EtnaShadow {
    mat4 EtnaShadowMat;  // camera-relative position -> shadow map clip space
    vec4 EtnaLightDir;   // xyz: direction towards the sun (or the moon at night), w: light strength
    vec4 EtnaShadowInfo; // x: 1 / map size, y: covered radius in blocks, z: depth units per block, w: the light rays setting (read by no shader since the shafts became a post pass, which takes it from EtnaSettings)
    vec4 EtnaLook;       // settings menu. x: shadow softness in texels, y: shadow darkness, z: sunlight, w: distance haze
    vec4 EtnaLook2;      // x: water reflections, y: warmth (-1 cool .. 1 warm), z: frame counter, w: 1 when the shadow map was drawn this frame
    vec4 EtnaFaceShade;  // vanilla's fixed shading per face in this dimension. x: down, y: up, z: north/south, w: west/east
    vec4 EtnaWorld;      // x: 1 in dimensions with a sun, y: 1 while temporal anti-aliasing smooths out per-frame noise, z: 1 when the light is the moon, w: rain (0 .. 1)
    vec4 EtnaMaterial;   // settings menu. x: surface detail, y: shine, z: leaf glow, w: torch warmth
    vec4 EtnaWater;      // 176. x: what the camera is in (0 air, 1 water, 2 lava, 3 powder snow), y: water vision (0 .. 1), z: height of the water surface above the camera in blocks (64: deep down); y and z are 0 out of water, w: reserved (0)
    vec4 EtnaWaterColor; // 192. rgb: colour of the water's fog, w: how far you see under water, in blocks (0 when vanilla's fog is kept: Etna off, out of water, Blindness, Darkness, boss fog)
    vec4 EtnaWaterLook;  // 208. settings menu. x: water clarity, y: caustics, z: underwater light rays, w: reserved (0)
    vec4 EtnaSkyColor;   // 224. rgb: colour of the overworld sky, dimmed by rain (0 in the Nether and the End), seen through the water surface from below, w: how open the water surface above the camera is to the sky (0 a flooded cave .. 1 the open sea; 0 out of water)
    vec4 EtnaDark;       // 240. x: cave light (0 .. 0.325 in lightmap units: a quarter of the setting's square root, more with Visibility, EtnaConfig.caveLightFloor; 0 without a sun), y: glowing blocks, z: reserved (0), w: 1, fading to 0 as Darkness sets in, 0 under Blindness
    vec4 EtnaHand;       // 256. the light held in the player's hand (EtnaTouch.java). rgb: its tint over Etna's flame colour (1, 1, 1 for fire), w: its light level (0 .. 15; 0 with nothing that gives light in either hand, and in spectator mode)
    vec4 EtnaHandPos;    // 272. xyz: where that light is, relative to the camera, w: how wet the ground is (0 .. 1: soaks up in rain, dries slowly after it)
    vec4 EtnaSky0;       // 288. rgb: colour of the direct light (the sun's from Etna's sky model, the moon's at night), luma 1, w: weather (0 clear, 1 rain, 2 thunder)
    vec4 EtnaSky1;       // 304. rgb: colour of the light from the whole sky (EtnaSkyModel.skylight), luma 1, w: the moon's brightness by phase (0 new .. 1 full); all 0 without a sun
    vec4 EtnaCloud;      // 320. vanilla's clouds, for their shadows (EtnaClouds.java). x: height of the middle of the cloud layer above the camera, in blocks, y/z: where the camera is on vanilla's cloud texture (u, v, 0 .. 1, drifting with the clouds), w: strength of the cloud shadows (0: none, with clouds off, without a sun, with the setting at 0)
    vec4 EtnaSkySunLow;  // 336. sky.glsl's EtnaSkyFrame for this frame, worked out once by EtnaSkyModel.java: rgb sunLow, w rain (0 .. 1)
    vec4 EtnaSkySunHigh; // 352. rgb: sunHigh, w: thunder (0 .. 1)
    vec4 EtnaSkyMulti;   // 368. rgb: multi, w: glow
    vec4 EtnaSkyMoonLow; // 384. rgb: moonLow, w: 0
    vec4 EtnaSkyMoonHigh;// 400. rgb: moonHigh, w: 0
    vec4 EtnaSkyOvercast;// 416. rgb: overcast, w: 1 while Etna draws the sky (so the sky behind the land is the model's), else 0
    mat4 EtnaShadowMat2; // 432. the second shadow map (EtnaShadowSampler2), as EtnaShadowMat for the first, while the terrain fades from one to the other as the sun moves (EtnaShadows.chooseMap); EtnaShadowMat the rest of the time
    vec4 EtnaShadowFade; // 496. x: how much of the second map shadowVisibility and leafBacklight mix in (0 .. 0.5; 0: the first alone), y: the Nether's lava light from below in vanilla's units (0 outside the Nether; EtnaAir.lavaLight, terrain_dark.glsl), z: the lava sea's surface as a world height, w: reserved (0)
    vec4 EtnaSurface;    // 512. settings menu (EtnaMaterials.java). x: materials, y: puddles, z: sparkles, w: glowing flowers (each 0 .. 1)
    vec4 EtnaSurface2;   // 528. x: how much the bright specks of glowing blocks glow (the glowing blocks setting, 0 .. 1), y: the share of the screen's width the level is drawn at (EtnaScale: 1 at full size), zw: this frame's anti-aliasing jitter, in the level's pixels (EtnaFrame: x right, y down; 0 without anti-aliasing)
    vec4 EtnaNight;      // 544. the aurora's light on the land (EtnaSky.writeNight). rgb: its colour, luma 1 (a green with a little violet), w: how much aurora is in the sky (0 .. 1, eased over the biome, the moon, the night and the weather; 0 by day, in rain, outside the overworld and with Etna or the Aurora setting off)
    vec4 EtnaWaterSsr;   // 560. settings menu (EtnaWaterSsr.java). x: shoreline foam (0 .. 1), y: glitter (0 .. 1); for the reflections pass (water_ssr.fsh): z: estimate of etnaDaylight, the lightmap under the open sky (0 .. 1), w: the camera's height above a whole block (camera y - floor(camera y))
};
#endif

// Light and settings. Opaque and cutout terrain gets them from EtnaShadows.java; draws without the
// block use a sun high in the sky and the default look, so water and glass still match the land
// around them. Those draws are the translucent passes with improved transparency, but also every
// draw while Etna is switched off with F7: the texture bumps, the sheen and the warm torch colour
// stay off there, so switching Etna off doesn't leave them on at their default strength and moving
// their sliders to 0 really takes them away. A sheen here would sit in the wrong place anyway,
// since this sun never moves. The cost: water and glass drawn with improved transparency keep
// vanilla's torch colour while the blocks around them take the warmer one.
#if defined(ETNA_SHADOWS) || defined(ETNA_CLOUD_LIGHT) || defined(ETNA_WEATHER_LIGHT)
#define ETNA_LIGHT_DIR EtnaLightDir.xyz
#define ETNA_LIGHT_STRENGTH EtnaLightDir.w
#define ETNA_SHADOW_DARKNESS EtnaLook.y
#define ETNA_SUNLIGHT EtnaLook.z
#define ETNA_HAZE EtnaLook.w
#define ETNA_WATER_REFLECTIONS EtnaLook2.x
#define ETNA_WARMTH EtnaLook2.y
#define ETNA_FACE_SHADE EtnaFaceShade
#define ETNA_MOON (EtnaWorld.z > 0.5)
#define ETNA_SUN_WORLD EtnaWorld.x
#define ETNA_RAIN EtnaWorld.w
#define ETNA_SURFACE_DETAIL EtnaMaterial.x
#define ETNA_SHINE EtnaMaterial.y
#define ETNA_LEAF_GLOW EtnaMaterial.z
#define ETNA_TORCH_WARMTH EtnaMaterial.w
#define ETNA_WETNESS EtnaHandPos.w
// The level's render scale (EtnaScale); 0 only in a block written before 0.9.0, read as full size.
#define ETNA_RENDER_SCALE (EtnaSurface2.y > 0.0 ? EtnaSurface2.y : 1.0)
#define ETNA_JITTER_NOW EtnaSurface2.zw
// Etna's sky model (sky.glsl): whether it describes this frame's sky (its skylight is never black, and
// EtnaSky.java leaves every sky field at 0 when it has no model: no overworld sky, or the model failed),
// the colours of the sunlight and the skylight, and whether Etna drew the sky this frame.
#define ETNA_SKY_MODEL (EtnaSky1.r + EtnaSky1.g + EtnaSky1.b > 0.0)
#define ETNA_SKY_SUNLIGHT EtnaSky0.rgb
#define ETNA_SKY_SKYLIGHT EtnaSky1.rgb
#define ETNA_SKY_DRAWN (EtnaSkyOvercast.w > 0.5)
#else
#define ETNA_LIGHT_DIR vec3(0.3419, 0.8547, 0.3907)
#define ETNA_LIGHT_STRENGTH smoothstep(0.35, 0.9, etnaDaylight)
#define ETNA_SHADOW_DARKNESS 0.5
#define ETNA_SUNLIGHT 1.0
#define ETNA_HAZE 0.35
#define ETNA_WATER_REFLECTIONS 0.75
#define ETNA_WARMTH 0.0
#define ETNA_FACE_SHADE vec4(0.5, 1.0, 0.8, 0.6)
#define ETNA_MOON false
// No telling the dimension or the weather here: a sunlit world and dry.
#define ETNA_SUN_WORLD 1.0
#define ETNA_RAIN 0.0
#define ETNA_SURFACE_DETAIL 0.0
#define ETNA_SHINE 0.0
#define ETNA_LEAF_GLOW 0.6
#define ETNA_TORCH_WARMTH 0.0
#define ETNA_WETNESS 0.0
#define ETNA_RENDER_SCALE 1.0
#define ETNA_JITTER_NOW vec2(0.0)
// No sky model here: 0.6.0's colours (sunColor and skyColor in terrain.fsh), and vanilla's fog colour.
#define ETNA_SKY_MODEL false
#define ETNA_SKY_DRAWN false
#endif
