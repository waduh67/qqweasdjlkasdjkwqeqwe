import { useCallback, useEffect, useMemo, useState } from 'react'
import { getReportOverview, type ReportOverview } from '../api/reports'
import { ApiError } from '../api/client'
import { EmptyState, Spinner } from '@/components/atoms'
import { useToast } from '@/system'
import { PageHeader } from '@/components/molecules'
import { IconChart } from '@/components/atoms/icons'

/**
 * Laporan & analitik — potret bisnis satu tenant. Merangkai angka keuangan
 * (billing) dan pelanggan/langganan (customer) yang sudah diagregasi server jadi
 * kartu ringkas + tren pendapatan. Read-only: tak ada mutasi, hanya membaca satu
 * endpoint `/api/reports/overview` dan mengekspor CSV di sisi klien.
 */

/** Label Indonesia untuk status tagihan (kunci `statusCounts`). */
const INVOICE_STATUS_LABEL: Record<string, string> = {
  ISSUED: 'Terbit',
  PAID: 'Lunas',
  OVERDUE: 'Menunggak',
  VOID: 'Batal',
}

/** Label Indonesia untuk status langganan (kunci `subscriptionsByStatus`). */
const SUBSCRIPTION_STATUS_LABEL: Record<string, string> = {
  PENDING: 'Menunggu',
  ACTIVE: 'Aktif',
  ISOLATED: 'Isolir',
  TERMINATED: 'Berhenti',
}

const MONTH_SHORT = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des']

