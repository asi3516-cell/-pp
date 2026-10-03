import zipfile, sys

apk = sys.argv[1]
z = zipfile.ZipFile(apk)
dexes = [n for n in z.namelist() if n.endswith(".dex")]
blob = b"".join(z.read(n) for n in dexes)
print("dex:", dexes)
names = [
    "ui/BottomNavActivity", "ui/ChannelListFragment", "ui/PlayerActivity",
    "ui/ExpandableChannelAdapter", "player/PlaybackController", "player/PlayerService",
    "data/ChannelRepository", "data/FavDatabase", "data/FavDao",
    "data/UserChannelEntity", "data/UserChannelDao", "RadioWidgetBase",
    "LocalServer", "MainActivity",
]
for t in names:
    key = ("Lcom/openhands/tvplayer/" + t + ";").encode()
    print(("VAR " if key in blob else "YOK "), t)
print("assets:", sorted(n for n in z.namelist() if n.startswith("assets/") and n.count("/") == 1))
