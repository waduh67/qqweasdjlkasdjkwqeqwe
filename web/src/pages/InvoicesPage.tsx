import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import type { CustomerView } from '../api/network'
import {
  generateInvoices,
  getTaxObligation,
  listInvoices,
  recordManualPayment,
  voidInvoice,
  type InvoiceStatus,
  type InvoiceView,
  type TaxObligationView,
} from '../api/billing'
import { useCan } from '../auth/useCan'
import { DataTable, type Column } from '../components/DataTable'
import { CommandBar, type CommandAction } from '../components/CommandBar'
import { PageHeader } from '../components/PageHeader'
import { Badge, EmptyState, Modal, SearchInput, Toolbar, useToast, type Tone } from '../components/ui'
import { IconInbox, IconPlus } from '../components/icons'

/** Rupiah ringkas dari nilai numerik, mis. "Rp 150.000". */
function fmtRupiah(n: number): string {
  return `Rp ${n.toLocaleString('id-ID')}`
}

/** LocalDate "YYYY-MM-DD" → "15 Jul 2026"; "—" bila kosong. */
function fmtDate(localDate: string | null): string {
  if (!localDate) return '—'
  const d = new Date(`${localDate}T00:00:00`)
  return Number.isNaN(d.getTime())
    ? localDate
    : d.toLocaleDateString('id-ID', { day: '2-digit', month: 'short', year: 'numeric' })
}

/** Tanggal lokal hari ini "YYYY-MM-DD" untuk membandingkan jatuh tempo (leksikografis). */
function todayLocalDate(): string {
  const n = new Date()
  return `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, '0')}-${String(n.getDate()).padStart(2, '0')}`
}

const INVOICE_TONE: Record<InvoiceStatus, Tone> = {
  PAID: 'good',
  ISSUED: 'warning',
  OVERDUE: 'critical',
  VOID: 'neutral',
}

const INVOICE_LABEL: Record<InvoiceStatus, string> = {
  PAID: 'Lunas',
  ISSUED: 'Terbit',
  OVERDUE: 'Jatuh tempo',
  VOID: 'Batal',
}

const STATUS_OPTIONS: { value: InvoiceStatus | ''; label: string }[] = [
  { value: '', label: 'Semua status' },
  { value: 'ISSUED', label: 'Terbit' },
  { value: 'OVERDUE', label: 'Jatuh tempo' },
  { value: 'PAID', label: 'Lunas' },
  { value: 'VOID', label: 'Batal' },
]

/** Tagihan menunggak: berstatus OVERDUE, atau ISSUED yang sudah lewat jatuh tempo. */
function isOutstanding(inv: InvoiceView, today: string): boolean {
  return inv.status === 'OVERDUE' || (inv.status === 'ISSUED' && inv.dueDate < today)
}

/** Ringkas nama pelanggan per-id. Nama tak ada di [InvoiceView], jadi digabung sisi klien. */
type CustomerLite = { name: string; code: string }

/**
 * Ambil peta id→nama pelanggan bertahap. Endpoint tagihan hanya membawa `customerId`,
 * jadi nama diambil terpisah; server membatasi ukuran halaman ≤ 200 sehingga halaman
 * berikutnya ditarik sampai habis (tenant besar tetap terpetakan lengkap).
 */
async function fetchCustomerNames(): Promise<Map<string, CustomerLite>> {
  const map = new Map<string, CustomerLite>()
  let page = 0
  for (;;) {
    const res = await api.get<PageResponse<CustomerView>>(`/api/customers?size=200&page=${page}`)
    for (const c of res.content) map.set(c.id, { name: c.name, code: c.code })
    if (res.content.length === 0 || page + 1 >= res.totalPages) break
    page += 1
  }
  return map
}

/** Satu metrik ringkasan di strip atas (tunggakan, jumlah menunggak, dsb.). */
function SummaryCard({ label, value, tone }: { label: string; value: string; tone?: 'critical' | 'good' }) {
  const color = tone === 'critical' ? 'var(--critical-ink)' : tone === 'good' ? 'var(--good-ink)' : undefined
  return (
    <div className="card stack" style={{ gap: '0.2rem', flex: 1, minWidth: 160 }}>
      <span className="muted" style={{ fontSize: '0.8rem' }}>{label}</span>
      <strong className="tnum" style={{ fontSize: '1.35rem', lineHeight: 1.1, color }}>{value}</strong>
    </div>
  )
}

