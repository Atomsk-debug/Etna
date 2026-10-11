"""Compiles every Etna shader to Vulkan SPIR-V with glslang, the way a Vulkan backend has to.

Minecraft's own preprocessor is mimicked: `#include <namespace:file.glsl>` is resolved from
assets/<namespace>/shaders/include, falling back to a copy of the vanilla includes, and defines are
injected after the #version/#extension lines. Etna's core shaders (assets/etna/shaders/core) are compiled
with every define combination the game builds them with: terrain, clouds and weather with those of the
vanilla pipelines Etna copies onto them (terrain has many; clouds vanilla's two and Etna's lit copy; weather
vanilla's and the Improved Transparency stages), the sky with the base defines; each with both depth conventions. In each combination the vertex outputs are checked
against the fragment inputs, after glslang's own preprocessor has resolved the #ifdefs.

Etna never overrides vanilla's own assets (assets/minecraft): with Etna off the game must look exactly as
without it, so any file there fails the check.

Each pass of Etna's post chains (assets/etna/post_effect) is also checked against its shader: a sampler
for every input, SamplerInfo laid out as the game fills it (OutSize, then one size per input in input
order), and a uniform group in the JSON for every other uniform block. A mismatch there still compiles and
only shows in game, as a chain that fails to load or a pass that reads another texture's size.

Usage: python3 tools/validate_shaders.py [vanilla_assets_dir]
vanilla_assets_dir defaults to build/vanilla-assets and is downloaded from the mcmeta mirror if missing.
"""
import itertools
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets"
MC_VERSION_BRANCH = "assets"  # misode/mcmeta branch tracking the newest snapshot
VANILLA_INCLUDES = [
    "fog", "globals", "projection", "sample_lightmap", "terrainglobals", "chunksection",
    "texture_sampling", "oit", "oit_common", "oit_depth_sample", "oit_depth_bounds",
    "oit_add_transmittance", "oit_sample", "fullscreen_triangle", "dynamictransforms",
]