/** yyyy-mm-dd dari komponen tanggal LOKAL (hindari geseran hari akibat toISOString UTC). */
function isoLocal(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** "Rp 1.500.000" — rupiah bulat (nilai uang datang sebagai string BigDecimal). */
function fmtRupiah(s: string): string {
  return `Rp ${Number(s).toLocaleString('id-ID', { maximumFractionDigits: 0 })}`
}

/** "2026-07" → "Jul 2026". */
function fmtMonth(m: string): string {
  const [y, mo] = m.split('-')
  return `${MONTH_SHORT[Number(mo) - 1] ?? mo} ${y}`
}

/** Bungkus satu sel CSV bila mengandung koma/kutip/baris baru (RFC-4180). */
function csvCell(v: string): string {
  return /[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v
}

/** Unduh tren bulanan sebagai CSV di sisi klien — tabel paling berguna untuk diolah lanjut. */
function downloadCsv(overview: ReportOverview) {
  const rows = [['Bulan', 'Pendapatan tertagih', 'Jumlah tagihan lunas']]
  overview.monthlyRevenue.forEach((p) => rows.push([p.month, p.revenue, String(p.paidInvoiceCount)]))
  const csv = rows.map((r) => r.map(csvCell).join(',')).join('\n')
  const blob = new Blob([`﻿${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `laporan-${overview.rangeStart}_${overview.rangeEnd}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

export function ReportsPage() {
  const toast = useToast()
  const today = useMemo(() => new Date(), [])
  const [from, setFrom] = useState(() => isoLocal(new Date(today.getFullYear(), today.getMonth(), 1)))
  const [to, setTo] = useState(() => isoLocal(today))
  const [overview, setOverview] = useState<ReportOverview | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(
    (f: string, t: string) => {
      setLoading(true)
      setError(null)
      getReportOverview({ from: f, to: t, trailingMonths: 6 })
        .then(setOverview)
        .catch((e) => setError(e instanceof ApiError ? e.message : 'Gagal memuat laporan'))
        .finally(() => setLoading(false))
    },
    [],
  )

  useEffect(() => {
    load(from, to)
    // Muat sekali saat mount dengan default (bulan berjalan); refresh berikutnya via tombol.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const apply = () => {
    if (from > to) {
      toast.error('Tanggal awal tak boleh setelah tanggal akhir.')
      return
    }
    load(from, to)
  }

  return (
    <div className="stack" style={{ gap: '1.5rem' }}>
      <PageHeader
        title={<>Laporan &amp; analitik</>}
        subtitle="Ringkasan keuangan dan pelanggan tenant dalam rentang terpilih."
        actions={
          <div className="row wrap" style={{ gap: '0.5rem', alignItems: 'flex-end' }}>
            <label className="stack" style={{ gap: '0.25rem' }}>
              <span className="muted" style={{ fontSize: '0.75rem' }}>Dari</span>
              <input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
            </label>
            <label className="stack" style={{ gap: '0.25rem' }}>
              <span className="muted" style={{ fontSize: '0.75rem' }}>Sampai</span>
              <input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} />
            </label>
            <button className="primary" onClick={apply} disabled={loading}>
              Terapkan
            </button>
            <button
              className="ghost"
              onClick={() => overview && downloadCsv(overview)}
              disabled={!overview || overview.monthlyRevenue.length === 0}
              title="Unduh tren bulanan sebagai CSV"
            >
              Ekspor CSV
            </button>
          </div>
        }
      />

      {loading && !overview ? (
        <div className="card row" style={{ gap: '0.6rem', justifyContent: 'center', padding: '2rem' }}>
          <Spinner /> <span className="muted">Memuat laporan…</span>
        </div>
      ) : error ? (
        <EmptyState icon={<IconChart size={28} />} title="Gagal memuat" hint={error} />
      ) : overview ? (
        <ReportBody overview={overview} />
      ) : null}
    </div>
  )
}

function ReportBody({ overview }: { overview: ReportOverview }) {
  const { finance, subscribers } = overview

  return (
    <div className="stack" style={{ gap: '1.5rem' }}>
      {/* Angka utama: pendapatan, tunggakan, MRR, ARPU. */}
      <div className="stat-grid">
        <Stat
          label="Pendapatan tertagih"
          value={fmtRupiah(finance.revenueCollected)}
          note={`${finance.paidInvoiceCount.toLocaleString('id-ID')} tagihan lunas`}
        />
        <Stat
          label="Tunggakan"
          value={fmtRupiah(finance.outstandingAmount)}
          note={`${finance.outstandingInvoiceCount.toLocaleString('id-ID')} tagihan belum lunas`}
          accent={Number(finance.outstandingAmount) > 0 ? 'warn' : undefined}
        />
        <Stat label="MRR" value={fmtRupiah(subscribers.mrr)} note="pendapatan berulang bulanan" />
        <Stat label="ARPU" value={fmtRupiah(overview.arpu)} note="rata-rata per langganan aktif" />
      </div>

      {/* Angka pelanggan/langganan. */}
      <div className="stat-grid">
        <Stat label="Total pelanggan" value={subscribers.totalCustomers.toLocaleString('id-ID')} />
        <Stat label="Langganan aktif" value={subscribers.billableCount.toLocaleString('id-ID')} note="ACTIVE + isolir (tetap ditagih)" />
        <Stat
          label="Tagihan terbit"
          value={fmtRupiah(finance.issuedAmount)}
          note={`${finance.issuedInvoiceCount.toLocaleString('id-ID')} tagihan pada rentang`}
        />
      </div>

      <div className="row wrap" style={{ alignItems: 'stretch', gap: '1rem' }}>
        {/* Tren pendapatan bulanan. */}
        <div className="card grow" style={{ minWidth: 340 }}>
          <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
            <h3 style={{ margin: 0 }}>Tren pendapatan bulanan</h3>
            <span className="muted" style={{ fontSize: '0.8rem' }}>{overview.monthlyRevenue.length} bulan</span>
          </div>
          <RevenueChart overview={overview} />
        </div>

        {/* Distribusi status langganan & tagihan. */}
        <div className="card grow" style={{ minWidth: 260 }}>
          <h3 style={{ marginTop: 0 }}>Sebaran langganan</h3>
          <Distribution counts={subscribers.subscriptionsByStatus} labels={SUBSCRIPTION_STATUS_LABEL} empty="Belum ada langganan." />
          <h3 style={{ marginBottom: '0.5rem', marginTop: '1.25rem' }}>Sebaran tagihan</h3>
          <Distribution counts={finance.statusCounts} labels={INVOICE_STATUS_LABEL} empty="Belum ada tagihan." />
        </div>
      </div>
    </div>
  )
}

/** Grafik batang mini pendapatan per bulan — tanpa pustaka chart, cukup div berskala. */
function RevenueChart({ overview }: { overview: ReportOverview }) {
  const points = overview.monthlyRevenue
  const max = Math.max(1, ...points.map((p) => Number(p.revenue)))

  if (points.length === 0) {
    return <div className="muted" style={{ padding: '1rem 0' }}>Belum ada data pendapatan.</div>
  }

  return (
    <div className="row" style={{ gap: '0.4rem', alignItems: 'flex-end', height: 180, marginTop: '1rem' }}>
      {points.map((p) => {
        const heightPct = (Number(p.revenue) / max) * 100
        return (
          <div key={p.month} className="stack grow" style={{ gap: '0.3rem', alignItems: 'center', minWidth: 0 }}>
            <div className="muted" style={{ fontSize: '0.7rem', whiteSpace: 'nowrap' }}>
              {Number(p.revenue) >= 1_000_000
                ? `${(Number(p.revenue) / 1_000_000).toLocaleString('id-ID', { maximumFractionDigits: 1 })}jt`
                : Math.round(Number(p.revenue) / 1000).toLocaleString('id-ID') + 'rb'}
            </div>
            <div
              title={`${fmtMonth(p.month)}: ${fmtRupiah(p.revenue)} (${p.paidInvoiceCount} lunas)`}
              style={{
                width: '100%',
                maxWidth: 44,
                height: `${Math.max(2, heightPct)}%`,
                minHeight: 2,
                background: 'var(--accent)',
                borderRadius: '4px 4px 0 0',
                transition: 'height 0.2s',
              }}
            />
            <div className="muted" style={{ fontSize: '0.7rem', whiteSpace: 'nowrap' }}>
              {MONTH_SHORT[Number(p.month.split('-')[1]) - 1] ?? p.month}
            </div>
          </div>
        )
      })}
    </div>
  )
}

/** Daftar "label — jumlah" untuk sebuah peta status→cacah, terurut menurun. */
function Distribution({
  counts,
  labels,
  empty,
}: {
  counts: Record<string, number>
  labels: Record<string, string>
  empty: string
}) {
  const entries = Object.entries(counts).filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1])
  if (entries.length === 0) return <div className="muted" style={{ fontSize: '0.85rem' }}>{empty}</div>
  return (
    <div className="stack" style={{ gap: '0.4rem' }}>
      {entries.map(([status, n]) => (
        <div key={status} className="row" style={{ justifyContent: 'space-between', fontSize: '0.88rem' }}>
          <span>{labels[status] ?? status}</span>
          <span style={{ fontWeight: 600 }}>{n.toLocaleString('id-ID')}</span>
        </div>
      ))}
    </div>
  )
}

function Stat({
  label,
  value,
  note,
  accent,
}: {
  label: string
  value: string
  note?: string
  accent?: 'warn' | 'crit'
}) {
  return (
    <div className={`stat ${accent === 'crit' ? 'crit-bar' : accent === 'warn' ? 'warn-bar' : 'accent-bar'}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value" style={{ fontSize: '1.5rem' }}>{value}</div>
      {note && <div className="stat-note">{note}</div>}
    </div>
  )
}
