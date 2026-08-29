import type { ReactNode } from 'react'
import { Text } from '@fluentui/react-components'
import { EmptyState, Spinner, type Tone } from '@/components/atoms'

/**
 * Format & potongan tampilan yang dipakai bersama oleh halaman-halaman portal.
 * Dipisah dari halamannya supaya "Rp" dan "5 Agu 2026" ditulis dengan cara yang sama
 * di Ringkasan, Tagihan, maupun Profil — pelanggan yang sama membaca ketiganya.
 */

/** Rupiah tanpa desimal, dari nilai string BigDecimal server. */
export function rupiah(amount: string | number): string {
  const n = typeof amount === 'string' ? Number(amount) : amount
  return new Intl.NumberFormat('id-ID', { style: 'currency', currency: 'IDR', maximumFractionDigits: 0 }).format(
    Number.isFinite(n) ? n : 0,
  )
}

/** Tanggal lokal (YYYY-MM-DD) → "5 Agu 2026". */
export function fmtDate(value: string | null): string {
  if (!value) return '—'
  const d = new Date(value.length <= 10 ? `${value}T00:00:00` : value)
  return Number.isNaN(d.getTime())
    ? value
    : d.toLocaleDateString('id-ID', { day: 'numeric', month: 'short', year: 'numeric' })
}

/** Lama sesi menyala, dibulatkan ke satuan yang wajar diucapkan ("3 hari 4 jam"). */
export function fmtUptime(seconds: number | null): string {
  if (seconds == null || seconds <= 0) return '—'
  const d = Math.floor(seconds / 86400)
  const h = Math.floor((seconds % 86400) / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  if (d > 0) return `${d} hari ${h} jam`
  if (h > 0) return `${h} jam ${m} menit`
  return `${m} menit`
}

/**
 * Status tagihan bukan status domain yang dikenal `StatusBadge` (PAID/ISSUED/… tak ada di
 * `STATUS_TONE`), jadi nadanya dipetakan di sini — tetap lewat `<Badge tone>` supaya warnanya
 * datang dari token yang sama, bukan gaya sebaris. Sepadan dengan peta di `InvoicesPage`
 * milik operator; yang berbeda hanya labelnya (lihat di bawah).
 */
export const INVOICE_TONE: Record<string, Tone> = {
  PAID: 'good',
  ISSUED: 'warning',
  OVERDUE: 'critical',
  VOID: 'neutral',
  REFUNDED: 'accent',
}

/** Status ditulis dengan bahasa pelanggan — portal bukan tempat memamerkan nama enum. */
export const INVOICE_STATUS_LABEL: Record<string, string> = {
  PAID: 'Lunas',
  ISSUED: 'Belum dibayar',
  OVERDUE: 'Jatuh tempo',
  VOID: 'Batal',
  REFUNDED: 'Dikembalikan',
}

/** Kartu metrik portal — memakai kelas `.stat` konsol supaya angkanya tampil seragam. */
export function Stat({
  label,
  value,
  note,
  tone,
  valueColor,
}: {
  label: string
  value: ReactNode
  note?: string
  tone?: 'good' | 'warn' | 'crit'
  valueColor?: string
}) {
  const bar = tone === 'good' ? ' accent-bar' : tone === 'warn' ? ' warn-bar' : tone === 'crit' ? ' crit-bar' : ''
  return (
    <div className={`stat${bar}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value" style={valueColor ? { color: valueColor } : undefined}>{value}</div>
      {note && <div className="stat-note">{note}</div>}
    </div>
  )
}

export function Loading() {
  return (
    <div className="card" style={{ display: 'grid', placeItems: 'center', minHeight: 160 }}>
      <div className="stack" style={{ alignItems: 'center', gap: '0.6rem' }}>
        <Spinner />
        <Text as="span" className="muted" size={300}>Memuat…</Text>
      </div>
    </div>
  )
}

/** Pemuatan sudah selesai tapi datanya tak sampai — dikatakan apa adanya, bukan berputar terus. */
export function Unavailable({ what }: { what: string }) {
  return (
    <div className="card">
      <EmptyState title={`${what} belum bisa ditampilkan`} hint="Coba muat ulang halaman sebentar lagi." />
    </div>
  )
}
