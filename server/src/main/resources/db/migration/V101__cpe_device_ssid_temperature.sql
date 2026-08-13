-- ============================================================
-- Dua kolom untuk tabel armada di konsol ACS: SSID & suhu
--
-- Proyeksi `cpe_device` sengaja tipis (lihat docs/cpe.md): yang cepat basi —
-- daftar SSID, host tersambung — dibaca LANGSUNG dari ACS saat panel dibuka,
-- bukan disimpan. Aturan itu tetap berlaku untuk panel per-pelanggan.
--
-- Yang berubah adalah tabel armada se-tenant: 300 baris berarti 300 panggilan
-- NBI kalau SSID dibaca live per baris. Maka SSID PERTAMA (bukan seluruh daftar
-- jaringan) ikut dibawa siklus sinkron yang memang sudah menarik semua device
-- sekali — nol round-trip tambahan, cuma beberapa byte per dokumen.
--
-- Suhu tak punya path standar TR-069; tiap vendor memakai `X_*` sendiri. Path
-- yang dibaca digerakkan konfigurasi (`ftth.cpe.temperature-params`) dan MATI
-- secara bawaan, jadi kolom ini realistisnya null di sebagian besar armada
-- sampai ISP mengisi path vendornya.
--
-- Sengaja TANPA kolom pppoe: `bng` sumber kebenarannya, dan salinan di sini
-- sudah basi dalam hitungan menit.
-- ============================================================

ALTER TABLE cpe_device ADD COLUMN ssid          varchar(64);
ALTER TABLE cpe_device ADD COLUMN temperature_c numeric(5, 2);

COMMENT ON COLUMN cpe_device.ssid IS
    'SSID jaringan WiFi pertama, hasil sinkron ACS. Untuk daftar SSID lengkap & akurat, baca live lewat GET /api/cpe/devices/{id}/live.';
COMMENT ON COLUMN cpe_device.temperature_c IS
    'Suhu perangkat (Celsius) dari parameter vendor yang dikonfigurasi di ftth.cpe.temperature-params; null bila tak dikonfigurasi atau tak dilaporkan.';
