-- ============================================================
-- Phase 5: pemeliharaan prediktif — work order yang dibuat sistem
--
-- Sampai kini setiap work order punya pembuat manusia (created_by NOT NULL).
-- Pemeliharaan prediktif mengubah itu: saat redaman sebuah ONU terpantau
-- memburuk, sistem mengangkat WO preventif sendiri — tanpa pengguna yang
-- mengkliknya. created_by dilonggarkan menjadi nullable; null berarti "dibuat
-- sistem", sejalan dengan wo_event.actor_id yang memang sudah nullable untuk
-- kejadian tanpa aktor manusia.
-- ============================================================

ALTER TABLE work_order ALTER COLUMN created_by DROP NOT NULL;
