-- ============================================================
-- Modul vpn — liveness peer (online + last_handshake_at).
--
-- Kolom `last_handshake_at` sudah ada sejak V26; kini diaktifkan sebagai jejak waktu koneksi
-- terakhir. `online` menandai peer sedang terhubung menurut laporan hub (callback OpenVPN
-- client-connect/disconnect). Keduanya CERMINAN koneksi nyata, bukan status administratif
-- (ENABLED/DISABLED). Default false: hub yang belum melapor dianggap belum diketahui online.
-- ============================================================

ALTER TABLE vpn_peer ADD COLUMN online boolean NOT NULL DEFAULT false;
