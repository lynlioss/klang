#!/usr/bin/env python3
"""Generate launcher icon resources from the artwork in assets/.

Run from anywhere: the script writes app/src/main/res/mipmap-* and the two
preview drawables. Source images are kept at 1024 px so the results stay sharp
for the 108 dp adaptive icon layers.
"""
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "assets"
RES = ROOT / "app/src/main/res"

CANVAS = 1024
# Adaptive icons only guarantee the inner 66 dp of their 108 dp canvas, so the
# disc is framed at 62 % to stay clear of every launcher mask.
DISC_FRACTION = 0.62
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
ADAPTIVE_DP = 108
LEGACY_DP = 48
PREVIEW_PX = 384
VARIANTS = {"light": "app-icon-light.png", "dark": "app-icon-dark.png"}


def disc_bounds(image: Image.Image) -> tuple[int, int, int, int]:
    """Find the disc by comparing every pixel with the flat artwork background."""
    pixels = np.asarray(image.convert("RGB")).astype(int)
    edge = np.concatenate([pixels[:8].reshape(-1, 3), pixels[-8:].reshape(-1, 3),
                           pixels[:, :8].reshape(-1, 3), pixels[:, -8:].reshape(-1, 3)])
    background = np.median(edge, axis=0)
    mask = np.abs(pixels - background).sum(axis=2) > 20
    # The scans carry a dark strip along one edge; ignore the frame entirely.
    margin = 32
    mask[:margin] = False
    mask[-margin:] = False
    mask[:, :margin] = False
    mask[:, -margin:] = False
    columns = np.nonzero(mask.sum(axis=0) > 5)[0]
    rows = np.nonzero(mask.sum(axis=1) > 5)[0]
    if not len(columns) or not len(rows):
        raise SystemExit("Could not find the disc in the artwork")
    return int(columns[0]), int(rows[0]), int(columns[-1]), int(rows[-1])


def normalize(image: Image.Image) -> Image.Image:
    """Center the disc, frame it at DISC_FRACTION and drop the artwork's edges."""
    left, top, right, bottom = disc_bounds(image)
    diameter = max(right - left, bottom - top)
    center_x = (left + right) // 2
    center_y = (top + bottom) // 2
    # The source art carries a dark scan edge, so crop a square around the disc
    # before scaling instead of pasting the whole picture onto the canvas.
    crop_size = min(image.width, image.height, round(diameter * 1.35))
    box = (max(0, center_x - crop_size // 2), max(0, center_y - crop_size // 2))
    crop = image.crop((box[0], box[1], box[0] + crop_size, box[1] + crop_size)).convert("RGB")
    ring = np.concatenate([np.asarray(crop)[:4].reshape(-1, 3), np.asarray(crop)[-4:].reshape(-1, 3),
                           np.asarray(crop)[:, :4].reshape(-1, 3), np.asarray(crop)[:, -4:].reshape(-1, 3)])
    background = tuple(int(value) for value in np.median(ring, axis=0))
    scale = DISC_FRACTION * CANVAS / diameter
    size = round(crop_size * scale)
    canvas = Image.new("RGB", (CANVAS, CANVAS), background)
    canvas.paste(crop.resize((size, size), Image.LANCZOS),
                 (round(CANVAS / 2 - size / 2), round(CANVAS / 2 - size / 2)))
    return canvas


def save(image: Image.Image, directory: str, name: str, pixels: int) -> None:
    target = RES / directory
    target.mkdir(parents=True, exist_ok=True)
    image.resize((pixels, pixels), Image.LANCZOS).save(target / name, optimize=True)


def main() -> None:
    # Keep the source art next to the script's inputs for reproducible builds.
    for variant, filename in VARIANTS.items():
        source = ASSETS / filename
        if not source.exists():
            raise SystemExit(f"Missing source artwork: {source}")
        original = Image.open(source).convert("RGB")
        icon = normalize(original)

        for density, factor in DENSITIES.items():
            save(icon, f"mipmap-{density}", f"ic_launcher_{variant}_foreground.png",
                 round(ADAPTIVE_DP * factor))
            save(icon, f"mipmap-{density}", f"ic_launcher_{variant}.png",
                 round(LEGACY_DP * factor))

        preview = RES / "drawable-nodpi"
        preview.mkdir(parents=True, exist_ok=True)
        icon.resize((PREVIEW_PX, PREVIEW_PX), Image.LANCZOS).save(
            preview / f"app_icon_{variant}.png", optimize=True)


if __name__ == "__main__":
    main()
