/**
 * Kesehatan proses server sendiri (bukan jaringan pelanggan) untuk area Platform admin.
 * Semua durasi datang sebagai DETIK — server sengaja tak mengirim ISO-8601 supaya
 * halaman tak perlu mengurai `PT12H` hanya untuk bisa membandingkan dan memformat.
 */

import { api } from './client'

export interface JobHealthView {
  /** `NamaKelas.namaMetode`, mis. `OltPollingScheduler.pollAll`. */
  name: string
  /** Modul asal, mis. `monitoring`. */
  module: string
  /** Null = dipicu cron/trigger, bukan selang tetap. */
  intervalSeconds: number | null
  runs: number
  failures: number
  lastStartedAt: string | null
  lastSuccessAt: string | null
  lastFailureAt: string | null
  lastError: string | null
  lastDurationSeconds: number | null
  running: boolean
  /** Umur sukses terakhir; belum pernah sukses = dihitung sejak server hidup. */
  sinceSuccessSeconds: number
  stallAfterSeconds: number | null
  stalled: boolean
}

export function listJobHealth(): Promise<JobHealthView[]> {
  return api.get('/api/platform/jobs')
}
