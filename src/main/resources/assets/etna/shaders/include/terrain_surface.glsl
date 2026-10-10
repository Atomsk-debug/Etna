// Surfaces: materials by block type, wet ground and puddles, sparkles on snow, sand and ice, glowing ores,
// crystals and flowers. terrain_hooks.glsl declares the hooks and says where shadeTerrain calls them.
//
// Blocks are told apart by the tag EtnaMaterials.java puts in their vertex alpha as chunks are built, which
// terrain.vsh passes on as etnaMaterial = -class. Everything works from the block's own texture, so it
// follows any resource pack at any resolution: metals mirror the sky tinted by their own colour, gems and
// ore specks glint, polished stone and glass take a soft or sharp reflection of the sky. All of it needs
// Etna's block (ETNA_SHADOWS): draws without it (improved transparency's translucent phases) keep the
// plain look. Every effect only does its work on the pixels it concerns, and fades away where its detail
// would get smaller than a pixel.

// The classes EtnaMaterials tags (MATERIAL_* there): etnaMaterial is minus the class.
const int SURF_METAL = 1;    // iron, gold, copper (with its patina), netherite, chains, lanterns, anvils, cauldrons
const int SURF_GEM = 2;      // diamond, emerald, lapis and amethyst blocks
const int SURF_POLISHED = 3; // polished and smooth stone, quartz, glazed terracotta, obsidian
const int SURF_ORE = 4;      // ores, ancient debris, gilded blackstone, raw ore blocks
const int SURF_GLASS = 5;    // glass and stained glass, blocks and panes
const int SURF_ICE = 6;      // ice, packed ice, blue ice
const int SURF_SNOW = 7;     // snow, snow blocks, powder snow
const int SURF_SAND = 8;     // sand, red sand
const int SURF_GLOWING = 9;  // redstone, crying obsidian, amethyst crystals, glow lichen, sculk, magma

#ifdef ETNA_SHADOWS
#define ETNA_MATERIALS EtnaSurface.x
#define ETNA_PUDDLES EtnaSurface.y
#define ETNA_SPARKLES EtnaSurface.z
#define ETNA_FLOWER_GLOW EtnaSurface.w
#define ETNA_SPECK_GLOW EtnaSurface2.x
#else
#define ETNA_MATERIALS 0.0
#define ETNA_PUDDLES 0.0
#define ETNA_SPARKLES 0.0
#define ETNA_FLOWER_GLOW 0.0
#define ETNA_SPECK_GLOW 0.0
#endif

// What etnaSurfaceBegin works out once per fragment for the hooks after it.
int etnaClass = 0;           // the material class, 0 for untagged blocks
float etnaSpeck = 0.0;       // ores and glowing blocks: how much this texel stands out from its texture, 0 .. 1
vec3 etnaTexel = vec3(0.0);  // ores and glowing blocks: the texel's own colour, straight from the texture
vec3 etnaPuddleNormal = vec3(0.0, 1.0, 0.0); // a puddle's surface, with the rain's rings on it

// Integer hash ("lowbias32", after Chris Wellons, as the wind in terrain.vsh): a value 0 .. 1 from a key.
uint etnaSurfaceMix(uint h) {
    h ^= h >> 16;
    h *= 0x7feb352du;
    h ^= h >> 15;
    h *= 0x846ca68bu;
    h ^= h >> 16;
    return h;
}

// Four values 0 .. 1 for a cell of the world (a texel, a block, a ripple's cell and time slot).
vec4 etnaSurfaceHash(ivec3 cell) {
    uint h = etnaSurfaceMix(uint(cell.x) * 0x9E3779B1u ^ etnaSurfaceMix(uint(cell.y) * 0x85EBCA77u ^ etnaSurfaceMix(uint(cell.z))));
    uint a = etnaSurfaceMix(h + 0x68E31DA4u);
    uint b = etnaSurfaceMix(h + 0xB5297A4Du);
    uint c = etnaSurfaceMix(h + 0x1B56C4E9u);
    return vec4(uvec4(h, a, b, c)) * (1.0 / 4294967296.0);
}

