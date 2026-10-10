#ifndef ETNA_SKY_UBO_GLSL
#define ETNA_SKY_UBO_GLSL

// The block Etna's sky pass reads (core/sky.vsh and sky.fsh): where the sun, the moon and the stars
// are, the weather, and the night sky's wonders. EtnaSky.java writes it once a frame, std140, 208 bytes,
// in this order. Each field from SkyNight on starts at the byte offset its comment begins with.
layout(std140) uniform EtnaSky {
    vec4 SkySun;     // xyz: towards the sun (below the horizon at night), w: weather (0 clear, 1 rain, 2 thunder)
    vec4 SkyMoon;    // xyz: towards the moon, w: the moon's brightness by its phase (0 new .. 1 full)
    vec4 SkyLook;    // x: star brightness (vanilla's, 0 by day, less in rain), y: sun disc (1 clear, 0 in rain), z: seconds, for the twinkle, the shooting stars, the aurora's drift and the End's nebula (wraps every hour), w: glow (sky.glsl's EtnaSkyFrame.glow)
    vec4 SkyStarX;   // xyz: the columns of the rotation from the star sphere to the world (the sky's daily
    vec4 SkyStarY;   //   turn with the sun path's tilt); SkyStarX is also the axis the sun, the moon and
    vec4 SkyStarZ;   //   the stars turn about
    vec4 SkyNight;   // 96. x: stars (1 drawn, 0 the Night Sky setting is Off), y: Milky Way (0 .. 1), z: nebula colours in it (0 .. 1), w: 0
    vec4 SkyShoot;   // 112. x: seconds between chances of a shooting star (0: none), y: the chance each time (0 .. 1), zw: 0
    vec4 SkyAurora;  // 128. x: how much aurora is in the sky (0 .. 1, eased, already 0 by day and in rain), y: how cold the biome at the camera is (0 .. 1, eased), zw: 0
    vec4 SkyMoonLook;// 144. x: 1 while Etna draws the moon (the Round Moon setting), y: the moon's phase as an angle (0 full, pi new, 0.5 pi third quarter, 1.5 pi first quarter), zw: 0
    vec4 SkyRainbow; // 160. x: how strong the rainbow is (0 .. 1, by the rain that just fell and the sun's height), yzw: 0
    vec4 SkyTint;    // 176. rgb: the biome's tint on the sunlit air (1, 1, 1 for none; Biome Sky Colour), w: 1 in the End (draws the End sky instead), 0 in the overworld
    vec4 SkyEnd;     // 192. xyz: towards the End's flash, w: its strength (0 .. 1; 0 outside the End)
};

#endif
