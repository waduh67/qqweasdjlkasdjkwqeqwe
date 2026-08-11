-- ============================================================
-- Status baru: ABANDONED — "fisiknya masih ada, tapi sudah tak dipakai".
--
-- Yang paling sering menyandangnya: kabel drop bekas pelanggan yang cabut.
-- Menghapusnya dari data salah — seratnya masih tergantung di tiang, masih bisa
-- tersangkut bucket truk, dan masih akan dilihat teknisi yang datang ke situ
-- tiga bulan lagi. Membiarkannya ACTIVE juga salah — ia ikut terhitung sebagai
-- kabel siap pakai, dan orang merencanakan pelanggan baru di atas kabel yang
-- ujungnya sudah digulung di halaman rumah orang.
--
-- Kenapa tidak dipaksa jadi INACTIVE: INACTIVE berarti "sengaja dimatikan,
-- sewaktu-waktu dinyalakan lagi" — mis. kabinet yang sedang dinonaktifkan saat
-- perbaikan. Menumpuk dua arti dalam satu kata membuat laporan aset tak bisa
-- lagi menjawab "berapa banyak barang saya yang sebetulnya mati".
--
-- Dilonggarkan untuk SEMUA aset fisik, bukan cuma kabel: kotak ODP yang
-- ditinggal di tiang setelah satu klaster dibongkar adalah barang yang sama
-- persoalannya. Nilai lama tak tersentuh; tak ada baris yang berubah.
-- ============================================================

ALTER TABLE olt DROP CONSTRAINT ck_olt_status;
ALTER TABLE olt ADD CONSTRAINT ck_olt_status
    CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE', 'PLANNED', 'ABANDONED'));

ALTER TABLE pon_port DROP CONSTRAINT ck_pon_port_status;
ALTER TABLE pon_port ADD CONSTRAINT ck_pon_port_status
    CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE', 'PLANNED', 'ABANDONED'));

ALTER TABLE odc DROP CONSTRAINT ck_odc_status;
ALTER TABLE odc ADD CONSTRAINT ck_odc_status
    CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE', 'PLANNED', 'ABANDONED'));

ALTER TABLE odp DROP CONSTRAINT ck_odp_status;
ALTER TABLE odp ADD CONSTRAINT ck_odp_status
    CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE', 'PLANNED', 'ABANDONED'));

ALTER TABLE cable DROP CONSTRAINT ck_cable_status;
ALTER TABLE cable ADD CONSTRAINT ck_cable_status
    CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE', 'PLANNED', 'ABANDONED'));

ALTER TABLE joint_box DROP CONSTRAINT ck_joint_box_status;
ALTER TABLE joint_box ADD CONSTRAINT ck_joint_box_status
    CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE', 'PLANNED', 'ABANDONED'));

ALTER TABLE odf DROP CONSTRAINT ck_odf_status;
ALTER TABLE odf ADD CONSTRAINT ck_odf_status
    CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE', 'PLANNED', 'ABANDONED'));