// Smooth gradient noise over the ground (Perlin's, with his quintic fade), about -0.7 .. 0.7, for where
// puddles lie: rounder blobs than value noise, which left squares and straight streaks at a threshold.
float etnaPuddleNoise(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = p - cell;
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    ivec2 c = ivec2(cell);
    float a = etnaSurfaceHash(ivec3(c, 17)).x * 6.2831853;
    float b = etnaSurfaceHash(ivec3(c + ivec2(1, 0), 17)).x * 6.2831853;
    float d = etnaSurfaceHash(ivec3(c + ivec2(0, 1), 17)).x * 6.2831853;
    float e = etnaSurfaceHash(ivec3(c + ivec2(1, 1), 17)).x * 6.2831853;
    float na = dot(vec2(cos(a), sin(a)), f);
    float nb = dot(vec2(cos(b), sin(b)), f - vec2(1.0, 0.0));
    float nd = dot(vec2(cos(d), sin(d)), f - vec2(0.0, 1.0));
    float ne = dot(vec2(cos(e), sin(e)), f - vec2(1.0, 1.0));
    return mix(mix(na, nb, u.x), mix(nd, ne, u.x), u.y);
}

// How much it is night, by the sun's height: 0 by day, 1 once the sun is down (and the light is the moon).
float etnaNight() {
    return ETNA_MOON ? 1.0 : 1.0 - smoothstep(0.0, 0.25, ETNA_LIGHT_DIR.y);
}

// Schlick's Fresnel for a reflectance f0 at the cosine ndv between the normal and the eye.
float etnaSchlick(float f0, float ndv) {
    float edge = 1.0 - clamp(ndv, 0.0, 1.0);
    float edge2 = edge * edge;
    return f0 + (1.0 - f0) * edge2 * edge2 * edge;
}

// How open this fragment is to the sky above, for anything that mirrors it: full sky light only (a block of
// water or leaves overhead dims it a level), so a reflection of the sky never shows under a roof or a sea.
float etnaSkyOpen() {
    return etnaSkyLight * smoothstep(0.85, 0.97, etnaLightLevels.y) * cameraInAir();
}

// Rain falling into a puddle: rings that open from random points and fade as they spread, in two offset
// grids of cells 0.8 blocks wide. Each ring starts and ends at nothing, so a cell moving on to its
// next random point never pops. Returns the slope of the water (how its normal tilts along x and z).
// Rings thinner than a pixel or two fade out with the distance.
const float RIPPLE_CELLS = 1.25;  // cells per block
const float RIPPLE_PERIOD = 1.2;  // seconds from a drop to its ring fading out; 1000 of them in GameTime's 20 minutes
const float RIPPLE_REACH = 0.3;   // the widest a ring opens, in cells (the drop lands 0.3 .. 0.7 into its cell)
const float RIPPLE_WIDTH = 0.035; // how wide its crest is, in cells
vec2 etnaRipples(vec2 p, float time) {
    float far = 1.0 - smoothstep(0.01, 0.02, etnaPixelFootprint);
    if (far <= 0.0) {
        return vec2(0.0);
    }
    vec2 slope = vec2(0.0);
    for (int layer = 0; layer < 2; layer++) {
        vec2 q = p * RIPPLE_CELLS + vec2(0.37, 0.71) * float(layer);
        vec2 cell = floor(q);
        ivec2 c = ivec2(cell);
        float phase = time / RIPPLE_PERIOD + etnaSurfaceHash(ivec3(c, 40 + layer)).x;
        float slot = floor(phase);
        float age = phase - slot;
        // The slot counts up to about 1000 over the 20-minute clock and starts again at 0; the ring
        // that is growing as it wraps keeps its place (mod), so it doesn't jump.
        vec4 h = etnaSurfaceHash(ivec3(c.x, c.y, int(mod(slot, 1000.0)) * 2 + layer));
        vec2 d = q - cell - (0.3 + 0.4 * h.xy);
        float r = length(d);
        float x = (r - age * RIPPLE_REACH) / RIPPLE_WIDTH;
        // The slope of a travelling bump: up on its outside, down on its inside.
        float ring = -x * exp(-x * x);
        // Not every cell has a drop in every slot.
        float strength = h.z < 0.15 ? 0.0 : (1.0 - age) * (1.0 - age) * smoothstep(0.0, 0.12, age);
        slope += d / max(r, 1e-4) * (ring * strength);
    }
    return slope * (0.8 * far);
}

