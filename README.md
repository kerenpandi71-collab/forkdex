# Forkdex

Forkdex adalah aplikasi launcher Android yang membawa desktop shell sederhana, drawer aplikasi, taskbar, serta kontrol freeform dan skala per aplikasi melalui Shizuku pada perangkat yang mendukungnya.

## Fitur utama

- Menemukan dan menjalankan aplikasi Android, termasuk aplikasi pada profil pengguna lain yang dapat diakses.
- Drawer Start dengan pencarian, aplikasi pinned, dan pengurutan daftar.
- Taskbar dan tampilan desktop pada layar yang didukung.
- Membuka aplikasi dalam mode freeform serta mengelola tugas melalui layanan Shizuku jika perangkat dan izin mendukungnya.
- Profil skala per aplikasi (50–200%).
- Tampilan drawer Start bergaya liquid glass transparan.

Kapabilitas freeform dan kontrol tugas bergantung pada dukungan firmware Android, API yang tersedia, serta konfigurasi Shizuku. Aplikasi akan melaporkan operasi yang tidak tersedia; build berhasil tidak menjamin fitur tersebut didukung oleh setiap perangkat.

## Build

Persyaratan:

- JDK 17
- Android SDK Platform 35 dan Android Build Tools 35.0.0

Build APK debug dari root repository:

```bash
./gradlew --no-daemon :app:assembleDebug
```

APK akan dibuat di `app/build/outputs/apk/debug/app-debug.apk`.

## Lisensi

Forkdex didistribusikan berdasarkan GNU General Public License versi 3. Lihat [`LICENSE`](LICENSE).
