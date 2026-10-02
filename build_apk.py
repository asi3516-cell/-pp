#!/usr/bin/env python3
"""Build an installable Android APK without Gradle.

It compiles the WebView wrapper (plus the bundled web player in assets/) with
javac + d8 + aapt2 + apksigner from the Android SDK. Everything runs on the
standard library, so no Gradle download is needed.

Prerequisites (see tools/ setup):
  * JDK 17            -> $JAVA_HOME
  * Android SDK       -> $ANDROID_SDK_ROOT with platforms;android-34 and
                         build-tools;34.0.0

Run:  python build_apk.py   ->  dist/IPTV-Player.apk
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ANDROID = ROOT / "android"
SRC = ANDROID / "app" / "src" / "main"
OUT = ROOT / "dist" / "IPTV-Player.apk"
BUILD = ROOT / "build" / "android"

PKG = "com.openhands.iptvplayer"


def env_path(name: str, default: str) -> str:
    return os.environ.get(name, default)


def find_tool(sdk: str, build_tools: str, name: str) -> str:
    p = Path(sdk) / "build-tools" / build_tools / name
    if p.exists():
        return str(p)
    raise SystemExit(f"bulunamadı: {p}")


def run(cmd: list[str], **kw) -> None:
    print("+", " ".join(str(c) for c in cmd))
    subprocess.run(cmd, check=True, **kw)


def main() -> None:
    java_home = env_path("JAVA_HOME", "")
    sdk = env_path("ANDROID_SDK_ROOT", str(ROOT.parent / "tools" / "android-sdk"))
    platform = env_path("ANDROID_PLATFORM", "34")
    build_tools = env_path("ANDROID_BUILD_TOOLS", "34.0.0")

    if not java_home:
        raise SystemExit("JAVA_HOME tanımlı değil (JDK 17 gerekir)")
    javac = str(Path(java_home) / "bin" / "javac")
    keytool = str(Path(java_home) / "bin" / "keytool")
    java = str(Path(java_home) / "bin" / "java")

    android_jar = str(Path(sdk) / "platforms" / f"android-{platform}" / "android.jar")
    aapt2 = find_tool(sdk, build_tools, "aapt2")
    d8 = find_tool(sdk, build_tools, "d8")
    zipalign = find_tool(sdk, build_tools, "zipalign")
    apksigner = find_tool(sdk, build_tools, "apksigner")

    # Keep assets in sync with the web player (single source of truth).
    assets = SRC / "assets"
    shutil.rmtree(assets, ignore_errors=True)
    shutil.copytree(ROOT / "static", assets)
    shutil.copy(ROOT / "data" / "channels.json", assets / "channels.json")

    shutil.rmtree(BUILD, ignore_errors=True)
    (BUILD / "classes").mkdir(parents=True)
    (BUILD / "dex").mkdir(parents=True)
    res_zip = BUILD / "resources.zip"
    gen = BUILD / "gen"
    gen.mkdir(parents=True)
    unsigned = BUILD / "app-unsigned.apk"
    aligned = BUILD / "app-aligned.apk"
    dist = OUT.parent
    dist.mkdir(parents=True, exist_ok=True)

    # 1. Compile resources.
    run([aapt2, "compile", "--dir", str(SRC / "res"), "-o", str(res_zip)])

    # 2. Link resources + manifest into a base APK with R.java.
    run([aapt2, "link", "-o", str(unsigned),
         "-I", android_jar,
         "--manifest", str(SRC / "AndroidManifest.xml"),
         "--java", str(gen),
         "--min-sdk-version", "21",
         "--target-sdk-version", platform,
         "--version-code", "1", "--version-name", "1.0",
         str(res_zip)])

    # 3. Compile Java (app sources + generated R.java).
    java_srcs = [str(p) for p in (SRC / "java").rglob("*.java")]
    java_srcs += [str(p) for p in gen.rglob("*.java")]
    libs = [str(p) for p in (ANDROID / "libs").glob("*.jar")]
    classes = BUILD / "classes"
    run([javac, "-source", "8", "-target", "8",
         "-bootclasspath", android_jar,
         "-classpath", os.pathsep.join([android_jar] + libs),
         "-d", str(classes)] + java_srcs)

    # 4. Dex classes -> classes.dex.
    class_files = [str(p) for p in classes.rglob("*.class")]
    run([d8, "--release", "--min-api", "21", "--lib", android_jar,
         "--output", str(BUILD / "dex")] + class_files + libs)

    # 5. Add classes.dex and assets into the APK, then align + sign.
    import zipfile
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as zf:
        zf.write(BUILD / "dex" / "classes.dex", "classes.dex")
        for f in assets.rglob("*"):
            if f.is_file():
                zf.write(f, "assets/" + str(f.relative_to(assets)))

    run([zipalign, "-f", "4", str(unsigned), str(aligned)])

    keystore = BUILD / "debug.keystore"
    if not keystore.exists():
        run([keytool, "-genkeypair", "-keystore", str(keystore),
             "-alias", "iptv", "-storepass", "android", "-keypass", "android",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=IPTV Player, OU=OpenHands, O=OpenHands, C=TR"])
    run([apksigner, "sign", "--ks", str(keystore), "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--ks-key-alias", "iptv",
         "--out", str(OUT), str(aligned)])

    print(f"\nHazır: {OUT}  ({OUT.stat().st_size // 1024} KB)")
    print("Telefona kur: dosyayı cihaza kopyala ve aç (bilinmeyen kaynak izni gerekir).")


if __name__ == "__main__":
    main()
