-- ============================================================
-- Ulangi backfill rute kontrol BRAS (V103) — kali ini benar-benar mengenai barisnya.
--
-- V103 tercatat "success" di flyway_schema_history tapi TAK menyentuh satu baris pun.
-- Sebabnya `nas` memakai FORCE ROW LEVEL SECURITY dengan policy
-- `tenant_id = current_setting('app.tenant_id')`, dan koneksi Flyway tak pernah memasang
-- GUC itu (ia bukan request bertenant). NULL = NULL berbuah NULL, jadi policy mencocokkan
-- NOL baris dan UPDATE-nya sunyi — Postgres tak menganggapnya galat, Flyway pun tidak.
--
-- V39 sudah pernah menemui jebakan yang sama dan mengakalinya dengan mematikan RLS di
-- sekeliling UPDATE lintas-tenant; V103 kelewat menirunya. Baris ini menambalnya.
-- (FORCE terbawa setelah ENABLE — relforcerowsecurity kolom terpisah dari relrowsecurity,
-- jadi mematikan lalu menyalakan RLS tidak melonggarkan apa pun secara permanen.)
--
-- Kenapa ini mendesak: selama `reachability` seluruhnya berbunyi 'COLLECTOR', worker
-- server hanya mengklaim BRAS ber-reachability ≠ COLLECTOR sementara jalur collector
-- hanya melayani BRAS yang punya agent on-prem. BRAS tanpa collector jatuh di antara
-- keduanya — Isolir dan Reset Login mengantre PENDING selamanya tanpa satu layar pun
-- keberatan, dan pelanggan yang mestinya terputus tetap menikmati internet.
--
-- Aturan penyimpulan identik dengan V103 & NasReachability.resolve.
-- ============================================================

ALTER TABLE nas DISABLE ROW LEVEL SECURITY;

UPDATE nas SET reachability = CASE
    WHEN collector_id IS NOT NULL THEN 'COLLECTOR'
    WHEN address IS NULL OR btrim(address) = '' THEN 'NONE'
    -- inet(...) hanya dipanggil setelah bentuknya dipastikan dotted-quad; nama host tak
    -- pernah masuk ke sini (dan memang bukan penghuni tunnel).
    -- vpn_server adalah tabel PLATFORM sejak V29 (tanpa tenant_id): satu hub overlay
    -- melayani semua tenant, jadi blok tunnel tak disaring per-tenant di sini.
    WHEN btrim(address) ~ '^\d{1,3}(\.\d{1,3}){3}$' AND EXISTS (
        SELECT 1 FROM vpn_server v
        WHERE v.status = 'ACTIVE'
          AND v.tunnel_cidr ~ '^\d{1,3}(\.\d{1,3}){3}/\d{1,2}$'
          AND inet(btrim(nas.address)) <<= inet(v.tunnel_cidr)
    ) THEN 'VPN'
    -- Blok yang paket dari internet tak akan pernah capai: bukan tujuan DAE dari server,
    -- melainkan wilayah agent on-prem yang sekamar dengan BRAS.
    WHEN btrim(address) ~ '^(10\.|127\.|0\.|169\.254\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.|(22[4-9]|2[3-5]\d)\.)'
        THEN 'COLLECTOR'
    -- Sisanya: IP publik atau nama host yang memang dimaksudkan bisa diresolusi.
    ELSE 'DIRECT'
END;

ALTER TABLE nas ENABLE ROW LEVEL SECURITY;