// Once per fragment, right after wetness: the material class, the texel's colour against its texture's
// for ores and glowing blocks, and the puddle here (0 .. 1) with its rippled surface; wet is what
// wetness says.
float etnaSurfaceBegin(vec3 normal, float wet) {
    #ifdef ETNA_SHADOWS
    etnaClass = etnaMaterial < -0.5 ? int(0.5 - etnaMaterial) : 0;
    if (etnaClass == SURF_ORE || etnaClass == SURF_GLOWING) {
        // The texel against its texture's average: one read at the mip level where a texel covers the whole
        // 16x16 texture (vanilla's size), at its centre so no neighbour blends in. A texture lying across that
        // level's grid, or a bigger one, gives a local average instead, which tells specks just as well.
        vec2 atlas = vec2(TextureSize);
        ivec2 texel = clamp(ivec2(texCoord0 * atlas), ivec2(0), max(TextureSize - 1, ivec2(0)));
        etnaTexel = texelFetch(Sampler0, texel, 0).rgb;
        vec3 mean = textureLod(Sampler0, (floor(texCoord0 * atlas / 16.0) + 0.5) * 16.0 / atlas, 4.0).rgb;
        // Specks of an ore differ from the stone around them in colour or in brightness (coal's black, iron's
        // beige, diamond's cyan); a glowing block's bright bits are brighter than the rest of it.
        float stand = etnaClass == SURF_ORE
            ? smoothstep(0.10, 0.24, length(etnaTexel - mean))
            : smoothstep(0.06, 0.22, max(etnaTexel.r, max(etnaTexel.g, etnaTexel.b)) - max(mean.r, max(mean.g, mean.b)));
        // Specks are a texel wide: gone where a texel shrinks below a pixel, as the bumps are (in the
        // atlas's own texels, so 32x and larger resource packs fade them as soon as they would crawl).
        etnaSpeck = stand * (1.0 - smoothstep(0.5, 1.0, etnaTexelFootprint));
    }
    if (wet <= 0.0 || ETNA_PUDDLES <= 0.0 || normal.y < 0.99) {
        return 0.0;
    }
    // Puddles gather on flat open ground as it soaks: two octaves of noise a few blocks across (the second
    // turned, so the two grids don't line up), the threshold falling as the ground gets wetter, so they grow
    // from the low spots and shrink as it dries. At full puddles about a third of the open ground is under
    // water, at the default about a sixth.
    vec2 p = etnaWorldPos.xz;
    vec2 turned = mat2(0.825, 0.565, -0.565, 0.825) * p;
    float n = 0.5 + 0.65 * etnaPuddleNoise(p / 7.0) + 0.23 * etnaPuddleNoise(turned / 2.7 + 11.0);
    float threshold = 0.82 - 0.24 * ETNA_PUDDLES * smoothstep(0.3, 1.0, wet);
    float puddle = smoothstep(threshold, threshold + 0.04, n);
    if (puddle > 0.0 && ETNA_RAIN > 0.0) {
        vec2 slope = etnaRipples(p, GameTime * 1200.0) * ETNA_RAIN;
        etnaPuddleNormal = normalize(vec3(-slope.x, 1.0, -slope.y));
    }
    return puddle;
    #else
    return 0.0;
    #endif
}

// What a mirror sees on the ground below the horizon: the brightness of the light around, off ground of a
// typical albedo and a neutral earthy colour (the shade's blue on it turned gold walls olive).
const vec3 GROUND_ALBEDO = vec3(0.36, 0.33, 0.28);

