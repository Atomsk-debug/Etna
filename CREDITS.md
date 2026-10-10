# Credits

Etna is original code under the MIT licence (`LICENSE`), with these exceptions: the temporal anti-aliasing
pass, the shadow map distortion and the water's Fresnel function are ported from another shader pack, under
that pack's own licence; the sky's star hash is David Hoskins's, under the MIT licence; and the terrain and
cloud shaders are based on Minecraft's own, which Etna's licence doesn't cover.

## Code ported from other projects

| Etna file | Source | Licence |
|---|---|---|
| `src/main/resources/assets/etna/shaders/post/taa.fsh` | [Photon Shaders](https://github.com/sixthsurge/photon) by SixthSurge: `program/c4_taa_exposure.fsh` (TAA, neighbourhood and variance clipping, offcenter rejection) and `include/utility/bicubic.glsl` (fast Catmull-Rom history filter), adapted to Minecraft's reversed depth buffer and Etna's camera matrices | Photon Shaders License, see `LICENSE-PHOTON.txt` |
| `src/main/resources/assets/etna/shaders/include/shadow_distortion.glsl`: `etnaShadowFactor`, used in `src/main/resources/assets/etna/shaders/core/terrain.vsh` (shadow pass), `src/main/resources/assets/etna/shaders/include/terrain_shadow.glsl` (shadow map lookups) and `src/main/resources/assets/etna/shaders/post/shafts.fsh` (the light shafts' lookups) | [Photon Shaders](https://github.com/sixthsurge/photon) by SixthSurge: `include/lighting/shadows/distortion.glsl` (quartic-length shadow map distortion) | Photon Shaders License, see `LICENSE-PHOTON.txt` |
| `src/main/resources/assets/etna/shaders/core/sky.fsh`: `hash33` (where the stars sit, how bright they are, their colour) | "Hash without Sine" by David Hoskins ([shadertoy.com/view/4djSRW](https://www.shadertoy.com/view/4djSRW)), `hash33`, unchanged | MIT, Copyright (c) 2014 David Hoskins, licence text as in `LICENSE` |
| `src/main/resources/assets/etna/shaders/include/terrain_water.glsl`: `fresnelDielectric` | [Photon Shaders](https://github.com/sixthsurge/photon) by SixthSurge: `include/lighting/bsdf.glsl`, `fresnel_dielectric_n` (Fresnel reflectance with total internal reflection, for Snell's window seen from under water), with floored divisors and a clamp added | Photon Shaders License, see `LICENSE-PHOTON.txt` |
| `src/main/resources/assets/etna/shaders/post/shafts.fsh`: `SUBPIXEL` (the order in which the light shafts' half-resolution pixels take turns) | [Photon Shaders](https://github.com/sixthsurge/photon) by SixthSurge: `checkerboard.glsl`, the same 2×2 order | Photon Shaders License, see `LICENSE-PHOTON.txt` |
| `src/main/resources/assets/etna/shaders/core/terrain.vsh`, `terrain.fsh`, `clouds.vsh`, `clouds.fsh` | Minecraft's vanilla `terrain` and `clouds` core shaders, which Etna replaces while it is on and extends | Based on Minecraft's vanilla shader, (c) Mojang AB, not covered by Etna's MIT licence |

The Photon Shaders License allows redistributing modified portions, except on platforms that pay creators
(Modrinth, CurseForge) without SixthSurge's written permission, and never for money. Builds of Etna that
contain these parts fall under those terms; publishing Etna on Modrinth or CurseForge needs that permission
first, or original replacements for them.

## Techniques Etna's own code follows

* Ambient occlusion (`world_light.fsh`): disc sampling in the surface plane with an elevation-angle weight, after
  Photon's SSAO (`include/lighting/ao/ssao.glsl`).
* Bloom (`bloom_*.fsh`): 13-tap downsampling filter and tent upsampling from "Next Generation
  Post-Processing in Call of Duty: Advanced Warfare" (Jorge Jimenez, SIGGRAPH 2014), Karis average against
  fireflies (Brian Karis, "High Quality Temporal Supersampling", SIGGRAPH 2014), progressive blend of the
  levels as Photon does it.
* Sharpening (`final.fsh`): contrast-adaptive sharpening after AMD FidelityFX CAS (MIT).
* Tone curve (`tone.glsl`, `final.fsh`): the rational shoulder of Timothy Lottes' tone curve ("Advanced
  Techniques and Optimization of HDR Color Pipelines", GDC 2016) with contrast and shoulder at 1, that is
  Reinhard's extended operator (Reinhard, Stark, Shirley and Ferwerda, "Photographic Tone Reproduction for
  Digital Images", 2002) pinned at mid grey, applied to luminance; its inverse reads the 8-bit picture back
  as scene light, and a path to white desaturates the brightest colours as film does. The bloom's soft
  threshold is the usual quadratic knee. Night vision follows the Purkinje shift (rods see no colour and peak
  in blue-green), with Etna's own weights. Eye adaptation (`EtnaEye.java`) smooths the light at the camera on
  the CPU, the idea of the eye brightness BSL and Complementary Reimagined smooth, with Etna's own curve and
  no code from either. The calmer default grade and the vignette that spares bright corners follow
  Complementary Reimagined's look.
* Terrain surfaces (`terrain.fsh`): bumps from each texel's brightness by central differences; the
  directions the texture runs along on a face come from the per-triangle tangent formula (Eric Lengyel,
  "Computing Tangent Space Basis Vectors for an Arbitrary Mesh", 2001) applied to screen-space
  derivatives, as in Christian Schüler's "Normal Mapping Without Precomputed Tangents" (2013), then
  snapped to the block's axes. Sheen: normalised Blinn-Phong (James Blinn, 1977; normalisation as in
  Akenine-Möller, Haines and Hoffman, "Real-Time Rendering") with Schlick's Fresnel approximation
  (Christophe Schlick, 1994), which also gives wet ground its reflection. Leaf glow: a forward-scattering
  lobe dimmed by the thickness of leaves between the leaf and the sun, read from the shadow map's depth
  and attenuated exponentially (Simon Green, "Real-Time Approximations to Subsurface Scattering", GPU
  Gems, 2004), over an area-weighted box of shadow map texels.
* Noise: interleaved gradient noise (Jorge Jimenez, 2014).
* Shadow casters (`EtnaShadows.java`): only the chunk sections whose shadow can fall on something the
  camera sees are drawn into the shadow map. The view is extruded towards the light: the sides of the view
  that face away from the light, and a plane along the light through each edge where such a side meets one
  facing the light, after L. Spiro's "Tightly Culling Shadow Casters for Directional Lights" (parts 1 and
  2, lspiroengine.com); the planes are moved out by how far the map's readers look around a point.
* Clouds (`clouds.vsh`, `clouds.fsh`, `terrain_clouds.glsl`), ideas after Complementary Reimagined, BSL and
  Photon with Etna's own code: each face of vanilla's cloud boxes takes wrapped diffuse sunlight (the
  light wrapped round past the terminator, as for skin in Simon Green's "Real-Time Approximations to
  Subsurface Scattering", GPU Gems, 2004) on top of sky light, and looking towards the sun two
  Henyey-Greenstein lobes (L. G. Henyey and J. L. Greenstein, 1941) light the cloud from behind, strongest
  on faces seen edge-on (silver lining). The shadows of the clouds on the land read vanilla's own cloud
  texture where the ray towards the sun crosses the cloud layer, drifting as vanilla moves the clouds,
  the usual cloud-shadow-map idea reduced to one bilinear read.
* Sky glow and atmosphere (`world_light.fsh`): the glow around the sun and the warm haze towards it use
  the Henyey-Greenstein phase function (L. G. Henyey and J. L. Greenstein, 1941); the air thinning with
  height uses the closed-form average density of exponential height fog, as in Iñigo Quílez's "Better
  Fog" (2010).
* Sky (`include/sky.glsl`, `core/sky.*`, the same model in `EtnaSkyModel.java`): single scattering of
  sunlight and moonlight by air (Rayleigh, with the phase 3/16π (1 + cos²θ) and scattering coefficients in
  the ratio of Bruneton and Neyret's "Precomputed Atmospheric Scattering", 2008) and by haze (a sum of two
  Henyey-Greenstein lobes and an isotropic part), with the air mass along a ray through a spherical shell
  of air in closed form, absorption by ozone that keeps the sky blue at twilight (as in Bruneton's and
  Sébastien Hillaire's "A Scalable and Production Ready Sky and Atmosphere Rendering Technique", 2020),
  light scattered more than once as one isotropic term, after Hillaire's, and the Earth's shadow rising
  after sunset. The model's colours are Etna's own fit, not a port of any of these. The sun's disc is
  darker towards its rim (limb darkening). Terrain light (`terrain.fsh`): the sunlight and skylight
  colours come from the same model; faces turned slightly away from the sun keep a little of it (wrap
  lighting) and sides facing down get warm light bounced off the sunlit ground.
* Light shafts and mist (`post/shafts.fsh`, `include/air.glsl`, `world_light.fsh`, `EtnaAir.java`): single
  scattering through the shadow map at half resolution. The march's steps are spaced exponentially along the
  view ray, as the depth slices of Bart Wroński's "Volumetric Fog: Unified Compute Shader-Based Solution to
  Atmospheric Scattering" (SIGGRAPH 2014), and shifted per pixel and per frame by interleaved gradient noise
  (Jorge Jimenez, 2014), interleaved sampling after Alexander Keller and Wolfgang Heidrich, "Interleaved
  Sampling" (2001). The air's phase function is half isotropic, half Henyey-Greenstein (g = 0.55). The mist is
  exponential height fog in two layers, integrated in closed form along the ray (Iñigo Quílez, "Better Fog",
  2010). The half-resolution result is brought back to full resolution by joint bilateral upsampling with the
  depth as the guide (Johannes Kopf, Michael F. Cohen, Dani Lischinski and Matt Uyttendaele, "Joint Bilateral
  Upsampling", SIGGRAPH 2007). The mist's look (valley mist at dawn and dusk, haze at night and in rain, cave
  fog) follows Complementary Reimagined; no code from it.
* Under water (`terrain_water.glsl`), ideas after Photon with Etna's own code: the phase function of the
  light shafts, 0.7 × Henyey-Greenstein (g = 0.5) + 0.3 isotropic, as Photon's water; caustics as the
  change in area of light refracted by the surface (the idea of Photon's `program/shadow.fsh`,
  `get_water_caustics`), which Etna evaluates analytically per pixel from its own sum of 8 sine waves; and
  the red > green > blue ratios of absorption under water.
* Water glint (`terrain_water.glsl`): the sun's reflection on the waves is a GGX (Trowbridge-Reitz) lobe
  (T. S. Trowbridge and K. P. Reitz, 1975; Walter, Marschner, Li and Torrance, "Microfacet Models for
  Refraction through Rough Surfaces", 2007) in reflected-direction form. Ripples a pixel cannot resolve
  become roughness from their slope variance (Michael Toksvig, "Mipmapping Normal Maps", 2005; Marc Olano
  and Dan Baker, "LEAN Mapping", 2010), and the curvature of those it can resolve widens glints smaller than
  a pixel (Kaplanyan, Hill, Patney and Lefohn, "Filtering Distributions of Normals for Shading
  Antialiasing", 2016). The fast sine is a parabola with one correction step, after Nicolas Capens
  (DevMaster forums, 2006). The look of the glints follows Complementary Reimagined; no code from it.
* Dark spaces (`terrain_dark.glsl`, `terrain_dark_vertex.glsl`), ideas after Photon with Etna's own code:
  a minimum cave light where the sky can't reach, faded out towards the open sky by (1 - sky light²), after
  Photon's cave lighting (`include/lighting/diffuse_lighting.glsl`); topping vanilla's own light up to that
  minimum, so vanilla's floor, night vision and gamma count towards it, is Etna's. The torch "hot core"
  (more light within a block or two of a light source) follows the idea in the same Photon file, with
  Etna's own formula.
* Small touches (`terrain_touch.glsl`, `terrain.vsh`, `terrain_dark.glsl`, `EtnaTouch.java`), ideas from other packs
  with Etna's own code: light from a torch or lantern held in the hand, which Complementary Reimagined, BSL and
  Photon all have; Etna lets it into vanilla's lightmap as one more block light level, falling a level a block
  from the hand and taking the place of the blocks' own light only where it is brighter, so it never adds up
  past a placed light. Ground that stays wet after the rain,
  after Photon's wetness that fades more slowly than it builds up. Wind in gusts that travel across the land,
  after Photon's gusty wind (`include/vertex/displacement.glsl`), here value noise carried along the wind,
  hashed with Chris Wellons's "lowbias32" ("Prospecting for Hash Functions", 2018). Glowing blocks that stay
  lit by day, after BSL's emissive blocks.
* The look Etna aims for is inspired by Complementary Reimagined by EminGT: among others, warm torch light
  against cool darkness, bright glowing blocks, and underwater fog that takes the water's own colour. No Complementary code is used:
  its licence does not allow it.
