import { useCallback, useEffect, useMemo, useState } from 'react'
import { Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow, Text, typographyStyles } from '@fluentui/react-components'
import {
  getOperationsReport,
  getReportOverview,
  type OperationsReport,
  type ReceivableAging,
  type ReportOverview,
  type RevenueSlice,
} from '../api/reports'
import { TICKET_CATEGORY_LABEL } from '../api/helpdesk'
import { ApiError } from '../api/client'
import { Button, EmptyState, Segmented, Spinner, TextField } from '@/components/atoms'
import { useToast } from '@/system'
import { PageHeader } from '@/components/molecules'
import { IconChart } from '@/components/atoms/icons'
import { TYPE_LABEL } from '@/utils/woLabels'

/**
 * Laporan & analitik — potret bisnis satu tenant. Read-only: tak ada mutasi, hanya
 * membaca dua endpoint dan mengekspor CSV di sisi klien.
 *
 * Dua tab karena dua pertanyaan yang berbeda pembacanya: **Keuangan** (uang masuk, umur
 * piutang, pendapatan per paket/wilayah, churn) dibaca pemilik; **Operasional** (MTTR,
 * produktivitas teknisi, kepatuhan SLA meja bantuan) dibaca penyelia. Tab operasional
 * dimuat saat dibuka saja — yang tak dilihat tak perlu dihitung server.
 */

/** Label Indonesia untuk status tagihan (kunci `statusCounts`). */
const INVOICE_STATUS_LABEL: Record<string, string> = {
  ISSUED: 'Terbit',
  PAID: 'Lunas',
  OVERDUE: 'Menunggak',
  VOID: 'Batal',
  REFUNDED: 'Dikembalikan',
}

/** Label Indonesia untuk status langganan (kunci `subscriptionsByStatus`). */
const SUBSCRIPTION_STATUS_LABEL: Record<string, string> = {
  PENDING: 'Menunggu',
  ACTIVE: 'Aktif',
  ISOLATED: 'Isolir',
  TERMINATED: 'Berhenti',
}

/** Label ember umur piutang (kunci `aging.buckets[].bucket`), urut dari termuda. */
const AGING_BUCKET_LABEL: Record<string, string> = {
  NOT_DUE: 'Belum jatuh tempo',
  D1_30: 'Telat 1–30 hari',
  D31_60: 'Telat 31–60 hari',
  D61_90: 'Telat 61–90 hari',
  D90_PLUS: 'Telat > 90 hari',
}

const MONTH_SHORT = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des']

type Tab = 'keuangan' | 'operasional'

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

/**
 * Durasi rata-rata. `null` ditampilkan "—", BUKAN "0 jam": tak ada yang selesai bukan
 * berarti selesai seketika. Di bawah sejam pakai menit supaya SLA respons terbaca wajar.
 */
function fmtHours(h: number | null): string {
  if (h == null) return '—'
  if (h < 1) return `${Math.round(h * 60)} mnt`
  if (h < 48) return `${h.toLocaleString('id-ID', { maximumFractionDigits: 1 })} jam`
  return `${(h / 24).toLocaleString('id-ID', { maximumFractionDigits: 1 })} hari`
}