// A material's mirror: the sky in the reflected direction where the sky is open overhead (the sky model's,
// as the water mirrors it) and the darker ground below the horizon, which is what makes a metal wall read
// as metal under any light; the light around it elsewhere, which is what a rough or roofed-over surface
// sees. mirror is how sharp the surface is (0 .. 1).
vec3 etnaEnvironment(vec3 n, vec3 viewDir, vec3 light, float mirror) {
    float open = etnaSkyOpen() * mirror;
    if (open <= 0.0) {
        return light;
    }
    vec3 r = reflect(viewDir, n);
    // The night sky as drawn is darker, next to the moonlit land, than the day sky is next to the sunlit land
    // (vanilla's night keeps the land brighter than a real night): a moonlit iron block mirrored a nearly black
    // sky. At night the mirrored sky is brought up a little towards the land's light.
    vec3 seen = mix(etnaWaterSky(r) * (1.0 + 1.5 * etnaNight()), dot(light, LUMA) * GROUND_ALBEDO, smoothstep(0.0, -0.3, r.y));
    return mix(light, seen, open);
}

// The lit colour of a tagged material (and of shaded leaves and plants): lit is shadeTerrain's colour,
// albedo the surface's own, light the light falling on it (lit = albedo * light), bump the per-texel normal.
// Metals keep part of their diffuse light and mirror the rest of it, coloured by their own albedo (a
// metal's reflectance is its colour); polished, glassy and gem surfaces mirror their surroundings by
// Schlick's Fresnel on top. Under an even light the result keeps vanilla's colour; only where the
// mirrored sky differs (blue overhead, pale at the horizon, warm towards a low sun) does it change.
vec3 etnaSurfaceLight(vec3 lit, vec3 albedo, vec3 light, vec3 normal, vec3 bump, float shadow, vec3 viewDir, float kind) {
    #ifdef ETNA_SHADOWS
    if (kind > 0.5 && kind < 2.5) {
        // Leaves and plants in the shade are lit softly from all round by skylight through the canopy,
        // a little more saturated for having passed through leaves: a sixth more light, by day.
        float shade = (1.0 - shadow) * ETNA_LEAF_GLOW * ETNA_LIGHT_STRENGTH * (ETNA_MOON ? 0.0 : 1.0) * etnaSkyLight;
        if (shade > 0.0) {
            float peak = max(albedo.r, max(albedo.g, albedo.b));
            vec3 through = albedo * mix(vec3(1.0), albedo / max(peak, 0.05), 0.5);
            lit += through * light * (0.16 * shade);
        }
        return lit;
    }
    if (etnaClass == 0 || etnaClass >= SURF_SNOW) {
        return lit;
    }
    float m = ETNA_MATERIALS;
    // Metals follow the texture's bumps more closely: each texel mirrors a slightly different part of the
    // sky, as a worked metal does.
    vec3 n = normal + (bump - normal) * (etnaClass == SURF_METAL ? 0.5 : 0.3);
    n *= inversesqrt(max(dot(n, n), 1e-6));
    float ndv = max(-dot(n, viewDir), 0.0);
    if (etnaClass == SURF_METAL) {
        // Copper's green patina (and any texel where green leads red) is a mineral, not bare metal.
        float metal = 1.0 - smoothstep(0.0, 0.08, albedo.g - albedo.r);
        float edge = etnaSchlick(0.0, ndv);
        vec3 f = albedo + (1.0 - albedo) * edge;
        vec3 env = etnaEnvironment(n, viewDir, light, 0.75);
        float k = 0.8 * m * metal;
        return lit * (1.0 - k) + env * f * k;
    }
    // Dielectrics: reflectance at normal incidence, how sharp the mirror is, how much of it shows.
    float f0 = 0.04;
    float mirror = 0.5;
    float share = 0.7;
    if (etnaClass == SURF_GEM) {
        f0 = 0.10;
        mirror = 0.85;
        share = 1.0;
    } else if (etnaClass == SURF_ORE) {
        // Only the specks shine, a little like a metal.
        f0 = 0.08;
        mirror = 0.4;
        share = etnaSpeck;
    } else if (etnaClass == SURF_GLASS || etnaClass == SURF_ICE) {
        f0 = 0.05;
        mirror = etnaClass == SURF_GLASS ? 1.0 : 0.9;
        share = 0.9;
    }
    float fresnel = etnaSchlick(f0, ndv) * share * m;
    if (fresnel <= 0.0) {
        return lit;
    }
    return mix(lit, etnaEnvironment(n, viewDir, light, mirror), fresnel);
    #else
    return lit;
    #endif
}

