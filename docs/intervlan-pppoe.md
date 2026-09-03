# Operasi provisioning InterVLAN/PPPoE

Runbook ini berlaku untuk jalur OLT - transit - BRAS/RADIUS. Preview tersedia sejak awal,
sedangkan mutasi produksi gagal tertutup sampai seluruh jalur eksak tersertifikasi, proteksi
manajemen lengkap, dan operator mengaktifkan rollout.

## Prasyarat dan onboarding

1. Cadangkan DB aplikasi, DB RADIUS, dan jurnal `FTTH_COLLECTOR_STATE_DIR`.
2. Daftarkan node, interface, link, pool VLAN, profil segmen, dan intent layanan di
   **Provisioning jaringan**. Residential shared memakai alokasi bersama ber-reference count;
   enterprise dedicated memakai VLAN unik atau override yang bebas konflik. Mode alokasi
   disimpan sebagai `SHARED`/`DEDICATED`; nama profil tidak menentukan perilaku.
3. Daftarkan BRAS/RADIUS dan pastikan auth, accounting interim, serta DAE/CoA sesuai
   [`bras-radius.md`](bras-radius.md). Voucher hotspot hanya direferensikan melalui site;
   kredensial voucher tidak masuk plan.
4. Jalankan collector produksi dengan `FTTH_ENVIRONMENT=production`, simulator mati, API key
   tersimpan lokal, dan state directory pada storage persisten berizin ketat.
5. Rekam observasi topology/device yang menjadi sumber proteksi manajemen.

## Default keselamatan rollout

```dotenv
FTTH_PROVISIONING_PLANNER_ENABLED=true
FTTH_PROVISIONING_UI_ENABLED=true
FTTH_PROVISIONING_AUTO_APPLY_ENABLED=false
FTTH_PROVISIONING_MAX_AFFECTED_SUBSCRIBERS=1
FTTH_PROVISIONING_BULK_EXPANSION_ENABLED=false
```

Konfigurasi baru mengizinkan plan dan dry-run, tetapi menolak apply dengan
`PRODUCTION_AUTO_APPLY_DISABLED`. Canary hanya satu subscriber. Nilai canary di atas satu
tidak valid kecuali bulk expansion dinyalakan eksplisit. Kegagalan verifikasi pertama membuka
circuit perangkat; ambang satu ini invariant keselamatan, bukan konfigurasi operator.

## Resource manajemen yang dilindungi

Inventaris harus menyebut seluruh VLAN manajemen, prefix IP, VRF, peran interface manajemen,
jalur sumber collector, rute out-of-band wajib, dan rute out-of-band tersedia. Setiap mutasi
dibandingkan dengan bukti tersebut. `PROTECTED_MANAGEMENT_RESOURCE`, bukti tidak lengkap atau
kedaluwarsa, dan rute OOB hilang menghentikan apply sebelum command collector dibuat.

## Matriks sertifikasi

Sertifikasi cocok eksak pada tenant, device kind/id, vendor, model, firmware, transport, dan
operation class. Satu langkah tidak cocok menggagalkan seluruh path. Fixture RouterOS,
IOS-XE, Junos, HSGQ, Huawei, dan ZTE berstatus `ADAPTER_FIXTURE / PROVISIONAL`; hanya simulator
mandiri berstatus `SIMULATOR_FIXTURE / CERTIFIED_BY_TEST`. Ini bukan bukti perangkat fisik dan
khususnya bukan sertifikasi fisik HSGQ Task 9.

## Dry-run dan interpretasi

1. Pilih intent lalu jalankan **Buat dan pratinjau plan**. Server merakit plan dari intent,
   alokasi VLAN, topologi, observasi, kapabilitas, dan proteksi yang tersimpan; operator tidak
   memasukkan ID plan atau bukti perangkat secara manual.
2. Dry-run tidak boleh menciptakan execution, attempt, receipt, command
   collector, perubahan RADIUS, atau mutasi perangkat.
3. Periksa urutan OLT/transit/BRAS, diff ternormalisasi, VLAN, interface, blast radius,
   evidence ID, dan warning.
4. `PROVISIONAL_ADAPTER`, `UNCERTIFIED_CAPABILITY`, `STALE_*`,
   `MANAGEMENT_PROTECTION_REQUIRED`, atau `PROTECTED_MANAGEMENT_RESOURCE` berarti jangan apply.

## Apply, canary, dan staged enablement

1. Biarkan auto-apply mati selama onboarding dan dry-run.
2. Sertifikasi satu jalur eksak lengkap, bukan hanya vendor atau perangkat tunggal.
3. Pastikan queue kosong, drift segar, circuit tertutup, backup sehat, dan log bebas secret.
4. Set `FTTH_PROVISIONING_AUTO_APPLY_ENABLED=true`, pertahankan canary `1`, lalu deploy.
5. Jalankan satu residential shared dan satu enterprise dedicated. Verifikasi transport,
   binding BRAS, RADIUS, sesi pelanggan, dan reference count.
6. Bulk baru boleh dipakai setelah canary sukses: aktifkan
   `FTTH_PROVISIONING_BULK_EXPANSION_ENABLED=true` dan naikkan batas secara eksplisit.

Apply memakai revision `If-Match` dan satu `Idempotency-Key` stabil per percobaan. Replay
dengan key yang sama tidak membuat eksekusi kedua.

