"""Render a bold circular-arrow "rotate" candidate and a screen+arrow one."""
import io

import cairosvg
from PIL import Image

CANDIDATES = {
    "F_bold_rotate": """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
  <path d="M12 4.6 A7.4 7.4 0 1 0 19.4 12" fill="none"
        stroke="#000" stroke-width="2.6" stroke-linecap="round"/>
  <path d="M8.4 1.9 L13.6 4.6 L8.4 7.4 Z" fill="#000"/>
</svg>""",
    "G_screen_bold_rotate": """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
  <rect x="4.5" y="6" width="15" height="11" rx="1.5"
        fill="none" stroke="#000" stroke-width="1.9"/>
  <path d="M12 1.6 A10.4 10.4 0 0 1 22.4 12" fill="none"
        stroke="#000" stroke-width="2.2" stroke-linecap="round"/>
  <path d="M20.6 1.4 L23.2 6.6 L17.7 7.4 Z" fill="#000"/>
</svg>""",
}


def ascii_icon(svg: str, size: int = 30) -> str:
    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size)
    img = Image.open(io.BytesIO(png)).convert("RGBA")
    base = Image.new("RGBA", img.size, (255, 255, 255, 255))
    img = Image.alpha_composite(base, img).convert("L")
    ramp = " .:-=+*#%@"
    return "\n".join(
        "".join(ramp[min(len(ramp) - 1, (255 - img.getpixel((x, y))) * len(ramp) // 256)]
                for x in range(size))
        for y in range(size))


for name, svg in CANDIDATES.items():
    print("=" * 34, name)
    print(ascii_icon(svg))
    print()
