import type { ReactNode } from 'react'

/**
 * Lencana status — dipusatkan agar status (aset/alarm/ONU) ditampilkan dengan warna
 * dan istilah yang seragam di seluruh aplikasi. Nada status yang tidak konsisten
 * membuat operator ragu, dan itu lebih berbahaya daripada polos.
 */
export type Tone = 'neutral' | 'good' | 'warning' | 'serious' | 'critical' | 'accent'

/** Memetakan status domain ke nada visual. Satu sumber kebenaran untuk semua tabel. */
const STATUS_TONE: Record<string, Tone> = {
  // Aset jaringan & pelanggan
  ACTIVE: 'good',
  ONLINE: 'good',
  GOOD: 'good',
  PLANNED: 'accent',
  PENDING: 'warning',
  MAINTENANCE: 'warning',
  WARNING: 'warning',
  ISOLATED: 'serious',
  SUSPENDED: 'serious',
  OFFLINE: 'serious',
  UNKNOWN: 'neutral',
  INACTIVE: 'neutral',
  // Fisiknya masih terpasang tapi sudah tak dipakai (mis. drop bekas pelanggan
  // yang cabut). Bukan 'neutral' seperti INACTIVE: yang nonaktif masih menunggu
  // dinyalakan lagi, yang ditinggal tak akan — dan barang mati yang mengaku
  // netral pelan-pelan menumpuk di peta tanpa ada yang membereskannya.
  ABANDONED: 'serious',
  PROSPECT: 'neutral',
  DISABLED: 'neutral',
  TERMINATED: 'critical',
  LOS: 'critical',
  CRITICAL: 'critical',
  DISMANTLED: 'critical',
  // Alarm
  INFO: 'accent',
  ACKNOWLEDGED: 'warning',
  CLEARED: 'neutral',
}

/**
 * Istilah Indonesia untuk status yang terjemahan otomatisnya (lihat [prettify])
 * salah atau kaku. Sengaja pendek: yang lain memang sudah terbaca apa adanya.
 */
const STATUS_LABEL: Record<string, string> = {
  ABANDONED: 'Ditinggal',
}

export function StatusBadge({ status, label }: { status: string; label?: string }) {
  const tone = STATUS_TONE[status] ?? 'neutral'
  return (
    <span className={`badge ${tone}`}>
      <span className="dot" />
      {label ?? STATUS_LABEL[status] ?? prettify(status)}
    </span>
  )
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: Tone }) {
  return <span className={`badge ${tone}`}>{children}</span>
}

/** Ubah `ONU_LOW_RX` / `ACTIVE` menjadi teks yang enak dibaca. */
function prettify(value: string): string {
  return value
    .toLowerCase()
    .replace(/_/g, ' ')
    .replace(/^\w/, (c) => c.toUpperCase())
}