BASE_DEFINES = {"OIT_NUMBER_OF_DEPTH_BINS": "8", "OIT_TRANSMITTANCE_TARGET_COUNT": "2"}
TERRAIN_VARIANTS = [
    {},
    {"ALPHA_CUTOUT": "0.5"},
    {"MULTIDRAW_TERRAIN": ""},
    {"MULTIDRAW_TERRAIN": "", "ALPHA_CUTOUT": "0.1"},
    {"IMPROVED_FOG": ""},
    {"OIT": "", "OIT_ACCUMULATE": ""},
    {"OIT": "", "OIT_ALPHA_ONLY": "", "OIT_TRANSMITTANCE": ""},
    {"OIT": "", "OIT_ALPHA_ONLY": "", "OIT_DEPTH_BOUNDS": ""},
    {"OIT": "", "OIT_ACCUMULATE": "", "MULTIDRAW_TERRAIN": "", "IMPROVED_FOG": ""},
    # Etna's shadow map pass and its shadow-receiving copies of the vanilla terrain pipelines
    {"ALPHA_CUTOUT": "0.5", "ETNA_SHADOW_PASS": ""},
    {"ETNA_SHADOWS": ""},
    {"ALPHA_CUTOUT": "0.5", "ETNA_SHADOWS": "", "IMPROVED_FOG": ""},
    {"ALPHA_CUTOUT": "0.1", "ETNA_SHADOWS": "", "MULTIDRAW_TERRAIN": ""},
    # Shadow pass copies of solid terrain (no alpha test) and of cutout terrain, single and multi-draw
    {"ETNA_SHADOW_PASS": ""},
    {"ETNA_SHADOW_PASS": "", "MULTIDRAW_TERRAIN": ""},
    {"ALPHA_CUTOUT": "0.5", "ETNA_SHADOW_PASS": "", "MULTIDRAW_TERRAIN": ""},
    # Shadow-receiving cutout terrain, and classic-transparency translucent terrain (water)
    {"ALPHA_CUTOUT": "0.5", "ETNA_SHADOWS": ""},
    {"ALPHA_CUTOUT": "0.1", "ETNA_SHADOWS": "", "ETNA_TRANSLUCENT_LAYER": ""},
    {"ALPHA_CUTOUT": "0.1", "ETNA_SHADOWS": "", "ETNA_TRANSLUCENT_LAYER": "", "MULTIDRAW_TERRAIN": ""},
    # Multi-draw shadow receivers: the pipelines the Mac draws solid and cutout terrain with
    {"ETNA_SHADOWS": "", "MULTIDRAW_TERRAIN": ""},
    {"ALPHA_CUTOUT": "0.5", "ETNA_SHADOWS": "", "MULTIDRAW_TERRAIN": ""},
    {"ALPHA_CUTOUT": "0.5", "ETNA_SHADOWS": "", "IMPROVED_FOG": "", "MULTIDRAW_TERRAIN": ""},
    # Shadow-receiving solid terrain with improved fog, single and multi-draw (the water hooks run there too)
    {"ETNA_SHADOWS": "", "IMPROVED_FOG": ""},
    {"ETNA_SHADOWS": "", "IMPROVED_FOG": "", "MULTIDRAW_TERRAIN": ""},
    # Improved transparency's translucent terrain as the game builds it (alpha test 0.1) and as Etna copies it
    # (EtnaShadows.overrideFor): the water surface without Etna's block, in both of its phases
    {"ALPHA_CUTOUT": "0.1", "OIT": "", "OIT_ACCUMULATE": "", "IMPROVED_FOG": ""},
    {"ALPHA_CUTOUT": "0.1", "OIT": "", "OIT_ALPHA_ONLY": "", "OIT_TRANSMITTANCE": "", "MULTIDRAW_TERRAIN": ""},
    {"ALPHA_CUTOUT": "0.1", "OIT": "", "OIT_ACCUMULATE": "", "ETNA_TRANSLUCENT_LAYER": ""},
    {"ALPHA_CUTOUT": "0.1", "OIT": "", "OIT_ACCUMULATE": "", "MULTIDRAW_TERRAIN": "", "ETNA_TRANSLUCENT_LAYER": ""},
    {"ALPHA_CUTOUT": "0.1", "OIT": "", "OIT_ALPHA_ONLY": "", "OIT_TRANSMITTANCE": "", "ETNA_TRANSLUCENT_LAYER": ""},
    {"ALPHA_CUTOUT": "0.1", "OIT": "", "OIT_ALPHA_ONLY": "", "OIT_TRANSMITTANCE": "", "MULTIDRAW_TERRAIN": "", "ETNA_TRANSLUCENT_LAYER": ""},
    # The other Improved Transparency stages as the game builds them (the accumulate stage with improved fog,
    # the depth bounds stage) and as Etna copies them
    {"ALPHA_CUTOUT": "0.1", "OIT": "", "OIT_ALPHA_ONLY": "", "OIT_DEPTH_BOUNDS": ""},
    {"ALPHA_CUTOUT": "0.1", "OIT": "", "OIT_ALPHA_ONLY": "", "OIT_DEPTH_BOUNDS": "", "ETNA_TRANSLUCENT_LAYER": ""},
    {"ALPHA_CUTOUT": "0.1", "OIT": "", "OIT_ALPHA_ONLY": "", "OIT_DEPTH_BOUNDS": "", "MULTIDRAW_TERRAIN": "", "ETNA_TRANSLUCENT_LAYER": ""},
    {"ALPHA_CUTOUT": "0.1", "OIT": "", "OIT_ACCUMULATE": "", "IMPROVED_FOG": "", "ETNA_TRANSLUCENT_LAYER": ""},
    {"ALPHA_CUTOUT": "0.1", "OIT": "", "OIT_ACCUMULATE": "", "IMPROVED_FOG": "", "MULTIDRAW_TERRAIN": "", "ETNA_TRANSLUCENT_LAYER": ""},
    # The debug wireframe's copies (EtnaShadows.overrideFor): no defines of its own
    {},
    {"MULTIDRAW_TERRAIN": ""},
    # The shadow filter's texelFetch fallback (ETNA_SHADOW_FETCH) on the receivers the Mac draws with
    {"ETNA_SHADOWS": "", "MULTIDRAW_TERRAIN": "", "ETNA_SHADOW_FETCH": ""},
    {"ALPHA_CUTOUT": "0.5", "ETNA_SHADOWS": "", "MULTIDRAW_TERRAIN": "", "ETNA_SHADOW_FETCH": ""},
    {"ALPHA_CUTOUT": "0.1", "ETNA_SHADOWS": "", "ETNA_TRANSLUCENT_LAYER": "", "MULTIDRAW_TERRAIN": "", "ETNA_SHADOW_FETCH": ""},
]
DEPTH_VARIANTS = [{}, {"RENDERPEARL_DEPTH_IS_ZERO_TO_ONE": ""}]
# Vanilla's cloud pipelines (fancy and fast) and Etna's lit copies of them (EtnaClouds.java).
CLOUDS_VARIANTS = [{}, {"ETNA_CLOUD_LIGHT": ""}]
# Etna's copies of vanilla's weather pipelines (EtnaWeather.java): rain and snow drawn straight into the world,
# and the three Improved Transparency stages they go through otherwise.
WEATHER_VARIANTS = [
    {},
    {"OIT": "", "OIT_ACCUMULATE": ""},
    {"OIT": "", "OIT_ALPHA_ONLY": "", "OIT_TRANSMITTANCE": ""},
    {"OIT": "", "OIT_ALPHA_ONLY": "", "OIT_DEPTH_BOUNDS": ""},
]
# Define sets each of Etna's core shaders is built with: terrain, clouds and weather take the defines of the
# vanilla pipelines Etna copies onto them (EtnaShadows.copy); any other is built with no defines of its own.
CORE_VARIANTS = {"terrain": TERRAIN_VARIANTS, "clouds": CLOUDS_VARIANTS, "weather": WEATHER_VARIANTS}


