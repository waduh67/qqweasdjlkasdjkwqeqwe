import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { Ban, Copy, ExternalLink, FlaskConical, Link2, Printer, Undo2, Wallet } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import type { CustomerView } from '../api/network'
import {
  generateInvoices,
  getTaxObligation,
  listInvoices,
  listPayments,
  listRefunds,
  recordManualPayment,
  requestRefund,
  settleRefund,
  simulateInvoicePayment,
  voidInvoice,
  type InvoiceStatus,
  type InvoiceView,
  type PaymentView,
  type RefundReason,
  type RefundStatus,
  type RefundView,
  type SimulatedChargeStatus,
  type TaxObligationView,
} from '../api/billing'
import { useCan } from '../auth/useCan'
import { useAuth } from '../auth/useAuth'
import { payLink } from '../api/publicPayment'
import { Blade, DataTable, type Column, type RowAction } from '@/components/organisms'
import { CommandBar, type CommandAction } from '@/components/molecules'
import { PageHeader } from '@/components/molecules'
import { Badge, Button, EmptyState, SelectField, TextField, Toolbar, type Tone } from '@/components/atoms'
import { Modal, SearchInput } from '@/components/molecules'
import { useToast } from '@/system'
import { IconInbox, IconPlus } from '@/components/atoms/icons'
import { printInvoiceSheet } from '@/utils/invoiceSheet'

/**
 * Jeda sebelum memuat ulang daftar setelah simulasi bayar (sandbox): gateway melunasi lewat
 * webhook, jadi status baru belum ada saat responsnya kembali.
 */
const SIMULATION_SETTLE_MS = 2500

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

/**
 * Cetak/unduh PDF tagihan sepenuhnya di sisi klien — templatnya dipakai bersama portal
 * pelanggan (lihat `utils/invoiceSheet`), jadi kertas dari operator dan dari portal sama persis.
 */
function printInvoice(inv: InvoiceView, customer: CustomerLite | undefined) {
  printInvoiceSheet({
    number: inv.number,
    issuedAt: inv.issuedAt,
    dueDate: inv.dueDate,
    statusLabel: INVOICE_LABEL[inv.status],
    customerName: customer?.name ?? 'Pelanggan',
    customerCode: customer?.code ?? null,
    periodStart: inv.periodStart,
    periodEnd: inv.periodEnd,
    prorated: inv.prorated,
    proratedDays: inv.proratedDays,
    baseAmount: inv.baseAmount,
    taxAmount: inv.taxAmount,
    totalAmount: inv.amount,
    taxRate: inv.taxRate,
    paidAt: inv.paidAt,
  })
}

const INVOICE_TONE: Record<InvoiceStatus, Tone> = {
  PAID: 'good',
  ISSUED: 'warning',
  OVERDUE: 'critical',
  VOID: 'neutral',
  REFUNDED: 'accent',
}

const INVOICE_LABEL: Record<InvoiceStatus, string> = {
  PAID: 'Lunas',
  ISSUED: 'Terbit',
  OVERDUE: 'Jatuh tempo',
  VOID: 'Batal',
  REFUNDED: 'Dikembalikan',
}

const STATUS_OPTIONS: { value: InvoiceStatus | ''; label: string }[] = [
  { value: '', label: 'Semua status' },
  { value: 'ISSUED', label: 'Terbit' },
  { value: 'OVERDUE', label: 'Jatuh tempo' },
  { value: 'PAID', label: 'Lunas' },
  { value: 'VOID', label: 'Batal' },
  { value: 'REFUNDED', label: 'Dikembalikan' },
]

/** Label alasan refund untuk operator — nilainya sendiri adalah enum penyedia. */
const REFUND_REASON_LABEL: Record<RefundReason, string> = {
  REQUESTED_BY_CUSTOMER: 'Diminta pelanggan',
  DUPLICATE: 'Pembayaran ganda',
  CANCELLATION: 'Pembatalan layanan',
  SUSPECT_FRAUDULENT: 'Dugaan penipuan',
  OTHERS: 'Lainnya',
}

const REFUND_REASON_OPTIONS: RefundReason[] = [
  'REQUESTED_BY_CUSTOMER',
  'DUPLICATE',
  'CANCELLATION',
  'SUSPECT_FRAUDULENT',
  'OTHERS',
]

