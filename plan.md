# Rencana UI ForkDex bergaya DeX

## Arah visual

Gunakan screenshot DeX yang dikirim pengguna sebagai referensi visual utama. Desktop memakai wallpaper hitam/dark tanpa panel wallpaper tambahan, ikon aplikasi berjajar di area tengah-bawah, serta taskbar penuh selebar layar di tepi bawah dengan kontrol navigasi kiri, launcher/task icons di tengah, dan status/jam/quick settings di kanan. Quick Settings ditampilkan sebagai panel gelap membulat yang menempel di kanan atas taskbar pada landscape. Di portrait, susun shortcut menjadi grid yang mengisi area desktop dan taskbar tetap di bawah; Quick Settings menjadi panel/bottom sheet lebar yang tidak menutup seluruh desktop. Gunakan tipografi putih, ikon sistem/app, garis divider halus, dan aksen biru seperlunya. Reproduksi susunan dan bahasa visual, bukan logo/asset proprietary Samsung.

## Implementasi

Rombak komposisi native View di `MainActivity.java`, pertahankan layanan Shizuku/freeform, pencarian/start drawer, pin aplikasi, daftar task aktif, tombol jendela, Per-App DPI, dan alur secondary display. Buat shortcut desktop dari pinned apps lalu isi ruang kosong dengan aplikasi launcher terpasang; klik membuka aplikasi dan long-press tetap membuka menu tindakan yang ada. Taskbar baru responsif: landscape memakai tombol navigation kiri, launcher/tugas di area tengah, dan status/time/quick-settings di kanan; portrait merapatkan taskbar dan memindah ikon menu agar dapat diakses. Quick Settings menjadi panel visual terintegrasi dengan kartu Wi-Fi/Bluetooth dan grid Sound, Mobile Data, DeX, Dark mode, serta Screen recorder; aksi diarahkan ke panel Settings resmi yang relevan, sedangkan fungsi ForkDex yang tersedia (Shizuku, window inventory, display mode, error log, power options) tetap dapat dibuka langsung. Jangan mengklaim mengubah keadaan radio/brightness sistem tanpa API atau izin yang sesuai.

Gunakan dimensi berdasarkan lebar/tinggi aktual, orientasi serta density; manifest sudah mengizinkan `orientation|screenSize|smallestScreenSize` dan activity resizeable, sehingga `onConfigurationChanged` dapat me-render ulang layout tanpa kehilangan pencarian. Tata drawer dan Quick Settings harus dibatasi terhadap taskbar dan tinggi layar agar tetap usable pada portrait.

## Struktur

- `app/src/main/java/io/github/forkdex/MainActivity.java`: komposisi desktop, shortcut, taskbar, panel quick settings, ukuran responsif dan interaksi.
- `app/src/main/res/values/styles.xml`: palet dasar bila diperlukan.
- `README.md`: jelaskan layout baru, behavior portrait/landscape, dan batasan kontrol sistem.

## Batasan

Android membatasi launcher biasa dalam mengontrol langsung Quick Settings sistem, status bar, Wi-Fi, data seluler, brightness dan screen recording. UI ForkDex menyediakan akses yang jelas ke panel Settings/aksi yang didukung tanpa menyamar sebagai kontrol sistem. Samsung DeX adalah produk proprietary; hasilnya merupakan reproduksi visual independen yang dekat dengan referensi, bukan system UI Samsung asli.