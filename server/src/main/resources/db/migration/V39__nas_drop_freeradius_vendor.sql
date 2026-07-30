-- ============================================================
-- Modul bng — buang nilai vendor 'FREERADIUS' dari registri BRAS (`nas`).
--
-- Alasan: `nas` adalah registri BRAS/BNG (router penutup sesi PPPoE) yang mendaftar
-- ke FreeRADIUS pusat sebagai RADIUS *client*. FreeRADIUS itu sendiri adalah SERVER-nya,
-- bukan client — jadi memilih vendor "FreeRADIUS" untuk sebuah BRAS tak punya makna
-- (tak ada adapter kontrol untuknya; hanya MIKROTIK yang membuka jalur live-control).
-- Enum aplikasi kini { MIKROTIK, CISCO, JUNIPER, OTHER }; CHECK harus mengekor.
--
-- Baris lama (bila sempat terlanjur dibuat) dinormalkan ke 'OTHER' — tetap ter-auth
-- sebagai RADIUS client biasa, hanya labelnya yang berubah. RLS dimatikan sementara
-- agar UPDATE lintas-tenant tak kefilter FORCE RLS (Flyway jalan sbg role NOBYPASSRLS);
-- FORCE tetap kebawa setelah ENABLE (relforcerowsecurity terpisah dari relrowsecurity).
-- ============================================================

ALTER TABLE nas DISABLE ROW LEVEL SECURITY;
UPDATE nas SET vendor = 'OTHER' WHERE vendor = 'FREERADIUS';
ALTER TABLE nas ENABLE ROW LEVEL SECURITY;

ALTER TABLE nas DROP CONSTRAINT ck_nas_vendor;
ALTER TABLE nas ADD CONSTRAINT ck_nas_vendor
    CHECK (vendor IN ('MIKROTIK', 'CISCO', 'JUNIPER', 'OTHER'));
