import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Ban, Copy, FlaskConical, Printer, Wallet } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import type { CustomerView } from '../api/network'
import {
  generateInvoices,
  getTaxObligation,
  listInvoices,
  listPayments,
  recordManualPayment,
  simulateInvoicePayment,
  voidInvoice,
  type InvoiceStatus,
  type InvoiceView,
  type PaymentView,
  type SimulatedChargeStatus,
  type TaxObligationView,
} from '../api/billing'
import { useCan } from '../auth/useCan'
import { Blade, DataTable, type Column, type RowAction } from '@/components/organisms'
import { CommandBar, type CommandAction } from '@/components/molecules'
import { PageHeader } from '@/components/molecules'
import { Badge, Button, EmptyState, SelectField, TextField, Toolbar, type Tone } from '@/components/atoms'
import { Modal, SearchInput } from '@/components/molecules'
import { useToast } from '@/system'
import { IconInbox, IconPlus } from '@/components/atoms/icons'

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

/** Escape teks pengguna sebelum disisipkan ke HTML cetak (hindari HTML injection). */
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * Cetak/unduh PDF tagihan sepenuhnya di sisi klien (tak ada endpoint PDF di server):
 * merakit dokumen HTML rapi ke dalam iframe tersembunyi lalu memanggil `print()` —
 * dialog cetak browser memberi opsi "Simpan sebagai PDF". Nilai dari pengguna di-escape.
 */
