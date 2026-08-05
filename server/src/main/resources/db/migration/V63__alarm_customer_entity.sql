-- Alarm kini bisa menyasar PELANGGAN, bukan cuma perangkat optik/collector.
-- Dipakai PPPOE_DOWN: sesi PPPoE putus di BRAS diwarnai gis ke marker pelanggan +
-- kabel drop lewat customerId. Ganti CHECK entity_type agar menerima 'CUSTOMER';
-- baris lama (ONU/OLT/ODP/ODC/COLLECTOR) tetap valid. Nama constraint dipertahankan.
ALTER TABLE alarm DROP CONSTRAINT ck_alarm_entity_type;
ALTER TABLE alarm ADD CONSTRAINT ck_alarm_entity_type
    CHECK (entity_type IN ('ONU', 'OLT', 'ODP', 'ODC', 'COLLECTOR', 'CUSTOMER'));
