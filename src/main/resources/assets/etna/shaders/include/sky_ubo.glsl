#ifndef ETNA_SKY_UBO_GLSL
#define ETNA_SKY_UBO_GLSL

// The block Etna's sky pass reads (core/sky.vsh and sky.fsh): where the sun, the moon and the stars
// are, and the weather. EtnaSky.java writes it once a frame, std140, 96 bytes, in this order.
layout(std140) uniform EtnaSky {
    vec4 SkySun;   // xyz: towards the sun (below the horizon at night), w: weather (0 clear, 1 rain, 2 thunder)
    vec4 SkyMoon;  // xyz: towards the moon, w: the moon's brightness by its phase (0 new .. 1 full)
    vec4 SkyLook;  // x: star brightness (vanilla's, 0 by day, less in rain), y: sun disc (1 clear, 0 in rain), z: seconds, for the twinkle, w: glow (sky.glsl's EtnaSkyFrame.glow)
    vec4 SkyStarX; // xyz: the columns of the rotation from the star sphere to the world (the sky's daily
    vec4 SkyStarY; //   turn with the sun path's tilt)
    vec4 SkyStarZ;
};

#endif
