import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
version = "0"
try:
    version = (ROOT / "VERSION").read_text("utf-8").strip() or "0"
except OSError:
    pass

data = json.load(open(ROOT / "data" / "channels.json", encoding="utf-8"))
js = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
with open(ROOT / "static" / "channels-data.js", "w", encoding="utf-8") as fh:
    fh.write(
        "/* Bundled channel list. Loaded as a plain script so the lists show\n"
        "   even when no local server or network is available. */\n"
        'window.HH_VERSION = "' + version + '";\n'
        "window.HH_CHANNELS = " + js + ";\n"
    )
print("wrote", len(js), "bytes")
