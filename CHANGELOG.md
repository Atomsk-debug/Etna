# Changelog

Every release is for Minecraft 26.4-snapshot-3 with Fabric. Versions: the middle number goes up for new features, the last one for fixes. Jars are on the [Releases](https://github.com/Atomsk-debug/Etna/releases) page.

## 0.7.1 (2026-10-10)

Fixes, better waving and one new slider; the look is otherwise 0.7.0's.

* **Etna off is vanilla.** With Etna off (F7 or the settings screen) nothing of it is left: the game draws its terrain, water, clouds, sun and leaves with its own shaders, textures and models. Before, Etna's terrain and cloud shaders, its round sun and its leaf and plant models stayed in place with Etna off, so the game still looked a little different. Turning Etna on or off now also builds the chunks around you again, in the background. On a Mac, Half resolution now applies only when Etna is on as the game starts.
* **Smoother shadows.** The shadow map used to turn with the sun every frame, so shadow edges shimmered texel by texel all day. Now its direction holds still and, as the sun moves on, Etna fades over to a second map drawn along the new direction, so edges glide. The shadow pass still draws one map per frame.
* **Plain water.** No trace of vanilla's water texture is left near you, nor on waterfalls, their rims and the sides of pools: only Etna's own waves, ripples and light. With Improved Transparency on, swamp, mangrove and pale garden water now get Etna's water too.
* Sun glints on water and the light on deep sea floors follow the shadows' fade, so they glide too.
* **Waving, redone.** Short grass and ferns, which the game sets at slightly different heights, mostly stood still before: now they all sway. Crops, living corals and pale hanging moss sway too. Tall grass, sugar cane, kelp and vines move in one piece instead of opening gaps between their blocks, hanging vines and roots stay attached to the ceiling while their tips sway, and pale moss, propagules and vines hanging from leaves move with the leaves. Plants bend from their roots, and leaves and plants keep vanilla's exact shape and light. It works with resource packs, bushy leaves and 3D plants included. Cobwebs, lily pads, sea pickles, azaleas, dead corals, stems grown into a melon or pumpkin and flat ground covers stay still.
* **Visibility slider** (General page, default 30 %): dark places read better without brighter brights. It raises the cave light, lightens sun and moon shadows, lets the eyes open further in the dark and lifts the darkest tones, while skies, snow and sunlit ground stay about as they are. 0 % is 0.7.0's darkness. Cave Light Off and Eye Adaptation Off stay off.
* Reset All also resets Visibility and the sun path's tilt.
* The benchmark's settings line now records windowed, fullscreen or exclusive fullscreen, and the display's refresh rate.
* New README, with every setting explained in `docs/settings.md`, plus pages on performance and on how Etna works. `CREDITS.md` and the shader files now also credit Mojang for the vanilla shaders Etna's terrain and cloud shaders build on.

## 0.7.0 (2026-10-09), "golden hour"

* Etna's own sky: sunsets, a moonlit navy night with moon phases, sun disc and stars; the same sky model colours the light on blocks, the clouds, the distance and the water's reflection.
* Golden sunlight over blue-tinted shade, softer highlights, a tilted sun path.
* Clouds lit by the sun, and cloud shadows drifting over the land.
* Light shafts and mist in one cheaper half-resolution pass.
* A torch, lantern or glowstone in your hand lights your surroundings.
* Water drawn from Etna's own waves instead of vanilla's blocky texture.

## 0.6.0 (2026-10-09)

Same look as 0.5.0, three times cheaper: Etna went from about 10 ms to about 3 ms per frame on a Mac mini M4.

* The shadow map only draws blocks whose shadow can fall on something you see, skips faces turned away from the sun and uses half the memory.
* Fewer texture reads for soft shadow edges and light shafts; fewer passes and no needless clears in the post-processing.
* Sun glints on rivers break up into small glints on the ripples.
* GPU timers per pass in the performance log and the benchmark.

## 0.5.0 (2026-10-08)

* Water: waves, reflections, clear shallows, and caustics and light shafts under water.
* Caves and dark rooms are never pitch black; lava, glowstone and lanterns glow.
* Cheaper shadows, a performance log and the built-in benchmark.

## 0.4.0 (2026-10-08)

Richer surfaces (relief and sheen from each texture) and sky. Not released on its own.

## 0.3.0 and 0.3.1 (2026-10-08)

Temporal anti-aliasing (ported from Photon Shaders), new bloom and ambient occlusion, and a simpler settings screen with Quality presets and Looks.

## 0.1.0 to 0.2.3 (2026-10-08)

First versions: shadows, light shafts and colour grading on the native Vulkan renderer, then fixes for shadow stripes, dark triangles and blinking shadow edges.
