-- ============================================================
-- Phase 7 (slice 7c): jalur tulis BNG — antrean + audit perintah BRAS
--
-- Satu tabel bng_action memikul DUA peran: antrean perintah (jalur turun ke
-- collector) sekaligus jejak audit. Identitas satu baris (siapa minta, perintah apa,
-- ke akun/BRAS mana, kapan) tak pernah berubah — hanya statusnya berpindah:
--   PENDING → DISPATCHED → COMPLETED / FAILED
--
-- Alur: operator/isolir menaruh baris PENDING → contributor menandainya DISPATCHED
--       saat menyerahkannya di respons denyut → collector eksekusi & ACK di denyut
--       berikutnya → listener menuntaskan jadi COMPLETED/FAILED. Perintah dikirim
--       ulang tiap denyut sampai di-ACK (at-least-once) → eksekusi harus idempoten.
-- ============================================================

CREATE TABLE bng_action (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenant (id),
    -- FK intra-module: perintah ikut terhapus saat akunnya dihapus.
    subscriber_access_id uuid         NOT NULL REFERENCES subscriber_access (id) ON DELETE CASCADE,
    -- BRAS penyasar. Sengaja TANPA FK: nilai rute (ke collector mana), tak boleh
    -- memblokir penghapusan BRAS — cermin nas_id di radius_session.
    nas_id               uuid         NOT NULL,
    username             varchar(64)  NOT NULL,
    action               varchar(20)  NOT NULL,
    -- Target kecepatan; hanya terisi untuk COA.
    down_mbps            integer,
    up_mbps              integer,
    status               varchar(20)  NOT NULL,
    -- Pesan hasil: kosong saat sukses, sebab-gagal saat FAILED.
    detail               varchar(500),
    -- Pelaku boleh null bila perintah dipicu sistem (mis. isolir dari event langganan),
    -- bukan operator. Email didenormalisasi agar riwayat terbaca meski pengguna dihapus.
    requested_by         uuid,
    requested_by_email   varchar(320),
    requested_at         timestamptz  NOT NULL DEFAULT now(),
    dispatched_at        timestamptz,
    completed_at         timestamptz,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_bng_action_action CHECK (action IN ('DISCONNECT', 'COA')),
    CONSTRAINT ck_bng_action_status CHECK (status IN ('PENDING', 'DISPATCHED', 'COMPLETED', 'FAILED'))
);
-- Klaim perintah belum-tuntas per BRAS untuk dispatch ke collector (predikat
-- nas_id + status, memimpin tenant_id yang dipakai RLS).
CREATE INDEX ix_bng_action_dispatch ON bng_action (tenant_id, nas_id, status);
-- Riwayat perintah per akun (audit).
CREATE INDEX ix_bng_action_access ON bng_action (tenant_id, subscriber_access_id);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE bng_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE bng_action FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bng_action
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
