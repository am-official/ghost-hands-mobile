#!/usr/bin/env python3
"""Ghost Hands launcher icon generator (v2.8.0, Item 4).

Master of record: the A design in this folder (spectral ghost
hand, glowing cyan-white on deep navy — the ethereal skin's
palette). The B design is the saved alternate; it is NOT
shipped.

Outputs (into ../app/src/main/res/):
  mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
      the full art at 48/72/96/144/192 px (pre-adaptive launchers)
  mipmap-*/ic_launcher_round.png
      the art circle-cropped on the sampled navy (roundIcon)
  mipmap-*/ic_launcher_foreground.png
      the adaptive-icon foreground: the art at 75% of the
      108dp canvas, centered on transparency, so launcher masks
      (circle / squircle / rounded square) never clip the
      fingertips or the tail; the sampled navy background shows
      in the padded margin and matches the art's own ground
  mipmap-anydpi-v26/ic_launcher.xml + ic_launcher_round.xml
      adaptive icons: background = the sampled navy, foreground
      = @mipmap/ic_launcher_foreground

The navy is sampled from the master's border ring at build
time (measured #111340 on the shipped master), so the adaptive
background is the art's own ground color, not a guess.

Re-run: python3 build_icon.py  (deterministic; LANCZOS)
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
MASTER = os.path.join(
    HERE,
    "media-generation-ghost-hands-icon-a-0-"
    "dfec0367-3274-40e8-bbac-30bfc6018e58.webp")

DENSITIES = {  # name -> (legacy px, adaptive canvas px = 108dp)
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}
FG_FRACTION = 0.75  # art occupies this share of the adaptive canvas


def sample_background(img):
    """Mean color of the outermost border ring."""
    w, h = img.size
    ring = []
    step = 7
    for x in range(0, w, step):
        ring.append(img.getpixel((x, 0)))
        ring.append(img.getpixel((x, h - 1)))
    for y in range(0, h, step):
        ring.append(img.getpixel((0, y)))
        ring.append(img.getpixel((w - 1, y)))
    n = len(ring)
    return tuple(sum(p[i] for p in ring) // n for i in range(3))


def main():
    art = Image.open(MASTER).convert("RGB")
    bg = sample_background(art)
    bg_hex = "#%02X%02X%02X" % bg
    print("sampled background:", bg_hex)

    for name, (legacy_px, canvas_px) in DENSITIES.items():
        d = os.path.join(RES, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)

        # Legacy square icon: the art as-is.
        art.resize((legacy_px, legacy_px), Image.LANCZOS).save(
            os.path.join(d, "ic_launcher.png"))

        # Round icon: art inscribed in a circle over the navy.
        round_img = Image.new("RGBA", (legacy_px, legacy_px),
                              bg + (255,))
        round_art = art.resize((legacy_px, legacy_px),
                               Image.LANCZOS).convert("RGBA")
        mask = Image.new("L", (legacy_px, legacy_px), 0)
        ImageDraw.Draw(mask).ellipse(
            [0, 0, legacy_px - 1, legacy_px - 1], fill=255)
        round_img.paste(round_art, (0, 0), mask)
        round_img.save(os.path.join(d, "ic_launcher_round.png"))

        # Adaptive foreground: padded art on transparency.
        inner = int(round(canvas_px * FG_FRACTION))
        fg = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
        fg_art = art.resize((inner, inner), Image.LANCZOS).convert("RGBA")
        off = (canvas_px - inner) // 2
        fg.paste(fg_art, (off, off))
        fg.save(os.path.join(d, "ic_launcher_foreground.png"))
        print(name, "legacy", legacy_px, "canvas", canvas_px,
              "fg inner", inner)

    values = os.path.join(RES, "values")
    os.makedirs(values, exist_ok=True)
    with open(os.path.join(values, "colors.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<resources>\n'
                '    <color name="ic_launcher_background">'
                + bg_hex + '</color>\n'
                '</resources>\n')
    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android='
        '"http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable='
        '"@color/ic_launcher_background"/>\n'
        '    <foreground android:drawable='
        '"@mipmap/ic_launcher_foreground"/>\n'
        '</adaptive-icon>\n')
    for fname in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, fname), "w") as f:
            f.write(xml)
    print("adaptive xml written with background", bg_hex)


if __name__ == "__main__":
    main()