// The share of the plain sun sheen (sunSheen) a surface keeps: the classes that bring a sheen of their own
// give it up as their material shows.
float etnaSheenShare() {
    if (etnaClass == 0 || etnaClass >= SURF_SNOW) {
        return 1.0;
    }
    return 1.0 - ETNA_MATERIALS;
}

// Wet ground mirrors the sky in the reflected direction (Schlick's Fresnel, more on smooth stone than on a
// grass block); a puddle is a full mirror on a flat surface, rippled while it rains. lit is the lit colour.
vec3 etnaWetShade(vec3 lit, vec3 normal, vec3 bump, float smoothness, vec3 viewDir, float wet, float puddle) {
    // The film of water evens out the texture's bumps.
    vec3 n = normal + (bump - normal) * 0.15;
    n *= inversesqrt(max(dot(n, n), 1e-6));
    float film = etnaSchlick(0.02, -dot(n, viewDir)) * wet * mix(0.35, 0.75, smoothness);
    // Looking down at the ground nearby the film shows next to nothing of the sky: not worth working it out.
    vec3 result = film > 0.01 ? mix(lit, etnaEnvironment(n, viewDir, lit, 1.0), film) : lit;
    if (puddle > 0.0) {
        // The crests of the rain's rings catch the bright sky low down even where the flat water shows little
        // of it, so the rings read under an even grey sky too.
        float crest = length(etnaPuddleNormal.xz);
        float mirror = min(etnaSchlick(0.02, -dot(etnaPuddleNormal, viewDir)) + 0.6 * crest, 1.0) * puddle;
        result = mix(result, etnaEnvironment(etnaPuddleNormal, viewDir, lit, 1.0), mirror);
    }
    return result;
}

// A glint on one texel: some texels are tiny facets turned a random way (world-anchored, so they stay put
// on their texel), and one lights up where it mirrors the light into the eye. Each changes only as the eye
// or the light moves: snow sparkles as you walk and holds still while you stand. density is the share of
// texels that are facets, tilt how far they turn, sharpness the highlight's exponent; l is towards the light.
float etnaGlint(vec3 normal, vec3 l, vec3 viewDir, float density, float tilt, float sharpness) {
    ivec3 cell = ivec3(floor(etnaWorldPos * 16.0 - normal * 0.5));
    vec4 h = etnaSurfaceHash(cell);
    if (h.x > density) {
        return 0.0;
    }
    vec3 facet = normalize(normal + tilt * (h.yzw * 2.0 - 1.0));
    return pow(max(dot(reflect(-l, facet), -viewDir), 0.0), sharpness);
}

