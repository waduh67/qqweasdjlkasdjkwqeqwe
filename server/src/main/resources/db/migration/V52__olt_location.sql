-- ------------------------------------------------------------
-- OLT sebagai aset ber-koordinat sendiri di peta.
--
-- Sampai kini OLT hanya "menempel" di site-nya dan tak pernah muncul sebagai
-- titik di peta, sehingga saat OLT mati operator tak melihat perangkatnya —
-- padahal seluruh jalur di hilirnya ikut modar. OLT kini punya lokasi sendiri
-- (baku diwarisi dari site-nya) plus area_id untuk penyaringan tile berbasis
-- area, persis pola site/odc/odp.
-- ------------------------------------------------------------

ALTER TABLE olt ADD COLUMN location geometry(Point, 4326);
ALTER TABLE olt ADD COLUMN area_id  uuid REFERENCES area (id);

-- Backfill: OLT lama berdiri di lokasi site induknya (dan mewarisi areanya).
-- Flyway jalan sebagai role NOBYPASSRLS → baik `olt` (target) maupun `site` (sumber)
-- ter-FORCE RLS memfilter SEMUA baris karena GUC app.tenant_id tak di-set saat migrasi,
-- sehingga UPDATE tak menyentuh baris apa pun dan `location` tetap NULL. Nonaktifkan RLS
-- sementara pada kedua tabel agar backfill lintas-tenant terlihat; FORCE tetap bertahan
-- melewati ENABLE (relforcerowsecurity terpisah dari relrowsecurity). Pola sama V29/V39/V44.
ALTER TABLE olt  DISABLE ROW LEVEL SECURITY;
ALTER TABLE site DISABLE ROW LEVEL SECURITY;
UPDATE olt o
SET location = s.location,
    area_id  = s.area_id
FROM site s
WHERE o.site_id = s.id;
ALTER TABLE site ENABLE ROW LEVEL SECURITY;
ALTER TABLE olt  ENABLE ROW LEVEL SECURITY;

-- Setelah backfill, lokasi wajib — sama seperti site/odc/odp.
ALTER TABLE olt ALTER COLUMN location SET NOT NULL;

-- Indeks spasial: wajib agar query bbox peta tidak jadi seq-scan.
CREATE INDEX ix_olt_location ON olt USING GIST (location);
CREATE INDEX ix_olt_area ON olt (area_id);
