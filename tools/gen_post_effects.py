"""Regenerates assets/etna/post_effect/*.json. Edit the numbers here, then run: python3 tools/gen_post_effects.py

Three chains:
- etna_world runs right after the level is drawn and before the hand (EtnaFrame.java): the light shafts at
  half resolution, then ambient occlusion, sky glow, atmosphere and mist in one pass, then temporal
  anti-aliasing, which need the depth of the world alone. EtnaFrame runs it while anti-aliasing is on.
- etna_world_plain takes its place with anti-aliasing off and shadows on: the same light shafts and the same
  world pass (where ambient occlusion is off, as it needs the anti-aliasing to smooth it), without the
  anti-aliasing. The shafts' noise is blurred by the world pass instead of averaged over frames. With both
  off (Low quality) no world chain runs, as before 0.7.0.
- etna runs after the hand, as one of Minecraft's own post effects: bloom and the colour grade.

Everything the settings menu controls lives in the EtnaSettings block, one buffer all passes share and
Etna rewrites once per frame (EtnaLook.java); the value written here for it is only a placeholder. The
same goes for the shafts pass's EtnaShadow block and its "EtnaShadow" input: PostPassMixin hands that pass
the terrain's shadow block and the shadow map itself (EtnaShadows.java), which a chain can't name. Every
target an Etna chain owns is a 16-bit float texture (PostChainMixin), so bloom and the anti-aliasing
history keep their precision, except the colour grade's result, which is already clamped and dithered to
8 bits. Every Etna pass draws every pixel of its target, so its targets are neither cleared when the frame
takes them nor loaded when the pass starts (PostChainMixin, PostPassMixin).
"""
import json
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/etna/post_effect"
VS = "minecraft:core/screentriangle"
MAIN = "minecraft:main"

PLACEHOLDER = [{"name": "Placeholder", "type": "vec4", "value": [0.0, 0.0, 0.0, 0.0]}]
SETTINGS = {"EtnaSettings": PLACEHOLDER}
SETTINGS_AND_SHADOW = {"EtnaSettings": PLACEHOLDER, "EtnaShadow": PLACEHOLDER}

# Bloom levels, sized for a 1920x1080 screen: PostChainMixin scales them to the real screen and its
# shape, so the first level is always half the screen (its filter covers every pixel), and the glow
# stays round and the same size on screen at any resolution.
BLOOM_SIZES = [(960, 540), (480, 270), (240, 135), (120, 68), (60, 34), (30, 17)]

# The anti-aliasing history: two persistent targets used in turn. The pass reads last frame's result
# from the first and writes this frame's into the second, and PostChainMixin swaps the two textures
# behind these names every frame (EtnaFrame.historySwapped), so the result never has to be copied.
# PostChainMixin knows these names.
HISTORY_READ = "history0"
HISTORY_WRITE = "history1"

# The light shafts' lit share and ray length, at half the screen's size (sized for 1920x1080 and scaled, as
# the bloom levels are).
SHAFTS_SIZE = (960, 540)


def pas(fragment, inputs, output, settings=False, shadow=False):
    p = {"vertex_shader": VS, "fragment_shader": fragment, "inputs": inputs, "output": output}
    if shadow:
        p["uniforms"] = SETTINGS_AND_SHADOW
    elif settings:
        p["uniforms"] = SETTINGS
    return p


def inp(sampler, target, bilinear=False, depth=False):
    i = {"sampler_name": sampler, "target": target}
    if bilinear:
        i["bilinear"] = True
    if depth:
        i["use_depth_buffer"] = True
    return i


def shafts_target():
    w, h = SHAFTS_SIZE
    return {"width": w, "height": h}


def shafts_pass():
    # The "EtnaShadow" input names main's depth only so the chain loads; PostPassMixin swaps it for the
    # shadow map before the pass is ever drawn.
    return pas("etna:post/shafts", [inp("Depth", MAIN, depth=True), inp("EtnaShadow", MAIN, depth=True)], "shafts",
               shadow=True)


def world_light_pass(output):
    return pas("etna:post/world_light", [inp("In", MAIN), inp("Depth", MAIN, depth=True), inp("Shafts", "shafts")], output,
               settings=True)


def world():
    return {
        "targets": {"shafts": shafts_target(), "air": {}, HISTORY_READ: {"persistent": True},
                    HISTORY_WRITE: {"persistent": True}},
        "passes": [
            shafts_pass(),
            # Before anti-aliasing, so the sky and the air in front of the land are smoothed with the rest.
            world_light_pass("air"),
            pas("etna:post/taa",
                [inp("In", "air"), inp("History", HISTORY_READ, bilinear=True), inp("Depth", MAIN, depth=True)],
                HISTORY_WRITE, settings=True),
            pas("etna:post/copy_dither", [inp("In", HISTORY_WRITE)], MAIN, settings=True),
        ],
    }


def world_plain():
    return {
        "targets": {"shafts": shafts_target(), "lit": {}},
        "passes": [
            shafts_pass(),
            world_light_pass("lit"),
            pas("etna:post/copy_dither", [inp("In", "lit")], MAIN, settings=True),
        ],
    }


def final():
    targets = {}
    for i, (w, h) in enumerate(BLOOM_SIZES):
        targets[f"bloom{i}"] = {"width": w, "height": h}
        if i < len(BLOOM_SIZES) - 1:
            targets[f"up{i}"] = {"width": w, "height": h}
    # 8 bits per channel (PostChainMixin knows this name).
    targets["graded"] = {}

    last = len(BLOOM_SIZES) - 1
    passes = [pas("etna:post/bloom_prefilter", [inp("In", MAIN, bilinear=True)], "bloom0", settings=True)]
    for i in range(1, last + 1):
        passes.append(pas("etna:post/bloom_down", [inp("In", f"bloom{i - 1}", bilinear=True)], f"bloom{i}"))
    for i in range(last - 1, -1, -1):
        src = f"bloom{last}" if i == last - 1 else f"up{i + 1}"
        passes.append(pas("etna:post/bloom_up", [inp("Dst", f"bloom{i}", bilinear=True), inp("Src", src, bilinear=True)],
                          f"up{i}", settings=True))
    passes.append(pas("etna:post/final", [inp("In", MAIN), inp("Bloom", "up0", bilinear=True)], "graded", settings=True))
    passes.append(pas("etna:post/copy_opaque", [inp("In", "graded")], MAIN))
    return {"targets": targets, "passes": passes}


OUT.mkdir(parents=True, exist_ok=True)
for old in OUT.glob("*.json"):
    old.unlink()
for name, build in (("etna", final), ("etna_world", world), ("etna_world_plain", world_plain)):
    path = OUT / f"{name}.json"
    path.write_text(json.dumps(build(), indent=4) + "\n")
    print("wrote", path)
