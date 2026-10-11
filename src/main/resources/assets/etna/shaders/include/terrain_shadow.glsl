// Everything the terrain reads Etna's shadow map for: shadows on terrain, single lookups (the water uses
// them for its light shafts) and the light through leaves. The light shafts in the air are a post pass
// (post/shafts.fsh) with its own lookups. Included by terrain.fsh after its own helpers (sunColor,
// etnaDither) and after shadow_distortion.glsl; terrain_hooks.glsl declares these functions. Only
// shadow-receiving draws (ETNA_SHADOWS) have the map.
#ifdef ETNA_SHADOWS
// The shadow filter and the light through leaves read the map four texels at a time with
// textureGather, where 0.5.0 fetched every texel on its own: the same texels, weights and depths for a
// quarter of the reads. Under #version 330 the gather needs GL_ARB_texture_gather (core from 4.00);
// terrain.fsh enables it at its top, and the line below enables it again (legal) for any other shader
// that includes this file. A compiler without it, or a pipeline built with ETNA_SHADOW_FETCH defined
// (shadowGather=false in etna.properties), reads one texel at a time as 0.5.0 did.
#if defined(GL_ARB_texture_gather) && !defined(ETNA_SHADOW_FETCH)
#extension GL_ARB_texture_gather : enable
#define ETNA_SHADOW_GATHER
#endif

// Size of a shadow texel at this spot of the map, in blocks, along its coarser (radial) direction.
float etnaShadowTexel(float factor) {
    float texelBlocks = 2.0 * EtnaShadowInfo.y * EtnaShadowInfo.x;
    return texelBlocks * factor * factor / (1.0 - ETNA_SHADOW_DISTORTION);
}

// One column or row of shadowVisibility's filter, for texel index `t` along that axis: x is how much of
// the texel the filter's square [lo, hi] covers along it, y how far the texel's centre lies from the
// filter's centre, in texels. Worked out once per column and once per row, then shared by their texels.
vec2 etnaFilterAxis(int t, float lo, float hi, float center) {
    return vec2(max(min(hi, float(t + 1)) - max(lo, float(t)), 0.0), float(t) + 0.5 - center);
}

// One texel of the filter, from its column and its row (etnaFilterAxis): x is the part of it that is
// lit, y its weight, the share of the texel the square covers; z is the part of it that is blocked and
// w that times how far in front of the receiver the blocker lies (in depth units), which the contact
// hardening below uses to tell how far the shadow has reached from what casts it. The texel counts as
// blocked when the map holds something closer to the light there than the receiver plane at the
// texel's centre, by more than the bias. Both ways of reading the map add up their texels through this.
vec4 etnaFilterTexel(float stored, vec2 column, vec2 row, float depth, vec2 slope, float bias) {
    float w = column.x * row.x;
    // Reversed depth: bigger is closer to the light.
    float front = stored - depth - dot(slope, vec2(column.y, row.y));
    float blocked = front > bias ? w : 0.0;
    return vec4(w - blocked, w, blocked, blocked * front);
}

#ifdef ETNA_SHADOW_GATHER
// Four texels of the filter in one read: the 2x2 block from texel (x, y) to (x + 1, y + 1). The gather
// is taken at the corner the four texels share, half a texel from the nearest neighbouring block, so no
// rounding in the texture unit can pick another one. Its components are the block's texels (0, 1),
// (1, 1), (1, 0) and (0, 0), in that order. The map's sampler clamps to the edge, as the texelFetch
// loop clamps its indices.
vec4 etnaFilterGather(sampler2D map, int x, int y, vec2 lo, vec2 hi, vec2 center, float depth, vec2 slope, float bias) {
    vec4 stored = textureGather(map, vec2(x + 1, y + 1) * max(EtnaShadowInfo.x, 1e-6));
    vec2 column0 = etnaFilterAxis(x, lo.x, hi.x, center.x);
    vec2 column1 = etnaFilterAxis(x + 1, lo.x, hi.x, center.x);
    vec2 row0 = etnaFilterAxis(y, lo.y, hi.y, center.y);
    vec2 row1 = etnaFilterAxis(y + 1, lo.y, hi.y, center.y);
    return etnaFilterTexel(stored.w, column0, row0, depth, slope, bias)
        + etnaFilterTexel(stored.z, column1, row0, depth, slope, bias)
        + etnaFilterTexel(stored.x, column0, row1, depth, slope, bias)
        + etnaFilterTexel(stored.y, column1, row1, depth, slope, bias);
}
#endif

