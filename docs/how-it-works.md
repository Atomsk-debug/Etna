# How Etna works

Minecraft 26.4 renders through its own Vulkan backend (or OpenGL) and still compiles its GLSL shaders itself.
Etna works inside that renderer:

* While Etna is on, the terrain, the clouds and the sky draw through Etna's own copies of the game's pipelines,
  with Etna's shaders for lighting, shadows, water and the sky.
* A world post chain runs after the terrain (light shafts and mist at half resolution, ambient occlusion, distance
  air, temporal anti-aliasing), and a final chain after the hand (bloom, tone and colour grade), both in the
  game's own post-processing format.
* A few mixins draw the shadow map and bind Etna's uniforms and textures.
* Through Fabric's model API, leaves and plants carry a tag in their vertex colour while Etna is on, so the
  shader can sway them.

Etna replaces none of the game's own files, so with Etna off (F7) the game looks exactly like vanilla. The shaders
are in `src/main/resources/assets/etna/shaders/`, and a resource pack can override any of them (reload with F3+T).

## Building

```
./gradlew build
```

Needs Java 25; the jar lands in `build/libs/`. GitHub Actions builds every push, and a push to `main` with a new
`mod_version` publishes it as a release with its jar. `python3 tools/validate_shaders.py` compiles every shader
variant the game uses to SPIR-V with glslang.
