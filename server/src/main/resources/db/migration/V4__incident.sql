-- ============================================================
-- Phase 3, slice 1b: incident — korelasi alarm menjadi insiden
--
-- Mesin korelasi (module incident) menggabungkan banjir alarm sejenis di bawah
-- satu induk topologi menjadi SATU insiden ber-akar-masalah, lalu memelihara
-- lifecycle-nya: terbuka → diakui → selesai (otomatis saat akar pulih, atau
-- ditutup manual). Timeline tiap perubahan disimpan di incident_event.
-- ============================================================

CREATE TABLE incident (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid         NOT NULL REFERENCES tenant (id),
    -- Akar masalah: satu insiden per (tenant, tipe-akar, id-akar) yang masih terbuka.
    root_type               varchar(20)  NOT NULL,
    root_id                 uuid         NOT NULL,
    -- Label akar disalin saat insiden dibuka agar tetap terbaca meski entitasnya
    -- kemudian berubah/dihapus (sama alasannya dengan alarm.entity_label).
    root_label              varchar(150) NOT NULL,
    severity                varchar(20)  NOT NULL,
    status                  varchar(20)  NOT NULL DEFAULT 'OPEN',
    title                   varchar(300) NOT NULL,
    alarm_count             integer      NOT NULL DEFAULT 0,
    affected_customer_count integer      NOT NULL DEFAULT 0,
    opened_at               timestamptz  NOT NULL DEFAULT now(),
    last_seen_at            timestamptz  NOT NULL DEFAULT now(),
    acknowledged_at         timestamptz,
    acknowledged_by         uuid,
    resolved_at             timestamptz,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_incident_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_incident_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT ck_incident_root_type CHECK (root_type IN ('OLT', 'ODC', 'ODP', 'ONU', 'COLLECTOR'))
);

-- Satu akar hanya boleh punya satu insiden yang belum selesai. Siklus korelasi
-- berikutnya memperbarui baris yang sama, bukan menambah baris — tanpa ini,
-- satu ODC yang bermasalah semalaman menumpuk ratusan insiden identik.
CREATE UNIQUE INDEX uq_incident_open ON incident (tenant_id, root_type, root_id)
    WHERE status <> 'RESOLVED';
CREATE INDEX ix_incident_tenant_status ON incident (tenant_id, status, opened_at DESC);

-- Timeline insiden: dibuka, keparahan berubah, diakui, selesai.
CREATE TABLE incident_event (
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    incident_id uuid        NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    type        varchar(30) NOT NULL,
    message     varchar(500) NOT NULL,
    actor_id    uuid,
    at          timestamptz NOT NULL DEFAULT now(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_incident_event_type
        CHECK (type IN ('OPENED', 'SEVERITY_CHANGED', 'ACKNOWLEDGED', 'RESOLVED'))
);
CREATE INDEX ix_incident_event_incident ON incident_event (incident_id, at);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['incident', 'incident_event']
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
