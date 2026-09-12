-- Menutup loop approval -> mutasi stok.
--
-- Sampai sekarang `inventory_approval` tidak tahu mutasi mana yang diotorisasinya:
-- `movement_id` hanya muncul di `inventory_approval_effect`, DAN nilainya datang dari body
-- request keputusan — approver yang mengklik "setuju" ikut mengirim id mutasi yang mau
-- diberlakukan. Akibatnya dua hal buruk sekaligus:
--   1) approval yang disetujui tapi pemanggilnya lupa/enggan mengisi `movement_id` tidak
--      pernah memberlakukan apa pun. Mutasinya diam di PENDING_APPROVAL selamanya; barang
--      restock tercatat "sudah disetujui" tapi saldo tidak pernah bertambah, dan petugas
--      gudang hanya tahu setelah stok fisik dan sistem berselisih.
--   2) approver bisa menyetujui permintaan A lalu memberlakukan mutasi B.
--
-- Kolom di bawah ini mengikat keduanya SAAT PERMINTAAN DIBUAT, bukan saat diputuskan.
ALTER TABLE inventory_approval
    ADD COLUMN movement_id uuid REFERENCES inventory_movement(id);

-- Nullable SENGAJA: tidak semua approval mengotorisasi satu mutasi yang sudah tercatat
-- (mis. permintaan restock ke pemasok yang barangnya baru datang belakangan). Yang punya
-- mutasi terikat cukup satu, jadi UNIQUE parsial mencegah dua approval berbeda mengklaim
-- mutasi yang sama — tanpa itu, satu mutasi bisa "disetujui" oleh permintaan lain yang
-- ambangnya jauh lebih rendah.
CREATE UNIQUE INDEX inventory_approval_movement_uq
    ON inventory_approval (tenant_id, movement_id)
    WHERE movement_id IS NOT NULL;
