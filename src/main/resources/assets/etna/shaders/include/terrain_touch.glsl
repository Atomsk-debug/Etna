// The light held in the player's hand, for both terrain stages: terrain.vsh lets it into vanilla's light at
// every vertex, terrain.fsh gives it its colour. EtnaTouch.java fills EtnaHand and EtnaHandPos. Both stages
// include this right after terrain_ubo.glsl.

#ifdef ETNA_SHADOWS
// The held light at a camera-relative position, as a block light level (0 .. 14). Like the light of a placed
// block it drops one level for each block away, but measured straight from the hand rather than block by
// block, so it lights a round pool around the player instead of a diamond. It starts half a block lower:
// the faces right next to a placed torch read the light of the cells around it, a level or so below the
// torch's own. And it never passes 14, the most a face next to a placed level-15 block reads, so a held
// light never outshines a placed one.
float etnaHeldLevel(vec3 position) {
    if (EtnaHand.w <= 0.0) {
        return 0.0;
    }
    return clamp(EtnaHand.w - 0.5 - length(position - EtnaHandPos.xyz), 0.0, 14.0);
}
#endif
