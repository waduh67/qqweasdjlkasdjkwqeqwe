-- ============================================================
-- billing — pengembalian dana (refund)
--
-- Sebelum ini uang cuma bisa bergerak SATU arah: tagihan terbit, pelanggan bayar, selesai. Kalau
-- pelanggan bayar dua kali, salah kamar, atau batal dipasang, satu-satunya jalan adalah menolak
-- lewat dashboard penyedia dan membiarkan catatan di sini berbohong — tagihannya tetap tampak
-- lunas padahal uangnya sudah dipulangkan.
--
-- Satu baris per PERCOBAAN pengembalian, bukan per hasil: perjalanannya (diminta → dikirim ke
-- penyedia → berhasil/gagal) itulah yang harus terlihat, karena refund lewat transfer bank bisa
-- menggantung berhari-hari dan operator perlu tahu bedanya "sudah balik" dengan "sedang jalan".
-- Ditutup callback REFUND.* Pivot (gateway_ref = data.id) atau dinyatakan tangan untuk MANUAL.
--
-- Nominal numeric(14,2) mengikuti invoice/payment (bukan minor-unit seperti tenant_payout):
-- angkanya dibandingkan langsung dengan nilai tagihan, dan pembulatan ke rupiah bulat cuma terjadi
-- di batas adapter penyedia.
-- ============================================================

CREATE TABLE refund (
    id             uuid PRIMARY KEY,
    tenant_id      uuid          NOT NULL REFERENCES tenant (id),
    -- FK intra-module diperbolehkan (menjaga integritas tagihan & pembayaran yang dirujuk).
    invoice_id     uuid          NOT NULL REFERENCES invoice (id),
    customer_id    uuid          NOT NULL,
    -- Pembayaran yang dikembalikan; boleh kosong untuk tagihan lama tanpa jejak pembayaran.
    payment_id     uuid REFERENCES payment (id),
    amount         numeric(14,2) NOT NULL,
    reason         varchar(30)   NOT NULL,
    -- Penyedia yang mengembalikan uangnya — DIBEKUKAN dari cara tagihan itu dibayar, bukan dibaca
    -- ulang dari setelan: tenant yang pindah gateway tak boleh mengubah jalur pulang uang lama.
    provider       varchar(40)   NOT NULL,
    note           varchar(200),
    status         varchar(20)   NOT NULL DEFAULT 'PENDING',
    -- Referensi refund di penyedia (data.id Pivot) — kunci rekonsiliasi callback.
    gateway_ref    varchar(200),
    failure_reason varchar(200),
    requested_at   timestamptz   NOT NULL DEFAULT now(),
    completed_at   timestamptz,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_refund_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_refund_reason CHECK (
        reason IN ('SUSPECT_FRAUDULENT', 'DUPLICATE', 'REQUESTED_BY_CUSTOMER', 'CANCELLATION', 'OTHERS')
    ),
    CONSTRAINT ck_refund_amount CHECK (amount > 0)
);

-- Riwayat refund satu tagihan (panel detail tagihan + penjaga kuota refund).
CREATE INDEX ix_refund_invoice ON refund (tenant_id, invoice_id, requested_at DESC);
-- Daftar refund tenant, terbaru dulu.
CREATE INDEX ix_refund_tenant_requested ON refund (tenant_id, requested_at DESC);
-- Rekonsiliasi callback mencari baris via ref penyedia dalam konteks tenant.
CREATE INDEX ix_refund_ref ON refund (gateway_ref);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain — lihat V50).
-- ------------------------------------------------------------
ALTER TABLE refund ENABLE ROW LEVEL SECURITY;
ALTER TABLE refund FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON refund
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------
-- Sisi tagihan: berapa yang sudah benar-benar kembali, dan status REFUNDED.
--
-- DEFAULT 0 mengisi baris lama tanpa UPDATE — jadi tak ada backfill yang perlu mematikan RLS
-- sementara (bandingkan V83). Kolomnya hanya menjumlah refund yang BERHASIL; permintaan yang
-- masih berjalan ditahan di tabel `refund`, supaya angka ini selalu terbaca apa adanya.
-- ------------------------------------------------------------
ALTER TABLE invoice
    ADD COLUMN refunded_amount numeric(14,2) NOT NULL DEFAULT 0;

-- REFUNDED = lunas lalu dikembalikan PENUH. Status tersendiri, bukan VOID: tagihan yang dibatalkan
-- tak pernah menghasilkan uang, yang direfund menghasilkan lalu memulangkannya — beda yang harus
-- terlihat di laporan pendapatan. Refund SEBAGIAN membiarkan status PAID.
ALTER TABLE invoice DROP CONSTRAINT ck_invoice_status;
ALTER TABLE invoice
    ADD CONSTRAINT ck_invoice_status CHECK (status IN ('ISSUED', 'PAID', 'OVERDUE', 'VOID', 'REFUNDED'));

ALTER TABLE invoice
    ADD CONSTRAINT ck_invoice_refunded CHECK (refunded_amount >= 0 AND refunded_amount <= amount);