Eksekusi yang diterima masuk antrean persisten. Worker server mengambil status `QUEUED`,
`RUNNING`, atau `ROLLING_BACK`, lalu menggunakan kanal heartbeat collector yang sama untuk
dispatch dan ACK. Bila server atau collector restart, lease dan fencing token mencegah dua
worker menulis perangkat yang sama. Jangan menjalankan jalur mutasi paralel di luar kanal ini.

Endpoint `POST /api/provisioning/intents/{id}/plans` merakit plan dari state tersimpan. UI
memanggil endpoint ini sebelum preview; ID plan bukan lagi input operator. Endpoint intent
`/suspend`, `/restore`, dan `/deprovision` menghubungkan lifecycle akses dan pembongkaran.

Tombol **Tangguhkan** mempertahankan transport dan mengisolasi akses; **Pulihkan** mengaktifkan
akses kembali. **Deprovision** menyusun plan DELETE dari state tersimpan, memeriksa sesi aktif,
menjalankan rollback-safe removal, lalu melepas referensi VLAN dan menandai intent dihentikan
hanya setelah eksekusi sukses.

## Pembatalan, rollback, dan rekonsiliasi manual

Pembatalan hanya aman selama eksekusi `QUEUED`. Setelah mutasi dimulai, kegagalan menghentikan
langkah hilir dan mengompensasi resource terverifikasi dalam urutan terbalik. Rollback tetap
memerlukan proteksi manajemen terkini dan tidak boleh menghapus resource brownfield atau yang
masih direferensikan subscriber lain.

Jika status `MANUAL_RECONCILIATION`, matikan auto-apply, biarkan circuit terbuka, bandingkan
snapshot before/after/rollback dengan observasi langsung, dan pulihkan layanan yang masih
bekerja. Jangan mengubah status DB atau mengulang command mentah. Buat plan baru dari state
otoritatif setelah penyebab dan ownership jelas.

## Brownfield dan drift

Adopsi brownfield hanya untuk observasi semantik ekuivalen dengan proteksi lengkap dan
sertifikasi eksak aktif. Drift `CONFLICTING` atau `UNKNOWN` tidak boleh diadopsi otomatis.
Observasi gagal tidak menghasilkan fakta drift palsu dan tidak menghentikan scan perangkat
lain. Setelah perubahan eksternal, jalankan scan, periksa umur observasi, lalu pilih rollback
eksternal atau plan pengganti tervalidasi.

## Pemeriksaan operator

Jalankan query read-only dengan role yang tunduk RLS dan tenant context yang benar.

```bash
# Queue health
docker compose -f deploy/docker-compose.prod.yml exec postgres psql -U "$FTTH_DB_USER" -d "$FTTH_DB_NAME" -c \
  "select status,count(*) from provisioning_execution group by status order by status"

# Drift age
docker compose -f deploy/docker-compose.prod.yml exec postgres psql -U "$FTTH_DB_USER" -d "$FTTH_DB_NAME" -c \
  "select status,max(recorded_at) as terbaru from provisioning_drift_record group by status"

# Exact certification state, without credentials or raw config
docker compose -f deploy/docker-compose.prod.yml exec postgres psql -U "$FTTH_DB_USER" -d "$FTTH_DB_NAME" -c \
  "select device_kind,vendor,model,firmware,transport,operation_class,status,valid_until,revoked_at from provisioning_adapter_certification order by valid_until"

# Open circuits
docker compose -f deploy/docker-compose.prod.yml exec postgres psql -U "$FTTH_DB_USER" -d "$FTTH_DB_NAME" -c \
  "select device_kind,device_id,failure_count,open_until from provisioning_device_circuit where open_until > now()"

# Rollback/manual-reconciliation state
docker compose -f deploy/docker-compose.prod.yml exec postgres psql -U "$FTTH_DB_USER" -d "$FTTH_DB_NAME" -c \
  "select id,status,detail from provisioning_execution where status in ('ROLLING_BACK','ROLLED_BACK','MANUAL_RECONCILIATION')"

# Secret exposure check: success means no match
docker compose -f deploy/docker-compose.prod.yml logs --no-color server 2>/dev/null | \
  grep -Eai 'password=|secret=|private.?key|BEGIN (RSA|OPENSSH)|raw.?config' && exit 1 || true
```

Periksa juga `docker compose ... logs backup` dan `backups/*/last-backup.txt`. Jangan salin
hasil query yang mengandung identitas pelanggan ke tiket publik.

## Disaster recovery

1. Set auto-apply `false` sebelum restore.
2. Pulihkan DB aplikasi dan DB RADIUS sesuai [`backup.md`](backup.md).
3. Pulihkan jurnal collector ke path semula dengan izin 600/700 sebelum collector start.
4. Periksa queue, sertifikasi, circuit, rollback, drift, dan paparan secret.
5. Jalankan dry-run residential dan enterprise. Aktifkan kembali canary hanya setelah state
   server, collector, simulator/lab, BNG/RADIUS, hotspot, dan UI konsisten.

## Uninstall atau rollback tanpa memutus layanan

Set planner dan auto-apply ke `false`, lalu hentikan collector provisioning. Jangan menghapus
VLAN, interface, binding BRAS, row RADIUS, atau kredensial layanan yang sudah aktif. Rollback
image/server boleh dilakukan setelah queue berhenti dan backup lengkap tersedia. Pertahankan
RADIUS/BRAS serta konfigurasi perangkat existing; penghapusan layanan harus melalui plan
decommission tersertifikasi, bukan dengan menghapus tabel atau volume Compose.
