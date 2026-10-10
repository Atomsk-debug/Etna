# Etna settings

Every option of **Options > Etna Shaders...**, page by page. The settings screen shows the same text next to each
option, and every change shows in the world right away. **F7** turns Etna on and off in game, and **Reset All**
puts every page back to High quality, the Natural look, 30 % Visibility and a 25 degree sun path.

Two presets set most of the rest:

* **Quality** (General page) sets how much work Etna does: the shadow options on Lighting & Shadows and
  Anti-Aliasing on Effects. Changing one of those switches Quality to Custom.
* **Look** (General page) sets the colours and the mood: the sliders on every other page. Moving one of them
  switches Look to Custom. Visibility and Sun Path Tilt are not part of the Look.

All settings are saved in `config/etna.properties` (see [the end of this page](#the-config-file)).

## General

Start here. Quality sets how much work Etna does, Look sets the colours and mood; the other pages fine-tune them. Every change shows right away. In game, F7 turns Etna on and off.

* **Shaders**: Turns Etna on or off. F7 does the same in game.
* **Quality**: Low: no shadows or anti-aliasing, the fastest. Medium: shadows close by. High: the recommended balance. Ultra: sharp shadows far away.
* **Look**: Natural keeps Minecraft's own colours under real light. Soft is gentler and hazier, Vivid more saturated, Cinematic warmer with more contrast and glow.
* **Visibility**: How well you see in the dark, without making bright places brighter: caves get more of their faint light (with Cave Light on), sun and moon shadows get lighter, your eyes open up further in dark places (with Eye Adaptation on) and the darkest tones are lifted. Bright skies, snow and sunlit ground stay as they are. 0% (Off) is Etna 0.7.0's darkness. It stays where you put it when you change the Look.
* **Resolution** *(macOS only)*: Half draws a quarter of the pixels and lets macOS scale the picture up: much faster, but blurry. Applies after restarting the game, and only when Etna is on as the game starts.
* **Performance log**: Summary writes a line to latest.log every few seconds while you play: frame rate, frame time and where it goes, on the CPU and on the GPU (timed on one frame in eight, so the frame rate stays the normal one). Off writes nothing.
* **Run benchmark**: Takes about 100 seconds, in a world: Etna tries its settings one at a time, writes what each one costs to latest.log, then puts everything back as it was. Stand still and don't move the mouse: a step disturbed by a camera move or a stutter is measured again, which can add up to about 20 seconds. Turn any frame-rate limiter mod and VSync off first.

## Lighting & Shadows

Sunlight, moonlight, shadows, and the faint light in caves.

* **Shadows**: Real shadows cast by the sun and the moon.
* **Shadow Quality**: Detail of the shadow map: higher is sharper and slower.
* **Shadow Distance**: How far from you shadows are drawn. Further spreads the same detail over a bigger area.
* **Shadow Softness**: How soft the edges of shadows are.
* **Shadow Darkness**: How dark the shade is next to sunlight.
* **Sunlight**: Strength of direct sunlight.
* **Cave Light**: Caves and dark rooms are never pitch black: a faint, cool light shows their shape.

## Surfaces

How blocks catch the light: the bumps in their textures, shine, leaves lit from behind, torchlight and blocks that glow.

* **Surface Detail**: Gives block textures real bumps that catch the sun and throw tiny shadows, worked out from the textures themselves, so it works with any resource pack.
* **Shine**: Sun glints on smooth blocks, and wet, shiny ground when it rains.
* **Leaf Glow**: Leaves and grass glow when the sun shines through them from behind.
* **Torch Light**: Warm orange light from torches, lanterns and lava, fading softly with distance.
* **Glowing Blocks**: Lava, glowstone, sea lanterns and lanterns glow in the dark, brightly enough to bloom, and by day their glowing parts stay lit on the shaded sides too. Needs Smooth Lighting.

## Sky & Air

The sky's colours, the glow around the sun and the air between you and the distance.

* **Sun Path Tilt**: How far the path of the sun and the moon leans to the south. Tilted, the noon sun stands to one side, south walls catch the light and shadows fall to the north. At 0 (Off) the sun keeps vanilla's path, straight overhead.
* **Sky Glow**: The warm glow the haze makes around the sun, strongest at sunrise and sunset.
* **Atmosphere**: Distant land fades into blue air, and towards the sun into a warm haze.
* **Distance Haze**: Far terrain slowly takes the colour of the air.
* **Mist**: Low mist in the valleys at dawn and dusk, a soft haze at night and in the rain, and a faint murk deep in caves. Lit by the sun where it reaches.
* **Cloud Shadows**: Clouds drifting over the sun cast soft shadows that sweep across the land. Off when clouds are off in Video Settings.
* **Light Rays**: Shafts of sunlight through the air and the mist, golden when the sun is low and strongest under trees. Needs Shadows.

## Water

How water looks from above and below: how far you see under water, its colour, and the light dancing on the seabed.

* **Underwater Visibility**: How far you can see under water. Things far away fade into the water's colour, reds first.
* **Water Clarity**: How deep sunlight reaches when you are under water, and how clear shallow water looks from above (with Shadows on).
* **Caustics**: Rippling light the waves focus onto the ground, while you swim under water.
* **Underwater Light Rays**: Beams of sunlight through the water, and shade under overhangs. Needs Shadows.
* **Water Reflections**: Sky reflections and sun glints on water.

## Effects

Anti-aliasing, ambient occlusion, glow and sharpening.

* **Anti-Aliasing**: Temporal anti-aliasing: smooth edges that stay still instead of crawling and flickering. Ambient Occlusion needs it; Sky Glow, Atmosphere and Mist need it or Shadows.
* **Ambient Occlusion**: Soft shade in corners, in cracks and under things.
* **Bloom**: A soft glow around the brightest things only: the sun, clouds, snow, flames and glowing blocks.
* **Bloom Size**: How far the glow spreads.
* **Sharpen**: Brings back crisp texture detail.

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
* `sunPathTilt`: 0 to 45 degrees; 0 is vanilla's path straight overhead.
* `visibility`: 0 to 1; 0 is 0.7.0's darkness.
* `profiling` (`off`, `summary`): the performance line in `logs/latest.log`.
* `shadowCulling` (`true`/`false`): `false` draws every shadow caster, to compare.
* `shadowGather` (`true`/`false`): `false` reads the shadow map one texel at a time, in case a graphics driver
  gets the faster way wrong. Applies after a restart.