// The highlights of materials, wet ground and sparkles, screened over the lit colour with sunSheen's.
// skyPart is vanilla's light from the sky here (time of day, caves), which the sun's highlights follow as
// sunSheen's do; torch the light from blocks on this face, which lights the glints of ores and gems in a
// cave from where the eye is (a torch in hand, or the light behind the player).
vec3 etnaSurfaceHighlight(vec3 normal, vec3 bump, float smoothness, float shadow, vec3 viewDir, vec3 skyPart,
        vec3 torch, vec3 albedo, float wet, float puddle) {
    #ifdef ETNA_SHADOWS
    if (etnaClass == 0 && wet <= 0.0) {
        return vec3(0.0);
    }
    vec3 total = vec3(0.0);
    vec3 l = ETNA_LIGHT_DIR;
    vec3 v = -viewDir;
    // The direct light on this fragment: the sun's (the moon's at night, fainter and bluer), through the
    // shadow map, under the open sky only, as for sunSheen.
    float sun = directSun() * shadow * etnaSkyLight * smoothstep(0.85, 0.97, etnaLightLevels.y) * cameraInAir();
    float ndl = max(dot(normal, l), 0.0);
    vec3 sunLight = sunColor() * skyPart;
    // Texels smaller than a pixel would only flicker: glints fade before they get there.
    float texelFade = 1.0 - smoothstep(0.35, 0.7, etnaPixelFootprint * 16.0);

    if (sun * ndl > 0.0) {
        vec3 h = normalize(l + v);
        // Wet ground: the film of water adds a sharp glint of its own above the surface's soft sheen; a
        // puddle a mirror-sharp one.
        if (wet > 0.0) {
            vec3 n = puddle > 0.0 ? etnaPuddleNormal : normalize(normal + (bump - normal) * 0.15);
            float exponent = mix(mix(60.0, 260.0, wet), 900.0, puddle);
            float glint = (exponent + 8.0) / 8.0 * pow(max(dot(n, h), 0.0), exponent) * etnaSchlick(0.02, dot(v, h));
            total += sunLight * (min(glint, 8.0) * max(wet, puddle) * sun * ndl);
        }
        if (etnaClass != 0 && etnaClass != SURF_GLOWING) {
            float m = ETNA_MATERIALS;
            vec3 n = normal + (bump - normal) * 0.3;
            n *= inversesqrt(max(dot(n, n), 1e-6));
            float ndh = max(dot(n, h), 0.0);
            float edge = etnaSchlick(0.0, dot(v, h));
            // The material's own sun highlight: a sharper sheen than sunSheen's, coloured by the albedo on
            // metals. Snow and sand keep sunSheen's and add only their sparkles.
            float exponent = 0.0;
            vec3 f = vec3(0.04 + 0.96 * edge);
            if (etnaClass == SURF_METAL) {
                exponent = 90.0;
                float metal = 1.0 - smoothstep(0.0, 0.08, albedo.g - albedo.r);
                f = mix(f, albedo + (1.0 - albedo) * edge, metal);
            } else if (etnaClass == SURF_GEM) {
                exponent = 160.0;
                f = vec3(0.10 + 0.90 * edge);
            } else if (etnaClass == SURF_POLISHED) {
                exponent = 60.0;
            } else if (etnaClass == SURF_ORE) {
                exponent = 120.0;
                f *= etnaSpeck * 1.5;
            } else if (etnaClass == SURF_GLASS || etnaClass == SURF_ICE) {
                exponent = 300.0;
            }
            if (exponent > 0.0) {
                float spec = (exponent + 8.0) / 8.0 * pow(ndh, exponent) * ndl;
                total += sunLight * f * (min(spec, 8.0) * sun * m);
            }
            // Sparkles: facets on snow, sand, ice, gems and ore specks that catch the sun.
            float sparkle = ETNA_SPARKLES * texelFade;
            if (sparkle > 0.0) {
                float glint = 0.0;
                vec3 tint = vec3(1.0);
                if (etnaClass == SURF_SNOW) {
                    glint = etnaGlint(normal, l, viewDir, 0.25, 0.3, 350.0);
                } else if (etnaClass == SURF_ICE) {
                    glint = etnaGlint(normal, l, viewDir, 0.2, 0.25, 500.0);
                } else if (etnaClass == SURF_SAND) {
                    glint = 0.7 * etnaGlint(normal, l, viewDir, 0.15, 0.3, 350.0);
                    tint = vec3(1.0, 0.92, 0.78);
                } else if (etnaClass == SURF_GEM) {
                    glint = etnaGlint(normal, l, viewDir, 0.2, 0.4, 200.0);
                    tint = mix(vec3(1.0), albedo / max(max(albedo.r, max(albedo.g, albedo.b)), 0.05), 0.3);
                } else if (etnaClass == SURF_ORE && etnaSpeck > 0.0) {
                    glint = etnaSpeck * etnaGlint(normal, l, viewDir, 0.5, 0.6, 400.0);
                }
                total += sunLight * tint * (min(glint * 8.0, 4.0) * sparkle * sun);
            }
        }
    }
    // In the dark the light comes from torches, and the one the eye sees best is near the eye: facets of
    // gems and ore specks turned towards the camera glint as it moves past them.
    if ((etnaClass == SURF_GEM || etnaClass == SURF_ORE) && ETNA_SPARKLES > 0.0) {
        float lamp = dot(torch, LUMA);
        if (lamp > 0.02) {
            float density = etnaClass == SURF_GEM ? 0.15 : 0.8 * etnaSpeck;
            float glint = etnaGlint(normal, v, viewDir, density, 0.35, 40.0);
            total += torch * (min(glint * 6.0, 3.0) * ETNA_SPARKLES * texelFade);
        }
    }
    return total;
    #else
    return vec3(0.0);
    #endif
}

