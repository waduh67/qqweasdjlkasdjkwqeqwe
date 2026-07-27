-- Data contoh lab BRAS/RADIUS.
--
-- Cukup untuk menguji JALUR BACA adapter FreeRADIUS tanpa perangkat apa pun: begitu
-- container naik, tabel radacct sudah berisi satu sesi "hidup" (acctstoptime NULL).
-- Daftarkan BRAS di app dengan URL JDBC ke basis data ini, lalu jalankan collector —
-- sesi budi@isp.net akan muncul sebagai online di halaman pelanggan.
--
-- Pelanggan uji juga punya kredensial (radcheck) + kecepatan awal (radreply) sehingga
-- bila BRAS sungguhan (mis. Mikrotik CHR) diarahkan ke FreeRADIUS ini, ia bisa login
-- betulan dan menghasilkan baris radacct baru menggantikan yang di-seed.

-- Kredensial PPPoE pelanggan uji.
INSERT INTO radcheck (username, attribute, op, value)
VALUES ('budi@isp.net', 'Cleartext-Password', ':=', 'rahasia123');

-- Kecepatan awal (VSA MikroTik) — unggah/unduh.
INSERT INTO radreply (username, attribute, op, value)
VALUES ('budi@isp.net', 'Mikrotik-Rate-Limit', ':=', '10M/50M');

-- BRAS lab: seluruh subnet privat diizinkan dengan satu secret (LAB SAJA).
INSERT INTO nas (nasname, shortname, type, secret, description)
VALUES ('0.0.0.0/0', 'lab-bras', 'other', 'testing123', 'BRAS lab (CHR/accel-ppp)');

-- Satu sesi hidup: mulai 2 jam lalu, ~0.9 GB unggah / ~4.5 GB unduh. acctstoptime
-- sengaja NULL = masih tersambung. acctuniqueid unik (dipakai FreeRADIUS untuk upsert).
INSERT INTO radacct (
    acctsessionid, acctuniqueid, username, nasipaddress, nasportid, nasporttype,
    acctstarttime, acctupdatetime, acctsessiontime, acctauthentic,
    acctinputoctets, acctoutputoctets, callingstationid, framedipaddress,
    servicetype, framedprotocol
) VALUES (
    '81440000', 'a1b2c3d4e5f600000000000000000001', 'budi@isp.net', '10.20.0.1', 'pppoe-budi', 'Virtual',
    now() - interval '2 hours', now(), 7200, 'RADIUS',
    900000000, 4500000000, 'AA:BB:CC:DD:EE:FF', '100.64.0.5',
    'Framed-User', 'PPP'
);
