-- Tambah jenis aksi CPE: reset pabrik & refresh koneksi ACS (TR-069 FactoryReset +
-- connection request). Enum disimpan sebagai string, jadi cukup melonggarkan CHECK
-- constraint-nya — tak ada perubahan kolom.
ALTER TABLE cpe_action_log DROP CONSTRAINT ck_cpe_action_type;
ALTER TABLE cpe_action_log
    ADD CONSTRAINT ck_cpe_action_type
        CHECK (action IN ('REBOOT', 'SET_WIFI', 'PING_TEST', 'SPEED_TEST',
                          'FIRMWARE_UPGRADE', 'FACTORY_RESET', 'REFRESH_ACS'));
