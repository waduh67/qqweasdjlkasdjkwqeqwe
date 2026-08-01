-- ============================================================
-- Work order: tim teknisi (banyak teknisi per WO, model "tim datar").
--
-- Sampai kini satu WO cuma bisa ditugaskan ke SATU teknisi (kolom
-- work_order.assigned_to). Di lapangan pemasangan/perbaikan lazim dikerjakan
-- lebih dari satu orang, jadi penugasan dipindah ke tabel penghubung
-- work_order_assignee (satu baris = satu teknisi pada satu WO). Semua anggota
-- setara — tak ada konsep lead; siapa pun anggota boleh mulai/menyelesaikan,
-- dan WO muncul di "WO saya" tiap anggota.
--
-- Surrogate id + UNIQUE(work_order_id, technician_id) mengikuti konvensi tabel
-- lain (TenantAwareJpaEntity ber-id UUID tunggal + auto-isi tenant_id). Kolom
-- scalar work_order.assigned_at DIPERTAHANKAN (kapan roster terakhir disetel);
-- hanya assigned_to yang dibuang setelah di-backfill.
-- ============================================================

CREATE TABLE work_order_assignee (
    id            uuid PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    work_order_id uuid        NOT NULL REFERENCES work_order (id) ON DELETE CASCADE,
    -- Teknisi ter-assign (id pengguna iam), tanpa FK lintas-module (pola sama assigned_to lama).
    technician_id uuid        NOT NULL,
    assigned_at   timestamptz NOT NULL DEFAULT now(),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_work_order_assignee UNIQUE (work_order_id, technician_id)
);
-- "Work order teknisi ini" untuk papan tugas per orang (mengganti ix_work_order_assigned).
CREATE INDEX ix_wo_assignee_technician ON work_order_assignee (tenant_id, technician_id);
-- Muat roster satu/serombongan WO.
CREATE INDEX ix_wo_assignee_work_order ON work_order_assignee (work_order_id);

-- Backfill roster dari kolom tunggal lama, DILAKUKAN SEBELUM RLS work_order_assignee
-- dinyalakan. Flyway jalan sebagai role NOBYPASSRLS → work_order yang ter-RLS memfilter
-- semua baris (GUC app.tenant_id tak di-set saat migrasi), jadi RLS-nya dimatikan
-- sementara untuk membaca lintas-tenant (FORCE tetap bertahan melewati ENABLE). Tabel
-- work_order_assignee baru dibuat & BELUM ber-policy, sehingga INSERT lolos tanpa perlu
-- GUC tenant. RLS-nya dipasang setelahnya. Pola sama V29/V39/V44.
ALTER TABLE work_order DISABLE ROW LEVEL SECURITY;
INSERT INTO work_order_assignee (id, tenant_id, work_order_id, technician_id, assigned_at)
SELECT gen_random_uuid(), tenant_id, id, assigned_to, COALESCE(assigned_at, created_at)
FROM work_order
WHERE assigned_to IS NOT NULL;
ALTER TABLE work_order ENABLE ROW LEVEL SECURITY;

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain) — dipasang PASCA-backfill.
-- ------------------------------------------------------------
DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['work_order_assignee']
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

-- Buang kolom tunggal lama + indeksnya (penugasan kini di work_order_assignee).
DROP INDEX IF EXISTS ix_work_order_assigned;
ALTER TABLE work_order DROP COLUMN assigned_to;
