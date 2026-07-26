-- ============================================================
-- Phase 7 (slice 7b): jalur baca BNG — sesi PPPoE & akunting trafik
--
-- Dua tabel, dua bentuk berbeda:
--   radius_session     keadaan sesi PPPoE TERKINI per akun (satu baris per akun,
--                      di-upsert tiap poll). Agregat kecil → JPA entity biasa.
--   accounting_record  deret waktu penghitung akunting (hypertable TimescaleDB),
--                      sumber tren trafik. Laju Mbps DIHITUNG saat query dari selisih
--                      penghitung kumulatif — tak disimpan. Seperti onu_metric: tanpa
--                      id surrogate, ditulis lewat JDBC batch.
--
-- Alur: collector --/api/collector/bng-sessions--> monitoring menerbitkan event
--       shared-kernel -> bng menyerap (AFTER_COMMIT): upsert radius_session +
--       append accounting_record.
-- ============================================================

-- ------------------------------------------------------------
-- Sesi PPPoE terkini per akun
--
-- FK ke subscriber_access (intra-module) menjaga integritas & membersihkan sesi saat
-- akun dihapus. nas_id sengaja TANPA FK: nilai observasi (BRAS yang melaporkan), tak
-- boleh memblokir penghapusan BRAS. subscription_id/customer_id didenormalisasi agar
-- panel tampil tanpa join lintas-module.
-- ------------------------------------------------------------
CREATE TABLE radius_session (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenant (id),
    subscriber_access_id uuid        NOT NULL REFERENCES subscriber_access (id) ON DELETE CASCADE,
    subscription_id      uuid        NOT NULL,
    customer_id          uuid        NOT NULL,
    username             varchar(64) NOT NULL,
    nas_id               uuid,
    nas_ip               varchar(45),
    framed_ip            varchar(45),
    session_id           varchar(128),
    calling_station_id   varchar(64),
    online               boolean     NOT NULL,
    uptime_seconds       bigint,
    started_at           timestamptz,
    last_seen_at         timestamptz NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    -- Satu sesi terkini per akun; dasar upsert-per-akun di adapter.
    CONSTRAINT uq_radius_session_access UNIQUE (tenant_id, subscriber_access_id)
);
-- Panel "siapa yang online" per pelanggan.
CREATE INDEX ix_radius_session_customer ON radius_session (tenant_id, customer_id);

-- ------------------------------------------------------------
-- Akunting deret waktu — hypertable TimescaleDB
--
-- in_octets = unggah pelanggan (masuk BRAS), out_octets = unduh (keluar BRAS).
-- Keduanya penghitung KUMULATIF; laju dihitung saat query lewat LAG/selisih-waktu.
-- ------------------------------------------------------------
CREATE TABLE accounting_record (
    time                 timestamptz NOT NULL,
    tenant_id            uuid        NOT NULL,
    subscriber_access_id uuid        NOT NULL,
    nas_id               uuid,
    in_octets            bigint,
    out_octets           bigint,
    uptime_seconds       bigint
);

-- Chunk harian, sama alasannya dengan onu_metric.
SELECT create_hypertable('accounting_record', by_range('time', INTERVAL '1 day'));

-- Index unik sekaligus dedup: batch yang terkirim ulang (time+akun sama) ditolak
-- diam-diam lewat ON CONFLICT DO NOTHING. Index unik hypertable WAJIB memuat kolom
-- partisi (time) — terpenuhi. Juga melayani query tren (memimpin tenant_id +
-- subscriber_access_id yang dipakai predikat RLS + filter akun).
CREATE UNIQUE INDEX uq_accounting_record_point
    ON accounting_record (tenant_id, subscriber_access_id, time);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis).
--
-- Sama seperti onu_metric: RLS pada hypertable menutup pintu continuous aggregate &
-- columnstore (background worker tak punya GUC tenant) — isolasi tenant dipilih di
-- atas keduanya. Retensi dipasang SETELAH blok ini.
-- ------------------------------------------------------------
ALTER TABLE radius_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE radius_session FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON radius_session
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE accounting_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounting_record FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting_record
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- Retensi data akunting mentah (cermin onu_metric).
SELECT add_retention_policy('accounting_record', INTERVAL '90 days');
