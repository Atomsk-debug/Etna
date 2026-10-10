// Every function terrain.fsh calls in the terrain includes, declared up front: the includes come at the
// end of terrain.fsh, after the helpers they use (LUMA, sunColor, skyColor, directSun, etnaDither,
// vanillaFaceShade, etnaWaterSky). Each line says where terrain.fsh calls it. The bodies live in terrain_shadow.glsl,
// terrain_clouds.glsl, terrain_water.glsl, terrain_dark.glsl and terrain_surface.glsl.

#ifdef ETNA_SHADOWS
// terrain_shadow.glsl: the shadow map. Only shadow-receiving draws (ETNA_SHADOWS) have it.
float shadowVisibility(vec3 normal, bool thin); // shadeTerrain: filtered shadow on this fragment, 1 lit .. 0 shaded
float shadowAt(vec3 position);                  // sunGlint, waterFromBelow, etnaWaterPrepare: unfiltered lookup at a camera-relative point
float shadowAtSample(vec3 position, float pick); // underwaterShafts: the same, one map per jittered sample
vec4 shadowSpace(vec3 position);                // a camera-relative point in the map: xy uv, z expected depth, w how far out (1 at the edge)
float leafBacklight();                          // leafGlow: sunlight through the leaves to this one, 0 .. 1
// terrain_clouds.glsl: the clouds' shadows, in the same draws.
float etnaCloudShadow();                        // shadeTerrain: direct light the clouds let through, 1 in the open .. 1 - strength under a cloud
#endif

// terrain_water.glsl: water around the camera and the fragment.
float cameraInAir();                    // wetness, sunSheen, leafGlow: 1 out of the water, 0 in it
void etnaWaterPrepare();                // main, after the alpha test and before shadeTerrain
bool etnaAirEffects();                  // main: gates aerialHaze()
float etnaSunOpen();                    // shadeTerrain: the gate on the shadow map lookup
vec3 etnaSunReach();                    // skyLighting: open-sky factor on the direct sun
vec3 etnaSkyShare(vec3 skyPart);        // shadeTerrain: the sky's share of vanilla's light
void etnaFinalSkyShare(vec3 skyPart);   // shadeTerrain: the sky's share as skyLighting gets multiplied with it
float etnaShadeOpen();                  // etnaShadeWeight (terrain_dark.glsl): how open to the light from above
vec4 shadeWater(vec4 color, vec3 normal); // main, for water (etnaMaterial 3)
vec3 etnaMediumFog(vec3 color);         // main, after the distance haze and before vanilla's fog
float etnaEnvironmentalFog();           // calculateFinalColor: factor on vanilla's environmental fog

// terrain_dark.glsl: dark places and light from blocks.
float etnaShadeWeight();                // skyLighting: how far the shade darkens the sky light
float etnaTorchBoost();                 // shadeTerrain: factor on the torch light
vec3 etnaLavaLight(vec3 normal);        // shadeTerrain: the Nether's lava light from below, added to the light
vec3 aerialHaze(vec3 color);            // main, when etnaAirEffects(): the distance haze
vec3 etnaGlow(vec3 lit, vec3 albedo, float kind); // shadeTerrain, last: the final lit colour

// terrain_surface.glsl: materials by block type, wet ground and puddles, sparkles, glowing specks and flowers.
float etnaSurfaceBegin(vec3 normal, float wet); // shadeTerrain, after wetness: the class, and the puddle here (0 .. 1)
vec3 etnaSurfaceLight(vec3 lit, vec3 albedo, vec3 light, vec3 normal, vec3 bump, float shadow, vec3 viewDir, float kind); // shadeTerrain: the lit colour of a material, or of shaded leaves
vec3 etnaWetShade(vec3 lit, vec3 normal, vec3 bump, float smoothness, vec3 viewDir, float wet, float puddle); // shadeTerrain, wet ground: the sky mirrored
float etnaSheenShare();                 // shadeTerrain: factor on sunSheen (materials bring their own)
vec3 etnaSurfaceHighlight(vec3 normal, vec3 bump, float smoothness, float shadow, vec3 viewDir, vec3 skyPart, vec3 torch, vec3 albedo, float wet, float puddle); // shadeTerrain: highlights and sparkles
vec3 etnaSurfaceGlow(vec3 lit, vec3 albedo, float kind); // shadeTerrain, after etnaGlow: glowing specks and flowers
