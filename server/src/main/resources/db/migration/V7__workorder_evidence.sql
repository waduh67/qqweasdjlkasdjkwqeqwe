-- ============================================================
-- Phase 4, slice 4.2: bukti pengerjaan work order — foto & tanda tangan
--
-- Byte berkas TIDAK disimpan di sini; hanya metadata + kunci object storage
-- (MinIO/S3). Server memproksi konten lewat endpoint terautentikasi sehingga
-- gating auth/tenant tetap terpusat (bukan presigned URL). Satu work order boleh
-- punya banyak foto, tapi hanya satu tanda tangan (unik per work order).
-- ============================================================

-- Foto bukti lapangan: sebelum/sesudah, lokasi, serial ONU, dsb.
CREATE TABLE wo_evidence (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    work_order_id uuid         NOT NULL REFERENCES work_order (id) ON DELETE CASCADE,
    kind          varchar(20)  NOT NULL,
    caption       varchar(300),
    -- Kunci object storage tempat byte foto tersimpan.
    storage_key   varchar(300) NOT NULL,
    content_type  varchar(100) NOT NULL,
    size_bytes    bigint       NOT NULL,
    -- Geotag opsional dari kamera teknisi.
    latitude      double precision,
    longitude     double precision,
    captured_at   timestamptz,
    uploaded_by   uuid         NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_wo_evidence_kind
        CHECK (kind IN ('BEFORE', 'AFTER', 'LOCATION', 'SERIAL', 'OTHER'))
);
-- Galeri foto per work order, urut kronologis.
CREATE INDEX ix_wo_evidence_work_order ON wo_evidence (work_order_id, created_at);

-- Tanda tangan pelanggan sebagai bukti serah-terima; satu per work order.
CREATE TABLE wo_signature (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    work_order_id uuid         NOT NULL REFERENCES work_order (id) ON DELETE CASCADE,
    signer_name   varchar(200) NOT NULL,
    storage_key   varchar(300) NOT NULL,
    content_type  varchar(100) NOT NULL,
    size_bytes    bigint       NOT NULL,
    signed_by     uuid         NOT NULL,
    signed_at     timestamptz  NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);
-- Satu tanda tangan per work order (per tenant, defense-in-depth).
CREATE UNIQUE INDEX uq_wo_signature_work_order ON wo_signature (tenant_id, work_order_id);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['wo_evidence', 'wo_signature']
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
