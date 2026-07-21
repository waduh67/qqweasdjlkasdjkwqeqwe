-- ============================================================
-- Phase 2a: monitoring — collector agent, metrik ONU, alarm
--
-- Alur data:
--   collector (di jaringan ISP) --HTTPS outbound--> /api/collector/metrics
--     -> onu_metric (hypertable TimescaleDB)
--     -> mesin alarm -> alarm
-- ============================================================

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
            RAISE EXCEPTION 'Extension timescaledb belum terpasang. Sebagai superuser: ALTER SYSTEM SET shared_preload_libraries = ''timescaledb''; restart; CREATE EXTENSION timescaledb;';
        END IF;
    END
$$;

-- ------------------------------------------------------------
-- Collector agent
--
-- TANPA RLS, sama alasannya dengan refresh_token: barisnya dicari lewat hash
-- API key SEBELUM tenant diketahui, jadi kebijakan RLS justru akan memblokir
-- proses autentikasinya sendiri. Tenant di-set dari baris ini setelah cocok.
-- ------------------------------------------------------------
CREATE TABLE collector (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenant (id),
    name                 varchar(150) NOT NULL,
    -- Hanya hash SHA-256; kunci mentah ditampilkan sekali saat dibuat lalu hilang.
    api_key_hash         varchar(64)  NOT NULL UNIQUE,
    -- Empat karakter awal kunci, agar operator bisa mencocokkan collector mana
    -- ini tanpa pernah menyimpan kuncinya.
    api_key_hint         varchar(12)  NOT NULL,
    status               varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    poll_interval_seconds integer     NOT NULL DEFAULT 300,
    agent_version        varchar(40),
    last_seen_at         timestamptz,
    last_cycle_summary   text,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name),
    CONSTRAINT ck_collector_status CHECK (status IN ('ACTIVE', 'PAUSED', 'DISABLED')),
    CONSTRAINT ck_collector_interval CHECK (poll_interval_seconds BETWEEN 30 AND 86400)
);
CREATE INDEX ix_collector_tenant ON collector (tenant_id);

-- Collector melayani sekumpulan OLT. Tanpa penugasan, satu ISP dengan beberapa
-- POP terpisah harus menjalankan satu collector untuk semua — padahal jaringan
-- antar-POP belum tentu saling terjangkau.
CREATE TABLE collector_olt (
    collector_id uuid NOT NULL REFERENCES collector (id) ON DELETE CASCADE,
    olt_id       uuid NOT NULL REFERENCES olt (id) ON DELETE CASCADE,
    PRIMARY KEY (collector_id, olt_id)
);

-- ------------------------------------------------------------
-- Deduplikasi batch
--
-- Koneksi ISP kerap putus-nyambung: collector mengirim ulang batch yang sama
-- bila jawabannya tak sampai. Tanpa tabel ini, metrik terhitung ganda dan
-- rata-rata redaman ikut melenceng.
-- ------------------------------------------------------------
CREATE TABLE ingest_batch (
    batch_id     varchar(64) PRIMARY KEY,
    collector_id uuid        NOT NULL REFERENCES collector (id) ON DELETE CASCADE,
    tenant_id    uuid        NOT NULL REFERENCES tenant (id),
    reading_count integer    NOT NULL,
    received_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_ingest_batch_received ON ingest_batch (received_at);

-- ------------------------------------------------------------
-- Metrik ONU — hypertable TimescaleDB
--
-- Bukan agregat domain melainkan deret waktu append-only, jadi sengaja TIDAK
-- dipetakan sebagai JPA entity: tidak ada id surrogate, tidak ada created_at/
-- updated_at, dan penulisannya lewat JDBC batch.
-- ------------------------------------------------------------
CREATE TABLE onu_metric (
    time           timestamptz      NOT NULL,
    tenant_id      uuid             NOT NULL,
    onu_id         uuid             NOT NULL,
    olt_id         uuid,
    status         varchar(20)      NOT NULL,
    rx_power_dbm   double precision,
    tx_power_dbm   double precision,
    uptime_seconds bigint,
    distance_meters integer
);

-- Chunk harian: cukup besar agar jumlah chunk terkendali, cukup kecil agar
-- kebijakan retensi bisa membuang data lama dengan granular.
SELECT create_hypertable('onu_metric', by_range('time', INTERVAL '1 day'));

CREATE INDEX ix_onu_metric_onu_time ON onu_metric (onu_id, time DESC);
CREATE INDEX ix_onu_metric_tenant_time ON onu_metric (tenant_id, time DESC);

-- Kebijakan retensi dipasang setelah blok RLS di bawah.

-- ------------------------------------------------------------
-- Alarm
-- ------------------------------------------------------------
CREATE TABLE alarm_rule (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid        NOT NULL REFERENCES tenant (id),
    kind                varchar(40) NOT NULL,
    enabled             boolean     NOT NULL DEFAULT true,
    -- Ambang dipakai sesuai jenis alarm: dBm untuk redaman, detik untuk durasi.
    warning_threshold   double precision,
    critical_threshold  double precision,
    -- Berapa lama kondisi harus bertahan sebelum alarm diangkat. Meredam
    -- flapping: ONU yang restart sesaat tidak perlu membangunkan siapa pun.
    sustain_seconds     integer     NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, kind)
);