// ---- Contact hardening ------------------------------------------------------------------------------
// Real shadows are sharp where they meet what casts them and blur the further they reach from it: the sun
// is a disc, not a point, so the edge's blur grows with the distance between the caster and the ground.
// A tree's shadow is crisp at its trunk and a soft smudge at its far end, and a long evening shadow
// loses its detail and fades out towards its tip. The idea is percentage-closer soft shadows (Randima
// Fernando, "Percentage-Closer Soft Shadows", NVIDIA, 2005), written here for Etna's map. The box filter
// below reads the texels round the point anyway; it and SHADOW_SEARCH reads further out find how far in
// front of the point, towards the light, the casters lie on average. The blur's radius is the shadow
// softness setting plus SHADOW_SPREAD of that distance, times how far the setting lies above its lowest
// (0.25: hard shadow edges stay hard, and skip all of this). The near part of a shadow keeps the exact
// area-weighted box filter, the wide part reads SHADOW_DISK points spread over a disc of that radius, and
// the two blend where the blur widens.
//
// Every read is the bilinear blend of the four texels round it, and the search's reads sit at the same
// place round the point in every frame, so how wide the blur is only changes when the point, the sun or
// the casters move, and then smoothly. So does the disc while it is narrow. A disc wider than about
// eight texels has gaps between its reads that the edge shows through as faint copies of itself; it is
// turned from pixel to pixel and frame to frame (etnaDither), by more the wider it is, and the anti-
// aliasing averages the turns into a smooth blur. 0.8.1's first version turned every read, the search's
// too, and gave up where its few reads found nothing in the way: the anti-aliasing could not settle
// that, and penumbras shimmered.

// Blocks of blur radius per block between the caster and the ground, per unit of shadow softness above
// its lowest: at the default 1.3 about five times the real sun's, which looks right at Minecraft's scale.
const float SHADOW_SPREAD = 0.025;
// The widest blur, in blocks and in texels of the map: the casters are looked for this far round the
// point. The wider the disc in texels, the more of its smoothness rests on the anti-aliasing. At the
// default map size and distance a texel near the camera is about a sixtieth of a block, so the blur
// there still reaches a quarter of a block; from about 35 blocks out the 1.2 blocks set the limit.
const float SHADOW_SPREAD_BLOCKS = 1.2;
const float SHADOW_SPREAD_TEXELS = 16.0;
// Where the reads lie, as fractions of the search radius and of the blur's radius. Both sets are point
// symmetric (each read has its opposite), so a receiver plane that is a little off cancels out. The search
// covers the ring beyond the box filter; the disc's sixteen points sit on eight rings of equal area.
const int SHADOW_SEARCH = 6;
const vec2 SHADOW_SEARCH_TAPS[6] = vec2[6](
    vec2(0.5401, 0.0000), vec2(0.0663, -0.7554), vec2(-0.9123, -0.1614),
    vec2(-0.5401, 0.0000), vec2(-0.0663, 0.7554), vec2(0.9123, 0.1614));
const int SHADOW_DISK = 16;
const vec2 SHADOW_DISK_TAPS[16] = vec2[16](
    vec2(0.2500, 0.0000), vec2(-0.3193, 0.2925), vec2(0.0489, -0.5569), vec2(0.4024, 0.5249),
    vec2(-0.7385, -0.1306), vec2(0.6996, -0.4450), vec2(-0.2340, 0.8705), vec2(-0.4463, -0.8593),
    vec2(-0.2500, -0.0000), vec2(0.3193, -0.2925), vec2(-0.0489, 0.5569), vec2(-0.4024, -0.5249),
    vec2(0.7385, 0.1306), vec2(-0.6996, 0.4450), vec2(0.2340, -0.8705), vec2(0.4463, 0.8593));

