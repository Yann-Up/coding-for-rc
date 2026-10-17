#!/usr/bin/env python3
"""Regenerate the launcher icon, the splash logo and the notification bitmaps.

    python tools/make-icons.py [source.png]

Default source is assets/logo.png. The artwork is expected to sit on a flat,
near-white canvas: that canvas is flood-filled away from the image border, so
the outputs carry real transparency while white enclosed by the artwork (the
character's eyes) is left alone.

The status bar icon is a single-colour cutout: Android draws the notification
small icon as an alpha mask and tints it itself, so anything white (the canvas
and those enclosed eyes) is punched out to a hole instead of being painted. The
large icon of an expanded notification is drawn as-is, so that one keeps the
full colour artwork.
"""

import os
import sys

from PIL import Image, ImageChops, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "assets", "logo.png")

# Density buckets and their scale factor relative to mdpi.
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

# Adaptive icon layers are a 108dp canvas; only the middle 72dp is guaranteed
# visible, and the inner 66dp circle survives every launcher mask.
FOREGROUND_CANVAS_DP = 108.0
FOREGROUND_CONTENT_DP = 66.0

# Standalone logo shown on the splash screen.
LOGO_DP = 144.0

# Status bar icon: a 24dp mask with the artwork inset a little, so it sits
# comfortably next to the system icons.
NOTIFY_CANVAS_DP = 24.0
NOTIFY_CONTENT_DP = 21.0

# Expanded notification large icon: full colour, drawn by the system as-is, so it
# keeps a small inset instead of running into the rounded corners of the frame.
NOTIFY_LARGE_DP = 64.0
NOTIFY_LARGE_CONTENT_DP = 58.0

# "Ink" test for the status bar cutout: keep pixels that carry colour (the blue
# character) or are clearly dark (the phone), drop flat white (canvas, eyes).
INK_SATURATION = 24
INK_LUMA = 200

# Sentinel colour for the canvas flood fill. The script refuses to run if the
# artwork itself already uses it.
SENTINEL = (255, 0, 255)

# Maximum sum-of-channels distance from the border pixel still considered
# canvas. The canvas is flat, and the artwork's anti-aliased rim sits far
# outside this tolerance, so the fill stops cleanly at the artwork.
CANVAS_TOLERANCE = 40


def strip_canvas(img):
    """Flood the flat canvas away from the border and return an RGBA cutout."""
    rgb = img.convert("RGB")
    if SENTINEL in rgb.getdata():
        raise SystemExit("error: %s already contains %s" % (SRC, SENTINEL))

    ImageDraw.floodfill(rgb, (0, 0), SENTINEL, thresh=CANVAS_TOLERANCE)

    flat = Image.new("RGB", rgb.size, SENTINEL)
    diff = ImageChops.difference(rgb, flat).split()
    # 0 (black) exactly where the fill landed, non-zero everywhere else.
    filled = ImageChops.lighter(ImageChops.lighter(diff[0], diff[1]), diff[2])
    alpha = filled.point(lambda v: 0 if v == 0 else 255)

    out = img.convert("RGBA")
    out.putalpha(alpha)
    if out.getchannel("A").getbbox() is None:
        raise SystemExit("error: %s looks blank; no artwork found" % SRC)
    return out


def content_box(img):
    """Bounding box of the opaque pixels, squared off around its centre."""
    min_x, min_y, max_x, max_y = img.getchannel("A").getbbox()
    side = max(max_x - min_x, max_y - min_y)
    cx, cy = (min_x + max_x) / 2.0, (min_y + max_y) / 2.0
    return (int(round(cx - side / 2.0)), int(round(cy - side / 2.0)), side)


def square_crop(img, box):
    """Crop to `box`, padding with transparency where it runs off the canvas."""
    left, top, side = box
    out = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    src = img.crop((max(left, 0), max(top, 0),
                    min(left + side, img.width), min(top + side, img.height)))
    out.paste(src, (max(-left, 0), max(-top, 0)))
    return out


def ink_mask(img):
    """Mask of the "ink" in the artwork: colour or clearly dark, not flat white."""
    mask = Image.new("L", img.size, 0)
    values = []
    for red, green, blue in img.convert("RGB").getdata():
        top, bottom = max(red, green, blue), min(red, green, blue)
        luma = (red * 299 + green * 587 + blue * 114) // 1000
        values.append(255 if (top - bottom) > INK_SATURATION or luma < INK_LUMA else 0)
    mask.putdata(values)
    return mask


def status_icon(art):
    """Single-colour cutout of the artwork's ink, for the notification small icon.

    Android only reads the alpha channel of a small icon, so the shape has to
    come from the mask; white areas become holes. Edges are anti-aliased by the
    per-density downscale, not here.
    """
    out = Image.new("RGBA", art.size, (255, 255, 255, 0))
    out.putalpha(ImageChops.multiply(art.getchannel("A"), ink_mask(art)))
    return out


def write(path, img):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG", optimize=True)
    print("  %s (%dx%d)" % (os.path.relpath(path, ROOT), img.width, img.height))


def main():
    if not os.path.isfile(SRC):
        raise SystemExit("error: source artwork not found: %s" % SRC)

    src = strip_canvas(Image.open(SRC))
    box = content_box(src)
    art = square_crop(src, box)
    print("%s: content %dx%d at (%d,%d)"
          % (os.path.relpath(SRC, ROOT), box[2], box[2], box[0], box[1]))

    cutout = status_icon(art)

    for name, scale in DENSITIES.items():
        fg_canvas = int(round(FOREGROUND_CANVAS_DP * scale))
        fg_content = int(round(FOREGROUND_CONTENT_DP * scale))
        canvas = Image.new("RGBA", (fg_canvas, fg_canvas), (0, 0, 0, 0))
        canvas.paste(art.resize((fg_content, fg_content), Image.LANCZOS),
                     ((fg_canvas - fg_content) // 2, (fg_canvas - fg_content) // 2))
        write(os.path.join(ROOT, "res", "mipmap-%s" % name, "ic_launcher_foreground.png"),
              canvas)

        logo_px = int(round(LOGO_DP * scale))
        write(os.path.join(ROOT, "res", "drawable-%s" % name, "logo.png"),
              art.resize((logo_px, logo_px), Image.LANCZOS))

        stat_canvas = int(round(NOTIFY_CANVAS_DP * scale))
        stat_content = int(round(NOTIFY_CONTENT_DP * scale))
        stat = Image.new("RGBA", (stat_canvas, stat_canvas), (255, 255, 255, 0))
        stat.paste(cutout.resize((stat_content, stat_content), Image.LANCZOS),
                   ((stat_canvas - stat_content) // 2, (stat_canvas - stat_content) // 2))
        write(os.path.join(ROOT, "res", "drawable-%s" % name, "ic_stat_notify.png"),
              stat)

        large_canvas = int(round(NOTIFY_LARGE_DP * scale))
        large_content = int(round(NOTIFY_LARGE_CONTENT_DP * scale))
        large = Image.new("RGBA", (large_canvas, large_canvas), (0, 0, 0, 0))
        large.paste(art.resize((large_content, large_content), Image.LANCZOS),
                    ((large_canvas - large_content) // 2,
                     (large_canvas - large_content) // 2))
        write(os.path.join(ROOT, "res", "drawable-%s" % name, "ic_notify_large.png"),
              large)


if __name__ == "__main__":
    main()
