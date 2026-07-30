-- ============================================================
-- Rute kontrol sesi (DAE CoA/Disconnect) per-NAS
--
-- RADIUS-as-a-service: auth & accounting selalu ditembak Mikrotik KELUAR ke server,
-- jadi tak pernah butuh jalur balik. Hanya kontrol sesi hidup (memutus/menurunkan
-- kecepatan lewat DAE RFC 5176) yang perlu tahu bagaimana server menjangkau NAS:
--   - DIRECT    NAS ber-IP-publik      → server tembak :3799 langsung
--   - VPN       NAS di-NAT + overlay   → server tembak lewat IP overlay (menyusul, S2c)
--   - COLLECTOR NAS di-NAT tanpa VPN   → titipkan ke agent on-prem yang sekamar NAS
--   - NONE      tak terjangkau         → degradasi anggun (berlaku saat login ulang)
--
-- Default COLLECTOR menjaga perilaku warisan: NAS yang sudah ada tetap dilayani agent
-- on-prem seperti sebelum jalur server-side ada. Rute ditetapkan saat pembuatan &
-- dijaga lintas update (bukan field form biasa) — S3 menyambungkannya ke self-service.
-- ============================================================

ALTER TABLE nas
    ADD COLUMN reachability varchar(20) NOT NULL DEFAULT 'COLLECTOR';

ALTER TABLE nas
    ADD CONSTRAINT ck_nas_reachability CHECK (reachability IN ('DIRECT', 'VPN', 'COLLECTOR', 'NONE'));
