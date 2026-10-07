"""Render a few candidate "rotate screen" icons to ASCII so the clearest one
can be picked without an image viewer."""
import io

import cairosvg
from PIL import Image

CANDIDATES = {
    "A_phone_arc": """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
  <g transform="rotate(22 12 12)">
    <rect x="8.6" y="3.6" width="6.8" height="16.8" rx="1.6"
          fill="none" stroke="#000" stroke-width="1.7"/>
  </g>
  <path d="M4.2 14.4 A8.6 8.6 0 0 1 7.4 5.3" fill="none"
        stroke="#000" stroke-width="1.7" stroke-linecap="round"/>
  <path d="M3.2 4.6 L7.6 5.4 L5.6 9.4 Z" fill="#000"/>
</svg>""",
    "B_screen_arrows": """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
  <rect x="6.5" y="3.5" width="11" height="17" rx="1.6"
        fill="none" stroke="#000" stroke-width="1.7"/>
  <path d="M12 7.5 v6.5 H8.8 L12 18 l3.2-4 H12" fill="#000"/>
</svg>""",
    "C_material_screenrot": """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
  <path fill="#000" d="M16.48 2.52c3.27 1.55 5.61 4.72 5.97 8.48h1.5C23.44 6.02 20.16 2.04 15.73.5l-.01.01L16.48 2.52zM10.23.5C5.8 2.04 2.52 6.02 2.05 11h1.5c.36-3.76 2.7-6.93 5.97-8.48L10.23.5z"/>
  <path fill="#000" d="M14.34 17.93l-5.27-5.27c-.29-.29-.76-.29-1.05 0l-5.27 5.27c-.29.29-.29.76 0 1.05l5.27 5.27c.29.29.76.29 1.05 0l5.27-5.27c.29-.29.29-.76 0-1.05zm-1.05 4.75L8.02 17.41l5.27-5.27 5.27 5.27-5.27 5.27z"/>
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
