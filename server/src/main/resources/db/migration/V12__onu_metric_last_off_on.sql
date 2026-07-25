-- ------------------------------------------------------------
-- Waktu putus & pulih terakhir ONU pada deret waktu metrik.
--
-- Register "last off / last on" OLT menjawab dua pertanyaan operator tanpa
-- menunggu siklus polling berikutnya: sejak kapan ONU ini mati, dan (kalau sudah
-- pulih) berapa lama tadi putusnya. Berpasangan menjadi durasi gangguan terakhir.
-- Urutan keduanya menandakan keadaan sekarang: last_on_at > last_off_at berarti
-- sudah pulih; sebaliknya berarti masih putus sejak last_off_at.
--
-- Disimpan di deret waktu, bukan di agregat onu, dengan alasan yang sama seperti
-- down_cause (V11): ini telemetri register OLT yang bertahan melewati pemulihan,
-- sehingga bacaan terbaru selalu memuat nilai terakhirnya.
--
-- Nullable, tanpa backfill dan tanpa default: OLT/vendor yang tidak melaporkannya
-- cukup mengisi NULL, dan menambah kolom tanpa default menjaga ALTER pada
-- hypertable tetap operasi metadata yang murah.
-- ------------------------------------------------------------
ALTER TABLE onu_metric
    ADD COLUMN last_off_at timestamptz,
    ADD COLUMN last_on_at  timestamptz;
