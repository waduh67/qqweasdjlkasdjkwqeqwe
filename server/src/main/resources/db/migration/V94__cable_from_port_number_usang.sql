-- ============================================================
-- `cable.from_port_number` ditandai USANG (untuk sumber kabinet).
--
-- Kolom ini lahir saat sebuah ODC dianggap punya satu splitter, sehingga "kabel
-- ini berangkat dari kaki 3" terdengar seperti kalimat yang lengkap. Sejak
-- splitter jadi benda tersendiri (V92) dan sambungan dicatat per core (V89),
-- kalimat itu kehilangan dua hal sekaligus:
--
--   1. Kaki 3 MILIK MODUL YANG MANA? Satu kabinet bisa berisi SPL-1 1:8,
--      SPL-2 1:8, SPL-3 1:16 — masing-masing punya kaki 3.
--   2. Sebuah selubung 8 core yang menyuapi delapan ODP berangkat dari DELAPAN
--      kaki sekaligus. Satu kolom cuma sanggup menyimpan satu di antaranya, dan
--      tujuh sisanya diam-diam hilang.
--
-- Penggantinya sudah berjalan: baris `fiber_connection` (kaki splitter ↔ core),
-- yang menyebut modul dan core-nya, dan yang sudah dipakai telusur jalur,
-- simulasi putus, serta anggaran redaman.
--
-- Kolomnya SENGAJA tidak dihapus. Data lama tetap dibaca dan tetap ditampilkan
-- di panel kabel — membuang catatan bertahun-tahun demi kerapian skema adalah
-- kerugian yang tak bisa dibatalkan. Yang berubah: ia tak lagi ditanyakan saat
-- menggambar kabel baru dari kabinet/kotak.
--
-- Slot ODP asal sebuah DROP tidak termasuk usang: di situ drop memang benar-benar
-- dicolok ke port panel, dan nomornya menunjuk satu tempat yang tak bermakna ganda.
-- ============================================================

COMMENT ON COLUMN cable.from_port_number IS
    'USANG untuk sumber ODC (kaki splitter): pakai fiber_connection (SPLITTER_OUT <-> CORE) '
        'yang menyebut modul & core-nya. Masih dipakai untuk slot ODP asal kabel drop. '
        'Nilai lama tetap dibaca, tak lagi diisi untuk kabel baru dari kabinet/kotak.';