// Light that surfaces give off themselves, on top of etnaGlow: the bright specks of redstone, crying
// obsidian, amethyst crystals, glow lichen, sculk and magma, a little by day and more in the dark (the
// glowing blocks setting), and at night the petals and berries of plants (saturated texels that aren't
// green), breathing slowly, each plant at its own pace. Off under Darkness and Blindness (EtnaDark.w).
vec3 etnaSurfaceGlow(vec3 lit, vec3 albedo, float kind) {
    #ifdef ETNA_SHADOWS
    float dayLight = smoothstep(0.0, 0.5, (ETNA_MOON ? 0.0 : ETNA_LIGHT_STRENGTH) * etnaSkyLight);
    if (etnaClass == SURF_GLOWING && etnaSpeck > 0.0) {
        float peak = max(etnaTexel.r, max(etnaTexel.g, etnaTexel.b));
        vec3 hot = mix(etnaTexel / max(peak, 0.05), vec3(1.0), 0.08) * peak;
        float glow = etnaSpeck * smoothstep(0.3, 0.55, peak) * ETNA_SPECK_GLOW * EtnaDark.w;
        // In the dark it adds light; by day it is screened over, so a sunlit block never washes out.
        vec3 dark = lit + hot * (0.6 * glow);
        vec3 day = lit + (1.0 - min(lit, 1.0)) * hot * (0.25 * glow);
        return mix(dark, day, dayLight);
    }
    if (kind > 1.5 && kind < 2.5 && ETNA_FLOWER_GLOW > 0.0) {
        // Night, and always deep under a roof or in a cave, and in dimensions without a sun.
        float dark = max(max(etnaNight(), 1.0 - smoothstep(0.3, 0.9, etnaLightLevels.y)), 1.0 - ETNA_SUN_WORLD);
        if (dark <= 0.0) {
            return lit;
        }
        float peak = max(albedo.r, max(albedo.g, albedo.b));
        float low = min(albedo.r, min(albedo.g, albedo.b));
        float saturation = (peak - low) / max(peak, 1e-3);
        // The hue in degrees, to leave stems and leaves (yellow-green to cyan-green) dark.
        float hue = 60.0 * (peak == albedo.r ? mod((albedo.g - albedo.b) / max(peak - low, 1e-3), 6.0)
            : peak == albedo.g ? (albedo.b - albedo.r) / max(peak - low, 1e-3) + 2.0
            : (albedo.r - albedo.g) / max(peak - low, 1e-3) + 4.0);
        float green = smoothstep(62.0, 75.0, hue) * (1.0 - smoothstep(160.0, 175.0, hue));
        float petal = smoothstep(0.35, 0.55, saturation) * smoothstep(0.3, 0.5, peak) * (1.0 - green);
        if (petal <= 0.0) {
            return lit;
        }
        // Petals and berries stand out in colour from the rest of their texture (the stem, the leaves);
        // a ripe crop is that colour all over, and a field of wheat glowing would be too much. The texture's
        // average comes from the mip level where a texel covers it all, as for ores.
        vec2 atlas = vec2(TextureSize);
        vec3 mean = textureLod(Sampler0, (floor(texCoord0 * atlas / 16.0) + 0.5) * 16.0 / atlas, 4.0).rgb;
        float meanPeak = max(mean.r, max(mean.g, mean.b));
        petal *= smoothstep(0.11, 0.17, length(albedo / max(peak, 1e-3) - mean / max(meanPeak, 1e-3)));
        if (petal <= 0.0) {
            return lit;
        }
        // A slow breath, about 8 seconds long, a whole number of times in GameTime's 20 minutes, so it carries
        // on unbroken when GameTime starts again.
        float pace = etnaSurfaceHash(ivec3(floor(etnaWorldPos.xz), 23)).x;
        float breath = 0.75 + 0.25 * sin(GameTime * 6.2831853 * 150.0 + pace * 6.2831853);
        vec3 glow = albedo / max(peak, 0.05) * mix(peak, 1.0, 0.3);
        return lit + glow * (0.45 * petal * dark * breath * ETNA_FLOWER_GLOW * EtnaDark.w);
    }
    #endif
    return lit;
}
