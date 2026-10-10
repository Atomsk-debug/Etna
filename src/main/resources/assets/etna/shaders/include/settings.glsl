#ifndef ETNA_SETTINGS_GLSL
#define ETNA_SETTINGS_GLSL

// Live values for every Etna post pass, in one buffer they all share, rewritten once per frame by
// EtnaLook.java: the camera matrices temporal anti-aliasing and ambient occlusion need, and the settings menu.
// "Position" below means a position relative to the camera, in blocks; "screen" means (uv, depth).
layout(std140) uniform EtnaSettings {
    mat4 InvViewProj;  // screen -> position, this frame
    mat4 ViewProj;     // position -> screen, this frame
    mat4 PrevViewProj; // position -> screen, last frame (positions measured from last frame's camera)
    vec4 Motion;       // xyz: how far the camera moved since last frame, w: 1 if last frame's picture can be reused
    vec4 Frame;        // x: frame counter, y/z: this frame's sub-pixel jitter in pixels, w: 1 when temporal anti-aliasing is on
    vec4 Color;        // x: brightness, y: contrast, z: saturation, w: vibrance
    vec4 Color2;       // x: warmth (-1 cool .. 1 warm), y: visibility (0 .. 1, the final pass's lift of the darkest tones; 0 with Etna off), z: vignette, w: sharpening
    vec4 Glow;         // x: bloom strength, y: unused, z: bloom size, w: unused
    vec4 Shade;        // x: ambient occlusion strength, yzw: colour of the skylight (luma 1) while Etna draws the sky (EtnaSky.java), else 0
    vec4 Sun;          // xyz: direction towards the sun (below the horizon at night), w: strength of the light casting shadows (0 at dusk, in rain, without a sun)
    vec4 Air;          // x: sky glow, y: atmosphere, z: rain (0 .. 1), w: 0 without a sun (Nether, End), 1 by day, 2 when the moon is the light
    vec4 Water;        // x: what the camera is in (0 air, 1 water, 2 lava, 3 powder snow), y: water vision (0 .. 1), z: height of the water surface above the camera in blocks, w: how far you see under water, in blocks (0 when vanilla's fog is kept: Etna off, out of water, Blindness, Darkness, boss fog); y and z are 0 out of water
    vec4 Eye;          // eye adaptation (EtnaEye.java). x: light reaching the eye, eased (0 dark .. 1 daylight), y: exposure the final pass multiplies scene light by (1 leaves it), z: night vision (0 .. 1, how far dim colours fade to a cool grey), w: 0
    vec4 Mist;         // 352. densities of the air per block (air.glsl): x: ground and valley mist at its base, y: night and rain haze at sea level, z: murk deep underground; w: height of the camera above sea level, in blocks. All 0 without a sun, under Blindness and Darkness
    vec4 MistColor;    // 368. rgb: the colour of the mist (vanilla's fog colour), w: the render distance in blocks
    vec4 Shafts;       // 384. x: density of the clear air the light shafts show in, per block, y: how much of the sky above the camera is covered (0 open .. 1 under trees or a roof), z: how deep underground the camera is (0 open air .. 1 a cave), w: the shadow map's radius in blocks when the shafts pass can march it this frame, else 0
    vec4 AirSun;       // 400. rgb: colour of the direct light as the terrain gets it from Etna's sky model (the sun's, or the moon's at night; luma 1), w: 1 while the model has one, else 0 (all 0)
};

const vec3 ETNA_LUMA = vec3(0.2126, 0.7152, 0.0722);

// Reversed depth (1 near, 0 far): nothing was drawn where the depth is still 0.
bool etnaIsSky(float depth) {
    return depth <= 0.0;
}

vec3 etnaPosition(vec2 uv, float depth) {
    vec4 p = InvViewProj * vec4(uv, depth, 1.0);
    return p.xyz / p.w;
}

vec3 etnaScreen(mat4 m, vec3 position) {
    vec4 p = m * vec4(position, 1.0);
    return p.xyz / p.w;
}

// Interleaved gradient noise (Jimenez 2014), shifted every frame when temporal anti-aliasing is on so
// the anti-aliasing pass averages it away.
float etnaNoise(vec2 pixel) {
    if (Frame.w > 0.5) {
        pixel += 5.588238 * mod(Frame.x, 64.0);
    }
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

#endif
