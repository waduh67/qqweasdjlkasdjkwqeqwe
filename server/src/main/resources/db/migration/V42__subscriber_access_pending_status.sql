-- ============================================================
-- Modul bng — status akun PENDING (onboarding "Ops mode").
--
-- Sampai kini akun jaringan dibuat langsung ACTIVE/ISOLATED dan ditulis ke RADIUS seketika,
-- jadi pelanggan bisa online sebelum instalasinya selesai. Slice ini membuka status PENDING:
-- akun boleh dibuat saat langganan masih menunggu instalasi (WO PSB belum selesai) — barisnya
-- ada di DB tapi BELUM ditulis ke RADIUS. Pelanggan baru resmi online saat WO PSB selesai →
-- langganan aktif → akun PENDING dialihkan ke ACTIVE lalu diprovisikan ke RADIUS
-- (SubscriberAccessLifecycle.onActivated).
--
-- Perlebar CHECK status agar menerima 'PENDING'. Baris lama (ACTIVE/ISOLATED/TERMINATED) tetap
-- valid — tak perlu backfill. Hanya ganti CHECK → tak ada UPDATE lintas-tenant, RLS tak perlu
-- dimatikan.
-- ============================================================

ALTER TABLE subscriber_access DROP CONSTRAINT ck_subscriber_access_status;
ALTER TABLE subscriber_access ADD CONSTRAINT ck_subscriber_access_status
    CHECK (status IN ('PENDING', 'ACTIVE', 'ISOLATED', 'TERMINATED'));
