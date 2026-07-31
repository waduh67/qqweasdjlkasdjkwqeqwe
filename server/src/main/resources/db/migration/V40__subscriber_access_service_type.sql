-- ============================================================
-- Modul bng — penegakan multi-tipe layanan (PPPoE + Hotspot + DHCP + Static).
--
-- Sampai kini `subscriber_access` PPPoE-only: CHECK membatasi auth_type ke 'PPPOE' dan
-- tak ada tempat menyimpan reservasi IP. Slice ini membuka tipe non-PPPoE agar beneran
-- di-provision ke RADIUS pusat lewat model yang sama (grup rate-limit `plan:{id}` dipakai
-- ulang lintas-tipe):
--   1. subscriber_access.auth_type: perlebar CHECK ke 4 tipe enum aplikasi
--      { PPPOE, HOTSPOT, DHCP, STATIC }. Baris lama tetap 'PPPOE' — tak perlu backfill.
--   2. subscriber_access.framed_ip: reservasi Framed-IP-Address untuk DHCP/STATIC
--      (radreply); NULL untuk PPPoE/Hotspot & baris lama.
--   3. bng_action.auth_type: skema identitas ikut aksi antrean agar jalur-tulis server tahu
--      memetakan identitas (slug-prefix untuk login vs MAC apa adanya). DEFAULT 'PPPOE'
--      menormalkan baris antrean lama; kolom NOT NULL setelah terisi.
--
-- Tak ada UPDATE lintas-tenant (hanya ADD COLUMN + ganti CHECK) → RLS tak perlu dimatikan.
-- ============================================================

-- 1 & 2. subscriber_access: perlebar tipe + kolom reservasi IP.
ALTER TABLE subscriber_access DROP CONSTRAINT ck_subscriber_access_auth;
ALTER TABLE subscriber_access ADD CONSTRAINT ck_subscriber_access_auth
    CHECK (auth_type IN ('PPPOE', 'HOTSPOT', 'DHCP', 'STATIC'));

ALTER TABLE subscriber_access ADD COLUMN framed_ip varchar(45);

-- 3. bng_action: skema identitas per aksi (antrean/audit jalur-tulis RADIUS).
ALTER TABLE bng_action ADD COLUMN auth_type varchar(20) NOT NULL DEFAULT 'PPPOE';
ALTER TABLE bng_action ADD CONSTRAINT ck_bng_action_auth
    CHECK (auth_type IN ('PPPOE', 'HOTSPOT', 'DHCP', 'STATIC'));
