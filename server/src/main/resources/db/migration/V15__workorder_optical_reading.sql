-- ============================================================
-- Work order: redaman optik sebelum/sesudah pengerjaan (bukti kualitas pasang)
--
-- Teknisi mengukur redaman (Rx, dBm) ONU sebelum dan sesudah pekerjaan. Selisih &
-- posisi terhadap ambang sehat jadi bukti kualitas instalasi/perbaikan. Nilai
-- selalu negatif untuk GPON; rentang wajar ditegakkan di domain (−40..0 dBm).
-- ============================================================

ALTER TABLE work_order
    ADD COLUMN rx_before_dbm double precision,
    ADD COLUMN rx_after_dbm  double precision;
