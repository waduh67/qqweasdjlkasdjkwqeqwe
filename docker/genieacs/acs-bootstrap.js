#!/usr/bin/env node
/**
 * Menyelaraskan autentikasi CWMP GenieACS dengan environment, sekali saat container
 * `genieacs-cwmp` naik.
 *
 * KENAPA ADA: port 7547 wajib terbuka ke internet — itu pintu tempat ONT pelanggan
 * melapor. Bawaan GenieACS menerima Inform SIAPA PUN tanpa kredensial (`cwmp.auth`
 * kosong → sesi diloloskan), jadi pemindai internet bisa mendaftarkan perangkat hantu
 * ke ACS kita. Itu bukan teori: di ACS produksi pernah muncul device ber-Manufacturer,
 * OUI, dan ProductClass yang semuanya harfiah "DISCOVERYSERVICE". Perangkat sampah
 * mengotori daftar armada, menggelembungkan Mongo, dan — yang paling berbahaya —
 * membuat operator terbiasa melihat baris yang tak dikenalnya.
 *
 * GenieACS menyimpan `cwmp.auth` di koleksi `config` Mongo (biasanya diisi lewat UI,
 * yang TIDAK kita jalankan). Skrip ini yang mengisinya, supaya setelannya ikut repo &
 * `.env` seperti setelan lain — bukan pengetahuan lisan yang hilang saat orangnya pindah.
 *
 * Env yang dibaca:
 *   FTTH_CPE_CWMP_AUTH_MODE      off (bawaan) | enforce
 *   FTTH_CPE_ONT_ACS_USERNAME    kredensial yang harus dikirim CPE
 *   FTTH_CPE_ONT_ACS_PASSWORD
 *
 * Kredensialnya SENGAJA memakai ulang pasangan `FTTH_CPE_ONT_ACS_*` yang sudah ada —
 * pasangan yang konsol tampilkan di kartu "Setelan ONT" untuk diketik teknisi ke perangkat.
 * Menambah pasangan env sendiri di sini akan melahirkan dua sumber kebenaran yang bisa
 * berbeda: layar memberi tahu teknisi satu password, ACS memeriksa yang lain, dan ONT
 * ditolak diam-diam tanpa satu pun petunjuk siapa yang keliru.
 *
 * SENGAJA bawaan `off` (= perilaku lama persis). Menyalakan auth pada armada yang
 * ONT-nya belum diberi kredensial akan MEMUTUS SEMUANYA dari ACS sekaligus, diam-diam:
 * pelanggan tetap online (TR-069 tak menyentuh jalur data), jadi tak ada keluhan — yang
 * hilang cuma kemampuan kita mengelola perangkat, dan baru ketahuan saat dibutuhkan.
 * Jadi ini harus keputusan sadar operator, bukan efek samping menaikkan versi.
 *
 * Env adalah SATU-SATUNYA sumber kebenaran: mode `off` menghapus config, bukan
 * membiarkannya. Kalau tidak, mematikan auth mustahil tanpa masuk ke Mongo — dan yang
 * butuh mematikannya adalah orang yang sedang panik karena armadanya lepas.
 */
'use strict'

const { MongoClient } = require('/usr/local/lib/node_modules/genieacs/node_modules/mongodb')

const CONFIG_ID = 'cwmp.auth'
const MODE = (process.env.FTTH_CPE_CWMP_AUTH_MODE || 'off').trim().toLowerCase()
const USERNAME = (process.env.FTTH_CPE_ONT_ACS_USERNAME || '').trim()
const PASSWORD = process.env.FTTH_CPE_ONT_ACS_PASSWORD || ''
const MONGO_URL =
  process.env.GENIEACS_MONGODB_CONNECTION_URL || 'mongodb://127.0.0.1:27017/genieacs'

/**
 * Kutip nilai untuk literal string bahasa expression GenieACS. Password ISP sering
 * memuat kutip atau backslash; tanpa lolos-kutip, expression-nya jadi tak sah dan
 * GenieACS gagal mengevaluasinya — yang artinya auth tak berlaku sama sekali. Gagal
 * ke arah "terbuka" persis kegagalan yang sedang kita tutup, jadi ini bukan detail.
 */
function quote(value) {
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`
}

async function main() {
  if (MODE !== 'off' && MODE !== 'enforce') {
    console.error(
      `[acs-bootstrap] FTTH_CPE_CWMP_AUTH_MODE="${MODE}" tidak dikenal (pakai: off | enforce)`,
    )
    process.exit(1)
  }
  // Gagal cepat saat start, BUKAN diam-diam jatuh ke "tanpa auth": operator yang mengetik
  // mode enforce percaya port 7547-nya sudah terkunci.
  if (MODE === 'enforce' && (!USERNAME || !PASSWORD)) {
    console.error(
      '[acs-bootstrap] mode enforce butuh FTTH_CPE_ONT_ACS_USERNAME dan FTTH_CPE_ONT_ACS_PASSWORD terisi',
    )
    process.exit(1)
  }

  const client = new MongoClient(MONGO_URL)
  await client.connect()
  try {
    const config = client.db().collection('config')
    if (MODE === 'off') {
      const { deletedCount } = await config.deleteOne({ _id: CONFIG_ID })
      console.log(
        deletedCount
          ? '[acs-bootstrap] auth CWMP DIMATIKAN — port 7547 kembali menerima Inform tanpa kredensial'
          : '[acs-bootstrap] auth CWMP tidak aktif (bawaan) — port 7547 menerima Inform tanpa kredensial',
      )
      return
    }
    // AUTH() milik GenieACS: di HTTP polos ia menantang dengan Digest (password tak pernah
    // melintas apa adanya), di balik TLS dengan Basic. Cocok untuk 7547 yang memang telanjang.
    const value = `AUTH(${quote(USERNAME)}, ${quote(PASSWORD)})`
    await config.replaceOne({ _id: CONFIG_ID }, { _id: CONFIG_ID, value }, { upsert: true })
    console.log(
      `[acs-bootstrap] auth CWMP AKTIF sebagai "${USERNAME}" — Inform tanpa kredensial ditolak 401`,
    )
  } finally {
    await client.close()
  }
}

main().catch((err) => {
  // Jangan biarkan cwmp naik dengan setelan auth yang tak jelas: lebih baik container gagal
  // dan terlihat di `docker ps` daripada port 7547 terbuka sementara kita mengira terkunci.
  console.error('[acs-bootstrap] gagal menyelaraskan auth CWMP:', err.message)
  process.exit(1)
})
