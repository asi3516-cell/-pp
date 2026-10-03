# PyInstaller spec: bundles the server + static UI + channel list into one
# self-contained executable. Build with:
#     pyinstaller build/tv_player.spec --noconfirm
import sys
from pathlib import Path

ROOT = Path(SPECPATH).resolve().parent

datas = [
    (str(ROOT / "static"), "static"),
    (str(ROOT / "data" / "channels.json"), "data"),
]

# Ship the Android build inside the desktop executable so the "download APK"
# button works from the standalone .exe (there is no dist/ folder next to it).
_apk = ROOT / "dist" / "TV-Player.apk"
if _apk.exists():
    datas.append((str(_apk), "dist"))

a = Analysis(
    [str(ROOT / "server.py")],
    pathex=[str(ROOT)],
    binaries=[],
    datas=datas,
    hiddenimports=[],
    hookspath=[],
    runtime_hooks=[],
    excludes=["tkinter", "test", "unittest"],
    noarchive=False,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="hh",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

if sys.platform == "darwin":
    app = BUNDLE(
        exe,
        name="hh.app",
        icon=None,
        bundle_identifier="com.openhands.tvplayer",
        info_plist={
            "LSUIElement": "1",
            "NSHighResolutionCapable": "True",
            "LSMinimumSystemVersion": "10.13",
        },
    )
