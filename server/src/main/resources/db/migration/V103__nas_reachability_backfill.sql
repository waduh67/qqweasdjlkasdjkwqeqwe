-- ============================================================
-- Rute kontrol sesi BRAS: perbaiki baris yang terlanjur tersimpan 'COLLECTOR'
--
-- Sejak V37 kolom reachability ada, tapi tak pernah ada jalan mengisinya: tak ada field di
-- form, tak ada di request simpan, tak ada satu pun penulisan di luar DEFAULT-nya. Jadi
-- SEMUA baris berbunyi 'COLLECTOR', termasuk BRAS yang tak punya collector sama sekali.
--
-- Akibatnya bisu dan pahit: RadiusSessionControlRunner hanya mengklaim BRAS ber-reachability
-- ≠ COLLECTOR, sementara jalur collector hanya melayani BRAS yang benar-benar punya agent
-- on-prem. BRAS tanpa collector jatuh di antara keduanya — isolir dan Reset Login mengantre
-- PENDING selamanya, tanpa satu pun layar yang keberatan, dan pelanggan yang mestinya
-- terputus tetap online.
--
-- Baris disimpulkan ulang dengan aturan yang sama persis dengan NasReachability.resolve:
-- punya collector → COLLECTOR; tanpa alamat → NONE; alamat di dalam blok tunnel VPN aktif
-- → VPN; alamat yang bisa dirute dari luar → DIRECT; alamat privat/CGNAT di balik NAT tetap
-- COLLECTOR (itu memang wilayah agent on-prem). Sesudah ini aplikasi yang menjaganya tetap
-- benar tiap simpan.
-- ============================================================

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
