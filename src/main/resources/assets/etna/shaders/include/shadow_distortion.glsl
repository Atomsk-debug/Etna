// How Etna spreads its shadow map over the land: fine texels round the camera, coarser ones further out.
// Etna's own warp (0.8.1). The ground d blocks from a camera above it is seen ever more edge-on, so along
// the line of sight a pixel there covers about d^2 times as much of it as right below the camera, and a
// shadow texel can grow about as fast before it shows. The warp r' = r / (k r + 1 - k) of the map's
// square gives that: its slope, (1 - k) / (k r + 1 - k)^2, falls as (r + c)^-2 with c = (1 - k) / k of the
// map's half-width, it enlarges the middle 1 / (1 - k) times, and it leaves r = 1 where it is.
//
// r is a distance of Etna's own, n = sqrt(1 - (1 - x^2)(1 - y^2)): near the middle the product of the two
// brackets is 1 - x^2 - y^2 up to fourth-order terms, so n is the ordinary distance there and shadows are
// as sharp in every direction round the camera; all along the square's edge one bracket is 0, so n is 1
// there and the warp keeps the square's edge where it is and fills the whole map, corners too. In between
// n lies between the larger of |x| and |y| and the ordinary distance, and along every line out from the
// middle it grows, never faster in proportion than the distance itself: the warp keeps every point on its
// line and in its order there, so it never folds. Past the edge (a corner of a caster outside the square) the
// brackets stop at 0 and n stays 1: the warp leaves those points where they are, outside the map.
// terrain.vsh includes this in the shadow pass (ETNA_SHADOW_PASS), terrain.fsh in the shadow-receiving draws
// (ETNA_SHADOWS) and post/shafts.fsh for the light shafts' lookups.

// k above: how strongly the middle of the map is enlarged. EtnaShadows.DISTORTION holds the same value.
const float ETNA_SHADOW_DISTORTION = 0.85;

// The warp's divisor at point p of the map's square (-1 to 1 on each axis, before the warp): the map
// holds p at p / etnaShadowFactor(p). Between 1 - k in the middle and 1 on the square's edge and beyond.
float etnaShadowFactor(vec2 p) {
    vec2 inside = max(1.0 - p * p, 0.0);
    return ETNA_SHADOW_DISTORTION * sqrt(1.0 - inside.x * inside.y) + (1.0 - ETNA_SHADOW_DISTORTION);
}
