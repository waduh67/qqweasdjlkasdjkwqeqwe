import { useEffect, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import {
  INVOICE_STATUS_LABEL,
  SUBSCRIPTION_STATUS_LABEL,
  type SubscriptionInvoiceView,
  type SubscriptionStatus,
} from '../api/platformBilling'
import {
  getMySubscription,
  payMyInvoice,
  renewMySubscription,
  type TenantSelfSubscriptionView,
  type UsageMetricView,
} from '../api/subscription'
import { useCan } from '../auth/useCan'
import { Badge, EmptyState, useToast } from '../components/ui'
import type { Tone } from '../components/ui'
import {
  IconGauge,
  IconRoute,
  IconInventory,
  IconPackage,
  IconCustomers,
  type IconProps,
} from '../components/icons'

/**
 * Halaman langganan sisi TENANT (self-service): tenant admin melihat masa aktif & status langganan
 * aplikasinya, pemakaian (kosmetik "N/Unlimited"), riwayat tagihan, dan memperpanjang mandiri lewat
 * gateway aktif. Masa aktif bertambah saat tagihan LUNAS (bukan saat terbit). Server penegak izin
 * (`billing.subscription.*`); `canRenew` di sini hanya untuk UX.
 *
 * Model saat ini FLAT (satu harga, tanpa tier). Rencana plan bertingkat (Starter/Business/Pro +
 * Upgrade) sengaja ditunda — lihat docs/saas-subscription.md.
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

const METRIC_ICON: Record<string, (p: IconProps) => ReactNode> = {
  olt: IconRoute,
  odc: IconInventory,
  odp: IconPackage,
  customer: IconCustomers,
}

/** Opsi bayar di muka (bulan) — cermin batas server (renew 1..12). */
const PREPAY_OPTIONS = [1, 3, 6, 12]

const fmtIdr = (n: number) => `Rp ${n.toLocaleString('id-ID')}`
const fmtDate = (iso: string | null) =>
  iso ? new Date(iso).toLocaleDateString('id-ID', { day: '2-digit', month: 'short', year: 'numeric' }) : '—'

/** Sisa hari masa aktif (dibulatkan ke atas), null bila belum ada masa aktif / sudah lewat. */
function daysLeft(activeUntil: string | null): number | null {
  if (!activeUntil) return null
  const ms = new Date(activeUntil).getTime() - Date.now()
  return ms <= 0 ? 0 : Math.ceil(ms / 86_400_000)
}

/** Fraksi periode berjalan yang sudah terpakai (0–1) untuk bar masa aktif; null bila tak bisa dihitung. */
function periodElapsed(start: string | null, end: string | null): number | null {
  if (!start || !end) return null
  const s = new Date(start).getTime()
  const e = new Date(end).getTime()
  if (e <= s) return null
  return Math.min(1, Math.max(0, (Date.now() - s) / (e - s)))
}

export function SubscriptionPage() {
  const { can } = useCan()
  const toast = useToast()
  const canRenew = can('billing.subscription.renew')

  const [sub, setSub] = useState<TenantSelfSubscriptionView | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [months, setMonths] = useState(1)

  const load = () =>
    getMySubscription()
      .then(setSub)
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat langganan'))
      .finally(() => setLoading(false))

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Perpanjang HANYA menerbitkan tagihan; pembayaran dilakukan lewat tombol "Bayar" per-tagihan di
  // Riwayat tagihan (tak lagi membuka tab bayar otomatis dari sini).
  const renew = async () => {
    if (busy) return
    setBusy(true)
    try {
      const invoice = await renewMySubscription(months)
      await load()
      toast.success(`Tagihan ${invoice.number} terbit — bayar lewat tombol Bayar di Riwayat tagihan.`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memperpanjang langganan')
    } finally {
      setBusy(false)
    }
  }

  // Bayar satu tagihan tertunggak: server charge ulang bila belum ada tautan, lalu buka di tab baru.
  const pay = async (inv: SubscriptionInvoiceView) => {
    if (busy) return
    setBusy(true)
    try {
      const updated = await payMyInvoice(inv.id)
      await load()
      if (updated.payUrl) {
        window.open(updated.payUrl, '_blank', 'noopener')
        toast.success(`Membuka pembayaran ${updated.number} di tab baru`)
      } else {
        toast.error('Tautan bayar belum siap — hubungi admin platform.')
      }
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyiapkan pembayaran')
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <p className="muted">Memuat langganan…</p>

  if (!sub) {
    return (
      <div className="stack" style={{ gap: '1.25rem' }}>
        <Header />
        <EmptyState
          title="Belum berlangganan"
          hint="Langganan belum diaktifkan untuk tenant ini. Hubungi admin platform."
          icon={<IconGauge size={30} />}
        />
      </div>
    )
  }

  const outstanding = sub.invoices.find((i) => i.status === 'ISSUED' || i.status === 'OVERDUE')
  const remaining = daysLeft(sub.activeUntil)
  const expiringSoon = remaining != null && remaining <= 7
  const elapsed = periodElapsed(sub.currentPeriodStart, sub.activeUntil)
  const canPrepay = canRenew && sub.status !== 'CANCELLED' && !outstanding
  const renewLabel = busy ? 'Memproses…' : `Perpanjang ${months} bulan`

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <Header />

      {/* Hero: identitas paket + masa aktif + CTA */}
      <div
        style={{
          border: '1px solid var(--border)',
          borderRadius: 'var(--radius-lg)',
          background: 'linear-gradient(135deg, var(--accent-soft), var(--surface) 62%)',
          boxShadow: 'var(--shadow-md)',
          padding: '1.6rem 1.75rem',
          display: 'grid',
          gridTemplateColumns: 'minmax(220px, 1fr) minmax(240px, 1.3fr) auto',
          gap: '1.5rem',
          alignItems: 'center',
        }}
      >
        <div className="stack" style={{ gap: '0.5rem' }}>
          <span className="muted" style={{ fontSize: '0.78rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
            Paket aktif
          </span>
          <div className="row" style={{ gap: '0.5rem', alignItems: 'baseline', flexWrap: 'wrap' }}>
            <strong style={{ fontSize: '1.9rem', lineHeight: 1 }}>{fmtIdr(sub.monthlyFee)}</strong>
            <span className="muted" style={{ fontSize: '0.9rem' }}>/ bulan</span>
          </div>
          <div>
            <Badge tone={STATUS_TONE[sub.status]}>{SUBSCRIPTION_STATUS_LABEL[sub.status]}</Badge>
          </div>
        </div>

        <div className="stack" style={{ gap: '0.5rem' }}>
          <div className="spread" style={{ alignItems: 'baseline', gap: '0.5rem' }}>
            <span className="muted" style={{ fontSize: '0.82rem' }}>Masa aktif s/d</span>
            <strong style={{ fontSize: '0.95rem' }}>{fmtDate(sub.activeUntil)}</strong>
          </div>
          {elapsed != null && (
            <div style={{ height: 8, borderRadius: 999, background: 'var(--surface-2)', overflow: 'hidden' }}>
              <div
                style={{
                  height: '100%',
                  width: `${Math.round(elapsed * 100)}%`,
                  borderRadius: 999,
                  background: expiringSoon ? 'var(--warning)' : 'var(--accent)',
                }}
              />
            </div>
          )}
          {remaining != null && (
            <span style={{ fontSize: '0.85rem', color: expiringSoon ? 'var(--warning-ink)' : 'var(--muted)' }}>
              {remaining === 0 ? 'Habis hari ini' : <><strong style={{ color: 'var(--text)' }}>{remaining}</strong> hari lagi</>}
            </span>
          )}
        </div>

        <div className="stack" style={{ gap: '0.5rem', alignItems: 'stretch', minWidth: 190 }}>
          {canPrepay && (
            <div className="row" style={{ gap: '0.3rem', justifyContent: 'flex-end', flexWrap: 'wrap' }}>
              {PREPAY_OPTIONS.map((m) => (
                <button
                  key={m}
                  className={m === months ? 'primary' : 'ghost'}
                  onClick={() => setMonths(m)}
                  disabled={busy}
                  style={{ padding: '0.28rem 0.5rem', fontSize: '0.76rem', minWidth: 44 }}
                >
                  {m} bln
                </button>
              ))}
            </div>
          )}
          {canRenew && sub.status !== 'CANCELLED' && (
            <button className="primary" onClick={() => void renew()} disabled={busy} style={{ padding: '0.6rem 1.1rem' }}>
              {renewLabel}
            </button>
          )}
          <span className="muted" style={{ fontSize: '0.75rem', textAlign: 'center' }}>
            {canPrepay ? (
              <>Total <strong style={{ color: 'var(--text)' }}>{fmtIdr(sub.monthlyFee * months)}</strong> · masa aktif +{months} bln</>
            ) : (
              <>Tagihan berikutnya {fmtDate(sub.nextInvoiceAt)}</>
            )}
          </span>
        </div>
      </div>

      {/* Pemakaian sebagai kartu-kartu (kosmetik: selalu Unlimited) */}
      {sub.usage.length > 0 && (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
            gap: '0.9rem',
          }}
        >
          {sub.usage.map((m) => (
            <UsageCard key={m.key} metric={m} />
          ))}
        </div>
      )}

      {/* Dua kolom: riwayat tagihan + panduan perpanjangan */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
          gap: '1.25rem',
          alignItems: 'start',
        }}
      >
        <div className="card stack" style={{ gap: '0.7rem', gridColumn: 'span 1' }}>
          <div className="spread" style={{ alignItems: 'baseline' }}>
            <h2 style={{ margin: 0, fontSize: '1.05rem' }}>Riwayat tagihan</h2>
            <span className="muted" style={{ fontSize: '0.8rem' }}>{sub.invoices.length} tagihan</span>
          </div>
          {sub.invoices.length === 0 ? (
            <p className="muted" style={{ margin: 0 }}>Belum ada tagihan.</p>
          ) : (
            <div className="stack" style={{ gap: '0.4rem' }}>
              {sub.invoices.map((inv) => (
                <InvoiceRow key={inv.id} inv={inv} onPay={pay} busy={busy} />
              ))}
            </div>
          )}
        </div>

        <div className="stack" style={{ gap: '1rem' }}>
          {outstanding && (
            <div
              className="stack"
              style={{
                gap: '0.35rem',
                padding: '0.9rem 1rem',
                borderRadius: 'var(--radius)',
                border: '1px solid var(--warning)',
                background: 'color-mix(in srgb, var(--warning) 10%, var(--surface))',
              }}
            >
              <strong style={{ fontSize: '0.9rem' }}>Ada tagihan menunggu pembayaran</strong>
              <span className="muted" style={{ fontSize: '0.83rem' }}>
                {outstanding.number} · {fmtIdr(outstanding.amount)}. Klik tombol <strong>Bayar</strong> di Riwayat
                tagihan. Masa aktif bertambah setelah pembayaran <strong>LUNAS</strong>.
              </span>
            </div>
          )}

          <div className="card stack" style={{ gap: '0.8rem' }}>
            <h2 style={{ margin: 0, fontSize: '1.05rem' }}>Cara perpanjangan</h2>
            <Step n={1} title="Pilih durasi">
              Pilih <strong>1 / 3 / 6 / 12 bulan</strong> lalu klik <strong>Perpanjang</strong> — tagihan sejumlah itu terbit.
            </Step>
            <Step n={2} title="Bayar">
              Klik <strong>Bayar</strong> pada tagihan di <strong>Riwayat tagihan</strong> — tab pembayaran gateway terbuka.
            </Step>
            <Step n={3} title="Masa aktif bertambah">
              Setelah pembayaran <strong>LUNAS</strong>, masa aktif memanjang sesuai jumlah bulan — menumpuk bila belum habis.
            </Step>
            <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
              Tak perlu ditunggu: tagihan bulanan terbit otomatis menjelang masa aktif habis. Perpanjang di sini
              hanya bila ingin membayar lebih awal / beberapa bulan sekaligus.
            </p>
            {sub.status === 'CANCELLED' && (
              <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
                Langganan dibatalkan. Hubungi admin platform untuk mengaktifkan kembali.
              </p>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

function Header() {
  return (
    <div>
      <h1 className="page-title">Langganan Aplikasi</h1>
      <p className="page-sub">Masa aktif, pemakaian, dan tagihan langganan Anda ke aplikasi.</p>
    </div>
  )
}

/**
 * Kartu pemakaian bergaya meter. Batas kosmetik: [limit] selalu null saat ini → tampil
 * "Unlimited" dengan track penuh redup. Bila kelak berbatas, fill jadi proporsional.
 */
function UsageCard({ metric }: { metric: UsageMetricView }) {
  const unlimited = metric.limit == null
  const pct = unlimited ? 100 : Math.min(100, Math.round((metric.used / Math.max(1, metric.limit!)) * 100))
  const cap = unlimited ? 'Unlimited' : metric.limit!.toLocaleString('id-ID')
  const Icon = METRIC_ICON[metric.key] ?? IconGauge
  return (
    <div className="card stack" style={{ gap: '0.65rem' }}>
      <div className="row" style={{ gap: '0.6rem', alignItems: 'center' }}>
        <span
          style={{
            display: 'grid',
            placeItems: 'center',
            width: 34,
            height: 34,
            borderRadius: 'var(--radius-sm)',
            background: 'var(--accent-soft)',
            color: 'var(--accent)',
            flexShrink: 0,
          }}
        >
          <Icon size={18} />
        </span>
        <span className="muted" style={{ fontSize: '0.85rem' }}>{metric.label}</span>
      </div>
      <div className="row" style={{ gap: '0.35rem', alignItems: 'baseline' }}>
        <strong style={{ fontSize: '1.5rem', lineHeight: 1 }}>{metric.used.toLocaleString('id-ID')}</strong>
        <span className="muted" style={{ fontSize: '0.82rem' }}>/ {cap}</span>
      </div>
      <div style={{ height: 6, borderRadius: 999, background: 'var(--surface-2)', overflow: 'hidden' }}>
        <div
          style={{
            height: '100%',
            width: `${pct}%`,
            borderRadius: 999,
            background: unlimited ? 'var(--accent-soft)' : 'var(--accent)',
          }}
        />
      </div>
    </div>
  )
}

function Step({ n, title, children }: { n: number; title: string; children: ReactNode }) {
  return (
    <div className="row" style={{ gap: '0.7rem', alignItems: 'flex-start' }}>
      <span
        style={{
          display: 'grid',
          placeItems: 'center',
          width: 24,
          height: 24,
          borderRadius: 999,
          background: 'var(--accent)',
          color: 'var(--accent-ink)',
          fontSize: '0.78rem',
          fontWeight: 700,
          flexShrink: 0,
        }}
      >
        {n}
      </span>
      <div className="stack" style={{ gap: '0.1rem' }}>
        <strong style={{ fontSize: '0.85rem' }}>{title}</strong>
        <span className="muted" style={{ fontSize: '0.82rem' }}>{children}</span>
      </div>
    </div>
  )
}

function InvoiceRow({
  inv,
  onPay,
  busy,
}: {
  inv: SubscriptionInvoiceView
  onPay: (inv: SubscriptionInvoiceView) => void
  busy: boolean
}) {
  const outstanding = inv.status === 'ISSUED' || inv.status === 'OVERDUE'
  return (
    <div
      className="row"
      style={{
        gap: '0.6rem',
        alignItems: 'center',
        flexWrap: 'wrap',
        padding: '0.6rem 0.7rem',
        borderRadius: 'var(--radius-sm)',
        border: '1px solid var(--border)',
        background: 'var(--surface-2)',
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
      {outstanding && (
        <button
          className="primary"
          onClick={() => onPay(inv)}
          disabled={busy}
          style={{ fontSize: '0.8rem', fontWeight: 600, padding: '0.35rem 0.7rem', whiteSpace: 'nowrap' }}
        >
          Bayar ↗
        </button>
      )}
    </div>
  )
}
