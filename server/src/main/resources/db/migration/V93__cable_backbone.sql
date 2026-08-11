-- ============================================================
-- BACKBONE jadi jenis kabel tersendiri.
--
-- Selama POP-nya cuma satu, "backbone" dan "feeder" memang menunjuk kabel yang
-- sama, dan banyak ISP lokal menyebut keduanya bergantian. Bedanya baru muncul
-- begitu POP kedua berdiri:
--
--     POP-A/ODF ══════ 96c ══════ POP-B/ODF      ← ruas ini bukan feeder:
--                                                  ujungnya bukan kabinet
--     ODC-1 ═════════ 48c ═════════ ODC-2        ← ring antar-kabinet, supaya
--                                                  satu putus tak memadamkan
--                                                  seluruh cabang
--
-- Aturan feeder mewajibkan ujung ODC, jadi sampai kini kabel semacam itu cuma
-- bisa disimpan dengan menggambar ODC bohongan di POP tujuan — dan ODC bohongan
-- itulah yang kemudian merusak hitungan kapasitas kabinet, laporan panjang kabel
-- per jenis, dan penelusuran jalur. Jenis tersendiri menghapus dorongannya.
--
-- Tak ada data yang perlu dipindahkan: kabel yang sudah terlanjur dicatat FEEDER
-- tetap sah dan tetap terbaca. Yang berubah cuma: mulai sekarang BACKBONE boleh
-- dipilih.
-- ============================================================

ALTER TABLE cable
    DROP CONSTRAINT ck_cable_type;
ALTER TABLE cable
    ADD CONSTRAINT ck_cable_type
        CHECK (cable_type IN ('BACKBONE', 'FEEDER', 'DISTRIBUTION', 'DROP'));
