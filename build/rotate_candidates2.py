"""Render two more "rotate screen" candidates to ASCII."""
import io

import cairosvg
from PIL import Image

CANDIDATES = {
    "D_phone_ring": """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
  <rect x="8" y="4.5" width="8" height="15" rx="1.8"
        fill="none" stroke="#000" stroke-width="1.8"/>
  <path d="M3.6 12 A8.4 8.4 0 0 1 8 4.3" fill="none"
        stroke="#000" stroke-width="1.8" stroke-linecap="round"/>
  <path d="M2.2 3.2 L7 4.3 L4.6 8.6 Z" fill="#000"/>
  <path d="M20.4 12 A8.4 8.4 0 0 1 16 19.7" fill="none"
        stroke="#000" stroke-width="1.8" stroke-linecap="round"/>
  <path d="M21.8 20.8 L17 19.7 L19.4 15.4 Z" fill="#000"/>
</svg>""",
    "E_screen_ring": """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
  <rect x="3.5" y="7" width="17" height="10" rx="1.6"
        fill="none" stroke="#000" stroke-width="1.8"/>
  <path d="M12 2.6 A9.4 9.4 0 0 1 21.4 12" fill="none"
        stroke="#000" stroke-width="1.8" stroke-linecap="round"/>
  <path d="M20.6 2.4 L22.2 7 L17.5 7.6 Z" fill="#000"/>
  <path d="M12 21.4 A9.4 9.4 0 0 1 2.6 12" fill="none"
        stroke="#000" stroke-width="1.8" stroke-linecap="round"/>
  <path d="M3.4 21.6 L1.8 17 L6.5 16.4 Z" fill="#000"/>
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
