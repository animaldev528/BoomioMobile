#!/usr/bin/env python3
"""Retint the Boomio wordmark into the seven variants NuvioMobile's theme system asks for.

The app resolves a wordmark per theme (AppTheme.wordmarkResource /
AppIconOption.wordmarkResource) to these drawable names, so every slot has to exist or the
theme silently falls back to Nuvio's artwork.

The master is a FLAT single-colour glyph (#BCBBF0) with antialiasing in the alpha channel
only, so retinting is exact: replace RGB, keep A. That preserves the edges rather than
re-thresholding them.

Output goes into the Android flavor's asset overlay. Compose Multiplatform packs its
resources as assets at composeResources/<package>/drawable/, and AGP merges an app
flavor's assets ABOVE a library's -- the same path here therefore shadows the copy
shipped inside composeApp.
"""
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
MASTER = os.path.join(HERE, "app_logo_wordmark_master.png")
OUT = os.path.join(
    HERE, "..", "..", "..",
    "androidApp/src/boomio/assets/composeResources",
    "nuvio.composeapp.generated.resources/drawable",
)

BOOMIO = (188, 187, 240)  # the master's own colour; keep it for the unbranded slots

# Accent colours sampled from Nuvio's own variants, so Boomio's themes stay in the same
# palette as the rest of the UI rather than inventing a second one.
VARIANTS = {
    "app_logo_wordmark.png":            BOOMIO,
    "app_logo_wordmark_original.png":   BOOMIO,
    "app_logo_wordmark_arctic_blue.png": (73, 102, 250),
    "app_logo_wordmark_emerald.png":     (19, 209, 152),
    "app_logo_wordmark_rose_gold.png":  (255, 143, 122),
    "app_logo_wordmark_copper.png":     (245, 180, 95),
    "app_logo_wordmark_graphite.png":   (156, 156, 156),
    "app_logo_wordmark_gold.png":       (255, 206, 68),
}


def retint(src, rgb):
    out = Image.new("RGBA", src.size)
    out.putdata([(rgb[0], rgb[1], rgb[2], a) for _, _, _, a in src.getdata()])
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    master = Image.open(MASTER).convert("RGBA")
    for name, rgb in sorted(VARIANTS.items()):
        path = os.path.join(OUT, name)
        retint(master, rgb).save(path, "PNG", optimize=True)
        print(f"  {name:38} {rgb}  {os.path.getsize(path)}B")


if __name__ == "__main__":
    main()
