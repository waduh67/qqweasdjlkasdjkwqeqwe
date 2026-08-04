import { useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import {
  INVOICE_STATUS_LABEL,
  SUBSCRIPTION_STATUS_LABEL,
  type SubscriptionInvoiceView,
  type SubscriptionStatus,
} from '../api/platformBilling'
import {
  getMySubscription,
  renewMySubscription,
  type TenantSelfSubscriptionView,
  type UsageMetricView,
} from '../api/subscription'
import { useCan } from '../auth/useCan'
import { Badge, EmptyState, useToast } from '../components/ui'
import type { Tone } from '../components/ui'
import { IconGauge } from '../components/icons'

/**
 * Halaman langganan sisi TENANT (self-service): tenant admin melihat masa aktif & status langganan
 * aplikasinya, pemakaian (kosmetik "N/Unlimited"), riwayat tagihan, dan memperpanjang mandiri lewat
 * gateway aktif. Masa aktif bertambah saat tagihan LUNAS (bukan saat terbit). Server penegak izin
 * (`billing.subscription.*`); `canRenew` di sini hanya untuk UX.
 */

const STATUS_TONE: Record<SubscriptionStatus, Tone> = {
  ACTIVE: 'good',
  PAST_DUE: 'warning',
  SUSPENDED: 'serious',
  CANCELLED: 'neutral',
}

const INVOICE_TONE: Record<SubscriptionInvoiceView['status'], Tone> = {
  ISSUED: 'accent',
  PAID: 'good',
  OVERDUE: 'critical',
  VOID: 'neutral',
}

const fmtIdr = (n: number) => `Rp ${n.toLocaleString('id-ID')}`
const fmtDate = (iso: string | null) =>
  iso ? new Date(iso).toLocaleDateString('id-ID', { day: '2-digit', month: 'short', year: 'numeric' }) : '—'

export function SubscriptionPage() {
  const { can } = useCan()
  const toast = useToast()
  const canRenew = can('billing.subscription.renew')

  const [sub, setSub] = useState<TenantSelfSubscriptionView | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)

  const load = () =>
    getMySubscription()
      .then(setSub)
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat langganan'))
      .finally(() => setLoading(false))

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const renew = async () => {
    if (busy) return
    setBusy(true)
    try {
      const invoice = await renewMySubscription()
      await load()
      if (invoice.payUrl) {
        window.open(invoice.payUrl, '_blank', 'noopener')
        toast.success(`Tagihan ${invoice.number} terbit — lanjutkan pembayaran di tab baru`)
      } else {
        toast.success(`Tagihan ${invoice.number} terbit. Tautan bayar belum siap — hubungi admin platform.`)
      }
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memperpanjang langganan')
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <p className="muted">Memuat langganan…</p>

  if (!sub) {
    return (
      <div className="stack" style={{ maxWidth: 820, gap: '1.25rem' }}>
        <div>
          <h1 className="page-title">Langganan Aplikasi</h1>
          <p className="page-sub">Masa aktif, pemakaian, dan tagihan langganan Anda ke aplikasi.</p>
        </div>
        <EmptyState
          title="Belum berlangganan"
          hint="Langganan belum diaktifkan untuk tenant ini. Hubungi admin platform."
          icon={<IconGauge size={30} />}
        />
      </div>
    )
  }

  const outstanding = sub.invoices.find((i) => i.status === 'ISSUED' || i.status === 'OVERDUE')

  return (
    <div className="stack" style={{ maxWidth: 820, gap: '1.25rem' }}>
      <div>
        <h1 className="page-title">Langganan Aplikasi</h1>
        <p className="page-sub">Masa aktif, pemakaian, dan tagihan langganan Anda ke aplikasi.</p>
      </div>

      {/* Kartu masa aktif + status + perpanjang */}
      <div className="card stack" style={{ gap: '0.85rem' }}>
        <div className="spread" style={{ alignItems: 'flex-start', flexWrap: 'wrap', gap: '0.75rem' }}>
          <div className="stack" style={{ gap: '0.35rem' }}>
            <div className="row" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
              <Badge tone={STATUS_TONE[sub.status]}>{SUBSCRIPTION_STATUS_LABEL[sub.status]}</Badge>
              <span className="muted" style={{ fontSize: '0.85rem' }}>{fmtIdr(sub.monthlyFee)}/bulan</span>
            </div>
            <div className="row" style={{ gap: '1.5rem', flexWrap: 'wrap' }}>
              <Stat label="Masa aktif s/d" value={fmtDate(sub.activeUntil)} />
              <Stat label="Tagihan berikutnya" value={fmtDate(sub.nextInvoiceAt)} />
            </div>
          </div>
          {canRenew && sub.status !== 'CANCELLED' && (
            <button className="primary" onClick={() => void renew()} disabled={busy}>
              {busy ? 'Memproses…' : outstanding ? 'Bayar sekarang' : 'Perpanjang'}
            </button>
          )}
        </div>
        {sub.status === 'CANCELLED' && (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Langganan dibatalkan. Hubungi admin platform untuk mengaktifkan kembali.
          </p>
        )}
        {outstanding && (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Ada tagihan <strong>{outstanding.number}</strong> ({fmtIdr(outstanding.amount)}) menunggu pembayaran.
            Masa aktif bertambah setelah pembayaran <strong>LUNAS</strong>.
          </p>
        )}
      </div>

      {/* Pemakaian (kosmetik) */}
      {sub.usage.length > 0 && (
        <div className="stack" style={{ gap: '0.6rem' }}>
          <h2 style={{ margin: 0, fontSize: '1.05rem' }}>Pemakaian</h2>
          <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
            {sub.usage.map((m) => (
              <UsageCard key={m.key} metric={m} />
            ))}
          </div>
        </div>
      )}

      {/* Riwayat tagihan */}
      <div className="stack" style={{ gap: '0.6rem' }}>
        <h2 style={{ margin: 0, fontSize: '1.05rem' }}>Riwayat tagihan</h2>
        {sub.invoices.length === 0 ? (
          <p className="muted" style={{ margin: 0 }}>Belum ada tagihan.</p>
        ) : (
          <div className="stack" style={{ gap: '0.4rem' }}>
            {sub.invoices.map((inv) => (
              <InvoiceRow key={inv.id} inv={inv} />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="stack" style={{ gap: '0.1rem' }}>
      <span className="muted" style={{ fontSize: '0.78rem' }}>{label}</span>
      <strong style={{ fontSize: '1rem' }}>{value}</strong>
    </div>
  )
}

/** Kartu pemakaian kosmetik — limit null tampil "Unlimited". */
function UsageCard({ metric }: { metric: UsageMetricView }) {
  const cap = metric.limit == null ? 'Unlimited' : String(metric.limit)
  return (
    <div
      className="stack"
      style={{
        gap: '0.2rem',
        padding: '0.6rem 0.8rem',
        borderRadius: 'var(--radius-sm)',
        border: '1px solid var(--border, #2a3340)',
        minWidth: 120,
      }}
    >
      <span className="muted" style={{ fontSize: '0.78rem' }}>{metric.label}</span>
      <span style={{ fontSize: '0.95rem', fontWeight: 600 }}>
        {metric.used.toLocaleString('id-ID')} <span className="muted" style={{ fontWeight: 400 }}>/ {cap}</span>
      </span>
    </div>
  )
}

function InvoiceRow({ inv }: { inv: SubscriptionInvoiceView }) {
  const outstanding = inv.status === 'ISSUED' || inv.status === 'OVERDUE'
  return (
    <div
      className="row"
      style={{
        gap: '0.6rem',
        alignItems: 'center',
        flexWrap: 'wrap',
        padding: '0.5rem 0.6rem',
        borderRadius: 'var(--radius-sm)',
        border: '1px solid var(--border, #2a3340)',
      }}
    >
      <div className="stack" style={{ gap: '0.15rem', flex: 1, minWidth: 180 }}>
        <span className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
          <strong style={{ fontSize: '0.85rem', fontFamily: 'monospace' }}>{inv.number}</strong>
          <Badge tone={INVOICE_TONE[inv.status]}>{INVOICE_STATUS_LABEL[inv.status]}</Badge>
        </span>
        <span className="muted" style={{ fontSize: '0.78rem' }}>
          {fmtDate(inv.periodStart)}–{fmtDate(inv.periodEnd)} · jatuh tempo {fmtDate(inv.dueDate)}
          {inv.paidAt && ` · lunas ${fmtDate(inv.paidAt)}`}
        </span>
      </div>
      <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>{fmtIdr(inv.amount)}</span>
      {inv.payUrl && outstanding && (
        <a
          href={inv.payUrl}
          target="_blank"
          rel="noreferrer"
          style={{ fontSize: '0.82rem', fontWeight: 600, alignSelf: 'center', whiteSpace: 'nowrap' }}
        >
          Tautan bayar ↗
        </a>
      )}
    </div>
  )
}
