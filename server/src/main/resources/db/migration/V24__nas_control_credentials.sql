-- ============================================================
-- Phase 7 (slice 7d): kredensial kontrol BRAS untuk adapter nyata
--
-- Adapter sungguhan butuh cara login ke BRAS — coa_secret saja tak cukup:
--   RouterOS   dikendalikan lewat REST API v7  → butuh user/password + port + TLS.
--   FreeRADIUS sesinya dibaca dari SQL radacct  → butuh URL JDBC + user/password DB,
--              dan Disconnect/CoA-nya tetap lewat coa_secret (RFC 5176) yang sudah ada.
--
-- api_secret disimpan TERENKRIPSI (batas enkripsi di adapter persistence, sama seperti
-- coa_secret & community SNMP OLT); kolomnya dilonggarkan agar muat ciphertext.
-- Semua kolom nullable/berdefault → BRAS lama tetap sah tanpa kredensial ini.
-- ============================================================

ALTER TABLE nas
    ADD COLUMN api_username varchar(128),
    -- Ciphertext password REST/DB; kolom dilonggarkan agar muat hasil enkripsi.
    ADD COLUMN api_secret   varchar(512),
    ADD COLUMN api_port     integer,
    ADD COLUMN api_use_tls  boolean NOT NULL DEFAULT true,
    -- URL JDBC basis data RADIUS (FreeRADIUS); kosong untuk vendor lain.
    ADD COLUMN api_database varchar(500);

ALTER TABLE nas
    ADD CONSTRAINT ck_nas_api_port CHECK (api_port IS NULL OR api_port BETWEEN 1 AND 65535);
