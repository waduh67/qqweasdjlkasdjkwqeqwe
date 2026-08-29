# Glosarium copy UI

Glosarium ini menjadi acuan copy NetOps Console. Gunakan istilah teknis yang sudah dikenal operator, bukan terjemahan baru.

## Istilah yang disetujui

| Konsep | Gunakan | Aturan |
|---|---|---|
| Infrastruktur | `server`, `hostname` | Pertahankan dalam bahasa Inggris. `hostname` adalah nama host, bukan nama server umum. |
| Kredensial | `Username`, `Kata sandi` | Gunakan `Username` sebagai label yang sudah dikenal produk. Gunakan `Kata sandi`, bukan terjemahan teknis lain. |
| Hotspot | `situs hotspot`, `sesi`, `voucher` | `situs hotspot` untuk lokasi atau konfigurasi hotspot, `sesi` untuk koneksi aktif, `voucher` untuk kredensial akses. |
| Pihak layanan | `pelanggan` | Gunakan untuk orang atau akun yang menerima layanan. Jangan ganti dengan istilah lain tanpa makna domain yang jelas. |
| Perangkat | `perangkat`, `ONU` | `perangkat` untuk istilah umum, `ONU` saat objeknya khusus ONU. |
| Jaringan dan protokol | `BRAS`, `NAS`, `RADIUS`, `OLT`, `ACS`, `VLAN`, `PPPoE`, `portal captive` | Pertahankan kapitalisasi dan istilah teknis ini. Jangan diterjemahkan atau diperluas bila konteks sudah jelas. |
| Aksi destruktif | `Hapus` | Pakai bila data atau konfigurasi dihapus dari sistem. Nyatakan objek yang dihapus pada dialog konfirmasi. |
| Pencabutan akses | `Cabut voucher` | Pakai bila voucher tidak lagi dapat digunakan, bukan saat catatan voucher dihapus. |

## Aturan penulisan

- Gunakan sentence case, kalimat aktif, dan label singkat.
- Utamakan penghapusan copy. Hapus instruksi kata sandi yang sudah jelas, narasi “click Edit”, serta narasi tiket atau linimasa yang hanya menjelaskan tindakan antarmuka.
- Jangan memakai pembuka percakapan, instruksi “Anda harus”, atau detail implementasi internal, kecuali informasi itu diperlukan untuk tindakan langsung atau keselamatan operasional.
- Helper text hanya boleh menjawab kebutuhan yang tidak dapat dijelaskan oleh label, nilai awal, format input, atau validasi. Hapus bila tidak mengubah keputusan operator.
- Bedakan `Hapus` dan `Cabut voucher`: `Hapus` menghilangkan data atau konfigurasi, sedangkan `Cabut voucher` menghentikan penggunaan voucher sambil mempertahankan riwayat yang diperlukan.
- Pertahankan makna error protokol, API, dan vendor. Boleh ringkas narasinya, tetapi jangan mengubah kode, batasan kompatibilitas, atau penyebab yang perlu ditindak operator.

## Nama aksesibel dan pengujian

Label tombol, input, tab, dialog, dan status dapat menjadi accessible name atau dipakai oleh pengujian. Sebelum mengubahnya, periksa keterkaitan dengan `aria-label`, label terkait, role, dan assertion pengujian. Perbarui pengujian hanya bila nama aksesibel yang disetujui memang berubah, tanpa mengubah makna tindakan atau status.
