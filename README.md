<div align="center">

# 🌋 Etna

**Golden light for the game's own renderer.**
A shader mod for Minecraft's native Vulkan renderer.

*Part of a family of Italian volcanoes:* **Etna** (shaders) ·
[Vesuvio](https://github.com/Atomsk-debug/Vesuvio) (performance) · [Stromboli](https://github.com/Atomsk-debug/Stromboli) (quality of life)

</div>

---

Etna is a Fabric mod that brings shaders to **Minecraft 26.4 snapshots** on the game's own **Vulkan renderer**.
It is a single mod: no Iris, no Sodium, no OptiFine, no VulkanMod. Everything it draws goes through the game's own
renderer, and with Etna off (F7) the game looks exactly like vanilla. The look aims at warm, natural light: a
golden sun over blue shade, soft highlights, never neon.

Made first for Apple Silicon Macs (MoltenVK), and meant to work on Windows and Linux as well.

> **Status: in development, playable.** Each version is tested in game before it ships.
> **[Download the latest release](https://github.com/Atomsk-debug/Etna/releases/latest)** · [Changelog](CHANGELOG.md)

## Features

**Sky and light**
* Etna's own sky: blue days, deep orange sunsets, a navy night lit by the moon's phase, a sun disc and twinkling
  stars. The same sky colours the sunlight, the shade, the clouds, the distance and the water.
* Soft shadows from the sun and the moon that glide as the sun moves, plus shadows of drifting clouds.
* The sun's path leans south (25°, adjustable), so noon shadows have a direction.

**Air**
* Light shafts through trees at dawn and dusk, valley mist in the morning and evening, haze in rain.

**Surfaces**
* Relief and sheen worked out from each block's own texture, so any resource pack works.
* Ground gets wet in rain and dries slowly. Leaves glow with the sun behind them.
* Leaves, grass, flowers, crops, kelp and vines sway in gusty wind, bending from their roots.

**Light in the dark**
* Warm torchlight, a held torch lights your surroundings, lava and lanterns glow.
* Caves are never pitch black, and the **Visibility** slider makes dark places easier to read without
  brightening the rest.

**Water**
* Waves, sky reflections, sun glints on the ripples, clear shallows and deep blue depths.
* Under water: fog in the biome's water colour, caustics, light shafts on the sea floor and Snell's window.

**Image**
* Temporal anti-aliasing, ambient occlusion, sharpening, bloom on the brightest things only, a soft tone curve
  and eye adaptation.
* Four looks: Natural (default), Soft, Vivid and Cinematic.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer and
   [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft 26.4-snapshot-3, with Java 25. Prism Launcher
   makes this easy.
2. Download `etna-shaders-<version>+mc26.4-snapshot-3.jar` from
   [Releases](https://github.com/Atomsk-debug/Etna/releases/latest) and put it in your `mods` folder.
3. Keep the *Graphics API* video option on *Default* or *Prefer Vulkan* (F3 shows which one is in use).

## Settings

Options > **Etna Shaders...**, and **F7** turns Etna on and off in game.

* **Quality** (Low, Medium, High, Ultra) sets how much work Etna does; **Look** sets the colours and the mood.
* Pages for Lighting & Shadows, Surfaces, Sky & Air, Water, Effects and Colors fine-tune them, each option with
  a short description. Every change shows in the world right away.
* Settings are saved in `config/etna.properties`.
* A built-in benchmark times Etna on, off, and with each costly part off in turn, and writes the table to
  `logs/latest.log`.

Every setting, explained: [docs/settings.md](docs/settings.md) · Performance and how to benchmark:
[docs/benchmark.md](docs/benchmark.md) · How Etna works and how to build it: [docs/how-it-works.md](docs/how-it-works.md)

## Compatibility

* Needs the game's native renderer (Vulkan, or OpenGL). Sodium, Iris, VulkanMod and OptiFine replace that
  renderer, so they can't run together with Etna.
* Works with Vesuvio and Stromboli, and with resource packs, including ones with their own leaf and plant models.
* Made and tested on Apple Silicon Macs. Windows x64 and Linux are supported but less tested: please open an
  issue if something looks wrong there.
* Known limits: the Nether and the End keep vanilla's sky, and so do the views under water, in lava, with
  Blindness or Darkness and in boss fog; the moon is vanilla's. With *Improved Transparency* on, water seen from
  above loses its depth colour and Snell's window. A light held in your hand shines through walls.

## FAQ

**How fast is it?**
On a Mac mini M4 drawing 1920×1080 pixels with the Ultra preset, Etna 0.7.0 ran at 116 to 120 fps in exclusive
fullscreen.
Lower presets are faster; Low has no shadows and no anti-aliasing. See [docs/benchmark.md](docs/benchmark.md).

**Is the game really vanilla with Etna off?**
Yes. Etna replaces none of the game's files: with F7 off it draws with the game's own shaders, textures and
models. On a Mac, Half resolution applies only when Etna is on as the game starts.

**Why isn't it on Modrinth or CurseForge?**
A few parts come from Photon Shaders, whose licence doesn't allow those sites without its author's permission.
Releases are here on GitHub.

**Something looks wrong.**
Press F7: if it goes away, it's Etna. Note where and when it happens (time of day, near or far, which blocks) and
open an issue, with a screenshot if you can.

**Do I need Vesuvio?**
No. Etna works on its own; Vesuvio makes the game, and Etna's shadows, faster.

## Credits and licence

Etna's own code is under the MIT licence, see [LICENSE](LICENSE). Its terrain and cloud shaders are based on
Minecraft's vanilla shaders (© Mojang AB), which Etna's licence doesn't cover. A few small parts are ported from
[Photon Shaders](https://github.com/sixthsurge/photon) by SixthSurge (temporal anti-aliasing, shadow map
distortion, the water's Fresnel function) and stay under the Photon Shaders License (`LICENSE-PHOTON.txt`).
Complementary Reimagined inspired the look; none of its code is used. Everything taken from other projects and
every technique Etna follows is listed in [CREDITS.md](CREDITS.md).
