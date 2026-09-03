ALTER TABLE notification_settings
    DROP CONSTRAINT ck_notification_settings_provider;

ALTER TABLE notification_settings
    ADD CONSTRAINT ck_notification_settings_provider
        CHECK (provider IN ('LOG', 'HTTP_GENERIC', 'FONNTE', 'META_CLOUD', 'QONTAK'));
