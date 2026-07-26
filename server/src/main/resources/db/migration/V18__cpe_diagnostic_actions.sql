-- Perluas jenis aksi CPE dengan diagnostik: ping (IPPingDiagnostics) & uji
-- kecepatan (TR-143). Enum disimpan sebagai string, jadi cukup melonggarkan
-- CHECK constraint-nya — tak ada perubahan kolom.
ALTER TABLE cpe_action_log DROP CONSTRAINT ck_cpe_action_type;
ALTER TABLE cpe_action_log
    ADD CONSTRAINT ck_cpe_action_type
        CHECK (action IN ('REBOOT', 'SET_WIFI', 'PING_TEST', 'SPEED_TEST'));
