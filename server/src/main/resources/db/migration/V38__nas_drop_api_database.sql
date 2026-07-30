-- ============================================================
-- Buang kolom `api_database` (URL JDBC FreeRADIUS tenant) dari `nas`
--
-- Peninggalan model lama "tenant setup FreeRADIUS sendiri": collector membuka
-- koneksi JDBC ke basis data RADIUS per-BRAS lewat URL ini untuk provisioning &
-- baca radacct. Di model RADIUS-as-a-service, data-plane RADIUS dipegang server
-- pusat (radius-db platform co-located) — provisioning, baca radacct, dan DAE
-- semuanya server-side. Adapter FREERADIUS collector dihapus, jadi kolom ini tak
-- lagi punya konsumen dan hanya membocorkan plumbing JDBC ke form tenant.
--
-- Kredensial REST RouterOS (api_username/api_secret/api_port/api_use_tls) tetap —
-- masih dipakai adapter MIKROTIK-native di collector untuk kontrol sesi on-prem.
-- ============================================================

ALTER TABLE nas
    DROP COLUMN api_database;
