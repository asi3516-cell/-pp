# IPTV Player — Kaynak Sürüm (EXE'siz)

Bu paket, programın **kaynak kodudur**. Derlenmiş `.exe` yoktur; kodu
doğrudan düzenleyebilir, kendi bilgisayarında çalıştırabilir veya yeniden
derleyebilirsin.

## 1) Çalıştırmak için (Python gerekir)

Windows'ta Python 3.10+ kurulu olmalı (https://python.org → "Add to PATH").

```bat
:: çift tıkla (tarayıcı otomatik açılır):
run.bat
```

veya elle:

```bat
python server.py --open
```

Ardından tarayıcıdan şu adresi aç: **http://127.0.0.1:8000**

> Farklı port istersen: `set PORT=9000 && python server.py --port %PORT%`

Harici bağımlılık **yoktur** (yalnızca Python standart kütüphanesi).

## 2) Neyi nerede düzenlersin

| Ne | Dosya |
|----|-------|
| Arayüz (HTML) | `static/index.html` |
| Tasarım / CSS | `static/style.css` |
| Oynatıcı mantığı (JS) | `static/app.js` |
| Sunucu / API / proxy | `server.py` |
| Kanal listesi (veri) | `data/channels.json` |
| Kanal listesini üreten betik | `build_channels.py`, `build_canlitv.py` |

Kanal listesini yeniden üretmek için:

```bat
python build_channels.py
```

Bu komut `data/channels.json` dosyasını güncel listelerle yeniden yazar.

## 3) Kendi `.exe`'ni derlemek (isteğe bağlı)

```bat
pip install -r build\requirements-build.txt
python build\make.py
```

Sonuç: `dist\IPTV-Player.exe`. PyInstaller çapraz derleme yapmaz; Windows
`.exe` yalnızca Windows'ta üretilir.

## 4) Android APK (isteğe bağlı)

`android/` klasöründe kaynaklar, `build_apk.py` ile derleme betiği vardır.
Gerekenler: JDK 17 + Android SDK (platforms;android-34, build-tools;34.0.0).

```bat
python build_apk.py
```

## Not

Yayınlar ücretsiz/genel kaynaklardan geldiği için bazıları bölgesel olarak
engelli veya çevrimdışı olabilir; liste yalnızca işaret eder, garanti vermez.