/** Bungkus satu sel CSV bila mengandung koma/kutip/baris baru (RFC-4180). */
function csvCell(v: string): string {
  return /[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v
}

/** Rangkai baris jadi berkas CSV dan picu unduhannya (BOM agar Excel membaca UTF-8). */
function saveCsv(rows: string[][], filename: string) {
  const csv = rows.map((r) => r.map(csvCell).join(',')).join('\n')
  const blob = new Blob([`﻿${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/** Unduh tren bulanan + bedah pendapatan — tabel paling berguna untuk diolah lanjut. */
function downloadFinanceCsv(overview: ReportOverview) {
  const rows = [['Bulan', 'Pendapatan tertagih', 'Jumlah tagihan lunas', 'Pengembalian dana', 'Pendapatan bersih']]
  overview.monthlyRevenue.forEach((p) =>
    rows.push([
      p.month,
      p.revenue,
      String(p.paidInvoiceCount),
      p.refunded,
      String(Number(p.revenue) - Number(p.refunded)),
    ]),
  )
  const slices = (title: string, list: RevenueSlice[]) => {
    if (list.length === 0) return
    rows.push([], [title, 'Pendapatan', 'Tagihan lunas', 'Langganan'])
    list.forEach((s) => rows.push([s.label, s.amount, String(s.paidInvoiceCount), String(s.subscriptionCount)]))
  }
  slices('Paket', overview.revenueByPackage)
  slices('Wilayah', overview.revenueByArea)
  rows.push([], ['Umur piutang per', overview.aging.asOf], ['Ember', 'Nilai', 'Jumlah tagihan'])
  overview.aging.buckets.forEach((b) =>
    rows.push([AGING_BUCKET_LABEL[b.bucket] ?? b.bucket, b.amount, String(b.invoiceCount)]),
  )
  saveCsv(rows, `laporan-keuangan-${overview.rangeStart}_${overview.rangeEnd}.csv`)
}

/** Unduh produktivitas teknisi — yang paling sering diminta untuk penilaian bulanan. */
function downloadOpsCsv(report: OperationsReport) {
  const rows = [['Teknisi', 'Work order selesai', 'Rata-rata penyelesaian (jam)']]
  report.fieldOps.technicians.forEach((t) =>
    rows.push([t.technicianName, String(t.completedCount), t.avgResolutionHours?.toFixed(2) ?? '']),
  )
  saveCsv(rows, `laporan-operasional-${report.rangeStart}_${report.rangeEnd}.csv`)
}

export function ReportsPage() {
  const toast = useToast()
  const today = useMemo(() => new Date(), [])
  const [from, setFrom] = useState(() => isoLocal(new Date(today.getFullYear(), today.getMonth(), 1)))
  const [to, setTo] = useState(() => isoLocal(today))
  const [tab, setTab] = useState<Tab>('keuangan')
  const [overview, setOverview] = useState<ReportOverview | null>(null)
  const [ops, setOps] = useState<OperationsReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // Rentang yang sedang TERPAKAI — beda dari `from`/`to` di kotak tanggal yang boleh
  // diutak-atik tanpa memuat ulang. Tab operasional memuat pakai yang terpakai ini.
  const [applied, setApplied] = useState<{ from: string; to: string }>({ from, to })

  const load = useCallback(
    (f: string, t: string, which: Tab) => {
      setLoading(true)
      setError(null)
      const fetch =
        which === 'keuangan'
          ? getReportOverview({ from: f, to: t, trailingMonths: 6 }).then(setOverview)
          : getOperationsReport({ from: f, to: t }).then(setOps)
      fetch
        .catch((e) => setError(e instanceof ApiError ? e.message : 'Gagal memuat laporan'))
        .finally(() => setLoading(false))
    },
    [],
  )

  useEffect(() => {
    // Muat tab aktif dengan rentang terpakai. Tab yang belum pernah dibuka tak menembak
    // server; setelah dimuat, pindah tab bolak-balik memakai data yang sudah ada.
    if (tab === 'keuangan' ? overview : ops) {
      setLoading(false)
      setError(null)
      return
    }
    load(applied.from, applied.to, tab)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, applied])

  const apply = () => {
    if (from > to) {
      toast.error('Tanggal awal tak boleh setelah tanggal akhir.')
      return
    }
    // Rentang berubah → kedua tab basi; buang keduanya supaya tak ada angka rentang lama
    // yang tertinggal di tab sebelah.
    setOverview(null)
    setOps(null)
    setApplied({ from, to })
  }

  const data = tab === 'keuangan' ? overview : ops

  return (
    <div className="stack" style={{ gap: '1.5rem' }}>
      <PageHeader
        title={<>Laporan &amp; analitik</>}
        subtitle="Ringkasan keuangan dan operasional tenant dalam rentang terpilih."
        actions={
          <div className="row wrap" style={{ gap: '0.5rem', alignItems: 'flex-end' }}>
            <TextField label="Dari" type="date" value={from} max={to} onChange={(_, data) => setFrom(data.value)} />
            <TextField label="Sampai" type="date" value={to} min={from} onChange={(_, data) => setTo(data.value)} />
            <Button variant="primary" onClick={apply} disabled={loading}>
              Terapkan
            </Button>
            <Button
              variant="subtle"
              onClick={() => (tab === 'keuangan' ? overview && downloadFinanceCsv(overview) : ops && downloadOpsCsv(ops))}
              disabled={!data}
              title="Unduh laporan tab ini sebagai CSV"
            >
              Ekspor CSV
            </Button>
          </div>
        }
      />

      <Segmented
        ariaLabel="Jenis laporan"
        value={tab}
        onChange={setTab}
        options={[
          { value: 'keuangan', label: 'Keuangan' },
          { value: 'operasional', label: 'Operasional' },
        ]}
      />

      {loading && !data ? (
        <div className="card row" style={{ gap: '0.6rem', justifyContent: 'center', padding: '2rem' }}>
          <Spinner /> <span className="muted">Memuat laporan…</span>
        </div>
      ) : error ? (
        <EmptyState icon={<IconChart size={28} />} title="Gagal memuat" hint={error} />
      ) : tab === 'keuangan' ? (
        overview && <ReportBody overview={overview} />
      ) : (
        ops && <OperationsBody report={ops} />
      )}
    </div>
  )
}

function ReportBody({ overview }: { overview: ReportOverview }) {
  const { finance, subscribers, churn } = overview
  // Refund baru ditampilkan bila memang pernah terjadi — tenant yang tak pernah mengembalikan
  // uang tak perlu melihat kartu "Rp 0" yang cuma menambah kebisingan.
  const hasRefund = finance.refundCount > 0

  return (
    <div className="stack" style={{ gap: '1.5rem' }}>
      {/* Angka utama: pendapatan, tunggakan, MRR, ARPU. */}
      <div className="stat-grid">
        <Stat
          label="Pendapatan tertagih"
          value={fmtRupiah(finance.revenueCollected)}
          note={
            hasRefund
              ? `${finance.paidInvoiceCount.toLocaleString('id-ID')} tagihan lunas · bersih ${fmtRupiah(finance.netRevenue)}`
              : `${finance.paidInvoiceCount.toLocaleString('id-ID')} tagihan lunas`
          }
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
        {hasRefund && (
          <Stat
            label="Pengembalian dana"
            value={fmtRupiah(finance.refundedAmount)}
            note={`${finance.refundCount.toLocaleString('id-ID')} refund selesai pada rentang`}
            accent="warn"
          />
        )}
      </div>

      {/* Perputaran langganan: yang datang vs yang pergi pada rentang. */}
      <div className="stat-grid">
        <Stat
          label="Langganan baru"
          value={`+${churn.activatedCount.toLocaleString('id-ID')}`}
          note="diaktifkan pada rentang"
        />
        <Stat
          label="Berhenti"
          value={churn.terminatedCount.toLocaleString('id-ID')}
          note="dihentikan pada rentang"
          accent={churn.terminatedCount > 0 ? 'warn' : undefined}
        />
        <Stat
          label="Pertumbuhan bersih"
          value={`${churn.netGrowth > 0 ? '+' : ''}${churn.netGrowth.toLocaleString('id-ID')}`}
          note={`dari basis ${churn.baseCount.toLocaleString('id-ID')} langganan hidup`}
          accent={churn.netGrowth < 0 ? 'crit' : undefined}
        />
        <Stat
          label="Churn"
          value={`${Number(churn.churnRatePercent).toLocaleString('id-ID', { maximumFractionDigits: 2 })}%`}
          note="berhenti ÷ basis awal rentang"
          accent={Number(churn.churnRatePercent) >= 5 ? 'crit' : undefined}
        />
      </div>

      <div className="row wrap" style={{ alignItems: 'stretch', gap: '1rem' }}>
        {/* Tren pendapatan bulanan. */}
        <div className="card grow" style={{ minWidth: 340 }}>
          <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
            <Text as="h3" weight="semibold" style={{ margin: 0 }}>Tren pendapatan bulanan</Text>
            <Text as="span" className="muted" size={200}>{overview.monthlyRevenue.length} bulan</Text>
          </div>
          <RevenueChart overview={overview} />
        </div>

        {/* Distribusi status langganan & tagihan. */}
        <div className="card grow" style={{ minWidth: 260 }}>
          <Text as="h3" weight="semibold" style={{ marginTop: 0 }}>Sebaran langganan</Text>
          <Distribution counts={subscribers.subscriptionsByStatus} labels={SUBSCRIPTION_STATUS_LABEL} empty="Belum ada langganan." />
          <Text as="h3" weight="semibold" style={{ marginBottom: '0.5rem', marginTop: '1.25rem' }}>Sebaran tagihan</Text>
          <Distribution counts={finance.statusCounts} labels={INVOICE_STATUS_LABEL} empty="Belum ada tagihan." />
        </div>
      </div>

      <div className="row wrap" style={{ alignItems: 'stretch', gap: '1rem' }}>
        <div className="card grow" style={{ minWidth: 300 }}>
          <AgingTable aging={overview.aging} />
        </div>
        <div className="card grow" style={{ minWidth: 260 }}>
          <Text as="h3" weight="semibold" style={{ marginTop: 0 }}>Pendapatan per paket</Text>
          <SliceTable slices={overview.revenueByPackage} empty="Belum ada tagihan lunas pada rentang." />
        </div>
        <div className="card grow" style={{ minWidth: 260 }}>
          <Text as="h3" weight="semibold" style={{ marginTop: 0 }}>Pendapatan per wilayah</Text>
          <SliceTable slices={overview.revenueByArea} empty="Belum ada tagihan lunas pada rentang." />
        </div>
      </div>
    </div>
  )
}

/**
 * Umur piutang — potret hari ini (bukan per ujung rentang): utang tak punya periode, ia
 * keadaan. Ember "belum jatuh tempo" sengaja ikut supaya tabelnya juga menjawab "berapa
 * yang akan masuk kalau semua bayar", bukan cuma "siapa yang telat".
 */
function AgingTable({ aging }: { aging: ReceivableAging }) {
  const total = Math.max(1, Number(aging.totalAmount))
  return (
    <div className="stack" style={{ gap: '0.75rem' }}>
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
        <Text as="h3" weight="semibold" style={{ margin: 0 }}>Umur piutang</Text>
        <Text as="span" className="muted" size={200}>per {aging.asOf}</Text>
      </div>
      <div>
        <div style={typographyStyles.subtitle1}>{fmtRupiah(aging.totalAmount)}</div>
        <div className="muted" style={typographyStyles.body2}>
          {aging.totalInvoiceCount.toLocaleString('id-ID')} tagihan belum lunas
        </div>
      </div>
      <div className="stack" style={{ gap: '0.5rem' }}>
        {aging.buckets.map((b) => {
          const late = b.bucket !== 'NOT_DUE'
          const bad = b.bucket === 'D61_90' || b.bucket === 'D90_PLUS'
          return (
            <div key={b.bucket} className="stack" style={{ gap: '0.2rem' }}>
              <div className="row" style={{ justifyContent: 'space-between' }}>
                <Text as="span" size={300} style={{ color: bad ? 'var(--critical-ink)' : late ? 'var(--warning-ink)' : undefined }}>
                  {AGING_BUCKET_LABEL[b.bucket] ?? b.bucket}
                </Text>
                <Text as="span" size={300} weight="semibold">{fmtRupiah(b.amount)}{' '}
                <Text as="span" className="muted" size={300} weight="regular">({b.invoiceCount})</Text></Text>
              </div>
              <div style={{ height: 6, background: 'var(--line)', borderRadius: 3, overflow: 'hidden' }}>
                <div
                  style={{
                    width: `${(Number(b.amount) / total) * 100}%`,
                    height: '100%',
                    background: bad ? 'var(--critical-ink)' : late ? 'var(--warning)' : 'var(--accent)',
                  }}
                />
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

/** Tabel keratan pendapatan (paket/wilayah) dengan bilah proporsi terhadap keratan terbesar. */
function SliceTable({ slices, empty }: { slices: RevenueSlice[]; empty: string }) {
  if (slices.length === 0) return <div className="muted" style={typographyStyles.body2}>{empty}</div>
  const max = Math.max(1, ...slices.map((s) => Number(s.amount)))
  return (
    <div className="stack" style={{ gap: '0.55rem' }}>
      {slices.map((s) => (
        <div key={s.label} className="stack" style={{ gap: '0.2rem' }}>
          <div className="row" style={{ justifyContent: 'space-between', gap: '0.5rem' }}>
            <Text as="span" size={300} style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {s.label}
            </Text>
            <Text as="span" size={300} weight="semibold" style={{ whiteSpace: 'nowrap' }}>
              {fmtRupiah(s.amount)}{' '}
              <Text as="span" className="muted" size={300} weight="regular">({s.subscriptionCount})</Text>
            </Text>
          </div>
          <div style={{ height: 6, background: 'var(--line)', borderRadius: 3, overflow: 'hidden' }}>
            <div style={{ width: `${(Number(s.amount) / max) * 100}%`, height: '100%', background: 'var(--accent)' }} />
          </div>
        </div>
      ))}
    </div>
  )
}

/**
 * Laporan operasional: seberapa cepat kerja diselesaikan dan seberapa patuh SLA meja
 * bantuan. Angka jam sengaja "—" saat tak ada data — nol jam itu klaim, bukan ketiadaan.
 */
function OperationsBody({ report }: { report: OperationsReport }) {
  const { fieldOps, support } = report
  const slaOk = support.slaCompliancePercent == null ? null : Number(support.slaCompliancePercent)

  return (
    <div className="stack" style={{ gap: '1.5rem' }}>
      {/* Kerja lapangan. */}
      <div className="stat-grid">
        <Stat
          label="Work order selesai"
          value={fieldOps.completedCount.toLocaleString('id-ID')}
          note="pada rentang terpilih"
        />
        <Stat
          label="MTTR gangguan"
          value={fmtHours(fieldOps.avgRepairResolutionHours)}
          note="rata-rata perbaikan dari dibuat s/d selesai"
        />
        <Stat
          label="Rata-rata penyelesaian"
          value={fmtHours(fieldOps.avgResolutionHours)}
          note="semua jenis work order"
        />
        <Stat
          label="Rata-rata mulai dikerjakan"
          value={fmtHours(fieldOps.avgResponseHours)}
          note="dari dibuat s/d teknisi berangkat"
        />
      </div>

      {/* Meja bantuan. */}
      <div className="stat-grid">
        <Stat label="Tiket masuk" value={support.openedCount.toLocaleString('id-ID')} note="dibuka pada rentang" />
        <Stat label="Tiket selesai" value={support.resolvedCount.toLocaleString('id-ID')} note="dituntaskan pada rentang" />
        <Stat
          label="Rata-rata respons pertama"
          value={fmtHours(support.avgFirstResponseHours)}
          note={`${support.responseBreachedCount.toLocaleString('id-ID')} lewat tenggat respons`}
          accent={support.responseBreachedCount > 0 ? 'warn' : undefined}
        />
        <Stat
          label="Kepatuhan SLA"
          value={slaOk == null ? '—' : `${slaOk.toLocaleString('id-ID', { maximumFractionDigits: 2 })}%`}
          note={`${support.resolutionBreachedCount.toLocaleString('id-ID')} selesai melewati tenggat`}
          accent={slaOk != null && slaOk < 90 ? 'crit' : undefined}
        />
      </div>

      <div className="row wrap" style={{ alignItems: 'stretch', gap: '1rem' }}>
        {/* Produktivitas teknisi. */}
        <div className="card grow" style={{ minWidth: 340 }}>
          <div className="row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
            <Text as="h3" weight="semibold" style={{ margin: 0 }}>Produktivitas teknisi</Text>
            <Text as="span" className="muted" size={200}>work order selesai</Text>
          </div>
          {fieldOps.technicians.length === 0 ? (
            <div className="muted" style={{ ...typographyStyles.body2, marginTop: '0.75rem' }}>
              Belum ada work order selesai pada rentang ini.
            </div>
          ) : (
            <>
              <Table className="table" style={{ marginTop: '0.75rem' }}><TableHeader><TableRow ><TableHeaderCell >Teknisi</TableHeaderCell>
              <TableHeaderCell style={{ textAlign: 'right' }}>Selesai</TableHeaderCell>
              <TableHeaderCell style={{ textAlign: 'right' }}>Rata-rata</TableHeaderCell></TableRow></TableHeader>
              <TableBody>{fieldOps.technicians.map((t) => (
                <TableRow key={t.technicianId}><TableCell >{t.technicianName}</TableCell>
                <TableCell style={{ textAlign: 'right' }}><Text as="span" weight="semibold">{t.completedCount.toLocaleString('id-ID')}</Text></TableCell>
                <TableCell style={{ textAlign: 'right' }}>{fmtHours(t.avgResolutionHours)}</TableCell></TableRow>
              ))}</TableBody></Table>
              {/* Wajib dibaca sebelum menjumlah kolomnya. */}
              <div className="muted" style={{ ...typographyStyles.caption1, marginTop: '0.5rem' }}>
                Work order yang dikerjakan beberapa teknisi dihitung untuk masing-masing, jadi jumlah kolom
                ini bisa melebihi {fieldOps.completedCount.toLocaleString('id-ID')} work order.
              </div>
            </>
          )}
        </div>

        {/* Sebaran jenis pekerjaan & kategori keluhan. */}
        <div className="card grow" style={{ minWidth: 260 }}>
          <Text as="h3" weight="semibold" style={{ marginTop: 0 }}>Jenis work order</Text>
          <Distribution counts={fieldOps.completedByType} labels={TYPE_LABEL} empty="Belum ada work order selesai." />
          <Text as="h3" weight="semibold" style={{ marginBottom: '0.5rem', marginTop: '1.25rem' }}>Kategori keluhan</Text>
          <Distribution counts={support.openedByCategory} labels={TICKET_CATEGORY_LABEL} empty="Belum ada tiket masuk." />
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
            <div className="muted" style={{ ...typographyStyles.caption2, whiteSpace: 'nowrap' }}>
              {Number(p.revenue) >= 1_000_000
                ? `${(Number(p.revenue) / 1_000_000).toLocaleString('id-ID', { maximumFractionDigits: 1 })}jt`
                : Math.round(Number(p.revenue) / 1000).toLocaleString('id-ID') + 'rb'}
            </div>
            <div
              title={
                `${fmtMonth(p.month)}: ${fmtRupiah(p.revenue)} (${p.paidInvoiceCount} lunas)` +
                (Number(p.refunded) > 0
                  ? ` · dikembalikan ${fmtRupiah(p.refunded)} → bersih ${fmtRupiah(String(Number(p.revenue) - Number(p.refunded)))}`
                  : '')
              }
              style={{
                width: '100%',
                maxWidth: 44,
                height: `${Math.max(2, heightPct)}%`,
                minHeight: 2,
                background: 'var(--accent)',
                borderRadius: '4px 4px 0 0',
                transition: 'height 0.2s',
                position: 'relative',
                overflow: 'hidden',
              }}
            >
              {/* Potongan bawah batang = bagian yang uangnya kembali keluar bulan itu. */}
              {Number(p.refunded) > 0 && (
                <div
                  style={{
                    position: 'absolute',
                    left: 0,
                    right: 0,
                    bottom: 0,
                    height: `${Math.min(100, (Number(p.refunded) / Math.max(1, Number(p.revenue))) * 100)}%`,
                    background: 'var(--warning)',
                  }}
                />
              )}
            </div>
            <div className="muted" style={{ ...typographyStyles.caption2, whiteSpace: 'nowrap' }}>
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
  if (entries.length === 0) return <div className="muted" style={typographyStyles.body2}>{empty}</div>
  return (
    <div className="stack" style={{ gap: '0.4rem' }}>
      {entries.map(([status, n]) => (
        <div key={status} className="row" style={{ justifyContent: 'space-between' }}>
          <Text as="span" size={300}>{labels[status] ?? status}</Text>
          <Text as="span" size={300} weight="semibold">{n.toLocaleString('id-ID')}</Text>
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
      <div className="stat-value" style={typographyStyles.subtitle1}>{value}</div>
      {note && <div className="stat-note">{note}</div>}
    </div>
  )
}