CREATE TABLE alarm (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    kind            varchar(40) NOT NULL,
    severity        varchar(20) NOT NULL,
    entity_type     varchar(20) NOT NULL,
    entity_id       uuid        NOT NULL,
    -- Label yang bisa dibaca manusia, disalin saat alarm diangkat agar tetap
    -- terbaca meski entitasnya kemudian dihapus.
    entity_label    varchar(150) NOT NULL,
    status          varchar(20) NOT NULL DEFAULT 'ACTIVE',
    message         varchar(500) NOT NULL,
    measured_value  double precision,
    raised_at       timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz NOT NULL DEFAULT now(),
    cleared_at      timestamptz,
    acknowledged_at timestamptz,
    acknowledged_by uuid,
    -- Berapa kali kondisi yang sama terulang selama alarm masih terbuka.
    occurrence_count integer    NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_alarm_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_alarm_status CHECK (status IN ('ACTIVE', 'ACKNOWLEDGED', 'CLEARED')),
    CONSTRAINT ck_alarm_entity_type CHECK (entity_type IN ('ONU', 'OLT', 'ODP', 'ODC', 'COLLECTOR'))
);

-- Aturan inti peredam banjir alarm: satu entitas hanya boleh punya satu alarm
-- terbuka per jenis. Siklus polling berikutnya memperbarui baris yang sama,
-- bukan menambah baris baru — tanpa ini, ONU yang mati semalaman menghasilkan
-- ratusan alarm identik.
CREATE UNIQUE INDEX uq_alarm_open ON alarm (tenant_id, kind, entity_id)
    WHERE status <> 'CLEARED';
CREATE INDEX ix_alarm_tenant_status ON alarm (tenant_id, status, raised_at DESC);

-- ------------------------------------------------------------
-- Row-Level Security
--
-- Catatan penting: RLS pada hypertable membuat CONTINUOUS AGGREGATE mustahil —
-- TimescaleDB menolaknya terang-terangan ("cannot create continuous aggregate on
-- hypertable with row security") karena background worker yang me-refresh tidak
-- punya GUC tenant. Isolasi tenant dipilih daripada rollup otomatis; rollup
-- jangka panjang nanti dikerjakan aplikasi per tenant. Kompresi columnstore dan
-- kebijakan retensi tetap berjalan normal di bawah RLS.
-- ------------------------------------------------------------
DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['onu_metric', 'alarm', 'alarm_rule']
            LOOP
                EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format($f$
                    CREATE POLICY tenant_isolation ON %I
                        USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
                        WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
                    $f$, t);
            END LOOP;
    END
$$;

-- ------------------------------------------------------------
-- Retensi data mentah.
--
-- Kompresi kolumnar SENGAJA TIDAK dipakai. TimescaleDB melarangnya berdampingan
-- dengan Row-Level Security, dan larangannya dua arah — sudah diuji:
--
--   RLS dulu lalu columnstore : "columnstore cannot be used on table with row security"
--   columnstore dulu lalu RLS : "operation not supported on hypertables that have
--                                columnstore enabled"
--
-- Jadi pilihannya isolasi tenant ATAU kompresi, tidak bisa dua-duanya. Yang
-- dipilih isolasi tenant: itu properti keamanan inti sistem ini, sementara
-- kompresi hanya soal biaya penyimpanan yang sudah dibatasi retensi 90 hari.
--
-- Bila suatu saat disk menjadi kendala nyata, jalan keluarnya adalah mematikan
-- RLS KHUSUS tabel ini dan bersandar pada filter @TenantId Hibernate — keputusan
-- yang harus diambil sadar, bukan tergelincir.
--
-- Yang tetap didapat dari TimescaleDB: partisi chunk otomatis, chunk exclusion
-- saat query rentang waktu, dan kebijakan retensi di bawah ini.
-- ------------------------------------------------------------
SELECT add_retention_policy('onu_metric', INTERVAL '90 days');
