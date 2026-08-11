-- ============================================================
-- Data lapangan sebuah kotak: kapan dipasang, bagaimana ia menempel, dan apa
-- yang perlu diketahui orang yang akan membukanya.
--
-- Selama ini kotak cuma punya kode, nama, titik, dan kapasitas — cukup untuk
-- menggambar peta, tak cukup untuk MENDATANGINYA. Tiga hal yang selalu ditanya
-- di lapangan tapi tak punya tempat di data:
--
--   1. `installed_on` — umur aset. Splitter dan konektor menua; kotak yang
--      dipasang tujuh tahun lalu adalah tersangka pertama saat satu klaster
--      pelan-pelan meredup, dan tanggal inilah dasar jadwal preventif serta
--      klaim garansi ke vendor.
--   2. `mounting` — dudukannya. Menentukan alat yang dibawa SEBELUM berangkat:
--      tiang butuh tangga/bucket truck, handhole butuh kunci dan pompa air,
--      pedestal bisa dibuka sambil berdiri. Salah tebak = tim pulang tanpa
--      hasil dan pelanggan menunggu sehari lagi.
--   3. `notes` — kalimat teknisi untuk teknisi berikutnya: "kunci di pos
--      satpam", "tiang miring, jangan dipanjat sendirian", "core 5-8 disisakan
--      untuk klaster sebelah". Selama ini catatan begini hidup di grup WhatsApp
--      dan hilang bersama orangnya.
--
-- Semuanya NULL untuk baris lama, dan sengaja dibiarkan NULL: menebak dudukan
-- kotak yang tak pernah dilihat siapa pun lebih buruk daripada mengaku belum
-- tahu. Tak ada baris yang berubah, tak ada default yang mengarang data.
--
-- ODF tak ikut: ia rak di dalam POP, dudukannya selalu "dalam ruangan" dan
-- alamatnya alamat POP-nya — tiga kolom ini takkan pernah punya isi yang beda.
-- ============================================================

ALTER TABLE odc
    ADD COLUMN installed_on date,
    ADD COLUMN mounting     varchar(20),
    ADD COLUMN notes        varchar(1000);

ALTER TABLE odp
    ADD COLUMN installed_on date,
    ADD COLUMN mounting     varchar(20),
    ADD COLUMN notes        varchar(1000);

ALTER TABLE joint_box
    ADD COLUMN installed_on date,
    ADD COLUMN mounting     varchar(20),
    ADD COLUMN notes        varchar(1000);

-- Daftar dudukan dijaga di DB, bukan cuma di enum aplikasi: kolom teks bebas
-- akan terisi "tiang", "Tiang", dan "POLE" sekaligus begitu ada satu importir
-- data yang lewat samping aplikasi, dan laporan "berapa kotak saya yang di
-- bawah tanah" tak pernah bisa dipercaya lagi.
ALTER TABLE odc ADD CONSTRAINT ck_odc_mounting
    CHECK (mounting IS NULL OR mounting IN ('POLE', 'WALL', 'AERIAL', 'PEDESTAL', 'UNDERGROUND', 'INDOOR'));
ALTER TABLE odp ADD CONSTRAINT ck_odp_mounting
    CHECK (mounting IS NULL OR mounting IN ('POLE', 'WALL', 'AERIAL', 'PEDESTAL', 'UNDERGROUND', 'INDOOR'));
ALTER TABLE joint_box ADD CONSTRAINT ck_joint_box_mounting
    CHECK (mounting IS NULL OR mounting IN ('POLE', 'WALL', 'AERIAL', 'PEDESTAL', 'UNDERGROUND', 'INDOOR'));
