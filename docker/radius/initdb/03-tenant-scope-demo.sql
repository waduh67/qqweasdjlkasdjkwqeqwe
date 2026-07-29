-- Demo ISOLASI MULTI-TENANT (RADIUS-as-a-service) — dua tenant, USERNAME TELANJANG SAMA.
--
-- Modul SQL FreeRADIUS di-set `sql_user_name = "%{client:shortname}:%{User-Name}"`
-- (lihat docker/radius/freeradius/mods-enabled/sql). Artinya FreeRADIUS menurunkan
-- KODE TENANT dari shortname klien/BRAS yang cocok source-IP+secret, lalu mem-prefix
-- ke User-Name sebelum query. Jadi baris di bawah di-key "{kodeTenant}:{username}".
--
-- Buktinya: bare username "budi" ADA di dua tenant dengan password BEDA. Auth dari
-- BRAS tenant A menuju "tenantA:budi" (password budiA123), dari BRAS tenant B menuju
-- "tenantB:budi" (password budiB456) — nol tabrakan. Password silang otomatis ditolak.
-- Skenario ini diverifikasi lewat spike radclient (dua source-IP) di S0.
--
-- Subnet contoh (100.77.x = ruang bersama CGNAT, sengaja DI LUAR clients.conf statis
-- 10/8·172.16/12·192.168/16 agar klien SQL yang menang match, bukan client statis lab).

-- BRAS per-tenant. shortname = kode tenant; satu tenant boleh punya BANYAK baris nas
-- (banyak router) selama shortname-nya sama.
INSERT INTO nas (nasname, shortname, type, secret, description) VALUES
  ('100.77.1.0/24', 'tenantA', 'other', 'secretA', 'BRAS tenant A (demo multi-tenant)'),
  ('100.77.2.0/24', 'tenantB', 'other', 'secretB', 'BRAS tenant B (demo multi-tenant)');

-- Kredensial PPPoE: bare "budi" SAMA di dua tenant, password BEDA = bukti isolasi.
INSERT INTO radcheck (username, attribute, op, value) VALUES
  ('tenantA:budi', 'Cleartext-Password', ':=', 'budiA123'),
  ('tenantB:budi', 'Cleartext-Password', ':=', 'budiB456');

-- Keanggotaan grup plan. groupname = "plan:{UUID}" → UUID sudah unik lintas-tenant,
-- jadi TIDAK perlu prefix tenant. Dua pelanggan "budi" kebetulan di plan berbeda.
INSERT INTO radusergroup (username, groupname, priority) VALUES
  ('tenantA:budi', 'plan:11111111-1111-1111-1111-111111111111', 1),
  ('tenantB:budi', 'plan:22222222-2222-2222-2222-222222222222', 1);

-- Policy speed per-plan (VSA MikroTik up/down) ditaruh di grup, bukan per-user.
-- Ubah paket = ubah satu baris ini, nol sentuh router.
INSERT INTO radgroupreply (groupname, attribute, op, value) VALUES
  ('plan:11111111-1111-1111-1111-111111111111', 'Mikrotik-Rate-Limit', ':=', '5M/20M'),
  ('plan:22222222-2222-2222-2222-222222222222', 'Mikrotik-Rate-Limit', ':=', '10M/50M');