def vanilla_dir():
    if len(sys.argv) > 1 and sys.argv[1].startswith("-"):
        # Not a folder: "--help" once became one, with Mojang's shaders downloaded into it.
        print(__doc__)
        sys.exit(0 if sys.argv[1] in ("-h", "--help") else 2)
    d = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/vanilla-assets"
    inc = d / "minecraft/shaders/include"
    inc.mkdir(parents=True, exist_ok=True)
    for name in VANILLA_INCLUDES:
        f = inc / f"{name}.glsl"
        if not f.exists():
            url = f"https://raw.githubusercontent.com/misode/mcmeta/{MC_VERSION_BRANCH}/assets/minecraft/shaders/include/{name}.glsl"
            f.write_bytes(urllib.request.urlopen(url).read())
    return d


def resolve(ns, name, vanilla):
    for base in (ASSETS, vanilla):
        f = base / ns / "shaders/include" / name
        if f.exists():
            return f
    raise FileNotFoundError(f"{ns}:{name}")


def preprocess(text, vanilla, seen=None):
    seen = seen if seen is not None else set()
    out = []
    for line in text.splitlines():
        m = re.match(r'\s*#include\s+<([a-z0-9_.-]+):([^>]+)>', line)
        if m:
            key = m.groups()
            if key not in seen:
                seen.add(key)
                out.append(preprocess(resolve(*key, vanilla).read_text(), vanilla, seen))
            continue
        out.append(line)
    return "\n".join(out)


def with_defines(text, defines):
    lines = text.splitlines()
    i = 0
    while i < len(lines) and (lines[i].startswith("#version") or lines[i].startswith("#extension")):
        i += 1
    extra = [f"#define {k} {v}".rstrip() for k, v in defines.items()]
    return "\n".join(lines[:i] + extra + lines[i:])


