-- ============================================================
-- helpdesk — penanggung jawab + janji waktu (SLA)
--
-- Sebelum ini tiket punya status tapi tak punya PEMILIK dan tak punya TENGGAT. Dua-duanya
-- membuat antrean berbohong: tiket di daftar bersama adalah milik semua orang sekaligus tak
-- seorang pun, dan "sedang ditangani" bisa berarti sepuluh menit atau tiga minggu tanpa ada
-- yang bisa membedakan dari layar.
--
-- Dua jam yang dihitung terpisah, karena yang dirasakan pelanggan juga dua hal berbeda:
--   response_due_at   — tenggat DIBALAS, hidup hanya selama bola di tangan operator. Dikosongkan
--                       saat operator membalas, dinyalakan lagi saat pelanggan membalas.
--   resolution_due_at — tenggat DINYATAKAN SELESAI, dihitung dari tiket dibuka.
-- Keduanya diturunkan dari prioritas (lihat `TicketSla` di domain, 24/7 tanpa jam kerja).
-- ============================================================

ALTER TABLE helpdesk_ticket
    ADD COLUMN priority          varchar(10),
    -- Id operator lintas-module (iam) disimpan polos tanpa FK, sesuai konvensi module lain;
    -- namanya disalin agar antrean terbaca tanpa resolusi id per baris.
    ADD COLUMN assignee_id       uuid,
    ADD COLUMN assignee_name     varchar(150),
    ADD COLUMN first_response_at timestamptz,
    ADD COLUMN response_due_at   timestamptz,
    ADD COLUMN resolution_due_at timestamptz,
    -- Kapan penjaga SLA terakhir meneriakkan tiket ini; rem anti-banjir peringatan.
    ADD COLUMN sla_alerted_at    timestamptz;

-- Backfill tiket lama dengan kebijakan NORMAL (4 jam balas / 24 jam selesai). Tenggat balasan
-- hanya diberikan pada tiket yang masih di antrean: status IN_PROGRESS ke atas berarti seorang
-- operator sudah menyentuhnya, jadi menagih "balasan pertama" ke belakang cuma akan melahirkan
-- pelanggaran SLA fiktif di hari pertama fitur ini menyala.
--
-- Flyway jalan sebagai role NOBYPASSRLS dan `helpdesk_ticket` ter-FORCE RLS, sedangkan GUC
-- app.tenant_id tak di-set saat migrasi — tanpa mematikan RLS sementara, UPDATE ini menyentuh
-- NOL baris sementara `SET NOT NULL` di bawahnya melihat semua baris (DDL tak tunduk RLS) dan
-- migrasi gagal justru di database yang sudah berisi tiket. Pola sama V29/V39/V44/V52/V75.
ALTER TABLE helpdesk_ticket DISABLE ROW LEVEL SECURITY;

UPDATE helpdesk_ticket
SET priority          = 'NORMAL',
    resolution_due_at = opened_at + interval '24 hours',
    response_due_at   = CASE WHEN status = 'OPEN' THEN opened_at + interval '4 hours' END;

ALTER TABLE helpdesk_ticket ENABLE ROW LEVEL SECURITY;

ALTER TABLE helpdesk_ticket
    ALTER COLUMN priority SET NOT NULL,
    ALTER COLUMN priority SET DEFAULT 'NORMAL',
    ALTER COLUMN resolution_due_at SET NOT NULL;

ALTER TABLE helpdesk_ticket
    ADD CONSTRAINT ck_ticket_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT'));

-- Antrean "punya saya": operator membuka helpdesk untuk melihat tiketnya sendiri lebih dulu.
CREATE INDEX ix_ticket_assignee ON helpdesk_ticket (tenant_id, assignee_id, last_activity_at DESC);

-- Penjaga SLA menyapu tiket yang lewat tenggat tiap beberapa menit; parsial agar indeksnya
-- hanya memuat tiket yang masih hidup — tiket tertutup tak pernah jadi bahan sapuan.
CREATE INDEX ix_ticket_sla_due ON helpdesk_ticket (tenant_id, resolution_due_at)
    WHERE status <> 'CLOSED';
