-- ============================================================
-- Port SNMP per-OLT + vendor HSGQ (EPON)
--
-- Dua penambahan yang saling menopang untuk mendukung OLT EPON murah (HSGQ-E04I):
--
-- 1. snmp_port — sebagian OLT diekspos lewat NAT/DMZ dan mendengarkan di port
--    non-standar (mis. 1161), bukan 161. Sebelumnya collector selalu berasumsi 161
--    sehingga perangkat semacam itu tak pernah terjawab. Baku 161 → baris lama tetap
--    benar tanpa backfill khusus.
--
-- 2. Vendor 'HSGQ' pada CHECK — chipset EPON yang identitas ONU-nya MAC (bukan serial
--    GPON) dan dibaca collector lewat HsgqEponSnmpAdapter. CHECK lama harus di-drop lalu
--    dibuat ulang karena Postgres tak punya "ALTER CONSTRAINT ... ADD VALUE".
--
-- DDL murni (tak menyentuh baris) → tak terpengaruh RLS.
-- ============================================================

ALTER TABLE olt ADD COLUMN snmp_port integer NOT NULL DEFAULT 161;

ALTER TABLE olt DROP CONSTRAINT ck_olt_vendor;
ALTER TABLE olt ADD CONSTRAINT ck_olt_vendor
    CHECK (vendor IN ('ZTE', 'HUAWEI', 'FIBERHOME', 'NOKIA', 'HSGQ', 'OTHER'));
