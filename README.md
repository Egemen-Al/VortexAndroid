# 🌀 VORTEX Android — Kurulum Rehberi

## Proje Yapısı
```
VortexAndroid/
├── build.gradle                          ← Root gradle
├── settings.gradle
├── app/
│   ├── build.gradle                      ← App gradle (bağımlılıklar)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/vortex/downloader/
│       │   ├── ui/
│       │   │   ├── MainActivity.kt       ← Ana ekran
│       │   │   ├── MainViewModel.kt      ← Tüm state yönetimi
│       │   │   ├── SetupFragment.kt      ← İlk kurulum ekranı
│       │   │   ├── home/
│       │   │   │   └── HomeFragment.kt   ← İndirme ekranı
│       │   │   └── history/
│       │   │       └── HistoryFragment.kt ← Geçmiş ekranı
│       │   ├── service/
│       │   │   └── DownloadService.kt    ← Arka plan indirme servisi
│       │   ├── data/db/
│       │   │   └── VortexDatabase.kt     ← Room veritabanı
│       │   └── util/
│       │       ├── BinaryManager.kt      ← yt-dlp + ffmpeg otomatik indir
│       │       └── YtDlpRunner.kt        ← yt-dlp'yi çalıştır, parse et
│       └── res/
│           ├── layout/                   ← XML layoutlar
│           ├── navigation/nav_graph.xml  ← Navigasyon
│           ├── values/                   ← Renkler, temalar, stringler
│           ├── drawable/                 ← İkonlar
│           └── menu/                     ← Alt navigasyon menüsü
```

---

## Android Studio'da Açma

1. Android Studio'yu açın
2. **File → Open** → `VortexAndroid` klasörünü seçin
3. Gradle sync otomatik başlar — birkaç dakika bekleyin
4. Sync tamamlandıktan sonra **Run ▶** butonuna basın

---

## Nasıl Çalışır?

### İlk Açılış (Setup Ekranı)
Uygulama ilk açılışta:
- GitHub'dan `yt-dlp` binary indirir (~15MB, ARM64)
- GitHub'dan `ffmpeg` binary indirir (~30MB, ARM64)
- Bunları `/data/data/com.vortex.downloader/files/bin/` dizinine kaydeder
- Executable (çalıştırılabilir) yapar
- Bir daha indirmez — güncelleme manuel

### Normal Kullanım
1. URL girin veya tarayıcıdan "Paylaş → Vortex" yapın
2. **Analiz Et** → video bilgisi ve format listesi gelir
3. İstediğiniz kaliteyi seçin (4K / 1080p / 720p / MP3 / M4A vb.)
4. **İndir** → arka planda foreground service çalışır
5. Bildirimde ilerleme görünür
6. Video: `Movies/Vortex/`, Müzik: `Music/Vortex/` klasörüne kaydedilir

---

## Desteklenen Platformlar
yt-dlp sayesinde 1000+ site desteklenir:
- YouTube, YouTube Music
- Instagram, TikTok, Twitter/X
- Facebook, Reddit
- SoundCloud, Spotify (bazı içerikler)
- Dailymotion, Vimeo ve çok daha fazlası

---

## Bağımlılıklar (build.gradle'da zaten var)
- `androidx.navigation` — fragment yönetimi
- `androidx.room` — SQLite geçmiş
- `androidx.work` — arka plan işleri
- `com.squareup.okhttp3` — binary indirme
- `com.github.bumptech.glide` — thumbnail yükleme
- `com.google.code.gson` — JSON parse

---

## Sorun Giderme

| Sorun | Çözüm |
|-------|-------|
| Setup ekranı takılıyor | İnternet bağlantısını kontrol edin, Tekrar Dene'ye basın |
| "Binary bulunamadı" hatası | Uygulamayı sil, yeniden kur |
| Video inmiyor | URL'in desteklendiğinden emin olun, yt-dlp güncelleme gerekebilir |
| ARM hatası | Telefon ARM64 değil — x86 emülatörde test ediyorsanız normal |

---

## Uygulamayı APK'ya Çevirmek

Android Studio'da:
**Build → Build Bundle(s) / APK(s) → Build APK(s)**

APK: `app/build/outputs/apk/debug/app-debug.apk`

---

## Geliştirme Notları

- `BinaryManager.kt` → yeni binary URL eklemek için buraya bakın
- `YtDlpRunner.kt` → format listesini değiştirmek için `buildFormats()` metodunu düzenleyin  
- `DownloadService.kt` → kayıt dizinini değiştirmek için `getOutputDir()` metodunu düzenleyin
- Tüm renkler → `res/values/colors.xml`
