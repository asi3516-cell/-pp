# Yapılanlar

Bu dosya "hh" projesinde yapılan işleri ve mevcut durumu tutar. Devam ederken
buradan kontrol ederiz.

## Amaç
Taşınabilir, Türkçe, hazır listeli bir TV + radyo oynatıcı. Kullanıcı kendi
portalını/playlist'ini eklemek zorunda kalmadan açılır açılmaz çalışır.

## Kaldırılan özellikler (IPTV kaynak ekleme)
- M3U/M3U8 playlist ekleme (URL + dosyadan yükleme)
- Xtream Codes / XUI hesabı (portal + kullanıcı + şifre)
- MAC / Stalker / MAG portal hesabı
- Sunucu tarafı `/api/playlists`, `/api/stalker`, `/api/xtream` uçları
- `Stalker.java`, `xtream_channels()`, `stalker_channels()`
- Playlist yönetimi diyaloğu, "Playlistler" düğmesi, "Liste Seç" diyaloğu
  içindeki playlist satırları
- EPG (XMLTV) adresi kaydetme alanı

## Korunan özellikler
- Hazır gömülü listeler: **Canlı TV** ve **Radyo** (ayrı üst başlıklar)
- Gruplar/kategoriler ağaç görünümü; aç/kapa (iki yönde de çalışır)
- Arama (yalnızca gömülü kanallar içinde)
- Favoriler: "Canlı TV favorileri" ve "Radyo favorileri" en üstte
- "Ulusal" grubu (en çok izlenen 24 kanal, grupların en başında)
- Aynı adlı kanalların yedek adresleri (alternatif URL'ler)
- En çok izlenenler önce
- Kalıcılık (localStorage + sunucu store)
- **Yeni kanal ekleme** ("+ Kanal"): ad, adres, grup, logo
- **Adres düzenleme** (İncele): adresi düzelt, kontrol et, kanalı sil

## Eklenen yeni özellikler
- **Ekranı doldur**: video katmanı tam ekran (native tam ekran değil), geri
  tuşu ile çıkış
- **Widget (3 boyut)** — sadece radyo listesiyle çalışır:
  - Küçük (2x1): yalnız istasyon adı
  - Orta (3x2): istasyon adı + önceki / çal / durdur / sonraki
  - Büyük (4x3): üstte istasyon, altta kategori + kontroller
- **Arka planda çalma**: Android `MediaSession`; kilit ekranı ve araç
  kontrolleri, bildirim kontrolleri
- **Çalan şarkı adı**: sunucu ICY metadata okur (`/api/now`), widget ve
  bildirim istasyon adı yerine çalan parçayı gösterir (varsa)
- Widget'tan radyo değiştirme, çalma/durdurma

## Dosya düzeni
- `static/` — web arayüzü (index.html, app.js, style.css, sw.js)
- `server.py` — yerel sunucu: statik dosyalar, `/api/channels`, `/api/proxy`,
  `/api/stream`, `/api/now`, `/api/probe`
- `data/channels.json` — gömülü kanal listesi (TV + radyo)
- `build_channels.py` — listeyi canlitv.you + iptv-org + radyo kaynaklarından üretir
- `build_apk.py` — Gradle'sız APK (javac + d8 + aapt2 + apksigner)
- `android/` — WebView sarmalayıcı, widget'lar, medya oturumu
- `build_exe.bat` / `build_exe.py` — Windows .exe üretimi (PyInstaller)
- `run.bat` / `run.sh` — kaynaktan çalıştırma

## Derleme
- **APK**: `python build_apk.py` → `dist/hh.apk`
  (JDK 17 + Android SDK: platforms;android-34, build-tools;34.0.0)
- **EXE**: Windows'ta `build_exe.bat` çift tık → `dist\hh.exe`
  (PyInstaller; Windows'ta derlenmesi gerekir, çapraz derleme yapmaz)

## Durum
- [x] IPTV kaynak özellikleri kaldırıldı (playlist/xtream/MAC-Stalker)
- [x] Gruplar, favoriler, arama, yedek adresler korundu
- [x] Yeni kanal ekleme + adres düzenleme korundu
- [x] Ekranı doldur
- [x] Radyo widget'ları (3 boyut) + arka planda çalma + şarkı adı
- [x] APK derlendi (`dist/hh.apk`)
- [ ] Windows .exe (Windows makinede `build_exe.bat`)
- [x] GitHub'a private "hh" olarak yedeklendi

## Son tur (BOLUM 16)
- **Açılış**: kritik CSS gömülü, `style.css` bloklamıyor, ağaç kapalı başlar.
- **Bütünlük**: tek `VERSION` dosyası; `?v=` damgası ve `/api/version`
  bundan türer. `build/verify_integrity.py` zincir checksum denetler.
  Sürüm uyuşmazsa servis çalışanı önbelleği temizlenip bir kez yenilenir.
- **Yedek liste**: gömülü liste bozuksa sunucuya, o da yoksa localStorage'daki
  son listeye düşer ve "Son liste: HH:MM" etiketini gösterir.
- **Kumanda**: odak halkası, OK/CH±/BACK tuş kodları, render sonrası odak
  korunur, "+N daha göster" odakla yüklenir; native oynatıcıda DPAD yukarı/aşağı
  kanal değiştirir.
- **Probe önbelleği** `data/probe-cache.json`'a taşındı (urlcheck.json bozulmaz).
- **CI kapısı**: `node --check`, `acorn --ecma5`, `ast.parse` + stdlib-only,
  `verify_integrity.py`, `check_vectors.py`. Kırmızıysa paket çıkmaz.
- **Paketler**: `dist/TV-Player*.apk` (arm64/armv7/universal) ve
  `build/pkg/hh-Kaynak.zip|.7z|.rar` güncellendi.