// The 2x2 texels round corner `corner` of the map (in texels; texel i spans i .. i + 1), in
// textureGather's order: (-1, 0), (0, 0), (0, -1) and (-1, -1) from the corner.
vec4 etnaShadowQuad(sampler2D map, vec2 corner) {
    #ifdef ETNA_SHADOW_GATHER
    return textureGather(map, corner * max(EtnaShadowInfo.x, 1e-6));
    #else
    ivec2 c = ivec2(corner);
    ivec2 last = textureSize(map, 0) - 1;
    return vec4(texelFetch(map, clamp(c + ivec2(-1, 0), ivec2(0), last), 0).r,
        texelFetch(map, clamp(c, ivec2(0), last), 0).r,
        texelFetch(map, clamp(c + ivec2(0, -1), ivec2(0), last), 0).r,
        texelFetch(map, clamp(c - 1, ivec2(0), last), 0).r);
    #endif
}

// One read of the search or the disc at `q` (in texels), the bilinear blend of the four texels round it,
// each compared with the receiver plane (depth at `center`, changing by `slope` per texel) at its own
// centre: as etnaFilterTexel, x the lit share, y the weight (1), z the blocked share and w that times how
// far in front the blockers lie.
vec4 etnaShadowTap(sampler2D map, vec2 q, vec2 center, float depth, vec2 slope, float bias) {
    vec2 corner = floor(q - 0.5) + 1.0;
    vec2 f = q - (corner - 0.5);
    float here = depth + dot(slope, corner - center);
    vec4 plane = here + vec4(dot(slope, vec2(-0.5, 0.5)), dot(slope, vec2(0.5, 0.5)), dot(slope, vec2(0.5, -0.5)), dot(slope, vec2(-0.5, -0.5)));
    // Reversed depth: bigger is closer to the light.
    vec4 front = etnaShadowQuad(map, corner) - plane;
    vec4 blocked = step(vec4(bias), front) * vec4((1.0 - f.x) * f.y, f.x * f.y, f.x * (1.0 - f.y), (1.0 - f.x) * (1.0 - f.y));
    float share = dot(blocked, vec4(1.0));
    return vec4(1.0 - share, 1.0, share, dot(blocked, front));
}

