#ifndef ETNA_INK_GLSL
#define ETNA_INK_GLSL

// The Ink & Colour mood's comic outlines (EtnaMood.java), drawn by the world pass (world_light.fsh): there
// the depth buffer still holds the world alone, and the anti-aliasing right after smooths the lines. In the
// final chain the hand's depth is merged into it (integrate_depth.fsh), and no anti-aliasing would follow.
// Include after settings.glsl.
//
// The depth buffer's value changes at a steady rate across any flat surface on screen (it follows one over
// the distance, which a perspective view keeps linear in screen space), so the difference between a
// pixel's depth and the average of its four neighbours is zero on every face and only shows where the
// surface bends or ends. Only where this pixel is nearer than its neighbours (the outline of an object in
// front of others or of the sky, and the outer edges of blocks) does it count, so every line is drawn on
// the near object's side and stays one pixel wide.

// The relative depth step a line starts at and the one it is fully dark from: the outer edge of a block
// seen corner on makes about 0.003 on a 1080p screen (a line at half strength), an object a block in
// front of another ten blocks away about 0.1, and a flat face nothing.
const vec2 INK_STEP = vec2(0.001, 0.006);
// Lines fade out between these distances in blocks, before the detail they would trace (leaves, grass)
// gets smaller than a pixel and would only add noise. Without anti-aliasing (the plain world chain) nothing
// smooths their steps, so they fade out at half the distance and are drawn lighter.
const vec2 INK_FADE = vec2(24.0, 64.0);
const float INK_PLAIN = 0.6;

// How much of this pixel's light the outline leaves: 1 off a line, down to 1 - Mood5.w on one. depth is
// this pixel's, left, right, below and above its neighbours', blocks how far it is from the camera.
float etnaInk(float depth, float left, float right, float below, float above, float blocks) {
    float bend = (4.0 * depth - left - right - below - above) / max(depth, 1e-6);
    bool smoothed = Frame.w > 0.5;
    vec2 fade = smoothed ? INK_FADE : INK_FADE * 0.5;
    float line = smoothstep(INK_STEP.x, INK_STEP.y, bend) * (1.0 - smoothstep(fade.x, fade.y, blocks));
    return 1.0 - Mood5.w * (smoothed ? 1.0 : INK_PLAIN) * line;
}

#endif
