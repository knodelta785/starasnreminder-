# ASN ABSEN REMINDER (by Ongarand)

Aplikasi Android pengingat absen masuk dan absen pulang.

## Fitur

- **Absen masuk 07:30** dan **absen pulang 16:30** (jam alarm bisa diubah di menu Alarm).
- Default pengingat **Senin–Jumat**. Sabtu dan Minggu bisa diaktifkan lewat kartu *Hari Pengingat*.
  Hari libur dan tanggal merah tetap diingatkan sesuai hari yang dipilih.
- **Notifikasi status bar**
  - *Belum absen masuk* muncul sejak **00:01** sampai absen masuk dilakukan.
  - *Belum absen pulang* muncul sejak **16:31** sampai absen pulang dilakukan.
  - Hilang lewat tombol **Sudah absen** di aplikasi, atau lewat tombol kecil di notifikasi
    (seperti "Tandai dibaca" di WhatsApp).
- **Menu Alarm Absen Masuk** dan **Alarm Absen Pulang** (aktif/nonaktif, ubah jam, uji bunyi).
  Alarm otomatis OFF untuk hari itu begitu absen dilakukan, dan aktif lagi esok harinya.
- **Timestamp** jam:menit:detik saat absen dicatat, plus tombol *Ubah jam* bila perlu dikoreksi.
- **Reset data absen** (hari ini saja atau semua data) lewat menu titik tiga.
- Warna kartu **merah** = belum absen, **hijau** = sudah absen.
- Riwayat 7 hari terakhir.

## Cara mendapatkan APK lewat GitHub (tanpa Android Studio)

1. Buat repository baru di GitHub, lalu upload seluruh isi folder proyek ini
   (pastikan folder `.github` ikut terupload).
2. Buka tab **Actions** → workflow **Build APK** → tunggu sampai selesai (sekitar 3–5 menit).
   Bisa juga dijalankan manual dengan tombol *Run workflow*.
3. Buka hasil run tersebut, unduh artifact **ASN-ABSEN-REMINDER-apk**, ekstrak, lalu pasang
   `app-debug.apk` di HP (izinkan "Install dari sumber tidak dikenal").

## Cara lain: Android Studio

Buka folder ini di Android Studio (Hedgehog atau lebih baru), tunggu Gradle sync, lalu
**Build → Build APK(s)**.

## Pengaturan penting di HP

Buka menu titik tiga → **Izin & Baterai**, lalu:

- Izinkan **notifikasi**.
- Pilih **tanpa pembatasan** pada optimasi baterai agar alarm tepat waktu.
- Android 14: izinkan **notifikasi layar penuh** agar layar alarm muncul di atas kunci layar.
- Beberapa merek HP (Xiaomi, Oppo, Vivo, Realme, Samsung) perlu mengaktifkan **Mulai otomatis / Autostart**
  untuk aplikasi ini di pengaturan sistem.

## Struktur singkat

| File | Fungsi |
|------|--------|
| `Store.kt` | Penyimpanan data absen, hari pengingat, pengaturan alarm |
| `Scheduler.kt` | Penjadwalan alarm 07:30 / 16:30 dan pemicu notifikasi 00:01 / 16:31 |
| `Notifier.kt` | Notifikasi status bar dan notifikasi alarm |
| `AlarmService.kt`, `AlarmActivity.kt` | Suara/getar dan layar alarm penuh |
| `Receivers.kt` | Penerima alarm, tombol notifikasi, dan boot |
| `MainActivity.kt`, `AlarmSettingActivity.kt` | Layar utama dan menu alarm |

## v1.1 — Akun STAR ASN
- Menu ⋮ → **Akun STAR ASN**: simpan akun sendiri (password terenkripsi Android Keystore, disembunyikan, lihat butuh PIN/sidik jari HP).
- Tombol **BUKA STAR ASN** di layar utama, tombol di notifikasi pengingat, dan notifikasi "Login STAR ASN" yang muncul bersamaan dengan notifikasi belum absen (masuk/pulang) dan hilang otomatis setelah absen tercatat → membuka STAR ASN dengan login terisi otomatis.
- Kredensial hanya diisikan di domain `kemenimipas.go.id` (lihat `TRUSTED_HOSTS` di `StarAsnActivity.kt`).
- Tombol presensi tetap ditekan pengguna sendiri.

## v2.0 — Revisi
- **Jam absen default** masuk dan pulang (menu ⋮ → *Jam Absen Default*, atau ketuk jam default di kartu). Hanya jadwal acuan; **bukan** waktu alarm. Jam default pulang + 1 menit menentukan kapan notifikasi "belum absen pulang" muncul.
- **Alarm terpisah dari jam default**: hingga **3 waktu alarm absen masuk** dan **3 waktu alarm absen pulang** (menu ⋮ → *Alarm Absen Masuk / Pulang*), masing-masing bisa diaktifkan, dinonaktifkan, dan diubah jamnya.
- Begitu **absen masuk** tercatat hari itu, **semua alarm masuk otomatis OFF**; begitu **absen pulang** tercatat, **semua alarm pulang otomatis OFF**. Aktif lagi besok.
- Nama aplikasi: **ASN ABSEN REMINDER**. Ikon baru: jam sederhana + tulisan "by Ongarand" (font TeX Gyre Chorus).
- **Mode latar belakang** tanpa notifikasi tambahan: alarm memakai AlarmManager sehingga tetap berbunyi walau aplikasi ditutup, dipasang ulang setelah restart/update, plus alarm penjaga tiap 30 menit. Bisa dimatikan di menu ⋮ → *Mode Latar Belakang*.

## v2.1 — Sinkron otomatis dengan STAR ASN (tanpa tombol "Sudah absen")
- **Tombol "Sudah absen masuk/pulang" dihapus** (layar utama, notifikasi, dan layar alarm), begitu juga "Ubah jam".
  Status dan jam absen sekarang **hanya** berasal dari dashboard STAR ASN
  (`https://star-asn.kemenimipas.go.id/home/dashboard`). Aplikasi hanya **membaca** halaman, tidak pernah menekan tombol presensi.
- Presensi **masuk** dan **pulang** dibaca terpisah; jam aktualnya (HH:mm:ss) langsung muncul di kartu.
  Bila di website belum presensi, kartu tetap merah walau Anda menekan apa pun di aplikasi.
- Sinkron berjalan di latar belakang: tiap 15 menit, tiap aplikasi dibuka, 90 detik sebelum alarm,
  saat alarm berbunyi (alarm mati sendiri bila ternyata presensi sudah ada), dan saat Anda selesai memakai layar STAR ASN.
- Sesi habis? Aplikasi login otomatis memakai akun tersimpan. Bila terhalang captcha / kunci layar,
  muncul notifikasi "Presensi belum bisa disinkronkan" — login sekali lewat BUKA STAR ASN.
- Kartu **Status sinkron** di layar utama (tombol *Sinkron*), menu ⋮ → *Sinkron sekarang* dan *Diagnostik Sinkron*
  (menampilkan teks halaman yang terbaca, berguna bila tampilan STAR ASN berubah).
- *Reset Data Lokal* hanya menghapus catatan di HP; data akan terisi lagi dari STAR ASN pada sinkron berikutnya.
- Pembaca halaman: `app/src/main/assets/star_extract.js` (bisa diuji dengan node, lihat `module.exports`).