// shadowVisibility on one of the two maps (`map`, drawn through `shadowMat`).
float etnaShadowFilter(sampler2D map, mat4 shadowMat, vec3 normal, bool thin) {
    vec3 undistorted = (shadowMat * vec4(etnaViewPos, 1.0)).xyz;
    float factor = etnaShadowFactor(undistorted.xy);
    // Push the lookup off the surface so faces don't shadow themselves (shadow acne, which shows up
    // as stripes across flat ground), by about a texel of the map at this spot.
    float texelBlocks = etnaShadowTexel(factor);
    float softness = EtnaLook.x;
    vec3 offset = thin ? EtnaLightDir.xyz * texelBlocks * 1.5 : normal * texelBlocks * 1.2;
    vec4 clip = shadowMat * vec4(etnaViewPos + offset, 1.0);
    vec3 ndc = clip.xyz / clip.w;
    float edge = max(abs(ndc.x), abs(ndc.y));
    if (edge >= 1.0) {
        return 1.0;
    }
    factor = etnaShadowFactor(ndc.xy);
    vec2 uv = ndc.xy / factor * 0.5 + 0.5;
    #ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    float depth = ndc.z;
    #else
    float depth = ndc.z * 0.5 + 0.5;
    #endif
    // Beyond the far end of the map's depth range (more than DEPTH_RANGE blocks below the camera along
    // the light: far away under a low sun, or the ground seen from high up) the cleared map holds nothing,
    // and nothing there can be told to be in shadow.
    if (depth <= 0.0) {
        return 1.0;
    }
    float size = 1.0 / max(EtnaShadowInfo.x, 1e-6);
    // Receiver plane: how the surface's own depth in the map changes per texel across the filter.
    // A surface the sun grazes climbs towards the light within a few texels; compared against one
    // flat depth it shadowed itself in stripes and patches (bands on sand, ghostly blotches on walls
    // seen at an angle). Each texel is now compared with the surface's depth at that texel.
    vec2 slopePerTexel = vec2(0.0);
    if (!thin) {
        vec3 a = normalize(cross(normal, abs(normal.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
        vec3 b = cross(normal, a);
        vec3 da = mat3(shadowMat) * a;
        vec3 db = mat3(shadowMat) * b;
        #ifndef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
        da.z *= 0.5;
        db.z *= 0.5;
        #endif
        // Map space: xy in texels (the distortion scales them by about 1 / factor here), z in depth units.
        da.xy *= 0.5 * size / factor;
        db.xy *= 0.5 * size / factor;
        float det = da.x * db.y - db.x * da.y;
        if (abs(det) > 1e-6) {
            slopePerTexel = vec2(db.y * da.z - da.y * db.z, da.x * db.z - db.x * da.z) / det;
        }
        // Nearly edge-on to the sun the plane is useless (and lit by ndl ~ 0 anyway): keep it bounded.
        float limit = 8.0 * texelBlocks * EtnaShadowInfo.z;
        slopePerTexel = clamp(slopePerTexel, -limit, limit);
    }
    // Reversed depth: bigger is closer to the light.
    float bias = EtnaShadowInfo.z * (thin ? 0.03 + texelBlocks * softness : 0.03);
    // Area-weighted box filter: every shadow map texel under a square 2 * softness texels wide counts by
    // how much of it the square covers, so an edge slides smoothly as the sun moves instead of flipping
    // a texel at a time. The square stays put from frame to frame: moved by the same fraction of a
    // texel everywhere at once, it shifted whole penumbras by a few pixels each frame, and the
    // anti-aliasing pass, which keeps only the history that fits this frame's neighbouring pixels,
    // dropped the averaged edge and let them flicker.
    vec2 center = uv * size;
    vec2 lo = center - softness;
    vec2 hi = center + softness;
    ivec2 first = ivec2(floor(lo));
    vec4 box = vec4(0.0);
    #ifdef ETNA_SHADOW_GATHER
    // The square overlaps at most 2 texels a side up to softness 0.5, 4 up to 1.5, 6 up to 2.5, 8 up to
    // 3.5 and 9 up to 4, the most the menu allows. So one gather, or blocks of 2x2 to 5x5 gathers from
    // `first`, hold every texel the loop below gives any weight, and the ones past the square's edge
    // weigh nothing. The softness is the same for every pixel, so they all take the same branch.
    if (softness <= 0.5) {
        box = etnaFilterGather(map, first.x, first.y, lo, hi, center, depth, slopePerTexel, bias);
    } else if (softness <= 1.5) {
        for (int y = 0; y < 4; y += 2) {
            for (int x = 0; x < 4; x += 2) {
                box += etnaFilterGather(map, first.x + x, first.y + y, lo, hi, center, depth, slopePerTexel, bias);
            }
        }
    } else if (softness <= 2.5) {
        for (int y = 0; y < 6; y += 2) {
            for (int x = 0; x < 6; x += 2) {
                box += etnaFilterGather(map, first.x + x, first.y + y, lo, hi, center, depth, slopePerTexel, bias);
            }
        }
    } else {
        // Only custom settings get here: 4x4 gathers up to softness 3.5, 5x5 beyond. The count is not a
        // constant, so the compiler keeps this a loop instead of writing out up to 100 texels, which
        // would make the whole shader bigger and hungrier for registers at every softness. The loop
        // below stops at 9 texels a side (first + 8): here the tenth row and column count for nothing,
        // whatever the softness.
        int span = softness <= 3.5 ? 8 : 10;
        vec2 capped = min(hi, vec2(first + 9));
        for (int y = 0; y < span; y += 2) {
            for (int x = 0; x < span; x += 2) {
                box += etnaFilterGather(map, first.x + x, first.y + y, lo, capped, center, depth, slopePerTexel, bias);
            }
        }
    }
    #else
    ivec2 last = min(ivec2(floor(hi)), first + 8);
    for (int y = first.y; y <= last.y; y++) {
        vec2 row = etnaFilterAxis(y, lo.y, hi.y, center.y);
        for (int x = first.x; x <= last.x; x++) {
            float stored = texelFetch(map, clamp(ivec2(x, y), ivec2(0), ivec2(size) - 1), 0).r;
            box += etnaFilterTexel(stored, etnaFilterAxis(x, lo.x, hi.x, center.x), row, depth, slopePerTexel, bias);
        }
    }
    #endif
    float lit = box.x / max(box.y, 1e-4);
    // Contact hardening: how far the casters lie from here sets how wide the blur is.
    float spread = SHADOW_SPREAD * (softness - 0.25);
    if (spread > 0.0) {
        float search = clamp(SHADOW_SPREAD_BLOCKS / texelBlocks, softness + 1.0, SHADOW_SPREAD_TEXELS);
        vec4 found = box;
        for (int i = 0; i < SHADOW_SEARCH; i++) {
            found += etnaShadowTap(map, center + SHADOW_SEARCH_TAPS[i] * search, center, depth, slopePerTexel, bias);
        }
        // Everything read is in the way (deep inside a shadow) or nothing is (in the open): the disc
        // would find the same, so it is only read where the two meet.
        if (found.z > 0.0 && found.x > 0.0) {
            float apart = found.w / (found.z * max(EtnaShadowInfo.z, 1e-6));
            float radius = min(softness + spread * apart / texelBlocks, search);
            // A blocker only grazed by the reads (or a gap only grazed, deep in a shadow) widens the blur
            // only that much, so the disc fades in where it starts being read.
            float wide = smoothstep(softness + 0.5, softness + 1.5, radius) * smoothstep(0.0, 0.5, min(found.x, found.z));
            if (wide > 0.0) {
                // The disc is point symmetric, so half a turn covers every way it can lie.
                float angle = etnaDither() * 3.14159265 * smoothstep(4.0, 8.0, radius);
                mat2 turn = mat2(cos(angle), sin(angle), -sin(angle), cos(angle)) * radius;
                float disk = 0.0;
                for (int i = 0; i < SHADOW_DISK; i++) {
                    disk += etnaShadowTap(map, center + turn * SHADOW_DISK_TAPS[i], center, depth, slopePerTexel, bias).x;
                }
                lit = mix(lit, disk / float(SHADOW_DISK), wide);
            }
        }
    }
    // Fade out towards the edge of the area the map covers.
    return mix(lit, 1.0, smoothstep(0.85, 1.0, edge));
}

// A low sun casts long shadows, and the sky, which it no longer outshines, fills them in: below about 20
// degrees shadows fade, towards LOW_SUN_FILL lighter as the sun (or the moon) meets the horizon. Before
// 0.8.1 they kept their full darkness from 10 degrees up, at three to six times the length of what cast
// them.
const float LOW_SUN_FILL = 0.35;

float etnaLowSunFill() {
    return LOW_SUN_FILL * (1.0 - smoothstep(0.05, 0.35, ETNA_LIGHT_DIR.y));
}

// The shadow pass draws its casters from whole chunk columns round the camera (EtnaShadows.collectCasters):
// the shadow distance plus 14 blocks, rounded up to whole 16-block sections, along world x and z, so at
// least that far from the camera on every side. Under a sun below about 60 degrees the map reaches
// further than that towards and away from the sun, where nothing was drawn into it, and shadows stopped
// at a straight line there. They fade out before it instead. How much of the way to lit, for a
// camera-relative position.
float etnaCasterFade(vec3 position) {
    float reach = 16.0 * ceil((EtnaShadowInfo.y + 14.0) / 16.0);
    float away = max(abs(position.x), abs(position.z));
    return smoothstep(reach - 24.0, reach - 4.0, away);
}

// 1 where the sun reaches this fragment, 0 where something closer to the sun blocks it. The map holds
// the sun's direction still while the sun creeps on, then the terrain fades over to a second map drawn
// along its new direction (EtnaShadows.chooseMap): turned with the sun every frame, a map moved shadow
// edges a whole texel at a time, here and there along them, and they shimmered all day. While the two
// fade, both are filtered and mixed; the rest of the time only the first is read (the same branch for
// every pixel).
float shadowVisibility(vec3 normal, bool thin) {
    float lit = etnaShadowFilter(EtnaShadowSampler, EtnaShadowMat, normal, thin);
    if (EtnaShadowFade.x > 0.0) {
        lit = mix(lit, etnaShadowFilter(EtnaShadowSampler2, EtnaShadowMat2, normal, thin), EtnaShadowFade.x);
    }
    return mix(lit, 1.0, max(etnaLowSunFill(), etnaCasterFade(etnaViewPos)));
}

// shadowSpace for a point already in the map's clip space (EtnaShadowMat applied; the projection is
// orthographic, so w is 1): the distortion and the depth range, which are the same for every caller.
vec4 etnaShadowMapPoint(vec3 ndc) {
    #ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    float depth = ndc.z;
    #else
    float depth = ndc.z * 0.5 + 0.5;
    #endif
    return vec4(ndc.xy / etnaShadowFactor(ndc.xy) * 0.5 + 0.5, depth, max(abs(ndc.x), abs(ndc.y)));
}

// Where a camera-relative point lands in the shadow map, without filtering. xy: its texture coordinates
// in the (distorted) map; z: the depth the map stores for something at that point (reversed: bigger is
// closer to the light); w: how far out in the map it lies, the larger of its undistorted |x| and |y|:
// 0 at the centre, 1 at the edge of the area the map covers, above 1 outside it, where xy and z mean
// nothing.
vec4 shadowSpace(vec3 position) {
    vec4 clip = EtnaShadowMat * vec4(position, 1.0);
    return etnaShadowMapPoint(clip.xyz / clip.w);
}

// The unfiltered test in `map` for a point from shadowSpace (or the same for the second map): 1 lit, 0 in
// shadow.
float etnaShadowTest(sampler2D map, vec4 point) {
    if (point.w >= 1.0 || point.z <= 0.0) {
        return 1.0;
    }
    float stored = texture(map, point.xy).r;
    return stored > point.z + EtnaShadowInfo.z * 0.05 ? 0.0 : 1.0;
}

// The unfiltered test for a camera-relative point in the second map, the one the terrain fades to.
float etnaShadowTest2(vec3 position) {
    vec4 clip = EtnaShadowMat2 * vec4(position, 1.0);
    return etnaShadowTest(EtnaShadowSampler2, etnaShadowMapPoint(clip.xyz / clip.w));
}

// Shadow map lookup for a camera-relative point, without filtering: 1 lit, 0 in shadow. While the two
// maps fade, both are read and mixed as shadowVisibility mixes its filters, so the glints on the water
// and the light on a deep sea floor glide with the shadows instead of stepping when the second map
// takes over.
float shadowAt(vec3 position) {
    float lit = etnaShadowTest(EtnaShadowSampler, shadowSpace(position));
    if (EtnaShadowFade.x > 0.0) {
        lit = mix(lit, etnaShadowTest2(position), EtnaShadowFade.x);
    }
    return mix(lit, 1.0, etnaCasterFade(position));
}

// The test for a point from shadowSpace (or the same for the second map) with the four texels round it
// blended bilinearly, so it slides smoothly as the point moves instead of stepping a texel at a time, and
// fading out towards the map's edge as shadowVisibility does: for averages of a few points (the light in
// the water under its surface, waterBodyShadow), where single texels would show as steps.
float etnaShadowSoft(sampler2D map, vec4 point) {
    if (point.w >= 1.0 || point.z <= 0.0) {
        return 1.0;
    }
    vec2 q = point.xy / max(EtnaShadowInfo.x, 1e-6);
    vec2 corner = floor(q - 0.5) + 1.0;
    vec2 f = q - (corner - 0.5);
    // Reversed depth: open where the map holds nothing closer to the light than the point, by the bias.
    vec4 open = step(etnaShadowQuad(map, corner), vec4(point.z + EtnaShadowInfo.z * 0.05));
    float lit = dot(open, vec4((1.0 - f.x) * f.y, f.x * f.y, f.x * (1.0 - f.y), (1.0 - f.x) * (1.0 - f.y)));
    return mix(lit, 1.0, smoothstep(0.85, 1.0, point.w));
}

// etnaShadowSoft for a camera-relative point, from the two maps while they fade, and as light as
// shadowVisibility makes shadows under a low sun and towards the end of the casters.
float shadowSoftAt(vec3 position) {
    float lit = etnaShadowSoft(EtnaShadowSampler, shadowSpace(position));
    if (EtnaShadowFade.x > 0.0) {
        vec4 clip = EtnaShadowMat2 * vec4(position, 1.0);
        lit = mix(lit, etnaShadowSoft(EtnaShadowSampler2, etnaShadowMapPoint(clip.xyz / clip.w)), EtnaShadowFade.x);
    }
    return mix(lit, 1.0, max(etnaLowSunFill(), etnaCasterFade(position)));
}

// shadowAt for one of many jittered samples that are averaged (the light shafts under water): it reads
// one map only, the second when `pick` (0 .. 1, a different value for each sample) is below the fade's
// weight, so the average mixes the two maps as shadowAt does, for one read a sample.
float shadowAtSample(vec3 position, float pick) {
    return pick < EtnaShadowFade.x ? etnaShadowTest2(position) : etnaShadowTest(EtnaShadowSampler, shadowSpace(position));
}

// How much sunlight makes it through the leaves to this leaf's far side, 0 .. 1. Leaves let light
// through, so the shadow map is read as a thickness rather than a wall: everything between the first
// thing the sun meets and this leaf counts as leaves, each block passing a little over half the
// light (one block 0.57, three 0.19; a hill or a house dozens of blocks deep lets nothing through).
// The point read sits 0.3 blocks into the leaf's own block, so that block costs about one block of
// light whatever the sun's height, and thin canopies glow all the way through. An area-weighted box
// two texels wide keeps it steady while waving leaves and the moving sun shift the map underneath:
// one texel changing moves it by at most a quarter of its step. Nine texels, read in four gathers.
float etnaLeafThrough(sampler2D map, mat4 shadowMat) {
    vec4 clip = shadowMat * vec4(etnaViewPos + EtnaLightDir.xyz * 0.3, 1.0);
    vec3 ndc = clip.xyz / clip.w;
    float edge = max(abs(ndc.x), abs(ndc.y));
    if (edge >= 1.0) {
        return 1.0;
    }
    #ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    float depth = ndc.z;
    #else
    float depth = ndc.z * 0.5 + 0.5;
    #endif
    if (depth <= 0.0) {
        return 1.0;
    }
    float size = 1.0 / max(EtnaShadowInfo.x, 1e-6);
    vec2 center = (ndc.xy / etnaShadowFactor(ndc.xy) * 0.5 + 0.5) * size;
    vec2 lo = center - 1.0;
    vec2 hi = center + 1.0;
    ivec2 first = ivec2(floor(lo));
    float bias = EtnaShadowInfo.z * 0.05;
    float blocksPerUnit = 1.0 / max(EtnaShadowInfo.z, 1e-6);
    #ifdef ETNA_SHADOW_GATHER
    // The 3x3 texels from `first` in four gathers (see etnaFilterGather), row by row: the two lower
    // gathers hold rows 0 and 1, the two upper ones row 2. Their fourth row and column are not used.
    float gatherStep = max(EtnaShadowInfo.x, 1e-6);
    vec4 lower = textureGather(map, vec2(first + 1) * gatherStep);
    vec4 lowerRight = textureGather(map, vec2(first + ivec2(3, 1)) * gatherStep);
    vec4 upper = textureGather(map, vec2(first + ivec2(1, 3)) * gatherStep);
    vec4 upperRight = textureGather(map, vec2(first + 3) * gatherStep);
    vec3 rows[3] = vec3[](vec3(lower.wz, lowerRight.w), vec3(lower.xy, lowerRight.x), vec3(upper.wz, upperRight.w));
    #endif
    float through = 0.0;
    for (int y = 0; y < 3; y++) {
        float wy = max(min(hi.y, float(first.y + y + 1)) - max(lo.y, float(first.y + y)), 0.0);
        for (int x = 0; x < 3; x++) {
            float wx = max(min(hi.x, float(first.x + x + 1)) - max(lo.x, float(first.x + x)), 0.0);
            #ifdef ETNA_SHADOW_GATHER
            float stored = rows[y][x];
            #else
            float stored = texelFetch(map, clamp(first + ivec2(x, y), ivec2(0), ivec2(size) - 1), 0).r;
            #endif
            // Reversed depth: how far the first thing towards the sun lies in front of this point.
            float blocks = max(stored - depth - bias, 0.0) * blocksPerUnit;
            through += wx * wy * exp2(-0.8 * blocks);
        }
    }
    // The box covers four texels' worth of area.
    through *= 0.25;
    return mix(through, 1.0, smoothstep(0.85, 1.0, edge));
}

// The light through the leaves, from the two maps while they fade, as shadowVisibility mixes them.
float leafBacklight() {
    float through = etnaLeafThrough(EtnaShadowSampler, EtnaShadowMat);
    if (EtnaShadowFade.x > 0.0) {
        through = mix(through, etnaLeafThrough(EtnaShadowSampler2, EtnaShadowMat2), EtnaShadowFade.x);
    }
    return through;
}
#endif
