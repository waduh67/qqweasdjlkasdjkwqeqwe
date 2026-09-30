ALTER TABLE onu ADD CONSTRAINT onu_verified_canonical_truth CHECK (
    warehouse_admission<>'VERIFIED' OR (canonical_serial IS NOT NULL
        AND canonical_serial IS NOT DISTINCT FROM warehouse_canonical_serial(serial_number))
) NOT VALID;
