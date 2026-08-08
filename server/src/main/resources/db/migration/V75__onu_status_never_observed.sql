-- ------------------------------------------------------------
-- Perbaiki status ONU yang "OFFLINE" padahal belum pernah dipantau.
--
-- Dulu `Onu.attachTo()` menjadikan ONU PENDING → OFFLINE begitu dipasang ke port
-- ODP. Itu mengarang pengamatan: ONU yang serialnya tak pernah muncul di walk SNMP
-- OLT (mis. OLT belum dikonfigurasi SNMP, atau serial yang terdaftar bukan serial
-- yang dilaporkan OLT) selamanya tampil "Offline" yang meyakinkan, padahal kabarnya
-- sama sekali tak diketahui. Operator lalu melihat panel ODP bilang "Offline"
-- sementara sesi PPPoE pelanggan yang sama jelas online — dua lapis yang memang
-- berbeda, tapi yang satu berbohong. Kini status hanya lahir dari pengamatan nyata.
--
-- Baris lama dibereskan di sini: ONU OFFLINE yang tak punya satu pun titik metrik
-- dikembalikan ke PENDING ("belum terpantau"); yang benar-benar pernah terpantau
-- tak disentuh.
--
-- Flyway jalan sebagai role NOBYPASSRLS → `onu` (target) dan `onu_metric` (sumber
-- penyaring) sama-sama ter-FORCE RLS dan memfilter SEMUA baris karena GUC
-- app.tenant_id tak di-set saat migrasi. Keduanya dinonaktifkan sementara agar
-- pembersihan lintas-tenant terlihat; FORCE bertahan melewati ENABLE
-- (relforcerowsecurity terpisah dari relrowsecurity). Pola sama V29/V39/V44/V52.
--
-- Mode gagal sengaja dibuat aman: andai penyaring metrik tetap tak melihat apa pun
-- (onu_metric hypertable), NOT EXISTS jadi selalu benar dan reset-nya kelewat luas —
-- tapi itu sembuh sendiri, sebab polling berikutnya (~30 dtk) melihat PENDING ≠
-- status teramati lalu langsung menimpanya dengan yang sebenarnya. Yang tetap
-- PENDING hanyalah ONU yang memang tak pernah dipoll — dan itu justru maunya.
-- ------------------------------------------------------------

ALTER TABLE onu        DISABLE ROW LEVEL SECURITY;
ALTER TABLE onu_metric DISABLE ROW LEVEL SECURITY;

UPDATE onu
SET status = 'PENDING'
WHERE status = 'OFFLINE'
  AND NOT EXISTS (SELECT 1 FROM onu_metric m WHERE m.onu_id = onu.id);

ALTER TABLE onu_metric ENABLE ROW LEVEL SECURITY;
ALTER TABLE onu        ENABLE ROW LEVEL SECURITY;
