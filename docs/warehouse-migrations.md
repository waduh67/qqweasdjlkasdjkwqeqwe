# Migrasi dan lingkungan QA warehouse

Baseline `ebf98fdf270b30ac30b7a01b1f609b39e8414618` memiliki 169 migrasi,
versi maksimum `V172__evidence_retention_claim_state.sql`. Celah V56-V58
adalah riwayat, bukan slot bebas. Manifest ini dibekukan untuk branch
`feat/warehouse-workorder`; belum ada SQL baru yang dibuat.

| Slot | Versi | Pemilik tugas | Cakupan |
| --- | --- | --- | --- |
| M01 | V173 | 04 | Precision, masters, identity claims, cutover/auth fences |
| M02 | V174 | 04 | Documents, posting, reservations, inspection, scopes, material plans |
| M03 | V175 | 11 | Approval, counts, remaining operations |
| M04 | V176 | 19 | Assignments, customer installation episodes |
| M05 | V177 | 43 | Preservation, staging, reconciliation |
| M06 | V178 | 43 | Admission-scoped constraints and compatibility gates |

Jangan memakai ulang atau menomori ulang migrasi yang sudah diterapkan.
Jika merge menduduki slot ini, hentikan implementasi dan sepakati migrasi
kompatibilitas forward-only. Tambahan file per slot memerlukan pembaruan
manifest terkoordinasi sebelum SQL dibuat. Database produksi tidak diperiksa
atau diubah oleh tugas baseline ini.

## QA lokal terisolasi

Prasyarat: Linux, Bash, Docker Compose, akses socket lokal Docker (atau
`sudo -n docker`), JDK21/Gradle wrapper, Node/npm, curl, openssl, flock.
Port 25432 dan 29000 harus bebas. Tidak ada fallback ke `ftth_test`.

```sh
scripts/warehouse/test-environment.sh up
scripts/warehouse/test-environment.sh check
scripts/warehouse/qa.sh server --tests '*WarehouseEnvironmentIT*' --rerun-tasks --no-parallel
scripts/warehouse/qa.sh stop
scripts/warehouse/test-environment.sh down
```

Runner membuat `.omo/runtime/warehouse-test.env` milik user dengan mode0600.
Jangan source, cetak, atau commit file ini. Namespace Compose memuat hash
worktree dan token acak; container, network, volume, dan kedua database
memiliki marker. Endpoint dipublikasikan hanya pada 127.0.0.1.
`warehouse_owner` adalah pemilik migrasi NOSUPERUSER dengan BYPASSRLS untuk
backfill lintas tenant; `warehouse_app` bukan owner, bukan anggota owner,
NOSUPERUSER/NOCREATEROLE/NOCREATEDB/NOBYPASSRLS. Extension dipasang admin
hanya pada database task-owned. Aplikasi mendapat DML, bukan DDL.

Semua override konfigurasi dari shell ditolak sebelum akses DB. Runner
mengekspor datasource Spring dan Flyway secara terpisah setelah validasi.
Override inline lama pada `SchedulingSpringContextIT` juga mengikuti
`SPRING_DATASOURCE_URL`; ini penting karena properti annotation Spring test
berprioritas lebih tinggi daripada environment. Fallback lama di test tersebut
tetap hanya untuk eksekusi di luar runner, bukan jalur warehouse QA.
Gradle dan lifecycle lingkungan menggunakan satu lock per worktree.
`down` hanya menghapus container/network milik task dan mempertahankan volume.
Startup yang diinterupsi membersihkan container task, sehingga `up` dapat
diulang dengan env/volume yang sama. Marker tidak cocok harus diselidiki,
bukan diatasi dengan menghapus volume lama atau melonggarkan validator.
`stop` tetap dapat berjalan sesudah `down`: cleanup lokal memeriksa file env
dan identitas PID tanpa memerlukan koneksi DB yang sudah dihentikan.

`web-test FILTER`, `web-check`, dan `kmp` mengikuti command plan. KMP gagal
secara eksplisit hingga modul materials tugas42 tersedia. Browser juga
gagal eksplisit hingga config/spec tugas31 dan isolasi adapter mutasi eksternal
tersedia; tidak ada hasil browser palsu atau backend produksi yang dipakai.
Profil `application-warehouse-e2e.yml` tugas31 harus mematikan adapter mutasi
perangkat/notifikasi eksternal tanpa mengganti layanan warehouse/customer/WO
atau storage dengan mock. Runner menolak browser sebelum profil ini ada.
Sesudah tersedia, runner memakai metadata `server/build/warehouse/boot-jar-path.txt`
dari task bootJar (bukan wildcard JAR), port17880/14188 dengan proxy eksplisit,
JSON health backend/proxy, laporan Playwright nonzero pada desktop/mobile,
serta cleanup process group dengan PID/start-time/marker yang tercatat.
Readiness wajib menyertakan health contributor `components.warehouse.details`
dengan `database=warehouse_e2e`, `user=warehouse_app`, dan `marker` dari
`WAREHOUSE_ENVIRONMENT_MARKER`; respons generik UP saja tidak diterima.
Contributor task31 harus membaca identitas dari koneksi DB aktif, bukan
sekadar menyalin environment. Detail ini hanya boleh terbuka di profil lokal.
Config Playwright task31 harus mengaktifkan reporter JSON (boleh bersama line),
mengikuti `PLAYWRIGHT_JSON_OUTPUT_NAME`. Laporan hilang, nol test pada salah
satu project, test dilewati, flaky, atau gagal membuat runner gagal.
