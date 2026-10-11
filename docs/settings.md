# Etna settings

Every option of **Options > Etna Shaders...**, page by page. The settings screen shows the same text next to each
option, and every change shows in the world right away. **F7** turns Etna on and off in game, and **Reset All**
puts every page back to High quality, the Natural look, 30 % Visibility, a 25 degree sun path and the Natural mood
at full strength.

Two presets set most of the rest:

* **Quality** (General page) sets how much work Etna does: the shadow options on Lighting & Shadows and
  Anti-Aliasing on Effects. Changing one of those switches Quality to Custom.
* **Look** (General page) sets the colours and the mood: the sliders on every other page. Moving one of them
  switches Look to Custom. Visibility, Sun Path Tilt and the Mood page are not part of the Look.

All settings are saved in `config/etna.properties` (see [the end of this page](#the-config-file)).

## General

Start here. Quality sets how much work Etna does, Look sets the colours and mood; the other pages fine-tune them. Every change shows right away. In game, F7 turns Etna on and off.

* **Shaders**: Turns Etna on or off. F7 does the same in game.
* **Quality**: Low: no shadows or anti-aliasing, the fastest. Medium: shadows close by. High: the recommended balance. Ultra: sharp shadows far away.
* **Look**: Natural keeps Minecraft's own colours under real light. Soft is gentler and hazier, Vivid more saturated, Cinematic warmer with more contrast and glow.
* **Visibility**: How well you see in the dark, without making bright places brighter: caves get more of their faint light (with Cave Light on), sun and moon shadows get lighter, your eyes open up further in dark places (with Eye Adaptation on) and the darkest tones are lifted. Bright skies, snow and sunlit ground stay as they are. 0% (Off) is Etna 0.7.0's darkness. It stays where you put it when you change the Look.
* **World Resolution**: How many pixels the world is drawn at, as a share of the screen's width and height. Below 100% Etna draws the world smaller and its anti-aliasing builds the full picture back up over a few frames, the way DLSS and FSR do: much faster, a little softer, mostly in motion. 75% draws about half the pixels. The hand, the HUD and menus stay sharp. Needs anti-aliasing, which every Quality but Low has.
* **Resolution** *(macOS only)*: Half draws a quarter of the pixels and lets macOS scale the picture up: much faster, but blurry. Applies after restarting the game, and only when Etna is on as the game starts.
* **Performance log**: Summary writes a line to latest.log every few seconds while you play: frame rate, frame time and where it goes, on the CPU and on the GPU (timed on one frame in eight, so the frame rate stays the normal one). Off writes nothing.
* **Run benchmark**: Takes about 145 seconds, in a world: Etna tries its settings one at a time, writes what each one costs to latest.log, then puts everything back as it was. Stand still and don't move the mouse: a step disturbed by a camera move or a stutter is measured again, which can add up to about 20 seconds. Turn any frame-rate limiter mod and VSync off first.

## Mood

A colour mood laid over the Look, made to suit the style of your resource pack, or a purely magic one. Natural is
Etna's own look with a faint dreamy glow. The mood stays when you change the Look, and the Look when you change the
mood. Every mood keeps Visibility working, and its glow only ever adds light around bright things: textures stay
sharp.

* **Mood**:
  * **Natural**: Etna's own look, with a faint dreamy glow around bright things and a touch of warm film halation.
    Suits vanilla and any resource pack.
  * **Clear Day**: clean and crisp, a little more contrast and colour, cool shade and warm light. For vanilla-like
    packs: Faithful, Stay True, xali's Enhanced Vanilla, Vanilla Experience+, Default HD.
  * **Trailer**: like Minecraft's trailers, bright saturated colour, emerald grass, clear skies, soft blue-violet
    shadows and faint outlines. For flat, simple packs: Bare Bones, Dandelion, oCd.
  * **Storybook**: cosy and painterly, softer contrast, sage greens, honey wood, lavender skies, peach light and a
    strong dreamy glow. For cottagecore packs: Mizuno's 16 Craft, Jicklus.
  * **Old Kingdom**: heroic and weathered, deeper contrast, amber light against slate shadows, earthy greens and
    blues. For medieval packs: John Smith Legacy, Conquest, Excalibur, Ashen, Dokucraft.
  * **Ink & Colour**: comic book, bold clean colour and dark ink outlines around blocks and things (the outlines
    need Anti-Aliasing or Shadows on). For cartoon packs: Sphax PureBDcraft, SAPIXCRAFT.
  * **Lifelike**: grounded and filmic, natural colour, gentle highlights and a barely there teal and orange. For
    realistic and PBR packs: Patrix, rotrBLOCKS, LB Photo Realism, Modern HD.
  * **Enchanted**: a fairy tale, rose-gold light, plum shadows, golden greens, violet skies and a soft glow around
    everything bright.
  * **Mystic Twilight**: cool and mysterious, teal shadows, violet-pink highlights, cool light that makes torches and
    glowing blocks stand out.
  * **Harvest Autumn**: grass and leaves turn amber and gold under warm, low light. Lovely with Jicklus' autumn
    add-ons.
  * **Dreamy Pastel**: soft and airy, brighter milky pastel colours, mint greens, lilac skies, lifted blacks and the
    strongest dreamy glow.
* **Mood Strength** (default 100%): How strongly the mood is applied. 0% (Off) turns the mood off, dreamy glow included, and
  leaves the Look alone.

In the Nether and the End the moods keep their tone, glow and contrast but leave the hues alone, and the dreamy glow
grows a little at sunrise and sunset.

## Lighting & Shadows

Sunlight, moonlight, shadows, the faint light in caves, and the light on mobs and items.

* **Shadows**: Real shadows cast by the sun and the moon.
* **Shadow Quality**: Detail of the shadow map: higher is sharper and slower.
* **Shadow Distance**: How far from you shadows are drawn. Further spreads the same detail over a bigger area.
* **Shadow Softness**: How soft the edges of shadows are.
* **Shadow Darkness**: How dark the shade is next to sunlight.
* **Sunlight**: Strength of direct sunlight.
* **Cave Light**: Caves and dark rooms are never pitch black: a faint, cool light shows their shape.
* **Entity Lighting** (default On): Mobs, chests, armour, dropped and held items and your hand take the same sunlight, blue shade, shadows and warm torch light as the land around them. Off keeps Minecraft's own flat light on them. Your hand takes the shade of whatever stands over you. Not part of the Look or the Quality. Items and mobs in the inventory and other menus always keep Minecraft's light; with Improved Transparency on, translucent mobs (and a few translucent items) keep it in the world too.

## Surfaces

How blocks catch the light: the bumps in their textures, shine, leaves lit from behind, torchlight, blocks that glow, materials, puddles, sparkles and glowing flowers.

* **Surface Detail**: Gives block textures real bumps that catch the sun and throw tiny shadows, worked out from the textures themselves, so it works with any resource pack.
* **Shine**: Sun glints on smooth blocks, and wet, shiny ground when it rains.
* **Leaf Glow**: Leaves and grass glow when the sun shines through them from behind.
* **Torch Light**: Warm orange light from torches, lanterns and lava, fading softly with distance.
* **Glowing Blocks**: Lava, glowstone, sea lanterns and lanterns glow in the dark, brightly enough to bloom, and by day their glowing parts stay lit on the shaded sides too. Needs Smooth Lighting.

Materials, Puddles, Sparkles and Glowing Flowers (0.8.0) are not part of the Look: they stay where you put them
when you change it, and Reset All puts them back to 70 %, 70 %, 60 % and 50 %. They work with any resource pack:
blocks are told apart by their names as the world is built, and the shine comes from their own textures.

* **Materials**: Blocks shine by what they are made of, with any resource pack: iron, gold, copper and netherite mirror the sky in their own colour, diamond, emerald, lapis and amethyst catch it brightly, polished stone and quartz get a soft sheen, glass and ice reflect the sky at low angles, and the specks of ores glint. The bright specks of redstone, crying obsidian, amethyst crystals, glow lichen, sculk and magma glow softly as Glowing Blocks says. Off is Etna 0.7's plain sheen.
* **Puddles**: Puddles gather on flat open ground in the rain and dry up slowly afterwards, mirroring the sky and the sun, with rings where the raindrops fall. Wet ground needs Shine.
* **Sparkles**: Snow, sand and ice sparkle in the sun and faintly under the moon, gems and the specks of ores glint, and in a cave ores and amethyst glitter in your torchlight as you walk past. Each sparkle stays put while you stand still.
* **Glowing Flowers**: At night, in caves and in the Nether, the petals and berries of flowers and plants glow faintly, breathing slowly. Stems, leaves and ripe crops stay dark.

## Sky & Air

The sky's colours, the glow around the sun and the air between you and the distance.

* **Sun Path Tilt**: How far the path of the sun and the moon leans to the south. Tilted, the noon sun stands to one side, south walls catch the light and shadows fall to the north. At 0 (Off) the sun keeps vanilla's path, straight overhead.
* **Sky Glow**: The warm glow the haze makes around the sun, strongest at sunrise and sunset.
* **Atmosphere**: Distant land fades into blue air, and towards the sun into a warm haze.
* **Distance Haze**: Far terrain slowly takes the colour of the air.
* **Mist**: Low mist in the valleys at dawn and dusk, a soft haze at night and in the rain, and a faint murk deep in caves. Lit by the sun where it reaches.
* **Cloud Shadows**: Clouds drifting over the sun cast soft shadows that sweep across the land. Off when clouds are off in Video Settings.
* **Light Rays**: Shafts of sunlight through the air and the mist, golden when the sun is low and strongest under trees. Needs Shadows.
* **Nether Air** (default 60%): The Nether's own air: a haze that glows in each biome's colour, a fiery mist over the lava sea and warm lava light on the rock above it. A faint violet haze in the End. The near distance stays clear. Not part of the Look. The haze and the mist need a world pass (Anti-Aliasing or Shadows); the lava light shows without one.
* **Biome Sky Color** (default 50%): How much the sky takes on each biome's own colour, a touch more turquoise over deserts and more lavender over snow, as in vanilla. It changes gently from one biome to the next. Off keeps Etna's one sky everywhere. Not part of the Look.

## Sky Wonders

The night sky's wonders, rainbows and the End's sky. None of them is part of the Look, and all of them show only while Etna draws the sky (in the open air, not under water).

* **Night Sky** (Off, Stars, Stars & Milky Way, Nebula Colors; default Stars & Milky Way): stars alone, or with the Milky Way, its glowing clouds and dark dust lanes, more stars along its band. Nebula Colors adds faint rose and teal glows. A bright moon washes the Milky Way out.
* **Aurora** (Off, Cold Biomes, Cold Biomes & Full Moon, Every Night; default Cold Biomes & Full Moon): green curtains, violet at the top, folding slowly over the northern sky on clear nights. It fades in over a few seconds as you walk into a snowy biome; under a full moon it also shows, a little fainter, anywhere. The land below takes on a faint green from it.
* **Shooting Stars** (Off, Rare, Frequent; default Rare): about one every two minutes, or one every half minute.
* **Round Moon** (default On): a round moon with seas, lit to match its phase, with a soft glow and a 22 degree halo on cold clear nights. Off shows the resource pack's moon.
* **Rainbows** (Off, After Rain, Always; default After Rain): a faint bow with its dimmer second bow and the darker band between them, opposite a low sun, just after a rain has cleared (or on any clear morning and evening).
* **End Sky** (default On): the End as a dark violet void with a drifting nebula, small dense stars and a bright glow for its flashes. Off keeps vanilla's End sky.

## Water

How water looks from above and below: how far you see under water, its colour, and the light dancing on the seabed.

* **Underwater Visibility**: How far you can see under water. Things far away fade into the water's colour, reds first.
* **Water Clarity**: How deep sunlight reaches when you are under water, and how clear shallow water looks from above (with Shadows on).
* **Caustics**: Rippling light the waves focus onto the ground, while you swim under water.
* **Underwater Light Rays**: Beams of sunlight through the water, and shade under overhangs. Needs Shadows.
* **Water Reflections**: Reflections on water: the sky and sun glints, and the shore, trees, hills and clouds around it (see below).
* **Shoreline Foam**: A thin, soft line of foam where water meets the shore, drifting slowly, lit like the ground beside it. Needs Shadows. It stays where you put it when you change the Look. Default 50%.
* **Glitter**: Sparkles of the sun on the waves along its reflection, and of the moon on lakes at night. Each spark fades in and out over a few frames, so nothing flickers. It stays where you put it when you change the Look. Default 50%.

The water reflects the world around it with Shadows or Anti-Aliasing on (when a world pass runs) and Improved
Transparency off in Video Settings: whatever is on screen above the water shows in it, along the same waves as the
sky's reflection, and what is off screen or hidden falls back to the sky. With Improved Transparency on the water
is drawn another way and only mirrors the sky. The foam is found from the shadow map, so it is gone at dusk and in
rain while the map is not drawn, and next to a high bank it shows on the side the light comes from.

## Effects

Anti-aliasing, ambient occlusion, glow and sharpening.

* **Anti-Aliasing**: Temporal anti-aliasing: smooth edges that stay still instead of crawling and flickering. Ambient Occlusion needs it; Sky Glow, Atmosphere and Mist need it or Shadows.
* **Ambient Occlusion**: Soft shade in corners, in cracks and under things.
* **Bloom**: A soft glow around the brightest things only: the sun, clouds, snow, flames and glowing blocks.
* **Bloom Size**: How far the glow spreads.
* **Sharpen**: Brings back crisp texture detail.
* **Fireflies** (Off, Few, Normal, Many; Normal by default): Fireflies glow and flash softly over grass, flowers
  and firefly bushes from dusk to dawn, more by the water, never in the rain. Pale motes drift down in the Pale
  Garden, spores rise in lush caves, soul wisps circle over soul sand and violet motes float in the End. This sets
  how many there are at most (8, 18 or 32). The game places them, so they never float over bare sand or roofs.
  Drawn by the world pass, so it needs Anti-Aliasing or Shadows. Not part of the Look, and Reset All puts it back
  to Normal.
* **Rain** (Etna, Vanilla; default Etna): Etna draws thin, soft rain that takes the colour of the sky and glows warm by torches and lanterns, and softer snow, both leaning with the wind. Vanilla keeps Minecraft's own rain and snow. Not part of the Look.
* **Bloom Fog** (default 40%): At night, in the rain, in caves and in the Nether, distant lights and lit places glow softly in the damp or smoky air. The farther away, the more they glow; dark scenery and the sky at night add next to nothing, so the picture never turns milky. Needs Bloom. Not part of the Look.

## Colors

The final colour grade. Moving any of these switches Look to Custom.

* **Brightness**: Overall exposure.
* **Eye Adaptation**: Your eyes adjust to the light where you stand, over a few seconds: caves and nights read a little brighter, a sunny noon a little calmer, and at night, out under the sky, the darkest colours fade towards a cool moonlit grey. Off keeps the exposure fixed.
* **Contrast**: Deeper darks and brighter lights, without clipping either.
* **Saturation**: Strength of every colour.
* **Vibrance**: Boosts dull colours more than vivid ones.
* **Warmth**: White balance: cooler and bluer, or warmer and more golden.
* **Vignette**: Darker corners of the screen, where they are dark: a bright sky or snow in a corner stays as it is.

## The config file

`config/etna.properties` holds everything the screen sets, plus a few options that only live in the file. Edit it
with the game closed.

* `quality` (`low`, `medium`, `high`, `ultra`, `custom`): `shadows`, `shadowResolution`, `shadowDistance` and
  `antiAliasing` are read back only with `quality=custom`.
* `profile` (`natural`, `soft`, `vivid`, `cinematic`, `custom`): the look values are read back only with
  `profile=custom`.
* `toggleKey`: the key code of the on/off key, F7 by default.
* `halfResolution` (`true`/`false`): macOS only, applies after a restart, and only when Etna is on as the game
  starts.
* `renderScale` (`0.5` to `1`): the share of the screen's width and height the world is drawn at, with
  anti-aliasing on; `1` (the default) draws it at full size.
* `sunPathTilt`: 0 to 45 degrees; 0 is vanilla's path straight overhead.
* `visibility`: 0 to 1; 0 is 0.7.0's darkness.
* `mood` (`natural`, `clear_day`, `trailer`, `storybook`, `old_kingdom`, `ink`, `lifelike`, `enchanted`, `mystic`,
  `autumn`, `pastel`) and `moodStrength` (0 to 1): kept whatever the profile.
* `fireflies` (`off`, `few`, `normal`, `many`): how many fireflies and motes float around at most.
* `rainLook` (`etna`, `vanilla`), `bloomFog` (0 to 1, 0 is off) and `netherAir` (0 to 1, 0 is off): kept whatever
  the profile, like `visibility`.
* `materials`, `puddles`, `sparkles`, `glowingFlowers`: 0 to 1 each (0 turns that surface effect off); kept
  whatever the profile.
* `profiling` (`off`, `summary`): the performance line in `logs/latest.log`.
* `shadowCulling` (`true`/`false`): `false` draws every shadow caster, to compare.
* `shadowGather` (`true`/`false`): `false` reads the shadow map one texel at a time, in case a graphics driver
  gets the faster way wrong. Applies after a restart.
* `nightSky` (`off`, `stars`, `milky_way`, `nebula`), `aurora` (`off`, `cold`, `cold_full_moon`, `every_night`),
  `shootingStars` (`off`, `rare`, `frequent`), `rainbows` (`off`, `after_rain`, `always`), `roundMoon` and `endSky`
  (`true`/`false`), `biomeSkyColor` (0 to 1): the Sky Wonders page and Biome Sky Color, kept whatever the look.
