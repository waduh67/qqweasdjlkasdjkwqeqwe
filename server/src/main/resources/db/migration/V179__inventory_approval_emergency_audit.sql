-- Jejak override darurat persetujuan gudang.
--
-- `emergency` memotong seluruh rantai tier: satu orang dengan izin
-- `inventory.approval.emergency` bisa membuat mutasi berisiko berlaku tanpa empat mata.
-- Itu kadang memang perlu (gangguan massal jam 2 pagi, kepala gudang tidak bisa dihubungi),
-- tapi ia WAJIB meninggalkan jejak yang tidak bisa hilang — kalau tidak, override darurat
-- jadi pintu belakang permanen yang tidak pernah muncul di laporan mana pun.
--
-- Kenapa tabel sendiri dan bukan hanya `AuditRecorder`: `AuditRecorder` menerbitkan event
-- yang ditulis `AuditEventListener` pada fase AFTER_COMMIT dan kegagalannya SENGAJA ditelan
-- supaya tidak menggagalkan transaksi bisnis. Untuk audit biasa itu benar; untuk override
-- darurat itu berarti override bisa berhasil TANPA satu baris jejak pun dan tidak ada yang
-- akan pernah tahu. Baris di sini ditulis dalam transaksi yang SAMA dengan permintaannya:
-- kalau auditnya gagal, permintaannya ikut gagal. `AuditRecorder` tetap dipanggil sebagai
-- pelengkap supaya override muncul juga di linimasa audit global.
CREATE TABLE inventory_approval_emergency_audit (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    approval_id uuid NOT NULL REFERENCES inventory_approval(id),
    approval_type varchar(32) NOT NULL,
    amount bigint NOT NULL CHECK (amount >= 0),
    requester_id uuid NOT NULL,
    custodian_id uuid,
    -- Alasan yang diketik manusia. NOT NULL dan tidak boleh kosong: override tanpa alasan
    -- sama tidak bergunanya dengan tidak ada catatan sama sekali saat ditelusuri kemudian.
    reason varchar(500) NOT NULL,
    -- Tier apa saja yang DILEWATI. Tanpa ini, pemeriksa harus merekonstruksi kebijakan yang
    -- berlaku saat itu untuk tahu seberapa besar kontrol yang dipotong.
    bypassed_tiers jsonb NOT NULL DEFAULT '[]'::jsonb,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT inventory_approval_emergency_audit_uq UNIQUE (tenant_id, approval_id),
    CONSTRAINT inventory_approval_emergency_audit_reason_ck CHECK (length(btrim(reason)) > 0),
    CONSTRAINT inventory_approval_emergency_audit_tiers_ck CHECK (jsonb_typeof(bypassed_tiers) = 'array')
);

-- Laporan "semua override darurat bulan ini" adalah satu-satunya jalur baca tabel ini.
CREATE INDEX inventory_approval_emergency_audit_tenant_idx
    ON inventory_approval_emergency_audit (tenant_id, occurred_at DESC, id);

ALTER TABLE inventory_approval_emergency_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_approval_emergency_audit FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_approval_emergency_audit_tenant_policy ON inventory_approval_emergency_audit
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- Jejak audit yang bisa diperbaiki bukan jejak audit. Trigger yang sama polanya dengan
-- `inventory_approval_decision_immutable` (V146): kalau baris ini bisa di-UPDATE atau
-- DELETE, orang yang memakai override darurat untuk menutupi kebocoran aset tinggal
-- menghapus catatannya sesudahnya.
CREATE OR REPLACE FUNCTION reject_inventory_emergency_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'inventory emergency approval audit rows are immutable';
END $$;

CREATE TRIGGER inventory_approval_emergency_audit_immutable
    BEFORE UPDATE OR DELETE ON inventory_approval_emergency_audit
    FOR EACH ROW EXECUTE FUNCTION reject_inventory_emergency_audit_mutation();
