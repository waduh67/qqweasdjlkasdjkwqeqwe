-- ============================================================
-- Satu pelanggan = satu langganan hidup.
--
-- Skemanya sejak V2 memang 1:N (customer → subscription), tapi separuh sistem lain tak
-- pernah ikut menjamak: `onu` menempel ke customer_id (BUKAN ke langganan), koordinat peta
-- satu titik per pelanggan, `findPlacementOf` memilih ONU terpasang PERTAMA, panel trace
-- memakai perangkat CPE pertama, dan badge "menunggu instalasi" menjawab pertanyaan
-- "pelanggan ini punya ONU terpasang?" — bukan "langganan ini sudah dipasang?".
--
-- Akibatnya pelanggan dengan dua langganan hidup bukan fitur setengah jadi, melainkan
-- sumber jawaban salah yang tak bergejala: langganan kedua tak punya ONU sendiri, tak
-- pernah muncul di trace, sesi PPPoE-nya dipilih lewat tebakan (yang online dulu, lalu
-- username pertama), dan bila operator memakai "tambah langganan" untuk upgrade paket,
-- langganan lama tetap hidup → pelanggan tertagih dua kali.
--
-- Keputusannya: TUTUP PINTUNYA, bukan menjamakkan seluruh sistem. Pelanggan yang memang
-- berlangganan dua layanan didaftarkan sebagai dua pelanggan — itu pula yang dilakukan
-- operator di lapangan, karena layanan kedua punya alamat, ODP, ONU, dan tagihan sendiri.
-- Ganti paket tetap lewat sunting langganan (jalur yang memicu perpindahan grup RADIUS),
-- bukan lewat membuka langganan baru.
--
-- TERMINATED sengaja dikecualikan: riwayat WAJIB boleh menumpuk. Invoice lama menunjuk ke
-- langganan yang sudah berakhir, dan pelanggan yang berhenti lalu berlangganan lagi tak
-- boleh kehilangan jejak langganan sebelumnya.
--
-- Bila migrasi ini gagal, artinya ada pelanggan yang terlanjur punya >1 langganan hidup.
-- Cari pelakunya di dalam konteks tenant (`SET app.tenant_id = '<uuid-tenant>';`):
--     SELECT customer_id, count(*) FROM subscription
--      WHERE status <> 'TERMINATED' GROUP BY customer_id HAVING count(*) > 1;
-- lalu akhiri langganan yang tak lagi dipakai, atau pindahkan ke pelanggan tersendiri,
-- sebelum menjalankan ulang. Sengaja TIDAK dibereskan otomatis: memilih langganan mana
-- yang "benar" adalah keputusan komersial, bukan keputusan migrasi.
-- ============================================================

CREATE UNIQUE INDEX uq_subscription_live_per_customer
    ON subscription (customer_id)
    WHERE status <> 'TERMINATED';
