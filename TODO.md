# TODO — Rombak UI ForkDex

## [x] Desktop shell visual bergaya Samsung DeX

**Kriteria pengguna:** “buka repo forkdex ubah seluruh tampilan ui nya jadi kayak Gini harus sama persiis dengan Samsung dex rombak total support landscape maupun portrait”. Implementasi mengikuti susunan referensi yang diberikan: desktop gelap dengan shortcut aplikasi, taskbar penuh di bawah, navigasi kiri, aplikasi/task di tengah, jam/Quick Settings di kanan, serta panel Quick Settings grafit. Ini adalah reproduksi visual independen—bukan system UI Samsung dan tidak memakai logo/asset resmi Samsung.

## [x] Tata letak responsif landscape dan portrait

Landscape menggunakan deretan shortcut desktop dan taskbar horizontal. Portrait menggunakan grid shortcut, taskbar ringkas dengan akses Jendela berjalan, dan Quick Settings sebagai panel lebar. Rotasi merender ulang layout dan mempertahankan query serta status drawer yang sudah ada.

## [x] Quick Settings dan fitur ForkDex yang ada

Panel mempunyai kartu Wi-Fi/Bluetooth, Sound, Mobile data, Forkdex Desktop Mode, tema gelap Forkdex, Screen recorder yang menjelaskan penggunaan Quick Settings sistem Android, kontrol volume, Bluetooth devices, dan menu alat ForkDex. Perubahan radio, perekaman, dan fungsi sistem tetap dikelola Android; Forkdex tidak mengklaim mengubah fitur yang dibatasi OS. Start, pin, task switcher, Shizuku/freeform, Per-App DPI dan fungsi yang sudah ada tetap dipertahankan.

## [x] Validasi build CI

GitHub Actions run [42097566873](https://github.com/kerenpandi71-collab/forkdex/actions/runs/42097566873) berhasil menjalankan unit test, kompilasi APK debug, dan upload artifact pada commit `cf5705a` di branch `feature/dex-responsive-ui`.

## [ ] Uji perangkat nyata

Masih perlu memeriksa ukuran ikon, safe area/system bars, interaksi layar sentuh, panel settings, dan behavior freeform pada perangkat Android secara nyata dalam orientasi portrait dan landscape serta pada virtual/extended display bila tersedia.