def compile_glsl(source, stage, tmp, label):
    src = tmp / f"shader.{stage}"
    src.write_text(source)
    r = subprocess.run(["glslangValidator", "-V", "--target-env", "vulkan1.2", "--auto-map-bindings",
                        "--auto-map-locations", "-S", stage, "-o", str(tmp / "out.spv"), str(src)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print(f"FAIL {label}\n{r.stdout}{r.stderr}")
        return False
    return True


def expand(source, stage, tmp, label):
    """The source as the compiler sees it: macros expanded, #if branches resolved, comments gone."""
    src = tmp / f"expand.{stage}"
    src.write_text(source)
    r = subprocess.run(["glslangValidator", "-E", "-S", stage, str(src)], capture_output=True, text=True)
    if r.returncode != 0:
        print(f"FAIL {label}: could not preprocess\n{r.stdout}{r.stderr}")
        return None
    return r.stdout


def interface(source, direction):
    found = {}
    for m in re.finditer(r'layout\s*\(\s*location\s*=\s*(\d+)\s*\)\s*(?:flat\s+)?' + direction + r'\s+(\w+)\s+(\w+)', source):
        found[int(m.group(1))] = (m.group(2), m.group(3))
    return found


def check_interface(vert, frag, tmp, label):
    """Every fragment input has a vertex output of the same type at its location, in this variant."""
    v, f = expand(vert, "vert", tmp, label), expand(frag, "frag", tmp, label)
    if v is None or f is None:
        return False
    outs, ins = interface(v, "out"), interface(f, "in")
    ok = True
    for loc, (typ, name) in sorted(ins.items()):
        if outs.get(loc, (None,))[0] != typ:
            print(f"FAIL {label}: input location {loc} ({typ} {name}) has no matching output")
            ok = False
    return ok


def check_post_chains(vanilla, tmp):
    """Every Etna pass binds what its shader declares (see the module docstring)."""
    ok = True
    for chain in sorted((ASSETS / "etna/post_effect").glob("*.json")):
        for i, p in enumerate(json.loads(chain.read_text())["passes"]):
            ns, path = p["fragment_shader"].split(":", 1)
            if ns != "etna":
                continue
            label = f"{chain.name} pass {i} ({path})"
            src = expand(preprocess((ASSETS / ns / "shaders" / f"{path}.fsh").read_text(), vanilla), "frag", tmp, label)
            if src is None:
                ok = False
                continue
            names = [x["sampler_name"] for x in p["inputs"]]
            samplers = re.findall(r'uniform\s+sampler2D\s+(\w+)\s*;', src)
            if sorted(samplers) != sorted(n + "Sampler" for n in names):
                print(f"FAIL {label}: samplers {samplers}, inputs {names}")
                ok = False
            info = re.search(r'uniform\s+SamplerInfo\s*\{([^}]*)\}', src)
            if info:
                # The game writes OutSize and every input's size; a shader may stop reading early.
                fields = re.findall(r'vec2\s+(\w+)\s*;', info.group(1))
                expected = ["OutSize"] + [n + "Size" for n in names]
                if fields != expected[:len(fields)]:
                    print(f"FAIL {label}: SamplerInfo {fields}, the game writes {expected}")
                    ok = False
            blocks = set(re.findall(r'uniform\s+(\w+)\s*\{', src)) - {"SamplerInfo"}
            groups = set(p.get("uniforms", {}))
            if blocks != groups:
                print(f"FAIL {label}: uniform blocks {sorted(blocks)}, uniform groups {sorted(groups)}")
                ok = False
    if ok:
        print("OK   post chains bind what their shaders declare")
    return ok


def main():
    vanilla = vanilla_dir()
    ok = True
    with tempfile.TemporaryDirectory() as t:
        tmp = pathlib.Path(t)
        for f in sorted((ASSETS / "etna/shaders/post").glob("*.fsh")):
            src = preprocess(f.read_text(), vanilla)
            for depth in DEPTH_VARIANTS:
                ok &= compile_glsl(with_defines(src, depth), "frag", tmp, f"{f.name} {depth}")
            print(f"OK   post/{f.name}")
        ok &= compile_core(ASSETS / "etna/shaders/core", vanilla, tmp)
        ok &= check_post_chains(vanilla, tmp)
        ok &= check_no_vanilla_overrides()
    import json
    for p in ASSETS.rglob("*.json"):
        json.loads(p.read_text())
    print("OK   json files parse")
    sys.exit(0 if ok else 1)


def check_no_vanilla_overrides():
    """Nothing in assets/minecraft: a file there would change the game with Etna off too."""
    found = sorted(p.relative_to(ASSETS) for p in (ASSETS / "minecraft").rglob("*") if p.is_file())
    if found:
        print("FAIL vanilla assets overridden, so Etna off would not be vanilla: " + ", ".join(map(str, found)))
        return False
    print("OK   no vanilla assets overridden (Etna off looks exactly like vanilla)")
    return True


def compile_core(core, vanilla, tmp):
    """Compiles and links every vertex and fragment shader pair in one core shader folder."""
    ok = True
    namespace = core.parent.parent.name
    for vsh in sorted(core.glob("*.vsh")):
        fsh = vsh.with_suffix(".fsh")
        variants = CORE_VARIANTS.get(vsh.stem, [{}])
        for variant, depth in itertools.product(variants, DEPTH_VARIANTS):
            defines = {**BASE_DEFINES, **variant, **depth}
            v = preprocess(with_defines(vsh.read_text(), defines), vanilla)
            ok &= compile_glsl(v, "vert", tmp, f"{vsh.name} {variant}")
            if fsh.exists():
                fr = preprocess(with_defines(fsh.read_text(), defines), vanilla)
                ok &= compile_glsl(fr, "frag", tmp, f"{fsh.name} {variant}")
                # Compared per variant, as the game links them: declarations under #ifdef count only
                # where they are compiled.
                ok &= check_interface(v, fr, tmp, f"{fsh.name} {variant} {depth}")
        print(f"OK   {namespace}:core/{vsh.stem} ({len(variants) * len(DEPTH_VARIANTS)} variants)")
    return ok


if __name__ == "__main__":
    main()