const REFUND_TONE: Record<RefundStatus, Tone> = {
  PENDING: 'warning',
  PROCESSING: 'accent',
  SUCCESS: 'good',
  FAILED: 'critical',
}

const REFUND_LABEL: Record<RefundStatus, string> = {
  PENDING: 'Menunggu',
  PROCESSING: 'Diproses',
  SUCCESS: 'Berhasil',
  FAILED: 'Gagal',
}

/** Tagihan menunggak: berstatus OVERDUE, atau ISSUED yang sudah lewat jatuh tempo. */
function isOutstanding(inv: InvoiceView, today: string): boolean {
  return inv.status === 'OVERDUE' || (inv.status === 'ISSUED' && inv.dueDate < today)
}

/**
 * Masih ada uang yang bisa dikembalikan: tagihan sudah lunas dan sisanya > 0. Tagihan lama
 * (sebelum ada domain refund) membawa `refundableAmount` null — diperlakukan sebagai "penuh".
 */
function refundable(inv: InvoiceView): boolean {
  if (inv.status !== 'PAID') return false
  return inv.refundableAmount == null || Number(inv.refundableAmount) > 0
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

/** Baris "label · nilai" di pratinjau tagihan (rincian nominal & tanggal). */
function DetailLine({ label, value, muted }: { label: string; value: string; muted?: boolean }) {
  return (
    <div className="spread">
      <Text as="span" className="muted" size={200}>{label}</Text>
      <Text as="span" className={muted ? 'muted' : 'tnum'} size={200}>{value}</Text>
    </div>
  )
}

/** Satu metrik ringkasan di strip atas (tunggakan, jumlah menunggak, dsb.). */
function SummaryCard({ label, value, tone }: { label: string; value: string; tone?: 'critical' | 'good' }) {
  const color = tone === 'critical' ? 'var(--critical-ink)' : tone === 'good' ? 'var(--good-ink)' : undefined
  return (
    <div className="card stack" style={{ gap: '0.2rem', flex: 1, minWidth: 160 }}>
      <Text as="span" className="muted" size={200}>{label}</Text>
      <Text as="strong" className="tnum" size={500} weight="semibold" style={{ color }}>{value}</Text>
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
  const { user } = useAuth()
  const toast = useToast()
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
  // Pengajuan refund: nominal kosong = seluruh sisa yang masih bisa dikembalikan.
  const [refundTarget, setRefundTarget] = useState<InvoiceView | null>(null)
  const [refundAmount, setRefundAmount] = useState('')
  const [refundReason, setRefundReason] = useState<RefundReason>('REQUESTED_BY_CUSTOMER')
  const [refundNote, setRefundNote] = useState('')
  // Penutupan refund MANUAL oleh operator: berhasil, atau gagal beserta alasannya.
  const [settleTarget, setSettleTarget] = useState<RefundView | null>(null)
  const [settleSuccess, setSettleSuccess] = useState(true)
  const [settleReason, setSettleReason] = useState('')
  // Pratinjau tagihan (flyout ala klik baris tabel lain), plus riwayat pembayarannya.
  const [detail, setDetail] = useState<InvoiceView | null>(null)
  const [payments, setPayments] = useState<PaymentView[]>([])
  const [refunds, setRefunds] = useState<RefundView[]>([])
  const [loadingPayments, setLoadingPayments] = useState(false)

  const canManage = can('billing.invoice.manage')
  const canPay = can('billing.payment.manage')
  const canRefund = can('billing.refund.manage')
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

  /** Buka modal refund dengan isian bersih agar sisa ketikan tagihan lain tak terbawa. */
  const openRefund = (inv: InvoiceView) => {
    setRefundAmount('')
    setRefundReason('REQUESTED_BY_CUSTOMER')
    setRefundNote('')
    setRefundTarget(inv)
  }

  /**
   * Ajukan pengembalian dana. Nominal kosong = seluruh sisa (server yang menghitungnya), jadi
   * kolomnya sengaja opsional. Baris berpenyedia otomatis ditutup callback; MANUAL menunggu
   * operator menyatakan transfer baliknya lewat [doSettleRefund].
   */
  const doRefund = async () => {
    if (!refundTarget) return
    const id = refundTarget.id
    const amount = refundAmount.trim()
    const reason = refundReason
    const note = refundNote.trim()
    setRefundTarget(null)
    await run(
      () => requestRefund(id, { amount: amount || undefined, reason, note: note || undefined }),
      'Pengembalian dana diajukan',
    )
    if (detailIdRef.current === id) loadHistory(id)
  }

  const doSettleRefund = async () => {
    if (!settleTarget) return
    const { id, invoiceId } = settleTarget
    const success = settleSuccess
    const reason = settleReason.trim()
    setSettleTarget(null)
    await run(
      () => settleRefund(id, success, reason || undefined),
      success ? 'Pengembalian dana ditutup: berhasil' : 'Pengembalian dana ditandai gagal',
    )
    if (detailIdRef.current === invoiceId) loadHistory(invoiceId)
  }

  /**
   * Simulasi sandbox: minta gateway memaksa sesi bayar jadi lunas/kedaluwarsa. Pelunasan menyusul
   * lewat webhook (asinkron) — daftar dimuat ulang lagi setelah jeda singkat agar status barunya
   * terlihat tanpa perlu refresh manual.
   */
  const doSimulate = async (inv: InvoiceView, status: SimulatedChargeStatus) => {
    const label = status === 'SUCCESS' ? 'lunas' : 'kedaluwarsa'
    await run(
      () => simulateInvoicePayment(inv.id, status),
      `Simulasi ${label} dikirim untuk ${inv.number} — status menyusul dari gateway`,
    )
    window.setTimeout(() => void reload(), SIMULATION_SETTLE_MS)
  }

  // Salin id sesi bayar penyedia — dipakai di panel Simulasi Pembayaran (/platform) untuk
  // tagihan yang sesinya perlu dipaksa lunas/kedaluwarsa secara manual.
  const copySessionId = (sessionId: string) =>
    navigator.clipboard
      ?.writeText(sessionId)
      .then(() => toast.success('Payment session ID disalin'))
      .catch(() => toast.error('Gagal menyalin payment session ID'))

  // Tautan halaman bayar publik tagihan — inilah yang dikirim operator ke pelanggan (WhatsApp dsb).
  const copyPayLink = (invoiceId: string) =>
    navigator.clipboard
      ?.writeText(payLink(user?.tenantSlug ?? '', invoiceId))
      .then(() => toast.success('Link bayar disalin'))
      .catch(() => toast.error('Gagal menyalin link bayar'))

  // Klik baris membuka pratinjau (seragam dengan tabel lain). Riwayat pembayaran
  // ditarik terpisah; `detailIdRef` membuang balasan basi bila baris cepat ditukar.
  const detailIdRef = useRef<string | null>(null)
  const loadHistory = useCallback((invoiceId: string) => {
    setLoadingPayments(true)
    // Pembayaran & pengembalian ditarik bersamaan: keduanya mengisi satu blok riwayat uang
    // di pratinjau, jadi tak ada gunanya menampilkan salah satunya lebih dulu.
    Promise.all([listPayments(invoiceId), listRefunds(invoiceId)])
      .then(([p, r]) => {
        if (detailIdRef.current !== invoiceId) return
        setPayments(p)
        setRefunds(r)
      })
      .catch(() => undefined)
      .finally(() => {
        if (detailIdRef.current === invoiceId) setLoadingPayments(false)
      })
  }, [])
  const openDetail = (inv: InvoiceView) => {
    detailIdRef.current = inv.id
    setDetail(inv)
    setPayments([])
    setRefunds([])
    loadHistory(inv.id)
  }
  const closeDetail = () => {
    detailIdRef.current = null
    setDetail(null)
    setPayments([])
    setRefunds([])
  }

  // Jaga isi pratinjau tetap terkini setelah bayar/batal memuat ulang daftar.
  useEffect(() => {
    if (!detail) return
    const fresh = invoices.find((i) => i.id === detail.id)
    if (fresh && fresh !== detail) setDetail(fresh)
  }, [invoices, detail])

  const columns: Column<InvoiceView>[] = [
    {
      key: 'number',
      header: 'Nomor',
      sortValue: (i) => i.number,
      cell: (i) => i.number,
      onCellClick: openDetail,
      inlineActions: rowActions,
    },
    {
      key: 'customer',
      header: 'Pelanggan',
      sortValue: (i) => names.get(i.customerId)?.name ?? '',
      cell: (i) => names.get(i.customerId)?.name ?? 'Pelanggan',
    },
    {
      key: 'customerCode',
      header: 'Kode pelanggan',
      sortValue: (i) => names.get(i.customerId)?.code ?? '',
      cell: (i) => names.get(i.customerId)?.code ?? <span className="muted">—</span>,
    },
    { key: 'periodStart', header: 'Mulai periode', sortValue: (i) => i.periodStart, cell: (i) => fmtDate(i.periodStart) },
    { key: 'periodEnd', header: 'Akhir periode', sortValue: (i) => i.periodEnd, cell: (i) => fmtDate(i.periodEnd) },
    { key: 'due', header: 'Jatuh tempo', sortValue: (i) => i.dueDate, cell: (i) => fmtDate(i.dueDate) },
    {
      key: 'amount',
      header: 'Jumlah',
      align: 'right',
      sortValue: (i) => Number(i.amount),
      cell: (i) => fmtRupiah(Number(i.amount)),
    },
    {
      key: 'tax',
      header: 'PPN',
      align: 'right',
      sortValue: (i) => Number(i.taxAmount),
      cell: (i) => Number(i.taxAmount) > 0 ? fmtRupiah(Number(i.taxAmount)) : <span className="muted">—</span>,
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (i) => i.status,
      cell: (i) => `${INVOICE_LABEL[i.status]}${i.prorated ? ' · Prorata' : ''}`,
    },
  ]

  // Aksi per-baris di menu `…` ala Azure DataGrid (seragam dengan Pelanggan), bukan tombol inline.
  // Cetak/Unduh PDF selalu ada; Catat bayar & Batalkan hanya untuk tagihan yang masih tertagih.
  function rowActions(i: InvoiceView): RowAction[] {
    const payable = i.status === 'ISSUED' || i.status === 'OVERDUE'
    const list: RowAction[] = [
      {
        key: 'print',
        label: 'Cetak / Unduh PDF',
        icon: <Printer size={16} />,
        onClick: () => printInvoice(i, names.get(i.customerId)),
      },
    ]
    if (payable) {
      list.push({
        key: 'open-pay',
        label: 'Buka halaman bayar',
        icon: <ExternalLink size={16} />,
        onClick: () => window.open(payLink(user?.tenantSlug ?? '', i.id), '_blank', 'noopener'),
      })
      list.push({
        key: 'copy-pay-link',
        label: 'Salin link bayar',
        icon: <Link2 size={16} />,
        onClick: () => void copyPayLink(i.id),
      })
    }
    if (payable && canPay)
      list.push({
        key: 'pay',
        label: 'Catat bayar',
        icon: <Wallet size={16} />,
        disabled: busy,
        onClick: () => {
          setPayNote('')
          setPayTarget(i)
        },
      })
    if (payable && canManage)
      list.push({ key: 'void', label: 'Batalkan', icon: <Ban size={16} />, disabled: busy, onClick: () => setVoidTarget(i) })
    // Refund hanya masuk akal untuk tagihan yang uangnya SUDAH masuk dan masih ada sisa yang
    // bisa dikembalikan (server juga menjaganya — ini sekadar menyembunyikan aksi yang pasti gagal).
    if (canRefund && refundable(i))
      list.push({
        key: 'refund',
        label: 'Kembalikan dana',
        icon: <Undo2 size={16} />,
        disabled: busy,
        onClick: () => openRefund(i),
      })
    // Alat uji: hanya muncul saat gateway Pivot dalam mode sandbox & tagihan sudah punya sesi bayar
    // (server yang menentukan lewat `simulatable`) — di produksi tak pernah tampil.
    if (i.simulatable && canManage) {
      list.push({
        key: 'simulate-success',
        label: 'Simulasi: tandai lunas',
        icon: <FlaskConical size={16} />,
        disabled: busy,
        onClick: () => void doSimulate(i, 'SUCCESS'),
      })
      list.push({
        key: 'simulate-expired',
        label: 'Simulasi: kedaluwarsakan',
        icon: <FlaskConical size={16} />,
        disabled: busy,
        onClick: () => void doSimulate(i, 'EXPIRED'),
      })
    }
    if (i.paymentSessionId) {
      list.push({
        key: 'copy-session',
        label: 'Salin payment session ID',
        icon: <Copy size={16} />,
        onClick: () => void copySessionId(i.paymentSessionId!),
      })
    }
    return list
  }

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
        <SelectField value={statusFilter} onChange={(_, data) => setStatusFilter(data.value as InvoiceStatus | '')}>
          {STATUS_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(i) => i.id}
        loading={loading}
        initialSort={{ key: 'due', dir: 'desc' }}
        presentation="resource"
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

      <Blade
        open={detail != null}
        title={detail ? `Tagihan ${detail.number}` : ''}
        subtitle={detail ? names.get(detail.customerId)?.name ?? 'Pelanggan' : undefined}
        size="sm"
        onClose={closeDetail}
        footer={
          detail && (
            <>
              <Button variant="primary" onClick={() => printInvoice(detail, names.get(detail.customerId))}>
                <Printer size={15} /> Cetak / Unduh PDF
              </Button>
              {(detail.status === 'ISSUED' || detail.status === 'OVERDUE') && canPay && (
                <Button
                  disabled={busy}
                  onClick={() => {
                    setPayNote('')
                    setPayTarget(detail)
                  }}
                >
                  Catat bayar
                </Button>
              )}
              {canRefund && refundable(detail) && (
                <Button disabled={busy} onClick={() => openRefund(detail)}>
                  <Undo2 size={15} /> Kembalikan dana
                </Button>
              )}
              <Button onClick={closeDetail}>Tutup</Button>
            </>
          )
        }
      >
        {detail && (
          <div className="stack" style={{ gap: '1rem' }}>
            <div className="row" style={{ gap: '0.3rem', flexWrap: 'wrap' }}>
              <Badge tone={INVOICE_TONE[detail.status]}>{INVOICE_LABEL[detail.status]}</Badge>
              {detail.prorated && <Badge tone="accent">prorata{detail.proratedDays ? ` ${detail.proratedDays} hari` : ''}</Badge>}
              {detail.gatewayProvider && <Badge tone="neutral">{detail.gatewayProvider}</Badge>}
            </div>

            <div className="card stack" style={{ gap: '0.4rem' }}>
              <DetailLine label="Dasar (DPP)" value={fmtRupiah(Number(detail.baseAmount))} />
              {Number(detail.taxAmount) > 0 && (
                <DetailLine
                  label={`PPN${detail.taxRate ? ` (${(Number(detail.taxRate) * 100).toFixed(2).replace(/\.?0+$/, '')}%)` : ''}`}
                  value={fmtRupiah(Number(detail.taxAmount))}
                />
              )}
              <div className="spread" style={{ borderTop: '1px solid var(--line)', paddingTop: '0.4rem' }}>
                <strong>Total</strong>
                <strong className="tnum">{fmtRupiah(Number(detail.amount))}</strong>
              </div>
              {Number(detail.refundedAmount ?? 0) > 0 && (
                <>
                  <DetailLine label="Sudah dikembalikan" value={`− ${fmtRupiah(Number(detail.refundedAmount))}`} />
                  <div className="spread">
                    <Text as="span" className="muted" size={300}>Sisa yang bisa dikembalikan</Text>
                    <Text as="span" className="tnum" size={300}>
                      {fmtRupiah(Number(detail.refundableAmount ?? 0))}
                    </Text>
                  </div>
                </>
              )}
            </div>

            <div className="stack" style={{ gap: '0.4rem' }}>
              <DetailLine label="Periode" value={`${fmtDate(detail.periodStart)} – ${fmtDate(detail.periodEnd)}`} muted />
              <DetailLine label="Tanggal terbit" value={fmtDate(detail.issuedAt.slice(0, 10))} muted />
              <DetailLine label="Jatuh tempo" value={fmtDate(detail.dueDate)} muted />
              {detail.paidAt && <DetailLine label="Dibayar" value={fmtDate(detail.paidAt.slice(0, 10))} muted />}
            </div>

            <div className="stack" style={{ gap: '0.4rem' }}>
              <Text as="strong" size={300} weight="semibold">Riwayat pembayaran</Text>
              {loadingPayments ? (
                <Text as="span" className="muted" size={300}>Memuat…</Text>
              ) : payments.length === 0 ? (
                <Text as="span" className="muted" size={300}>Belum ada pembayaran tercatat.</Text>
              ) : (
                payments.map((p) => (
                  <div key={p.id} className="card spread" style={{ gap: '0.5rem', padding: '0.5rem 0.65rem' }}>
                    <div className="stack" style={{ gap: '0.1rem' }}>
                      <span className="tnum">{fmtRupiah(Number(p.amount))}</span>
                      <Text as="span" className="muted" size={200}>
                        {p.provider}
                        {p.note ? ` · ${p.note}` : ''}
                      </Text>
                    </div>
                    <Text as="span" className="muted" size={200} style={{ whiteSpace: 'nowrap' }}>
                      {fmtDate(p.paidAt.slice(0, 10))}
                    </Text>
                  </div>
                ))
              )}
            </div>

            {refunds.length > 0 && (
              <div className="stack" style={{ gap: '0.4rem' }}>
                <Text as="strong" size={300} weight="semibold">Pengembalian dana</Text>
                {refunds.map((r) => (
                  <div key={r.id} className="card stack" style={{ gap: '0.3rem', padding: '0.5rem 0.65rem' }}>
                    <div className="spread" style={{ gap: '0.5rem' }}>
                      <div className="stack" style={{ gap: '0.1rem' }}>
                        <span className="tnum">− {fmtRupiah(Number(r.amount))}</span>
                        <Text as="span" className="muted" size={200}>
                          {REFUND_REASON_LABEL[r.reason]} · {r.provider}
                          {r.note ? ` · ${r.note}` : ''}
                        </Text>
                      </div>
                      <div className="stack" style={{ gap: '0.2rem', alignItems: 'flex-end' }}>
                        <Badge tone={REFUND_TONE[r.status]}>{REFUND_LABEL[r.status]}</Badge>
                        <Text as="span" className="muted" size={200} style={{ whiteSpace: 'nowrap' }}>
                          {fmtDate((r.completedAt ?? r.requestedAt).slice(0, 10))}
                        </Text>
                      </div>
                    </div>
                    {r.failureReason && (
                      <Text as="span" size={200} style={{ color: 'var(--critical-ink)' }}>{r.failureReason}</Text>
                    )}
                    {/* Hanya baris MANUAL yang ditutup tangan — yang berpenyedia menunggu callback. */}
                    {canRefund && r.provider === 'MANUAL' && (r.status === 'PENDING' || r.status === 'PROCESSING') && (
                      <div className="row" style={{ gap: '0.4rem' }}>
                        <Button
                          disabled={busy}
                          onClick={() => {
                            setSettleSuccess(true)
                            setSettleReason('')
                            setSettleTarget(r)
                          }}
                        >
                          Sudah ditransfer
                        </Button>
                        <Button
                          disabled={busy}
                          onClick={() => {
                            setSettleSuccess(false)
                            setSettleReason('')
                            setSettleTarget(r)
                          }}
                        >
                          Tandai gagal
                        </Button>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </Blade>

      {confirmGenerate && (
        <Modal
          title="Terbitkan tagihan"
          onClose={() => setConfirmGenerate(false)}
          footer={
            <>
              <Button onClick={() => setConfirmGenerate(false)}>Batal</Button>
              <Button variant="primary" onClick={() => void doGenerate()} disabled={busy}>Terbitkan</Button>
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
              <Button onClick={() => setPayTarget(null)}>Batal</Button>
              <Button variant="primary" onClick={() => void doPay()} disabled={busy}>Tandai lunas</Button>
            </>
          }
        >
          <div className="stack">
            <p style={{ margin: 0 }}>
              Menandai <strong>{payTarget.number}</strong> sebesar <strong>{fmtRupiah(Number(payTarget.amount))}</strong> sebagai
              lunas via pembayaran manual (mis. transfer/QRIS di luar gateway).
            </p>
            {Number(payTarget.taxAmount) > 0 && (
              <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
                Dasar {fmtRupiah(Number(payTarget.baseAmount))} + PPN {fmtRupiah(Number(payTarget.taxAmount))}.
              </Text>
            )}
            <TextField
              label="Catatan (opsional)"
              value={payNote}
              onChange={(_, data) => setPayNote(data.value)}
              placeholder="Mis. transfer BCA 5 Agu"
              autoFocus
            />
          </div>
        </Modal>
      )}

      {voidTarget && (
        <Modal
          title={`Batalkan tagihan · ${voidTarget.number}`}
          onClose={() => setVoidTarget(null)}
          footer={
            <>
              <Button onClick={() => setVoidTarget(null)}>Batal</Button>
              <Button variant="danger" onClick={() => void doVoid()} disabled={busy}>Batalkan tagihan</Button>
            </>
          }
        >
          <p style={{ margin: 0 }}>
            Membatalkan <strong>{voidTarget.number}</strong>. Tagihan yang sudah lunas tidak bisa dibatalkan.
          </p>
        </Modal>
      )}

      {refundTarget && (
        <Modal
          title={`Kembalikan dana · ${refundTarget.number}`}
          onClose={() => setRefundTarget(null)}
          footer={
            <>
              <Button onClick={() => setRefundTarget(null)}>Batal</Button>
              <Button variant="danger" onClick={() => void doRefund()} disabled={busy}>Ajukan pengembalian</Button>
            </>
          }
        >
          <div className="stack">
            <p style={{ margin: 0 }}>
              Mengembalikan uang pelanggan atas <strong>{refundTarget.number}</strong>. Sisa yang masih bisa
              dikembalikan:{' '}
              <strong>{fmtRupiah(Number(refundTarget.refundableAmount ?? refundTarget.amount))}</strong>.
            </p>
            <TextField
              label="Nominal (kosongkan untuk seluruh sisa)"
              value={refundAmount}
              onChange={(_, data) => setRefundAmount(data.value)}
              placeholder={String(Number(refundTarget.refundableAmount ?? refundTarget.amount))}
              inputMode="decimal"
              autoFocus
            />
            <SelectField
              label="Alasan"
              value={refundReason}
              onChange={(_, data) => setRefundReason(data.value as RefundReason)}
            >
              {REFUND_REASON_OPTIONS.map((r) => (
                <option key={r} value={r}>{REFUND_REASON_LABEL[r]}</option>
              ))}
            </SelectField>
            <TextField
              label="Catatan (opsional)"
              value={refundNote}
              onChange={(_, data) => setRefundNote(data.value)}
              placeholder="Mis. salah tagih periode Juli"
            />
            <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
              Tagihan yang dibayar lewat gateway dikembalikan otomatis oleh penyedia — statusnya menyusul
              lewat callback. Pembayaran manual harus ditransfer sendiri, lalu ditutup dari pratinjau tagihan.
            </Text>
          </div>
        </Modal>
      )}

      {settleTarget && (
        <Modal
          title={settleSuccess ? 'Tutup pengembalian: berhasil' : 'Tutup pengembalian: gagal'}
          onClose={() => setSettleTarget(null)}
          footer={
            <>
              <Button onClick={() => setSettleTarget(null)}>Batal</Button>
              <Button
                variant={settleSuccess ? 'primary' : 'danger'}
                onClick={() => void doSettleRefund()}
                disabled={busy}
              >
                {settleSuccess ? 'Nyatakan berhasil' : 'Tandai gagal'}
              </Button>
            </>
          }
        >
          <div className="stack">
            <p style={{ margin: 0 }}>
              {settleSuccess ? (
                <>
                  Menyatakan <strong>{fmtRupiah(Number(settleTarget.amount))}</strong> sudah benar-benar
                  ditransfer balik ke pelanggan. Tagihannya ikut ditandai dikembalikan.
                </>
              ) : (
                <>
                  Menandai pengembalian <strong>{fmtRupiah(Number(settleTarget.amount))}</strong> gagal —
                  kuotanya kembali, jadi pengajuan ulang tetap mungkin.
                </>
              )}
            </p>
            {!settleSuccess && (
              <TextField
                label="Alasan gagal"
                value={settleReason}
                onChange={(_, data) => setSettleReason(data.value)}
                placeholder="Mis. rekening tujuan tak valid"
                autoFocus
              />
            )}
          </div>
        </Modal>
      )}
    </div>
  )
}
