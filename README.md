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

`data/channels.json` içinde iki Türkçe kaynak birleştirilir ve yalnızca
**Türkçe yayınlar** paketlenir (~370 kanal + 4 demo):

- **Canlitv** (`build_canlitv.py`): [canlitv.you](https://www.canlitv.you/)
  dizinindeki 278 kanaldan doğrudan `.m3u8` akışı veren, Türkçe yayın yapan
  ~180 kanal. Bu akışlar iptv-org'dan belirgin şekilde daha güvenilirdir.
- **iptv-org**: Türkçe dil listesi + Türkiye kanalları (~190 kanal).

Almanca/İngilizce/Arapça yayınlar `NON_TURKISH` listesiyle ayıklanır; grup
adları Türkçeleştirilir (Haber, Spor, Müzik, Film, Çocuk, Belgesel, Dini...).
Aynı kanal iki kaynakta varsa Canlitv adresi tercih edilir. Listeyi yenilemek için:

```bash
python build_canlitv.py    # canlitv.you listesini çeker
python build_channels.py   # birleştirip data/channels.json üretir
```

## Kendi listenizi ekleme / taşıma

1. **Playlistler** düğmesine bas.
2. **M3U playlist adresi** alanına listenin URL'sini yaz ve **Playlist ekle**
   (ya da *Dosyadan yükle* ile `.m3u` dosyanı seç).
3. Liste **sunucuda** saklanır; aynı adresi açan her cihazdan görünür.
4. Taşımak için **Yedek indir (JSON)** ile dosyayı al, diğer bilgisayarda
   **Yedek / M3U yükle** ile geri yükle.

## MAC adresi ile IPTV (Stalker / MAG portal)

Aboneliğiniz MAC adresine bağlıysa:

1. **Playlistler** düğmesine bas.
2. **Portal adresi** (ör. `http://portal.ornek.com/c/`) ve size verilen
   **MAC adresi** (`00:1A:79:XX:XX:XX`) alanlarını doldur.
3. **MAC hesabı ekle** → kanallar portal üzerinden çekilir ve listeye eklenir.

Kanalların yayın adresleri MAC oturumuna bağlı olduğu için oynatma sunucu
üzerinden (gerekli UA/cookie başlıklarıyla) yapılır. Portal ve MAC bilgisi
yalnızca sizin cihazınızda saklanır; başkasıyla paylaşılmaz.

## Tek dosyalık çalıştırılabilir (exe) üretme

Programın tamamını **tek bir dosyaya** paketlemek için bu betiği çalıştır:

```bash
python build_exe.py
```

Kendi bilgisayarında çalıştırdığın işletim sistemine göre `dist/` içinde
kurulum gerektirmeyen tek dosya üretir:

- **Windows:** `dist/IPTV-Player.exe`
- **macOS:** `dist/IPTV-Player` (ve `IPTV Player.app`)
- **Linux:** `dist/IPTV-Player`

PyInstaller çapraz derleme yapmaz: Windows `.exe` yalnızca Windows'ta, macOS
yapısı yalnızca macOS'ta üretilir. Üçü için birden GitHub Actions iş akışı
(`.github/workflows/build.yml`) kullanılabilir.

## Notlar

- Ücretsiz/genel kaynaklardaki yayınlar sık sık **bölgesel olarak kısıtlanır**
  (geo-block) veya çevrimdışı olur; kanal listesi sadece işaret eder, garanti vermez.
  Kendi abonelik/playlist adresinizi "Playlistler" bölümünden ekleyebilirsiniz.
- Tarayıcı, `https` sayfadan `http` yayın açamaz (mixed content). Böyle durumlarda
  `/api/proxy` kullanılır, ancak kaynak yine de erişilebilir olmalıdır.
- Yalnızca erişim hakkına sahip olduğunuz yayınları oynatın.

## Telefon (Android) kullanımı

- **En kolay yol:** Bu sunucu adresini telefon tarayıcısında aç. Chrome menüsünden
  **“Ana ekrana ekle”** (Add to Home screen) seçeneğini kullan; uygulama tam ekran
  açılır ve simge gibi görünür (PWA). iOS/Safari'de **Paylaş → Ana Ekrana Ekle**.
- **Kendi IPTV uygulamanı kullanmak istersen:** Kanal listesini indir
  (`/channels.m3u`) ve telefonundaki uygulamaya (VLC, IPTV Pro, Tivimate vb.)
  bu dosyayı veya adresi ver.

## Kanal listesi indirme

| Ne | Adres |
|----|-------|
| Kanal listesi (M3U, 370 kayıt) | `/channels.m3u` |
| Tüm paket (kaynak + program + liste) | `/download/all` |
| Tek dosyalık program | `/download/player` |

## Sunucu ne kadar çalışır?

Bu adres, kodun çalıştığı **sandbox/oturum** yaşadığı sürece açıktır. Yeni bir
oturum başında bu geçici adres değişir/silinir. Kalıcı bir adres için paketi
indirip kendi bilgisayarında ya da bir sunucuda `python server.py` ile
çalıştır; veriler (`data/store.json`) ve liste (`data/channels.json`) yanında
durur, favoriler/playlistler kaybolmaz.
