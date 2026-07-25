-- ============================================================
-- Work order: antrean persetujuan hasil kerja (kurasi pasca-selesai)
--
-- Setelah WO diselesaikan, hasilnya masuk antrean persetujuan (PENDING). Penyelia
-- menyetujui (APPROVED) atau menolak (REJECTED). Penolakan membuka WO kembali ke
-- IN_PROGRESS untuk dikerjakan ulang; menyelesaikannya lagi mengembalikannya ke
-- antrean. approved_by/approved_at/approval_note mencatat pengambil keputusan.
-- WO yang sudah selesai sebelum fitur ini dianggap sudah disetujui (grandfather)
-- agar antrean hanya berisi penyelesaian baru.
-- ============================================================

ALTER TABLE work_order
    ADD COLUMN approval_status varchar(20),
    ADD COLUMN approved_by     uuid,
    ADD COLUMN approved_at     timestamptz,
    ADD COLUMN approval_note   varchar(500),
    ADD CONSTRAINT ck_work_order_approval_status
        CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED'));

-- Penyelesaian lama dianggap sudah disetujui (pengambil keputusan tak diketahui).
UPDATE work_order
SET approval_status = 'APPROVED',
    approved_at     = completed_at
WHERE status = 'DONE';

-- Timeline kini juga mencatat persetujuan & penolakan.
ALTER TABLE wo_event
    DROP CONSTRAINT ck_wo_event_type;
ALTER TABLE wo_event
    ADD CONSTRAINT ck_wo_event_type
        CHECK (type IN ('CREATED', 'UPDATED', 'ASSIGNED', 'STARTED', 'COMPLETED', 'CANCELLED', 'APPROVED', 'REJECTED'));

-- Antrean persetujuan: WO selesai yang masih menunggu, terlama dulu.
CREATE INDEX ix_work_order_approval_pending ON work_order (tenant_id, completed_at)
    WHERE approval_status = 'PENDING';
