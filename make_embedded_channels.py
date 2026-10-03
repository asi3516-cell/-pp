import json

data = json.load(open("data/channels.json", encoding="utf-8"))
js = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
with open("static/channels-data.js", "w", encoding="utf-8") as fh:
    fh.write(
        "/* Bundled channel list. Loaded as a plain script so the lists show\n"
        "   even when no local server or network is available. */\n"
        "window.HH_CHANNELS = " + js + ";\n"
    )
print("wrote", len(js), "bytes")
