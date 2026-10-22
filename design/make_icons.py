"""Renders the Spotter app icon for every platform.

    python design/make_icons.py

Design: a white airliner climbing to the upper right through viewfinder brackets, trailing a
contrail, on a night-to-day sky gradient. Everything is drawn at 4x and downsampled.
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
TOP, BOTTOM = (8, 24, 52), (31, 116, 214)  # navy night -> morning blue


def gradient(size):
    """Diagonal sky gradient with a soft glow where the plane is heading."""
    g = Image.new("RGB", (size, size))
    px = g.load()
    for y in range(size):
        for x in range(size):
            t = min(1, max(0, (y * 0.75 + (size - x) * 0.25) / size))
            px[x, y] = tuple(round(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3))
    glow = Image.new("L", (size, size), 0)
    ImageDraw.Draw(glow).ellipse([size * 0.35, size * 0.05, size * 1.05, size * 0.75], fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.12))
    return Image.composite(Image.new("RGB", (size, size), (120, 190, 255)), g, glow)


# Material "flight" silhouette on a 24-unit grid, nose up; the nose is a separate circle.
PLANE = [(21, 16), (21, 14), (13, 9), (13, 3.5), (10, 3.5), (10, 9), (2, 14), (2, 16), (10, 13.5),
         (10, 19), (8, 20.5), (8, 22), (11.5, 21), (15, 22), (15, 20.5), (13, 19), (13, 13.5)]


def foreground(size, scale):
    """Transparent layer: contrail, plane and brackets. `scale` shrinks it for Android's safe zone."""
    S = size * 4
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    c = S / 2
    unit = S * scale

    # Contrail: a soft white streak behind the plane, fading toward the lower left.
    trail = Image.new("L", (S, S), 0)
    td = ImageDraw.Draw(trail)
    steps = 60
    for i in range(steps):
        t = i / steps
        x = c - unit * (0.06 + 0.42 * t)
        y = c + unit * (0.06 + 0.42 * t)
        r = unit * (0.028 + 0.03 * t)
        td.ellipse([x - r, y - r, x + r, y + r], fill=round(210 * (1 - t) ** 1.4))
    trail = trail.filter(ImageFilter.GaussianBlur(unit * 0.02))
    white = Image.new("L", (S, S), 255)
    layer = Image.merge("RGBA", (white, white, white, trail))  # white, with the trail as its opacity
    d = ImageDraw.Draw(layer)

    # Plane, heading up-right (rotated 45 degrees), centred.
    k = unit * 0.024
    pc = (11.5, 12.5)
    a = math.radians(45)

    def tf(p):
        x, y = (p[0] - pc[0]) * k, (p[1] - pc[1]) * k
        return (c + x * math.cos(a) - y * math.sin(a), c + x * math.sin(a) + y * math.cos(a))

    d.polygon([tf(p) for p in PLANE], fill="white")
    nx, ny = tf((11.5, 3.5))
    r = 1.5 * k
    d.ellipse([nx - r, ny - r, nx + r, ny + r], fill="white")

    # Viewfinder brackets: a rounded square outline with the middle of each side cut away.
    half = unit * 0.36
    gap = half - unit * 0.16  # half-length of each cut
    w = round(unit * 0.045)
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([c - half, c - half, c + half, c + half], radius=unit * 0.07, outline=255, width=w)
    md.rectangle([c - gap, c - half - w, c + gap, c + half + w], fill=0)
    md.rectangle([c - half - w, c - gap, c + half + w, c + gap], fill=0)
    for ex, ey in [(c - gap, c - half), (c + gap, c - half), (c - gap, c + half), (c + gap, c + half),
                   (c - half, c - gap), (c - half, c + gap), (c + half, c - gap), (c + half, c + gap)]:
        # round caps; the outline is drawn inward from the square's edge
        ox = ex + (w / 2 if ex == c - half else -w / 2 if ex == c + half else 0)
        oy = ey + (w / 2 if ey == c - half else -w / 2 if ey == c + half else 0)
        md.ellipse([ox - w / 2, oy - w / 2, ox + w / 2, oy + w / 2], fill=255)
    layer.paste((255, 255, 255, 255), (0, 0), mask)
    return layer.resize((size, size), Image.LANCZOS)


def full_icon(size, scale=0.78):
    bg = gradient(size).convert("RGBA")
    bg.alpha_composite(foreground(size, scale))
    return bg.convert("RGB")


def main():
    master = full_icon(1024)
    master.save(ROOT / "ios/AircraftVision/Assets.xcassets/AppIcon.appiconset/icon.png")
    master.save(ROOT / "design/icon-1024.png")
    for name, s in [("icon-192.png", 192), ("icon-512.png", 512), ("apple-touch-icon.png", 180)]:
        master.resize((s, s), Image.LANCZOS).save(ROOT / "web/icons" / name)
    # Android adaptive icon: separate background and foreground layers (108dp; xxxhdpi = 432px).
    # Launchers crop to a circle/squircle showing the middle ~61%, so the artwork is scaled into it.
    res = ROOT / "android/app/src/main/res/mipmap-xxxhdpi"
    res.mkdir(parents=True, exist_ok=True)
    gradient(432).save(res / "ic_launcher_background.png")
    foreground(432, 0.58).save(res / "ic_launcher_foreground.png")


if __name__ == "__main__":
    main()