function printInvoice(inv: InvoiceView, customer: CustomerLite | undefined) {
  const base = Number(inv.baseAmount)
  const tax = Number(inv.taxAmount)
  const total = Number(inv.amount)
  const taxPct = inv.taxRate ? `${(Number(inv.taxRate) * 100).toFixed(2).replace(/\.?0+$/, '')}%` : null
  const custName = escapeHtml(customer?.name ?? 'Pelanggan')
  const custCode = customer?.code ? escapeHtml(customer.code) : null
  const row = (label: string, value: string, strong = false) =>
    `<tr><td class="lbl">${label}</td><td class="val"${strong ? ' style="font-weight:700"' : ''}>${value}</td></tr>`

  const html = `<!doctype html><html lang="id"><head><meta charset="utf-8">
<title>Tagihan ${escapeHtml(inv.number)}</title>
<style>
  * { box-sizing: border-box; }
  body { font-family: -apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif; color: #1a1a1a; margin: 0; padding: 32px; font-size: 13px; }
  .head { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 2px solid #1a1a1a; padding-bottom: 12px; margin-bottom: 20px; }
  h1 { font-size: 22px; margin: 0; letter-spacing: 0.5px; }
  .num { font-family: monospace; font-size: 14px; margin-top: 4px; color: #555; }
  .meta { text-align: right; font-size: 12px; color: #555; line-height: 1.6; }
  .party { margin-bottom: 20px; }
  .party .h { font-size: 11px; text-transform: uppercase; letter-spacing: 0.6px; color: #888; margin-bottom: 3px; }
  .party .n { font-size: 15px; font-weight: 600; }
  table { width: 100%; border-collapse: collapse; }
  .amt { margin-top: 8px; }
  .amt td { padding: 7px 0; border-bottom: 1px solid #eee; }
  .amt td.lbl { color: #555; }
  .amt td.val { text-align: right; font-variant-numeric: tabular-nums; }
  .amt tr.total td { border-top: 2px solid #1a1a1a; border-bottom: none; font-size: 16px; padding-top: 12px; }
  .foot { margin-top: 28px; font-size: 11px; color: #999; border-top: 1px solid #eee; padding-top: 10px; }
  .status { display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: 11px; font-weight: 600; border: 1px solid #ccc; }
</style></head><body>
  <div class="head">
    <div><h1>TAGIHAN</h1><div class="num">${escapeHtml(inv.number)}</div></div>
    <div class="meta">
      <div>Tanggal terbit: <strong>${fmtDate(inv.issuedAt.slice(0, 10))}</strong></div>
      <div>Jatuh tempo: <strong>${fmtDate(inv.dueDate)}</strong></div>
      <div>Status: <span class="status">${INVOICE_LABEL[inv.status]}</span></div>
    </div>
  </div>
  <div class="party">
    <div class="h">Ditagihkan kepada</div>
    <div class="n">${custName}</div>
    ${custCode ? `<div style="color:#555">${custCode}</div>` : ''}
  </div>
  <table><tbody>
    ${row('Periode layanan', `${fmtDate(inv.periodStart)} – ${fmtDate(inv.periodEnd)}`)}
    ${inv.prorated ? row('Prorata', `${inv.proratedDays ?? '—'} hari`) : ''}
  </tbody></table>
  <table class="amt"><tbody>
    ${row('Dasar pengenaan (DPP)', fmtRupiah(base))}
    ${tax > 0 ? row(`PPN${taxPct ? ` (${taxPct})` : ''}`, fmtRupiah(tax)) : ''}
    <tr class="total"><td class="lbl">Total tagihan</td><td class="val">${fmtRupiah(total)}</td></tr>
  </tbody></table>
  ${inv.paidAt ? `<p style="margin-top:16px;color:#128a3a;font-weight:600">Lunas pada ${fmtDate(inv.paidAt.slice(0, 10))}</p>` : ''}
  <div class="foot">Dokumen ini dibuat otomatis oleh sistem. Nomor tagihan: ${escapeHtml(inv.number)}.</div>
</body></html>`

  const iframe = document.createElement('iframe')
  iframe.setAttribute('aria-hidden', 'true')
  iframe.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;'
  iframe.srcdoc = html
  iframe.onload = () => {
    const win = iframe.contentWindow
    if (!win) return
    win.focus()
    win.print()
    // Bersihkan setelah dialog cetak selesai; timeout jadi jaring pengaman lintas-browser.
    win.onafterprint = () => iframe.remove()
    setTimeout(() => {
      if (document.body.contains(iframe)) iframe.remove()
    }, 60_000)
  }
  document.body.appendChild(iframe)
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

/** Baris "label · nilai" di pratinjau tagihan (rincian nominal & tanggal). */
function DetailLine({ label, value, muted }: { label: string; value: string; muted?: boolean }) {
  return (
    <div className="spread">
      <span className="muted" style={{ fontSize: '0.85rem' }}>{label}</span>
      <span className={muted ? 'muted' : 'tnum'} style={{ fontSize: '0.85rem' }}>{value}</span>
    </div>
  )
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
  // Pratinjau tagihan (flyout ala klik baris tabel lain), plus riwayat pembayarannya.
  const [detail, setDetail] = useState<InvoiceView | null>(null)
  const [payments, setPayments] = useState<PaymentView[]>([])
  const [loadingPayments, setLoadingPayments] = useState(false)

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

  // Klik baris membuka pratinjau (seragam dengan tabel lain). Riwayat pembayaran
  // ditarik terpisah; `detailIdRef` membuang balasan basi bila baris cepat ditukar.
  const detailIdRef = useRef<string | null>(null)
  const openDetail = (inv: InvoiceView) => {
    detailIdRef.current = inv.id
    setDetail(inv)
    setPayments([])
    setLoadingPayments(true)
    listPayments(inv.id)
      .then((p) => {
        if (detailIdRef.current === inv.id) setPayments(p)
      })
      .catch(() => undefined)
      .finally(() => {
        if (detailIdRef.current === inv.id) setLoadingPayments(false)
      })
  }
  const closeDetail = () => {
    detailIdRef.current = null
    setDetail(null)
    setPayments([])
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
  ]

  // Aksi per-baris di menu `…` ala Azure DataGrid (seragam dengan Pelanggan), bukan tombol inline.
  // Cetak/Unduh PDF selalu ada; Catat bayar & Batalkan hanya untuk tagihan yang masih tertagih.
  const rowActions = (i: InvoiceView): RowAction[] => {
    const payable = i.status === 'ISSUED' || i.status === 'OVERDUE'
    const list: RowAction[] = [
      {
        key: 'print',
        label: 'Cetak / Unduh PDF',
        icon: <Printer size={16} />,
        onClick: () => printInvoice(i, names.get(i.customerId)),
      },
    ]
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
        onRowClick={openDetail}
        rowActions={rowActions}
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
            </div>

            <div className="stack" style={{ gap: '0.4rem' }}>
              <DetailLine label="Periode" value={`${fmtDate(detail.periodStart)} – ${fmtDate(detail.periodEnd)}`} muted />
              <DetailLine label="Tanggal terbit" value={fmtDate(detail.issuedAt.slice(0, 10))} muted />
              <DetailLine label="Jatuh tempo" value={fmtDate(detail.dueDate)} muted />
              {detail.paidAt && <DetailLine label="Dibayar" value={fmtDate(detail.paidAt.slice(0, 10))} muted />}
            </div>

            <div className="stack" style={{ gap: '0.4rem' }}>
              <strong style={{ fontSize: '0.9rem' }}>Riwayat pembayaran</strong>
              {loadingPayments ? (
                <span className="muted" style={{ fontSize: '0.85rem' }}>Memuat…</span>
              ) : payments.length === 0 ? (
                <span className="muted" style={{ fontSize: '0.85rem' }}>Belum ada pembayaran tercatat.</span>
              ) : (
                payments.map((p) => (
                  <div key={p.id} className="card spread" style={{ gap: '0.5rem', padding: '0.5rem 0.65rem' }}>
                    <div className="stack" style={{ gap: '0.1rem' }}>
                      <span className="tnum">{fmtRupiah(Number(p.amount))}</span>
                      <span className="muted" style={{ fontSize: '0.78rem' }}>
                        {p.provider}
                        {p.note ? ` · ${p.note}` : ''}
                      </span>
                    </div>
                    <span className="muted" style={{ fontSize: '0.78rem', whiteSpace: 'nowrap' }}>
                      {fmtDate(p.paidAt.slice(0, 10))}
                    </span>
                  </div>
                ))
              )}
            </div>
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
              <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
                Dasar {fmtRupiah(Number(payTarget.baseAmount))} + PPN {fmtRupiah(Number(payTarget.taxAmount))}.
              </p>
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
    </div>
  )
}
