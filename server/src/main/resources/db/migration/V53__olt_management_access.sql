-- ------------------------------------------------------------
-- OLT: kanal manajemen tambahan (versi SNMP + akses Web UI/HTTP).
--
-- Kompetitor membedakan cara mengelola OLT per-vendor: ZTE via SNMP (community +
-- versi) dengan port Web opsional untuk membaca suhu & daya optik yang tak
-- terekspos SNMP; HSGQ langsung lewat HTTP Web UI API (port + username + password
-- Web). Kolom-kolom ini menampung KEDUA kanal secara aditif — OLT lama tetap
-- berjalan apa adanya: snmp_enabled baku true mempertahankan polling SNMP yang
-- sudah aktif, kanal Web mati (web_enabled false) sampai operator mengisinya.
-- ------------------------------------------------------------

ALTER TABLE olt ADD COLUMN description  text;
ALTER TABLE olt ADD COLUMN snmp_enabled boolean     NOT NULL DEFAULT true;
ALTER TABLE olt ADD COLUMN snmp_version varchar(10) NOT NULL DEFAULT 'V2C';
ALTER TABLE olt ADD COLUMN web_enabled  boolean     NOT NULL DEFAULT false;
ALTER TABLE olt ADD COLUMN web_protocol varchar(10) NOT NULL DEFAULT 'HTTP';
ALTER TABLE olt ADD COLUMN web_port     integer;
ALTER TABLE olt ADD COLUMN web_username varchar(100);
-- Password Web disimpan terenkripsi aplikasi (lihat SecretCipher), sama seperti
-- snmp_community — tak pernah dikembalikan lewat API.
ALTER TABLE olt ADD COLUMN web_password text;

ALTER TABLE olt ADD CONSTRAINT ck_olt_snmp_version CHECK (snmp_version IN ('V1', 'V2C', 'V3'));
ALTER TABLE olt ADD CONSTRAINT ck_olt_web_protocol CHECK (web_protocol IN ('HTTP', 'HTTPS'));
ALTER TABLE olt ADD CONSTRAINT ck_olt_web_port CHECK (web_port IS NULL OR web_port BETWEEN 1 AND 65535);
