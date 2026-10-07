import cairosvg, os, re

D = "/workspace/hh/android/app/src/main/res/drawable"
OUT = "/workspace/hh/build"
for n in ["ic_rotate", "ic_fullscreen", "ic_resize"]:
    f = os.path.join(D, n + ".xml")
    s = open(f).read()
    paths = re.findall(r'android:pathData="([^"]+)"', s)
    vb = re.search(r'android:viewportWidth="(\d+)"', s).group(1)
    vb2 = re.search(r'android:viewportHeight="(\d+)"', s).group(1)
    body = "".join(f'<path fill="#222" d="{p}"/>' for p in paths)
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" '
           f'viewBox="0 0 {vb} {vb2}"><rect width="{vb}" height="{vb2}" fill="#eee"/>'
           f'{body}</svg>')
    cairosvg.svg2png(bytestring=svg.encode(),
                     write_to=os.path.join(OUT, n + ".png"),
                     output_width=96, output_height=96)
    print("render:", n, len(paths), "path")
