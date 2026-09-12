-- ============================================================
-- fieldservice — HASIL kunjungan yang gagal, bukan sekadar "CANCELLED"
--
-- `Visit.cancel(command, reason)` sudah ada di domain sejak awal dan menerima
-- alasan, tapi alasannya DIBUANG: `VisitPersistenceAdapter.save` tidak pernah
-- menyimpannya dan tidak ada satu pun endpoint yang memanggilnya. Akibatnya
-- kunjungan yang gagal karena rumah terkunci tampak persis sama dengan kunjungan
-- yang dibatalkan dispatcher karena salah jadwal — dan tak ada satu pun bit yang
-- bisa dipakai memutuskan "pesanan ini sekarang menunggu PELANGGAN".
--
-- `cancellation_cause` SENGAJA enum sempit, bukan teks bebas. Yang membedakan
-- "menunggu pelanggan" dari "urusan kami sendiri" adalah SEBABNYA, dan sebab itu
-- harus bisa dibandingkan mesin. Kalau ia teks bebas, pemicu otomatis harus
-- menebak-nebak kalimat teknisi ("gak ada orang", "kosong", "tutup") dan akan
-- salah memberi tahu pelanggan bahwa bola ada di tangannya.
--
-- `cancellation_reason` tetap ada dan tetap teks bebas: itu catatan INTERNAL
-- teknisi. Ia TIDAK PERNAH dikirim apa adanya ke halaman lacak pelanggan —
-- kalimat untuk pelanggan disusun sistem dari `cancellation_cause` (lihat
-- `OrderPortalNarrative`), supaya seragam dan tidak membocorkan urusan internal.
-- ============================================================

ALTER TABLE fieldservice_visit ADD COLUMN IF NOT EXISTS cancellation_cause  varchar(40);
ALTER TABLE fieldservice_visit ADD COLUMN IF NOT EXISTS cancellation_reason varchar(500);
ALTER TABLE fieldservice_visit ADD COLUMN IF NOT EXISTS cancelled_at        timestamptz;

-- Nilai di luar daftar ini berarti pemicu otomatis menerima sebab yang tak bisa
-- ia klasifikasikan, lalu DIAM-DIAM tidak memasang penanda apa pun: gejalanya
-- identik dengan "fiturnya tidak jalan" dan mustahil dilacak dari log.
ALTER TABLE fieldservice_visit
    ADD CONSTRAINT ck_fieldservice_visit_cancellation_cause
    CHECK (cancellation_cause IS NULL OR cancellation_cause IN (
        'CUSTOMER_NOT_PRESENT',
        'PREMISE_LOCKED',
        'CUSTOMER_REFUSED_INSTALL_POINT',
        'CUSTOMER_RESCHEDULED',
        'ADDRESS_NOT_FOUND',
        'TECHNICAL_BLOCKER',
        'INTERNAL_RESCHEDULE'
    ));

-- Sebab tanpa pembatalan = data yang tak pernah terbaca siapa pun tapi ikut
-- terbawa setiap ekspor. Pola sama dengan ck_order_record_portal_flag_reason.
ALTER TABLE fieldservice_visit
    ADD CONSTRAINT ck_fieldservice_visit_cancellation_pair
    CHECK ((cancellation_cause IS NULL) = (cancelled_at IS NULL));

-- Laporan "berapa kunjungan gagal karena pelanggan bulan ini" adalah pertanyaan
-- pertama yang ditanyakan supervisor begitu fitur ini hidup.
CREATE INDEX IF NOT EXISTS ix_fieldservice_visit_cancellation
    ON fieldservice_visit (tenant_id, cancellation_cause, cancelled_at DESC)
    WHERE cancellation_cause IS NOT NULL;
