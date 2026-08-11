import type { CustomerTrace } from '@/api/network'

/**
 * VONIS sebuah sambungan pelanggan: satu kalimat "apa yang salah dan tindakan
 * pertamanya apa", plus nada & kata pendampingnya.
 *
 * Ditaruh di luar panel karena kalimat inilah yang ikut terbawa ke tempat lain —
 * ia menjadi keterangan work order yang dibuat dari panel telusur. Bila panel dan
 * pembuat WO masing-masing menyimpulkan sendiri, tiket bisa berbunyi "redaman
 * lemah" sementara layar yang melahirkannya bilang "ONU LOS".
 */

/**
 * Ambang redaman Rx (dBm) untuk memberi vonis di panel. Sengaja disamakan dengan
 * ambang alarm `ONU_LOW_RX` di modul monitoring supaya kalimat vonis dan warna
 * simpul di peta tak pernah bertengkar.
 */
export const RX_WARN_DBM = -25
export const RX_CRIT_DBM = -27

export type VerdictTone = 'good' | 'warning' | 'critical' | 'neutral'

/** Nada vonis → `intent` MessageBar Fluent, agar ikon & tint-nya digambar tema. */
export const VERDICT_INTENT: Record<VerdictTone, 'success' | 'warning' | 'error' | 'info'> = {
  good: 'success',
  warning: 'warning',
  critical: 'error',
  neutral: 'info',
}

export const VERDICT_COLOR: Record<VerdictTone, string> = {
  good: 'var(--good-ink)',
  warning: 'var(--warning-ink)',
  critical: 'var(--critical-ink)',
  neutral: 'var(--muted)',
}

/**
 * Kata sifat pendamping angka Rx. Warna saja tak boleh jadi satu-satunya pembawa
 * arti (buta warna, cetakan hitam-putih), jadi nilainya selalu didampingi kata.
 */
export const RX_WORD: Record<VerdictTone, string> = {
  good: 'wajar',
  warning: 'lemah',
  critical: 'parah',
  neutral: '',
}

/**
 * Satu kalimat "apa yang salah dan tindakan pertamanya apa" — pengganti kerja
 * membaca-silang enam angka. Urutannya sengaja mengikuti urutan kerja operator:
 * yang paling hulu (belum tersambung) dan paling fisik (LOS/mati) lebih dulu,
 * sebab tak ada gunanya menyalahkan PPPoE kalau fibernya putus. Isolir berada di
 * atas pemeriksaan redaman karena itulah alasan sesungguhnya layanan mati.
 */
export function traceVerdict(trace: CustomerTrace): { tone: VerdictTone; text: string } {
  const onu = trace.liveOnuStatus ?? trace.onuStatus
  const rx = trace.liveRxPowerDbm ?? trace.installRxPowerDbm
  const bras = trace.bras

  if (!trace.onuSerialNumber || !trace.upstream)
    return { tone: 'neutral', text: 'Belum tersambung — ONU/port ODP belum ditetapkan. Butuh WO pemasangan.' }
  if (onu === 'LOS')
    return { tone: 'critical', text: 'ONU LOS — sinyal fiber hilang. Curigai drop core putus atau konektor lepas.' }
  if (onu && onu !== 'ONLINE')
    return { tone: 'critical', text: 'ONU mati — pastikan listrik/adaptor di rumah dulu sebelum turun ke fiber.' }
  if (bras?.accessStatus === 'ISOLATED')
    return { tone: 'warning', text: 'Akun diisolir — layanan sengaja diputus. Pulihkan dari detail pelanggan.' }
  if (rx != null && rx <= RX_CRIT_DBM)
    return { tone: 'critical', text: `Redaman parah ${rx.toFixed(1)} dBm — perlu perbaikan splicing/konektor.` }
  if (rx != null && rx <= RX_WARN_DBM)
    return { tone: 'warning', text: `Redaman lemah ${rx.toFixed(1)} dBm — masih jalan tapi rawan. Jadwalkan cek jalur.` }
  if (!bras)
    return { tone: 'warning', text: 'ONU online tapi belum punya akun PPPoE — layanan belum bisa dipakai.' }
  if (!bras.online)
    return { tone: 'warning', text: 'Fisik sehat, PPPoE tak tersambung — coba Reset Login, lalu cek user/password.' }
  if (trace.cpeOnline === false)
    return { tone: 'warning', text: 'Layanan jalan, tapi router tak melapor ke ACS — remote management mati.' }
  return { tone: 'good', text: 'Sehat — ONU online, sinyal wajar, sesi PPPoE tersambung.' }
}
