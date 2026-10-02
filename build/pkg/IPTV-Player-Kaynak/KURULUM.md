# IPTV Player — Kaynak Sürüm (EXE'siz)

Bu paket programın **kaynak kodudur**. Derlenmiş `.exe` yoktur; kodu
düzenleyebilir, çalıştırabilir veya kendin derleyebilirsin.

## 1) Çalıştırma (Python 3.10+ gerekir)

```bat
run.bat          :: çift tıkla, tarayıcı otomatik açılır
```

veya: `python server.py --open`  →  http://127.0.0.1:8000

Harici bağımlılık yoktur (yalnızca Python standart kütüphanesi).

## 2) Tek tıkla .exe üretme

**`build_exe.bat`** dosyasına çift tıkla. PyInstaller'ı kurar, derler ve
`dist\IPTV-Player.exe` üretir. (Python kurulu olmalı.)

## 3) Neyi nerede düzenlersin

| Ne | Dosya |
|----|-------|
| Arayüz (HTML) | `static/index.html` |
| Tasarım / CSS | `static/style.css` |
| Oynatıcı mantığı (JS) | `static/app.js` |
| Sunucu / API / proxy / inceleme | `server.py` |
| Kanal listesi (veri) | `data/channels.json` |
| Kanal üretme | `build_channels.py`, `build_canlitv.py` |

Kanal listesini yeniden üret: `python build_channels.py`

## 4) Android APK

`android/` kaynakları + `build_apk.py`. Gerekenler: JDK 17 ve Android SDK
(platforms;android-34, build-tools;34.0.0): `python build_apk.py`

## Not
Yayınlar ücretsiz/genel kaynaklardan geldiği için bazıları bölgesel olarak
engelli veya çevrimdışı olabilir. "İncele" butonu hangi adresin çalıştığını
gösterir.
