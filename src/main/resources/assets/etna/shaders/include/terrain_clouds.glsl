// Shadows of vanilla's clouds on the land, for terrain.fsh (EtnaClouds.java). Only the shadow-receiving
// draws (ETNA_SHADOWS) have Etna's block and the cloud texture.
//
// Vanilla builds its clouds from a small texture, clouds.png: one texel per cell of 12 by 12 blocks,
// 4 blocks high, drifting along the x axis (CloudRenderer). The shadow reads the same texture where the
// ray from the fragment towards the light meets the middle of the cloud layer, at the place the clouds
// have drifted to (EtnaCloud.yz, worked out the way CloudRenderer places them), so each shadow lies under
// its own cloud and drifts with it. One bilinear read: it blends neighbouring cells over 12 blocks, which
// the smoothstep narrows to a soft edge a few blocks wide around the outline the boxes cast.

#ifdef ETNA_SHADOWS
uniform sampler2D EtnaCloudSampler;

// The width of vanilla's cloud cells, in blocks.
const float ETNA_CLOUD_CELL = 12.0;

float etnaCloudShadow() {
    vec3 l = ETNA_LIGHT_DIR;
    // How far the middle of the cloud layer is above this fragment: ground up at the clouds' height, or
    // above them, gets no shadow from them.
    float below = EtnaCloud.x - etnaViewPos.y;
    // A low light crosses the cloud layer far away, at a slant through many cells: its shadows would only
    // sweep long stripes over the land, so they fade out as the light sinks.
    float amount = EtnaCloud.w * smoothstep(0.08, 0.30, l.y) * smoothstep(0.0, 4.0, below);
    if (amount <= 0.0) {
        return 1.0;
    }
    // As far out as vanilla draws the clouds: it fades them out by FogCloudsEnd, the shadows go with them.
    amount *= 1.0 - smoothstep(0.6, 1.0, length(etnaViewPos.xz) / max(FogCloudsEnd, 1.0));
    vec2 hit = etnaViewPos.xz + l.xz * (below / l.y);
    vec2 blocks = vec2(textureSize(EtnaCloudSampler, 0)) * ETNA_CLOUD_CELL;
    // The texture repeats, as the clouds do. No mipmaps: one level, with no derivatives needed.
    float cover = textureLod(EtnaCloudSampler, EtnaCloud.yz + hit / blocks, 0.0).a;
    return 1.0 - amount * smoothstep(0.2, 0.8, cover);
}
#endif
