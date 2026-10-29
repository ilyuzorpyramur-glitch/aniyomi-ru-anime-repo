#!/usr/bin/env python3
"""Generate launcher icons for every extension in one unified style.

Style: 192px canvas (xxxhdpi), rounded square inset 5px, radius 44,
accent-coloured 5px border, near-black tinted fill, big bold accent monogram.
All mipmap densities are derived from the 432px master by downscaling.

Usage: python3 .github/scripts/make-icons.py [ext ...]
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

SRC = Path(__file__).resolve().parents[2] / "src"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

# density -> icon side in px
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

MASTER = 768  # render big, downscale for clean edges
SCALE = MASTER / 192

# ext dir -> (monogram, accent rgb, dark fill rgb)
ICONS = {
    "anilibria": ("AL", (88, 166, 255), (24, 29, 46)),
    "animakima": ("AK", (240, 6, 69), (34, 16, 26)),
    "anime365": ("365", (90, 220, 160), (18, 32, 28)),
    "animedia": ("AM", (110, 170, 255), (20, 24, 44)),
    "animego": ("GO", (255, 120, 70), (28, 22, 38)),
    "animelib": ("LIB", (110, 90, 245), (22, 22, 34)),
    "animesss": ("SSS", (200, 120, 255), (30, 18, 34)),
    "animevost": ("AV", (255, 90, 120), (36, 18, 24)),
    "anisprout": ("AS", (120, 220, 120), (16, 34, 24)),
    "doramalend": ("DL", (255, 105, 160), (38, 16, 28)),
    "bldub": ("BL", (255, 80, 130), (30, 14, 24)),
    "eporner": ("EP", (255, 160, 40), (34, 22, 10)),
    "xnxx": ("XN", (255, 220, 60), (36, 30, 8)),
    "xvru": ("XV", (120, 140, 255), (16, 18, 40)),
    "xhamster": ("XH", (255, 120, 0), (36, 20, 6)),
    "doramyclub": ("DC", (80, 200, 210), (14, 30, 34)),
    "lakornmania": ("LM", (255, 170, 90), (36, 24, 12)),
    "lordfilm": ("LF", (230, 60, 60), (34, 12, 14)),
    "lordfilmmg": ("MG", (80, 200, 250), (14, 26, 36)),
    "kinogo": ("KG", (90, 200, 120), (12, 32, 20)),
    "justsu": ("JS", (255, 190, 80), (36, 26, 14)),
    "jutsunet": ("JN", (90, 200, 230), (14, 28, 38)),
    "kodikdreamcast": ("DC", (255, 140, 90), (34, 20, 16)),
    "senu": ("SN", (170, 200, 255), (18, 24, 38)),
    "anistar": ("AS", (235, 90, 105), (34, 18, 24)),
    "anidub": ("AD", (255, 165, 70), (34, 24, 14)),
    "yummyanime": ("YA", (255, 140, 60), (34, 24, 14)),
    "noodlemagazine": ("NM", (255, 200, 70), (36, 28, 10)),
    "megaporno": ("MP", (255, 90, 160), (38, 16, 28)),
    "animru": ("AR", (120, 200, 255), (16, 26, 40)),
    "kawaki": ("KW", (255, 210, 90), (34, 28, 12)),
    "domanime": ("DA", (140, 220, 255), (16, 28, 40)),
}


def fit_font(text: str, max_w: int, max_h: int) -> ImageFont.FreeTypeFont:
    size = max_h
    while size > 8:
        font = ImageFont.truetype(FONT, size)
        box = font.getbbox(text)
        if box[2] - box[0] <= max_w and box[3] - box[1] <= max_h:
            return font
        size -= 2
    return ImageFont.truetype(FONT, 8)


def render(monogram: str, accent, fill) -> Image.Image:
    img = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    inset = round(5 * SCALE)
    radius = round(44 * SCALE)
    border = round(5 * SCALE)
    box = (inset, inset, MASTER - inset - 1, MASTER - inset - 1)

    draw.rounded_rectangle(box, radius=radius, fill=(*accent, 255))
    draw.rounded_rectangle(
        (box[0] + border, box[1] + border, box[2] - border, box[3] - border),
        radius=radius - border,
        fill=(*fill, 255),
    )

    inner = MASTER - 2 * (inset + border)
    font = fit_font(monogram, int(inner * 0.80), int(inner * 0.52))
    left, top, right, bottom = font.getbbox(monogram)
    draw.text(
        (MASTER / 2 - (left + right) / 2, MASTER / 2 - (top + bottom) / 2),
        monogram,
        font=font,
        fill=(*accent, 255),
    )
    return img


def main(only=None):
    for ext, (monogram, accent, fill) in ICONS.items():
        if only and ext not in only:
            continue
        master = render(monogram, accent, fill)
        ext_dir = next((d for d in sorted(SRC.iterdir()) if (d / ext).is_dir()), SRC / "ru")
        for density, side in DENSITIES.items():
            out = ext_dir / ext / "res" / f"mipmap-{density}" / "ic_launcher.png"
            out.parent.mkdir(parents=True, exist_ok=True)
            master.resize((side, side), Image.LANCZOS).save(out)
        print(f"{ext}: {monogram}")


if __name__ == "__main__":
    import sys

    main(set(sys.argv[1:]) or None)
