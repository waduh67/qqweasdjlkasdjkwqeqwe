-- ============================================================
-- Phase 4, slice 4.1: workorder — tugas lapangan & lifecycle-nya
--
-- Work order (module workorder) adalah satu pekerjaan lapangan: pasang baru,
-- perbaikan, migrasi, bongkar, atau preventif. Punya lifecycle sendiri
-- (draft → ditugaskan → dikerjakan → selesai, atau batal dari mana pun sebelum
-- selesai) dan penugasan ke seorang teknisi. Bisa berdiri sendiri atau tertaut
-- ke insiden/pelanggan — id lintas-module disimpan polos tanpa FK (pola sama
-- dengan incident.acknowledged_by), dinamainya lewat kontrak module asal.
-- Tiap transisi dicatat ke timeline wo_event. Bukti foto & tanda tangan menyusul
-- di slice 4.2 (storage MinIO/S3).
-- ============================================================

CREATE TABLE work_order (
    id              uuid PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenant (id),
    -- Kode manusiawi, unik per tenant; diturunkan dari bagian acak id (UUIDv7).
    code            varchar(20)  NOT NULL,
    type            varchar(20)  NOT NULL,
    title           varchar(200) NOT NULL,
    description     varchar(2000),
    priority        varchar(10)  NOT NULL DEFAULT 'NORMAL',
    status          varchar(20)  NOT NULL DEFAULT 'DRAFT',
    -- Tautan lintas-module (opsional, tanpa FK): pelanggan, insiden asal, area scope.
    customer_id     uuid,
    incident_id     uuid,
    area_id         uuid,
    -- Teknisi ter-assign (id pengguna iam) + kapan ditugaskan.
    assigned_to     uuid,
    assigned_at     timestamptz,
    scheduled_at    timestamptz,
    started_at      timestamptz,
    completed_at    timestamptz,
    resolution_note varchar(2000),
    cancel_reason   varchar(500),
    created_by      uuid         NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_work_order_type
        CHECK (type IN ('PSB', 'REPAIR', 'MIGRATION', 'DISMANTLE', 'PREVENTIVE')),
    CONSTRAINT ck_work_order_priority
        CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_work_order_status
        CHECK (status IN ('DRAFT', 'ASSIGNED', 'IN_PROGRESS', 'DONE', 'CANCELLED'))
);

CREATE UNIQUE INDEX uq_work_order_code ON work_order (tenant_id, code);
-- Antrean dispatcher: daftar per status, terbaru dulu.
CREATE INDEX ix_work_order_tenant_status ON work_order (tenant_id, status, created_at DESC);
-- "Work order teknisi ini" untuk papan tugas per orang.
CREATE INDEX ix_work_order_assigned ON work_order (tenant_id, assigned_to) WHERE assigned_to IS NOT NULL;
-- Telusur balik dari pelanggan/insiden ke pekerjaannya.
CREATE INDEX ix_work_order_customer ON work_order (tenant_id, customer_id) WHERE customer_id IS NOT NULL;
CREATE INDEX ix_work_order_incident ON work_order (tenant_id, incident_id) WHERE incident_id IS NOT NULL;

-- Timeline work order: dibuat, diperbarui, ditugaskan, mulai, selesai, batal.
CREATE TABLE wo_event (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    work_order_id uuid         NOT NULL REFERENCES work_order (id) ON DELETE CASCADE,
    type          varchar(20)  NOT NULL,
    message       varchar(500) NOT NULL,
    actor_id      uuid,
    at            timestamptz  NOT NULL DEFAULT now(),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_wo_event_type
        CHECK (type IN ('CREATED', 'UPDATED', 'ASSIGNED', 'STARTED', 'COMPLETED', 'CANCELLED'))
);
CREATE INDEX ix_wo_event_work_order ON wo_event (work_order_id, at);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['work_order', 'wo_event']
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
