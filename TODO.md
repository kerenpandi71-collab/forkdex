# TODO — Rombak UI ForkDex

## [ ] Desktop shell visual bergaya Samsung DeX

**Kriteria pengguna:** “buka repo forkdex ubah seluruh tampilan ui nya jadi kayak Gini harus sama persiis dengan Samsung dex rombak total support landscape maupun portrait”. Gunakan screenshot yang diberikan sebagai panduan visual: desktop hitam/dark, ikon aplikasi di area desktop, taskbar penuh di bagian bawah, elemen navigasi di kiri, aplikasi/task di tengah, waktu dan status di kanan, serta panel Quick Settings dark di kanan. Rombak komposisi UI aplikasi ForkDex agar sedekat mungkin dengan susunan dan gaya Samsung DeX tanpa memakai logo/asset Samsung atau mengaku sebagai UI sistem Samsung.

## [ ] Tata letak responsif landscape dan portrait

Desktop harus mendukung landscape maupun portrait. Landscape mempertahankan deretan shortcut yang berpusat dan taskbar horizontal bawah. Portrait menata ikon aplikasi menjadi grid yang muat layar, menjaga taskbar dapat dipakai, dan menyusun Quick Settings sebagai panel lebar/bottom sheet. Rotasi tidak boleh menghapus teks pencarian, status drawer, preferensi, atau fitur launcher.

## [ ] Quick Settings serta fitur ForkDex yang ada

Quick Settings ditampilkan sebagai panel desktop bergaya DeX dengan kartu Wi-Fi/Bluetooth dan grid akses Sound, Mobile Data, DeX, Dark mode, Screen recorder, serta volume/media. Aksi radio/brightness/recording harus menuju API/panel Android resmi jika launcher tidak berhak mengubahnya secara langsung. Pertahankan Start/app drawer, app pins, task switcher, konfigurasi Freeform/window, Shizuku, Per-App DPI, secondary display dan power options.
