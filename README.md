 # Ekran Kaydedici — Android Uygulaması

Samsung Galaxy S25+ / One UI 9 / Android için yüksek kaliteli ekran kaydedici.

## Özellikler

- ✅ **1080p @ 60 FPS** ekran kaydı (MediaProjection API)
- ✅ **Yalnızca medya/uygulama sesi** — mikrofon kaydı yok (AudioPlaybackCapture API)
- ✅ **Kayıt sırasında sıfır overlay** — floating buton/timer yok
- ✅ Uygulama içinden **"Başlat"** ve **"Kaydı Durdur"** butonları
- ✅ **MP4** olarak doğrudan **Galeri / Movies** klasörüne kaydeder
- ✅ Android 10+ (minSdk 29) uyumlu

---

## APK Build Etme

### Yöntem 1: GitHub Actions (Önerilen — sıfır kurulum)

1. Bu projeyi GitHub'a push edin
2. **Actions** sekmesine gidin
3. **Build APK** workflow'u otomatik başlar
4. İş bitince **Artifacts** bölümünden `EkranKaydedici-debug.apk` indirin
5. APK'yı telefonunuza aktarın ve yükleyin

### Yöntem 2: Android Studio (Yerel)

1. [Android Studio](https://developer.android.com/studio) indirin
2. `ScreenRecorder` klasörünü açın
3. SDK indir: **Tools → SDK Manager → Android 14 (API 34)**
4. `Build → Generate Signed Bundle / APK → APK → debug` seçin
5. `app/build/outputs/apk/debug/app-debug.apk` dosyasını alın

### Yöntem 3: Komut Satırı (Android SDK yüklüyse)

```bash
cd ScreenRecorder
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## Kurulum

1. Telefonda **Ayarlar → Güvenlik → Bilinmeyen Kaynaklar** aktif edin
2. APK dosyasını telefona kopyalayın
3. Dosya yöneticisiyle APK'ya dokunun → yükle

## Kullanım

1. Uygulamayı açın
2. **"▶ Başlat"** butonuna basın
3. Sistem izin diyaloğunu onaylayın (ekran paylaşımı)
4. Kayıt başlar — hiçbir overlay görünmez
5. Uygulamaya dönün → **"⬛ Kaydı Durdur"** basın
6. Video **Galeri → Movies** klasöründe görünür

---

## İzinler

| İzin | Neden |
|------|-------|
| `FOREGROUND_SERVICE` | Kayıt arka planda devam etsin |
| `RECORD_AUDIO` | Medya sesi yakalamak için zorunlu |
| `MEDIA_PROJECTION` | Ekran içeriğine erişim |

> **Not:** `RECORD_AUDIO` yalnızca dahili medya sesini yakalamak için kullanılır (AudioPlaybackCapture). Mikrofon açılmaz.
