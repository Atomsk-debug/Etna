# Changelog

Every release is for Minecraft 26.4-snapshot-3 with Fabric. Versions: the middle number goes up for new features, the last one for fixes. Jars are on the [Releases](https://github.com/Atomsk-debug/Etna/releases) page.

## 0.9.0 (unreleased)

Faster with World Resolution, and Etna's own anti-aliasing.

* **Mobs, chests, armour, items and your hand in Etna's light.** They kept Minecraft's flat two-light shading while the land around them had Etna's golden sun, blue shade, shadows and warm torches, so they looked pasted in. Now they are lit the way the land is: the sun and the sky's colour on each face, the shadow map and the clouds' shadows, the warm torch light, the cave light and the distance haze, so a mob under a tree stands in its shade and a white block entity is as bright as the white ground it stands on. Your hand takes the shade of whatever stands over you. Enchantment glints, the hurt flash and the fog stay as they were, and the inventory and other menus keep Minecraft's own light. Entity Lighting (new, Lighting & Shadows page) turns it off; the benchmark has a step for it. With Improved Transparency on, translucent mobs and items keep Minecraft's light for now.
* **World Resolution (new, General page, 50 to 100%).** Draws the world at fewer pixels and builds the full-size picture back up in Etna's anti-aliasing, which moves the camera by a different fraction of a pixel every frame and puts the frames together at full size. Most of Etna's cost is per pixel, so at 75% the world shades a little over half the pixels. Textures keep their full-size detail, and the hand, the menus and the HUD stay sharp at full size. Needs anti-aliasing, which every Quality but Low has. The benchmark has a step for it.
* **New anti-aliasing, Etna's own.** Steadier and sharper than 0.8's: in Etna's test bench about a third less flicker and error, edges closer to a supersampled picture, and less trailing behind moving things. Glints and sparkles brighter than white no longer flash through it.
* **No code from other shader packs any more.** The anti-aliasing and the light shafts' pixel order were the last parts from Photon Shaders; with 0.8.1's own shadow warp and water Fresnel, Etna is now entirely its own code (and Minecraft's shaders it builds on), so it can go on Modrinth and CurseForge. `LICENSE-PHOTON.txt` is gone.

## 0.8.1 (unreleased)

Fixes for 0.8.0's water, and shadows that soften with distance.

* **Shadows soften with distance, as real ones do.** A shadow is sharp where it meets what casts it and blurs the further it reaches: a tree's shadow is crisp at the trunk and a soft smudge at its far end, and long evening shadows lose their detail and fade towards the tip. Before, every shadow edge had the same blur. Shadow Softness now also sets how quickly they soften; at its lowest, edges stay hard everywhere.
* **Steadier, cheaper soft shadows.** The test build turned its shadow reads every frame and gave up where its few reads found nothing, so the softened edges shimmered and cost up to 2.5 ms a frame. Now the search for what casts a shadow reads the same spots every frame, open ground and the inside of shadows skip the wide blur, and only very wide evening blurs still turn, gently. In Etna's shadow test bench the flicker after anti-aliasing is 35 to 65 % lower than the test build's and, with the sun low, lower than 0.8.0's too.
* **Sharper far shadows.** The shadow map is spread over the land in a new way: about as sharp round you as before, two to five times sharper towards the corners of the shadowed area, and the whole map is used (before, about 6 % of it was never filled).
* **Long low-sun shadows are fainter.** Below about 20 degrees the sky fills shadows in a little, up to a third lighter at the horizon.
* Far shadows fade out instead of stopping at a straight line about 180 blocks away when looking towards or away from a low sun, and ground more than 320 blocks below the camera along the light (far away under a low sun, or seen from very high up) no longer turns dark.

* **Reflections keep their shapes.** The shore, trees and hills mirrored in the water came out as scattered dark dots. Each reflected ray now takes the same fixed steps every frame and counts as a hit only where it really runs into something, rays that find nothing are averaged in instead of dropped, and the mirror image follows a calmer copy of the waves, so it wobbles as a whole instead of breaking up.
* **Steadier reflections.** Reflections in the water no longer flicker where a reflected edge (a tree, a roof, the shore) only just meets the water's ray: each spot of the water now blends its reflection over the last few frames, and the rays are traced from where the picture really is, not from where the anti-aliasing has moved it this frame.
* **Shadows on water make sense.** The water's own colour now takes the shade of the water under the surface, where its light comes from: on clear shallow water a tree's shadow is the one on the bottom, seen through the water, and on deep or murky water a soft shadow in the water itself. Before, the surface carried a second, sharp copy of each shadow next to the one on the bottom, and the two crossed each other out in strange shapes. Water in a wide shade (under a cliff, a big tree or a roof) also keeps the clarity of the water around it instead of turning murky in the shadow's shape.
* **Foam, redone.** Only a thin, faint, bubbly line where still water meets the shore, in a pale colour that no longer glows. Shallow water stays clear (0.8.0 foamed every floor just under the surface into a milky haze), and water flowing down a slope has no foam.

## 0.8.0 (2026-10-10), "Enchanted"

Magic, and closing the gap with the most popular shader packs. Every new effect has its own setting, none of them is part of the Look, and with Etna off none of them is left.

* **Moods** (new Mood page): eleven colour moods laid over the Look, each made for a family of resource packs or purely magic. Natural (default, Etna's own look with a faint dreamy glow), Clear Day (Faithful, Stay True and other vanilla-like packs), Trailer (Bare Bones and flat packs), Storybook (Mizuno's 16 Craft and cottagecore packs), Old Kingdom (medieval packs), Ink & Colour (Sphax PureBDcraft and cartoon packs, with ink outlines), Lifelike (realistic PBR packs), and the magic Enchanted, Mystic Twilight, Harvest Autumn and Dreamy Pastel. A Mood Strength slider blends any of them with the plain Look. The dreamy glow only ever adds light, so textures stay sharp. Etna does not come with its own resource pack: moods suit the packs people already use.
* **Night sky** (new Sky Wonders page): the Milky Way with dark dust lanes, turning with the stars (Nebula Colors adds faint rose and teal), aurora curtains over cold biomes and under a full moon, which also tint the land faintly green, rare shooting stars, a faint double rainbow after rain when the sun is low, a round moon lit by its phase with a halo on cold clear nights, and a violet End sky with a drifting nebula. By day the sky leans gently towards each biome's own colour (Biome Sky Color, Sky & Air page).
* **Fireflies and motes** (Effects page): fireflies over grass and flowers from dusk to dawn on dry nights, more by firefly bushes and water; pale motes in the Pale Garden, spores in lush caves, soul wisps over soul sand, motes in the End. They never smear and cost nothing by day.
* **Water reflections of the world**: lakes, rivers and the sea now mirror the shore, hills, trees and buildings with ripples, not only the sky. Plus a thin, slowly drifting line of foam along the shore and sparkles of the sun and the moon on the waves (Water page: Shoreline Foam, Glitter).
* **Materials** (Surfaces page): metals mirror the sky tinted by their colour, gems sparkle, polished stone, glass and ice catch the light, ore specks glint as you pass with a torch, redstone and amethyst glow softly, and flowers glow faintly at night. Puddles form on open ground in rain, with rings that never pop; snow, sand and ice sparkle in the sun. Blocks are recognised by name, so it works with any resource pack.
* **Weather and the Nether**: thinner rain that takes the sky's colour and glows warm near torches, softer snow, both leaning with the wind. The Nether gets a glowing haze in each biome's colour, a low mist over the lava sea and warm lava light on the rock above it. Distant lights glow softly at night, in rain, in caves and in the Nether (Bloom Fog, Effects page).
* The settings menu has nine pages now: long pages scroll with the mouse wheel, and the page tabs close up on small screens.

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
