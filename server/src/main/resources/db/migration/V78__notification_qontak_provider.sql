-- ============================================================
-- Mekari Qontak sebagai penyedia WhatsApp + katalog template jadi CERMIN penyedia
--
-- Dua perubahan yang saling terkait:
--
--  1. Penyedia baru QONTAK. Qontak adalah BSP (Business Solution Provider) resmi WhatsApp
--     yang banyak dipakai ISP di Indonesia. Kredensialnya sepasang: access token Open API
--     (rahasia → terenkripsi, sama seperti meta_access_token) dan channel_integration_id,
--     yaitu UUID kanal WhatsApp yang dipilih operator dari daftar yang ditarik aplikasi
--     lewat GET /v1/integrations?target_channel=wa. Bukan rahasia, jadi disimpan apa adanya.
--
--  2. Kolom template dilepas dari kosakata Meta. Sampai V76 katalog hanya bisa MEMBACA dari
--     Meta; mulai sekarang tambah/ubah/hapus di aplikasi benar-benar memanggil API penyedia,
--     jadi barisnya adalah CERMIN template di sana — bukan catatan lokal terpisah. Karena
--     penyedianya bisa dua, penamaan yang mengunci ke Meta jadi menyesatkan:
--
--       meta_template_id → remote_id   (Meta: id numerik; Qontak: UUID)
--       body_preview     → body_text   (bukan lagi sekadar pratinjau — inilah teks BODY yang
--                                       dikirim aplikasi saat MEMBUAT template di penyedia)
--       source 'META'    → 'REMOTE'
--
-- Kolom category/status sengaja TIDAK disentuh: respons Qontak yang sebenarnya memakai
-- kosakata Meta yang sama (UTILITY/MARKETING/AUTHENTICATION, APPROVED/REJECTED/...), bukan
-- daftar lawas ACCOUNT_UPDATE/PAYMENT_UPDATE yang masih tertulis di artikel dokumentasi mereka.
-- ============================================================

ALTER TABLE notification_settings
    -- ciphertext; token Open API Qontak panjangnya sekelas token Meta.
    ADD COLUMN qontak_access_token           varchar(2048),
    -- UUID kanal WhatsApp di Qontak; id publik, tak perlu dienkripsi.
    ADD COLUMN qontak_channel_integration_id varchar(64);

ALTER TABLE notification_settings
    DROP CONSTRAINT ck_notification_settings_provider;
ALTER TABLE notification_settings
    ADD CONSTRAINT ck_notification_settings_provider
        CHECK (provider IN ('LOG', 'HTTP_GENERIC', 'META_CLOUD', 'QONTAK'));

ALTER TABLE notification_message_template
    RENAME COLUMN meta_template_id TO remote_id;
ALTER TABLE notification_message_template
    RENAME COLUMN body_preview TO body_text;

UPDATE notification_message_template
SET source = 'REMOTE'
WHERE source = 'META';

ALTER TABLE notification_message_template
    DROP CONSTRAINT ck_notification_template_source;
ALTER TABLE notification_message_template
    ADD CONSTRAINT ck_notification_template_source
        CHECK (source IN ('MANUAL', 'REMOTE'));
