"""Regenerates assets/etna/post_effect/*.json. Edit the numbers here, then run: python3 tools/gen_post_effects.py

Three chains:
- etna_world runs right after the level is drawn and before the hand (EtnaFrame.java): the light shafts and
  the water's reflections at half resolution, then the reflections, ambient occlusion, sky glow, atmosphere
  and mist in one pass, then temporal anti-aliasing, which need the depth of the world alone. EtnaFrame runs
  it while anti-aliasing is on.
- etna_world_plain takes its place with anti-aliasing off and shadows on: the same light shafts, reflections
  and world pass (where ambient occlusion is off, as it needs the anti-aliasing to smooth it), without the
  anti-aliasing. The shafts' noise is blurred by the world pass instead of averaged over frames, and the
  reflections take more steps without noise. With both off (Low quality) no world chain runs, as before
  0.7.0.
- etna runs after the hand, as one of Minecraft's own post effects: bloom, the mood's dreamy glow and the
  colour grade.

Everything the settings menu controls lives in the EtnaSettings block, one buffer all passes share and
Etna rewrites once per frame (EtnaLook.java); the value written here for it is only a placeholder. The
same goes for the EtnaShadow block of the shafts and reflections passes and the shafts pass's "EtnaShadow"
input: PostPassMixin hands those passes the terrain's shadow block and the shadow map itself
(EtnaShadows.java), which a chain can't name, and for the last world pass's EtnaMotes block: PostPassMixin
hands it the fireflies' buffer (EtnaMotes.java). Every
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
SETTINGS_AND_MOTES = {"EtnaSettings": PLACEHOLDER, "EtnaMotes": PLACEHOLDER}

# Bloom levels, sized for a 1920x1080 screen: PostChainMixin scales them to the real screen and its
# shape, so the first level is always half the screen (its filter covers every pixel), and the glow
# stays round and the same size on screen at any resolution.
BLOOM_SIZES = [(960, 540), (480, 270), (240, 135), (120, 68), (60, 34), (30, 17)]

# The mood's dreamy glow (grade.glsl): the whole picture shrunk to a quarter of the screen (glow_prefilter, an
# exact 4x4 average there), then halved three times with the bloom's own filter, sized for 1920x1080 and
# scaled like the bloom levels. glow_combine mixes the levels into one quarter-size texture, the only one the
# final pass reads for it.
GLOW_SIZES = [(480, 270), (240, 135), (120, 68), (60, 34)]

# The anti-aliasing history: two persistent targets used in turn. The pass reads last frame's result
# from the first and writes this frame's into the second, and PostChainMixin swaps the two textures
# behind these names every frame (EtnaFrame.historySwapped), so the result never has to be copied.
# PostChainMixin knows these names.
HISTORY_READ = "history0"
HISTORY_WRITE = "history1"

# Render scale (EtnaScale.java has both): Minecraft's full-size picture in the scaled world chain, and the size
# that makes an Etna target as large as the screen.
SCREEN = "etna:screen"
SCREEN_SIZED = 16384
# A fixed size is written as this plus the size for a 1920x1080 screen, and PostChainMixin scales it to the size
# the chain runs at (EtnaScale.REFERENCE_SIZED). Without the offset a size that happened to equal the chain's own
# (960 wide on a 1280-wide picture drawn at 75%) couldn't be told from a target given no size at all.
REFERENCE_SIZED = 20000


def sized(w, h):
    return {"width": REFERENCE_SIZED + w, "height": REFERENCE_SIZED + h}

# The light shafts' lit share and ray length, at half the screen's size (sized for 1920x1080 and scaled, as
# the bloom levels are).
SHAFTS_SIZE = (960, 540)


def pas(fragment, inputs, output, settings=False, shadow=False, motes=False):
    p = {"vertex_shader": VS, "fragment_shader": fragment, "inputs": inputs, "output": output}
    if shadow:
        p["uniforms"] = SETTINGS_AND_SHADOW
    elif motes:
        p["uniforms"] = SETTINGS_AND_MOTES
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
    return sized(*SHAFTS_SIZE)


def water_ssr_target():
    # The same half size as the shafts: the world pass maps its pixels onto both the same way.
    return shafts_target()


def shafts_pass():
    # The "EtnaShadow" input names main's depth only so the chain loads; PostPassMixin swaps it for the
    # shadow map before the pass is ever drawn.
    return pas("etna:post/shafts", [inp("Depth", MAIN, depth=True), inp("EtnaShadow", MAIN, depth=True)], "shafts",
               shadow=True)


def water_ssr_pass():
    # Reads the picture (its alpha marks the water) and the depth the water left; the EtnaShadow block holds
    # this frame's sky, which the water mirrored.
    return pas("etna:post/water_ssr", [inp("In", MAIN), inp("Depth", MAIN, depth=True)], "water_ssr", shadow=True)


def world_light_pass(output):
    return pas("etna:post/world_light", [inp("In", MAIN), inp("Depth", MAIN, depth=True), inp("Shafts", "shafts"),
                                         inp("WaterSsr", "water_ssr")], output, settings=True)


def copy_pass(source, output=MAIN):
    # The last world pass, which also draws the fireflies and motes: their block is EtnaMotes.java's own
    # buffer, which PostPassMixin swaps in for the placeholder, and main's depth hides them behind surfaces.
    return pas("etna:post/copy_dither", [inp("In", source), inp("Depth", MAIN, depth=True)], output, motes=True)


def world(scaled=False):
    # With a render scale (EtnaScale) the chain runs on the smaller picture as main, keeps the anti-aliasing history
    # at the screen's own size (PostChainMixin makes a SCREEN_SIZED target that size) and the last pass writes
    # Minecraft's full-size picture, which the chain calls SCREEN.
    history = {"persistent": True}
    if scaled:
        history.update({"width": SCREEN_SIZED, "height": SCREEN_SIZED})
    return {
        "targets": {"shafts": shafts_target(), "water_ssr": water_ssr_target(), "air": {},
                    HISTORY_READ: dict(history), HISTORY_WRITE: dict(history)},
        "passes": [
            shafts_pass(),
            water_ssr_pass(),
            # Before anti-aliasing, so the sky and the air in front of the land are smoothed with the rest.
            world_light_pass("air"),
            pas("etna:post/taa",
                [inp("In", "air"), inp("History", HISTORY_READ, bilinear=True), inp("Depth", MAIN, depth=True)],
                HISTORY_WRITE, settings=True),
            copy_pass(HISTORY_WRITE, SCREEN if scaled else MAIN),
        ],
    }


def world_scaled():
    return world(scaled=True)


def world_plain():
    return {
        "targets": {"shafts": shafts_target(), "water_ssr": water_ssr_target(), "lit": {}},
        "passes": [
            shafts_pass(),
            water_ssr_pass(),
            world_light_pass("lit"),
            copy_pass("lit"),
        ],
    }


def final():
    targets = {}
    for i, (w, h) in enumerate(BLOOM_SIZES):
        targets[f"bloom{i}"] = sized(w, h)
        if i < len(BLOOM_SIZES) - 1:
            targets[f"up{i}"] = sized(w, h)
    for i, (w, h) in enumerate(GLOW_SIZES):
        targets[f"glow{i}"] = sized(w, h)
    targets["glow"] = sized(*GLOW_SIZES[0])
    # 8 bits per channel (PostChainMixin knows this name).
    targets["graded"] = {}

    last = len(BLOOM_SIZES) - 1
    # The bloom fog reads main's depth: the final chain runs after the hand, whose depth vanilla merges into
    # the level's (integrate_depth), so it holds the world's distances with the hand in front. With a render
    # scale the level's depth is the smaller picture's (EtnaScale.LevelDepth stands in for "Depth") and main's
    # own holds only the hand: "HandDepth" (PostPassMixin leaves it alone) masks the hand out.
    passes = [pas("etna:post/bloom_prefilter", [inp("In", MAIN, bilinear=True), inp("Depth", MAIN, depth=True),
                                                inp("HandDepth", MAIN, depth=True)], "bloom0", settings=True)]
    for i in range(1, last + 1):
        passes.append(pas("etna:post/bloom_down", [inp("In", f"bloom{i - 1}", bilinear=True)], f"bloom{i}"))
    for i in range(last - 1, -1, -1):
        src = f"bloom{last}" if i == last - 1 else f"up{i + 1}"
        passes.append(pas("etna:post/bloom_up", [inp("Dst", f"bloom{i}", bilinear=True), inp("Src", src, bilinear=True)],
                          f"up{i}", settings=True))
    passes.append(pas("etna:post/glow_prefilter", [inp("In", MAIN, bilinear=True)], "glow0"))
    for i in range(1, len(GLOW_SIZES)):
        passes.append(pas("etna:post/bloom_down", [inp("In", f"glow{i - 1}", bilinear=True)], f"glow{i}"))
    passes.append(pas("etna:post/glow_combine",
                      [inp("Narrow", "glow1", bilinear=True), inp("Mid", "glow2", bilinear=True),
                       inp("Wide", "glow3", bilinear=True)], "glow", settings=True))
    passes.append(pas("etna:post/final", [inp("In", MAIN), inp("Bloom", "up0", bilinear=True), inp("Glow", "glow", bilinear=True)],
                      "graded", settings=True))
    passes.append(pas("etna:post/copy_opaque", [inp("In", "graded")], MAIN))
    return {"targets": targets, "passes": passes}


OUT.mkdir(parents=True, exist_ok=True)
for old in OUT.glob("*.json"):
    old.unlink()
for name, build in (("etna", final), ("etna_world", world), ("etna_world_scaled", world_scaled),
                    ("etna_world_plain", world_plain)):
    path = OUT / f"{name}.json"
    path.write_text(json.dumps(build(), indent=4) + "\n")
    print("wrote", path)