/**
 * Halaman Tagihan lintas-pelanggan — daftar semua tagihan tenant dengan saring status &
 * pencarian, plus aksi penerbitan massal, catat pembayaran manual, dan pembatalan.
 * Semua daftar ditarik sekali lalu disaring sisi klien agar strip ringkasan tetap
 * mencerminkan seluruh tagihan (bukan hanya yang sedang tersaring). Klik satu baris
 * membuka detail pelanggan (tab Tagihan) untuk riwayat lengkapnya.
 */
export function InvoicesPage() {
  const { can } = useCan()
  const toast = useToast()
  const navigate = useNavigate()
  const [invoices, setInvoices] = useState<InvoiceView[]>([])
  const [obligation, setObligation] = useState<TaxObligationView | null>(null)
  const [names, setNames] = useState<Map<string, CustomerLite>>(new Map())
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<InvoiceStatus | ''>('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [confirmGenerate, setConfirmGenerate] = useState(false)
  const [payTarget, setPayTarget] = useState<InvoiceView | null>(null)
  const [payNote, setPayNote] = useState('')
  const [voidTarget, setVoidTarget] = useState<InvoiceView | null>(null)

  const canManage = can('billing.invoice.manage')
  const canPay = can('billing.payment.manage')
  const canViewTax = can('billing.tax.view')

  const reload = useCallback(async () => {
    try {
      const list = await listInvoices()
      setInvoices(list)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat tagihan')
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    void reload()
  }, [reload])

  useEffect(() => {
    // Nama non-kritis: bila gagal muat, baris cukup menampilkan kode/`—`.
    let alive = true
    void fetchCustomerNames()
      .then((m) => alive && setNames(m))
      .catch(() => undefined)
    return () => {
      alive = false
    }
  }, [])

  useEffect(() => {
    // KPI pajak (PPN terkumpul + kewajiban BHP/USO) tahun berjalan — hanya bila punya izinnya,
    // dan non-kritis: gagal muat cukup menyembunyikan kartunya, tak mengganggu daftar tagihan.
    if (!canViewTax) return
    let alive = true
    void getTaxObligation()
      .then((o) => alive && setObligation(o))
      .catch(() => undefined)
    return () => {
      alive = false
    }
  }, [canViewTax])

  const today = todayLocalDate()

  const summary = useMemo(() => {
    let outstandingAmount = 0
    let outstandingCount = 0
    let paidCount = 0
    for (const inv of invoices) {
      if (isOutstanding(inv, today)) {
        outstandingAmount += Number(inv.amount)
        outstandingCount += 1
      }
      if (inv.status === 'PAID') paidCount += 1
    }
    return { outstandingAmount, outstandingCount, paidCount }
  }, [invoices, today])

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return invoices.filter((inv) => {
      if (statusFilter && inv.status !== statusFilter) return false
      if (!q) return true
      const name = names.get(inv.customerId)
      return (
        inv.number.toLowerCase().includes(q) ||
        (name?.name.toLowerCase().includes(q) ?? false) ||
        (name?.code.toLowerCase().includes(q) ?? false)
      )
    })
  }, [invoices, statusFilter, query, names])

  /** Jalankan satu aksi tagihan, muat ulang daftar, dan tampilkan toast hasilnya. */
  const run = async (fn: () => Promise<unknown>, okMsg: string) => {
    setBusy(true)
    try {
      await fn()
      await reload()
      toast.success(okMsg)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memproses tagihan')
    } finally {
      setBusy(false)
    }
  }

  const doGenerate = async () => {
    setBusy(true)
    try {
      const res = await generateInvoices()
      await reload()
      // Satu toast berisi hasil nyata — 0 pun kabar berguna (tak ada yang layak tagih periode ini).
      if (res.created > 0) toast.success(`${res.created} tagihan diterbitkan`)
      else toast.info('Tidak ada tagihan baru untuk diterbitkan')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menerbitkan tagihan')
    } finally {
      setBusy(false)
      setConfirmGenerate(false)
    }
  }

  const doPay = async () => {
    if (!payTarget) return
    const id = payTarget.id
    const note = payNote.trim()
    setPayTarget(null)
    setPayNote('')
    await run(() => recordManualPayment(id, note || undefined), 'Pembayaran dicatat')
  }

  const doVoid = async () => {
    if (!voidTarget) return
    const id = voidTarget.id
    setVoidTarget(null)
    await run(() => voidInvoice(id), 'Tagihan dibatalkan')
  }

  const columns: Column<InvoiceView>[] = [
    {
      key: 'number',
      header: 'Nomor',
      sortValue: (i) => i.number,
      cell: (i) => <span className="badge">{i.number}</span>,
    },
    {
      key: 'customer',
      header: 'Pelanggan',
      sortValue: (i) => names.get(i.customerId)?.name ?? '',
      cell: (i) => {
        const c = names.get(i.customerId)
        return (
          <div className="stack" style={{ gap: '0.15rem' }}>
            <strong>{c?.name ?? 'Pelanggan'}</strong>
            {c?.code && <span className="muted" style={{ fontSize: '0.8rem' }}>{c.code}</span>}
          </div>
        )
      },
    },
    {
      key: 'period',
      header: 'Periode',
      sortValue: (i) => i.periodStart,
      cell: (i) => (
        <span className="muted" style={{ fontSize: '0.85rem' }}>
          {fmtDate(i.periodStart)} – {fmtDate(i.periodEnd)}
        </span>
      ),
    },
    { key: 'due', header: 'Jatuh tempo', sortValue: (i) => i.dueDate, cell: (i) => fmtDate(i.dueDate) },
    {
      key: 'amount',
      header: 'Jumlah',
      align: 'right',
      sortValue: (i) => Number(i.amount),
      cell: (i) => {
        const tax = Number(i.taxAmount)
        return (
          <div className="stack" style={{ gap: '0.1rem', alignItems: 'flex-end' }}>
            <span>{fmtRupiah(Number(i.amount))}</span>
            {tax > 0 && (
              <span className="muted" style={{ fontSize: '0.75rem' }}>
                termasuk PPN {fmtRupiah(tax)}
              </span>
            )}
          </div>
        )
      },
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (i) => i.status,
      cell: (i) => (
        <div className="row" style={{ gap: '0.3rem', flexWrap: 'wrap' }}>
          <Badge tone={INVOICE_TONE[i.status]}>{INVOICE_LABEL[i.status]}</Badge>
          {i.prorated && <Badge tone="accent">prorata</Badge>}
        </div>
      ),
    },
    {
      key: 'actions',
      header: '',
      width: '1%',
      cell: (i) => {
        const payable = i.status === 'ISSUED' || i.status === 'OVERDUE'
        if (!payable || (!canPay && !canManage)) return null
        return (
          <div className="row" style={{ gap: '0.3rem', justifyContent: 'flex-end' }}>
            {canPay && (
              <button
                className="ghost"
                disabled={busy}
                onClick={(e) => {
                  e.stopPropagation()
                  setPayNote('')
                  setPayTarget(i)
                }}
              >
                Catat bayar
              </button>
            )}
            {canManage && (
              <button
                className="ghost danger"
                disabled={busy}
                onClick={(e) => {
                  e.stopPropagation()
                  setVoidTarget(i)
                }}
              >
                Batalkan
              </button>
            )}
          </div>
        )
      },
    },
  ]

  // CommandBar ala Azure: primary `+ Terbitkan tagihan` dipatok kiri, seragam dengan Pelanggan.
  const primary: CommandAction | undefined = canManage
    ? {
        key: 'generate',
        label: 'Terbitkan tagihan',
        icon: <IconPlus size={16} />,
        onClick: () => setConfirmGenerate(true),
        disabled: busy,
      }
    : undefined

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader title="Tagihan" subtitle="Daftar tagihan seluruh pelanggan — terbitkan, catat pembayaran, atau batalkan." />
      <CommandBar primary={primary} />

      <div className="row" style={{ gap: '1rem', flexWrap: 'wrap' }}>
        <SummaryCard
          label="Tunggakan"
          value={fmtRupiah(summary.outstandingAmount)}
          tone={summary.outstandingAmount > 0 ? 'critical' : 'good'}
        />
        <SummaryCard label="Tagihan menunggak" value={String(summary.outstandingCount)} />
        <SummaryCard label="Sudah lunas" value={String(summary.paidCount)} tone="good" />
        {obligation?.ppnEnabled && (
          <SummaryCard
            label={`PPN terkumpul ${obligation.from.slice(0, 4)}`}
            value={fmtRupiah(Number(obligation.ppnCollected))}
          />
        )}
        {obligation?.regulatoryEnabled && (
          <SummaryCard
            label={`Kewajiban BHP/USO ${obligation.from.slice(0, 4)}`}
            value={fmtRupiah(Number(obligation.regulatoryObligation))}
          />
        )}
      </div>

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari nomor tagihan, nama, atau kode pelanggan…" />
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value as InvoiceStatus | '')}>
          {STATUS_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(i) => i.id}
        onRowClick={(i) => navigate(`/customers/${i.customerId}`)}
        loading={loading}
        initialSort={{ key: 'due', dir: 'desc' }}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada tagihan yang cocok' : 'Belum ada tagihan'}
            hint={
              query || statusFilter
                ? 'Coba ubah kata kunci atau filter.'
                : 'Terbitkan tagihan periode berjalan untuk langganan yang aktif.'
            }
            icon={<IconInbox size={32} />}
          />
        }
      />

      {confirmGenerate && (
        <Modal
          title="Terbitkan tagihan"
          onClose={() => setConfirmGenerate(false)}
          footer={
            <>
              <button onClick={() => setConfirmGenerate(false)}>Batal</button>
              <button className="primary" onClick={() => void doGenerate()} disabled={busy}>Terbitkan</button>
            </>
          }
        >
          <p style={{ margin: 0 }}>
            Menerbitkan tagihan periode berjalan untuk semua langganan yang layak tagih. Tagihan yang sudah
            ada untuk periode ini tidak digandakan.
          </p>
        </Modal>
      )}

      {payTarget && (
        <Modal
          title={`Catat pembayaran · ${payTarget.number}`}
          onClose={() => setPayTarget(null)}
          footer={
            <>
              <button onClick={() => setPayTarget(null)}>Batal</button>
              <button className="primary" onClick={() => void doPay()} disabled={busy}>Tandai lunas</button>
            </>
          }
        >
          <div className="stack">
            <p style={{ margin: 0 }}>
              Menandai <strong>{payTarget.number}</strong> sebesar <strong>{fmtRupiah(Number(payTarget.amount))}</strong> sebagai
              lunas via pembayaran manual (mis. transfer/QRIS di luar gateway).
            </p>
            {Number(payTarget.taxAmount) > 0 && (
              <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
                Dasar {fmtRupiah(Number(payTarget.baseAmount))} + PPN {fmtRupiah(Number(payTarget.taxAmount))}.
              </p>
            )}
            <label>
              <span>Catatan (opsional)</span>
              <input
                value={payNote}
                onChange={(e) => setPayNote(e.target.value)}
                placeholder="Mis. transfer BCA 5 Agu"
                autoFocus
              />
            </label>
          </div>
        </Modal>
      )}

      {voidTarget && (
        <Modal
          title={`Batalkan tagihan · ${voidTarget.number}`}
          onClose={() => setVoidTarget(null)}
          footer={
            <>
              <button onClick={() => setVoidTarget(null)}>Batal</button>
              <button className="danger" onClick={() => void doVoid()} disabled={busy}>Batalkan tagihan</button>
            </>
          }
        >
          <p style={{ margin: 0 }}>
            Membatalkan <strong>{voidTarget.number}</strong>. Tagihan yang sudah lunas tidak bisa dibatalkan.
          </p>
        </Modal>
      )}
    </div>
  )
}
