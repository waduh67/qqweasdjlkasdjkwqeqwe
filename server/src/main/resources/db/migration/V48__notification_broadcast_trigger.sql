-- ============================================================
-- Riwayat broadcast: tandai ASAL tiap siaran (manual vs pemicu otomatis).
--
-- Setelah gateway WA bisa dipicu otomatis (langganan/tagihan/WO/insiden), riwayat
-- perlu membedakan "operator menekan kirim" dari "sistem mengirim otomatis" agar
-- operator paham kenapa sebuah pesan terkirim. Baris lama semuanya broadcast insiden
-- manual → default 'MANUAL'.
-- ============================================================

ALTER TABLE notification_broadcast
    ADD COLUMN trigger varchar(30) NOT NULL DEFAULT 'MANUAL';

ALTER TABLE notification_broadcast
    ADD CONSTRAINT ck_notification_broadcast_trigger CHECK (trigger IN (
        'MANUAL',
        'SUBSCRIPTION_ACTIVATED', 'SUBSCRIPTION_ISOLATED', 'SUBSCRIPTION_TERMINATED',
        'INVOICE_DUE_SOON', 'INVOICE_OVERDUE',
        'WORK_ORDER_SCHEDULED',
        'INCIDENT_OPENED'
    ));
