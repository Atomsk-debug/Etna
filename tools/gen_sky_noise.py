"""Makes assets/etna/textures/environment/sky_noise.png, the noise Etna's sky reads (core/sky.fsh).

Four independent channels of smooth gradient noise (Perlin's improved noise with its quintic fade), each
one tiling seamlessly across the 256 x 256 texture so the sky can repeat it without seams: red and green
with 16 cells across, blue and alpha with 32. A cell spans 16 or 8 texels, so the bilinear filter between
texels follows the curve closely and one texture read costs about what one octave of noise in the shader
would, at a fraction of the arithmetic. Each channel is spread so its median sits at 0.5 and its 1st and
99th percentiles at 0.08 and 0.92: the aurora draws its curtains along the 0.5 line of the red channel.

The seed is fixed, so running this again gives the same file.

Usage: python3 tools/gen_sky_noise.py
"""
import pathlib

import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/assets/etna/textures/environment/sky_noise.png"
SIZE = 256
# Cells across the texture, per channel (red, green, blue, alpha), and the seed of each.
CELLS = (16, 16, 32, 32)
SEEDS = (11, 23, 37, 41)


def tiling_gradient_noise(cells, seed):
    rng = np.random.default_rng(seed)
    angles = rng.uniform(0.0, 2.0 * np.pi, (cells, cells))
    gx, gy = np.cos(angles), np.sin(angles)
    coords = (np.arange(SIZE) + 0.5) * cells / SIZE
    x, y = np.meshgrid(coords, coords)
    x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
    fx, fy = x - x0, y - y0

    def corner(dx, dy):
        cx, cy = (x0 + dx) % cells, (y0 + dy) % cells
        return gx[cy, cx] * (fx - dx) + gy[cy, cx] * (fy - dy)

    def fade(t):
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0)

    u, v = fade(fx), fade(fy)
    top = corner(0, 0) + (corner(1, 0) - corner(0, 0)) * u
    bottom = corner(0, 1) + (corner(1, 1) - corner(0, 1)) * u
    return top + (bottom - top) * v


def spread(n):
    low, mid, high = np.percentile(n, (1.0, 50.0, 99.0))
    out = np.where(n < mid, 0.5 - 0.42 * (mid - n) / (mid - low), 0.5 + 0.42 * (n - mid) / (high - mid))
    return np.clip(out, 0.0, 1.0)


def main():
    channels = [spread(tiling_gradient_noise(c, s)) for c, s in zip(CELLS, SEEDS)]
    rgba = np.round(np.stack(channels, axis=-1) * 255.0).astype(np.uint8)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(rgba, "RGBA").save(OUT, optimize=True)
    print(f"wrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
