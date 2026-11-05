# Forkdex

Forkdex adalah launcher Android dengan desktop shell ringan. UI desktop-nya mengikuti susunan Samsung DeX pada referensi pengguna: latar hitam, shortcut aplikasi di area desktop, taskbar penuh di bagian bawah, dan panel Quick Settings grafit. Implementasinya independen dan tidak menggunakan logo atau asset resmi Samsung.

## Fitur

- Desktop responsif untuk landscape dan portrait. Landscape menampilkan shortcut dalam satu baris; portrait mengubahnya menjadi grid agar ikon tidak terpotong.
- Taskbar edge-to-edge dengan navigasi, shortcut Start, aplikasi/window yang berjalan, waktu, dan akses Quick Settings.
- Quick Settings Forkdex bergaya DeX dengan tautan ke pengaturan Wi-Fi, Bluetooth, Sound, Mobile Data, Desktop Mode, dan kontrol volume media. Tombol Bluetooth devices menuju Bluetooth Settings; tile Screen recorder hanya menginformasikan bahwa perekaman dijalankan dari Quick Settings sistem. Panel dapat discroll pada layar pendek dan menjadi sheet lebar pada portrait.
- Menu Apps dengan pencarian, pin aplikasi, daftar semua aplikasi, sorting, opsi grid/list, serta menu konteks melalui tekan lama.
- Menemukan dan menjalankan aplikasi Android, termasuk aplikasi yang terlihat dari profil pengguna lain.
- Aplikasi dapat dibuka sebagai freeform window, dan task yang berjalan bisa difokuskan, diminimalkan, dimaksimalkan, dibagi area, dipulihkan, atau ditutup melalui Shizuku jika firmware mendukungnya.
- Profil Per-App DPI (50–200%) tersimpan per aplikasi. Forkdex tidak mengubah kepadatan global layar.
- Panel/tiles menggunakan gaya gelap dan refleksi tepi. Beberapa fungsi tetap diarahkan ke halaman Settings Android karena launcher biasa tidak memiliki hak untuk mengubah radio sistem, perekaman layar, atau kontrol quick panel OS.
- Back menutup Quick Settings, keyboard, atau menu Apps secara berurutan. Pencarian dan visibilitas menu Apps dipertahankan ketika Activity menerima perubahan orientasi.

## Batasan kompatibilitas

Forkdex mereplikasi susunan visual desktop DeX, tetapi bukan Samsung DeX atau system UI Samsung. Android membatasi launcher biasa untuk mengubah Wi-Fi/data seluler, brightness, screen recording, dan status panel sistem secara langsung; kontrol terkait mengarahkan pengguna ke pengaturan/Quick Settings Android atau memberi penjelasan. Freeform, pengelolaan task, secondary home, dan per-app density bergantung pada firmware Android, API tersedia, izin Shizuku, dan kebijakan perangkat.

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
