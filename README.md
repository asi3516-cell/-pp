# IPTV Player

Tarayıcıda çalışan, kurulum gerektirmeyen bir **IPTV / canlı TV oynatıcı**. M3U/M3U8
playlist'lerini yükler, HLS (`m3u8`) yayınlarını oynatır, kanal arama, favoriler,
gruplar, EPG (XMLTV) ve resim-içinde-resim (PiP) destekler.

Tek bağımlılık Python 3.9+ (standart kütüphane). Arayüz saf HTML/CSS/JS'tir;
HLS oynatma için `hls.js` yerel olarak paketlenmiştir.

## Hızlı başlangıç

```bash
python server.py --port 8000 --open
# tarayıcı otomatik açılır: http://127.0.0.1:8000
```

Python yoksa, hazır tek dosyalık sürümü kullan (aşağıya bak).

## Taşınabilir sürüm (tek dosya / .exe / .app)

Uygulamayı Python kurulu olmayan bir bilgisayarda da çalıştırmak için tek
dosyaya paketleyebilirsin:

```bash
pip install -r build/requirements-build.txt
python build/make.py
```

Çıktı `dist/` klasöründe:

| Platform | Dosya | Kullanım |
|----------|-------|----------|
| Windows  | `IPTV-Player.exe` | Çift tıkla |
| macOS    | `IPTV Player.app` ve `IPTV-Player` | Çift tıkla |
| Linux    | `IPTV-Player` | `./IPTV-Player` |

Program açılınca yerel bir sunucu başlar ve tarayıcı otomatik açılır.
**Paket kendi içinde web arayüzünü ve kanal listesini taşır**, ekstra dosya
gerekmez. `build/` klasörünü taşıman gerekmez, sadece `dist/` içindeki tek
dosyayı kopyalaman yeterli.

> Not: Her işletim sistemi için ayrı paket gerekir. Mac `.app`'i ancak bir Mac
> üzerinde derleyebilirsin (Windows'ta `.exe`, Linux'ta Linux dosyası).

### Python varsa (paketlemeden)

| Platform | Yöntem |
|----------|--------|
| Windows  | `run.bat` dosyasına çift tıkla |
| macOS    | `IPTV Player.command` dosyasına çift tıkla |
| Linux    | `./run.sh` |

Sunucu:

- `static/` altındaki web arayüzünü sunar,
- `/api/channels` ile paket içindeki kanal listesini verir,
- `/api/fetch` ile uzak M3U/XMLTV dosyalarını CORS olmadan çeker,
- `/api/proxy` ile HLS yayınlarını **sunucu üzerinden** iletir (CORS engelini aşar),
  playlist içindeki tüm segment ve alt-playlist adreslerini otomatik olarak kendi
  üzerinden geçecek şekilde yeniden yazar.

## Kullanım

- **Kanal seç:** sol listeden bir kanala tıkla.
- **Ara:** üstteki arama kutusu (`/` kısayolu).
- **Favoriler / Son:** sekmelerden.
- **Playlistler:** kendi M3U adresini veya `.m3u` dosyanı ekle; XMLTV (EPG) adresi gir.
- **+ Kanal:** tek bir yayın adresini elle ekle.
- **Kısayollar:** `/` ara, `f` tam ekran, `p` PiP, `m` sessiz.

Playlistler, favoriler ve EPG adresi **sunucuda** (`data/store.json`) saklanır;
`localStorage` yalnızca çevrimdışı yedek olarak kullanılır.

## Kanal listesi

`data/channels.json` içinde [iptv-org](https://github.com/iptv-org/iptv) projesinden
alınan **Türkiye kanalları, film kanalları (777) ve dizi kanalları (440)** ile
her ortamda çalışan birkaç demo yayın bulunur — toplam ~1400 kanal.
Listeyi yenilemek için:

```bash
python build_channels.py
```

## Kendi listenizi ekleme / taşıma

1. **Playlistler** düğmesine bas.
2. **M3U playlist adresi** alanına listenin URL'sini yaz ve **Playlist ekle**
   (ya da *Dosyadan yükle* ile `.m3u` dosyanı seç).
3. Liste **sunucuda** saklanır; aynı adresi açan her cihazdan görünür.
4. Taşımak için **Yedek indir (JSON)** ile dosyayı al, diğer bilgisayarda
   **Yedek / M3U yükle** ile geri yükle.

## Notlar

- Ücretsiz/genel kaynaklardaki yayınlar sık sık **bölgesel olarak kısıtlanır**
  (geo-block) veya çevrimdışı olur; kanal listesi sadece işaret eder, garanti vermez.
  Kendi abonelik/playlist adresinizi "Playlistler" bölümünden ekleyebilirsiniz.
- Tarayıcı, `https` sayfadan `http` yayın açamaz (mixed content). Böyle durumlarda
  `/api/proxy` kullanılır, ancak kaynak yine de erişilebilir olmalıdır.
- Yalnızca erişim hakkına sahip olduğunuz yayınları oynatın.
