# IPTV Player

Tarayıcıda çalışan, kurulum gerektirmeyen bir **IPTV / canlı TV oynatıcı**. M3U/M3U8
playlist'lerini yükler, HLS (`m3u8`) yayınlarını oynatır, kanal arama, favoriler,
gruplar, EPG (XMLTV) ve resim-içinde-resim (PiP) destekler.

Tek bağımlılık Python 3.9+ (standart kütüphane). Arayüz saf HTML/CSS/JS'tir;
HLS oynatma için `hls.js` yerel olarak paketlenmiştir.

## Hızlı başlangıç

```bash
python server.py --port 8000
# tarayıcıda aç: http://localhost:8000
```

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

Ayarlar, favoriler ve playlistler tarayıcının `localStorage`'ında tutulur.

## Kanal listesi

`data/channels.json` içinde [iptv-org](https://github.com/iptv-org/iptv) projesinden
alınan Türkiye kanalları ve her ortamda çalışan birkaç demo yayın bulunur.
Listeyi yenilemek için:

```bash
python build_channels.py
```

## Notlar

- Ücretsiz/genel kaynaklardaki yayınlar sık sık **bölgesel olarak kısıtlanır**
  (geo-block) veya çevrimdışı olur; kanal listesi sadece işaret eder, garanti vermez.
  Kendi abonelik/playlist adresinizi "Playlistler" bölümünden ekleyebilirsiniz.
- Tarayıcı, `https` sayfadan `http` yayın açamaz (mixed content). Böyle durumlarda
  `/api/proxy` kullanılır, ancak kaynak yine de erişilebilir olmalıdır.
- Yalnızca erişim hakkına sahip olduğunuz yayınları oynatın.
