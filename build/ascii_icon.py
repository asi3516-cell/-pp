"""Render a vector drawable's path data to an ASCII grid so the icon can be
eyeballed from a terminal (there is no image viewer in this environment)."""
import re
import sys

import cairosvg
from PIL import Image


def ascii_icon(xml_or_svg: str, size: int = 32) -> str:
    s = xml_or_svg
    paths = re.findall(r'(?:android:)?pathData="([^"]+)"', s)
    if not paths:
        paths = re.findall(r'<path[^>]*\sd="([^"]+)"', s)
    vb = re.search(r'(?:android:)?viewportWidth="(\d+)"', s)
    vbw = vb.group(1) if vb else "24"
    vb2 = re.search(r'(?:android:)?viewportHeight="(\d+)"', s)
    vbh = vb2.group(1) if vb2 else "24"
    even = "evenOdd" in s
    fill = 'fill-rule="evenodd"' if even else ""
    body = "".join(f'<path {fill} fill="#000" d="{p}"/>' for p in paths)
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
           f'viewBox="0 0 {vbw} {vbh}"><rect width="{vbw}" height="{vbh}" fill="#fff"/>{body}</svg>')
    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size)
    import io
    img = Image.open(io.BytesIO(png)).convert("L")
    ramp = " .:-=+*#%@"
    rows = []
    for y in range(size):
        line = "".join(
            ramp[min(len(ramp) - 1, (255 - img.getpixel((x, y))) * len(ramp) // 256)]
            for x in range(size))
        rows.append(line)
    return "\n".join(rows)


if __name__ == "__main__":
    src = open(sys.argv[1]).read() if len(sys.argv) > 1 else sys.stdin.read()
    print(ascii_icon(src))
