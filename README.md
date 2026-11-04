# Forkdex

Forkdex adalah aplikasi launcher Android dengan desktop shell ringan: drawer Start, dock/taskbar, daftar jendela, serta kontrol freeform dan skala per aplikasi melalui Shizuku pada perangkat yang mendukungnya.

## Fitur

- Menemukan dan menjalankan aplikasi Android, termasuk aplikasi yang terlihat dari profil pengguna lain.
- Drawer Start dengan pencarian aplikasi, tombol hapus pencarian, aplikasi favorit yang disematkan, serta urutan A–Z/Z–A yang disimpan.
- Tekan lama aplikasi untuk membuka, menyematkan/melepas, melihat info, mengatur Per-App DPI, menyembunyikan, atau menghapus instalasi jika didukung Android.
- Aplikasi profil lain dapat dibuka, disematkan per profil, disembunyikan, dan dibuka informasinya.
- Dock/taskbar kaca mengambang dengan shortcut Start/search, status koneksi Shizuku, jendela aktif, tanggal dan waktu.
- Panel dan dialog memakai latar liquid-glass transparan, refleksi tepi, ripple serta animasi interaksi. Android 12+ dapat menerapkan blur window jika perangkat mendukungnya; versi lama tetap memakai fallback transparan.
- Mode Freeform, kontrol jendela, dan profil skala aplikasi 50–200% melalui layanan Shizuku jika izin serta firmware perangkat mengizinkan.
- Back menutup drawer terlebih dahulu; ketuk di luar drawer untuk menutupnya. Pencarian dan visibilitas drawer dipertahankan saat ukuran/orientasi layar berubah.

## Batasan kompatibilitas

Freeform, pengelolaan task, secondary home, dan per-app density bergantung pada dukungan firmware Android, API yang tersedia, izin Shizuku, serta kebijakan perangkat. Efek blur juga opsional dan bergantung pada dukungan OS/perangkat. Build yang sukses tidak menjamin semua fitur tersedia pada setiap perangkat. Forkdex tidak mengubah kepadatan global layar.

## Build

Persyaratan:

- JDK 17
- Android SDK Platform 35 dan Android Build Tools 35.0.0

Build APK debug dari root repository:

```bash
./gradlew --no-daemon :app:assembleDebug
```

APK dibuat di `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions menjalankan build pada push/PR ke `main`; status dan artefak build tersedia di tab **Actions** repo.

## Lisensi

Forkdex didistribusikan berdasarkan GNU General Public License versi 3. Lihat [`LICENSE`](LICENSE).
