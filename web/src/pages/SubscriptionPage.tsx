import { useEffect, useState, type ReactNode } from 'react'
import { Text } from '@fluentui/react-components'
import { Copy, FlaskConical } from 'lucide-react'
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
  simulateMyInvoicePayment,
  type SimulatedChargeStatus,
  type SubscriptionLockView,
  type TenantSelfSubscriptionView,
  type UsageMetricView,
} from '../api/subscription'
import { payLink } from '../api/publicPayment'
import { downloadTenantArchive } from '../api/tenant'
import { useCan } from '../auth/useCan'
import { useAuth } from '../auth/useAuth'
import { downloadBlob } from '@/utils/download'
import { Badge, Button, EmptyState } from '@/components/atoms'
import { useToast } from '@/system'
import { type Tone } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import {
  IconGauge,
  IconRoute,
  IconInventory,
  IconPackage,
  IconCustomers,
  type IconProps,
} from '@/components/atoms/icons'

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

/**
 * Jeda sebelum memuat ulang setelah simulasi bayar (sandbox): gateway melunasi lewat webhook,
 * jadi status baru belum tersedia saat respons simulasi kembali.
 */
const SIMULATION_SETTLE_MS = 2500

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
  const { user, readOnly, subscriptionLock, refreshSubscriptionLock } = useAuth()
  const toast = useToast()
  const canView = can('billing.subscription.view')
  const canRenew = can('billing.subscription.renew')
  const canExport = can('tenancy.data.export')

  const [sub, setSub] = useState<TenantSelfSubscriptionView | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [months, setMonths] = useState(1)
  const [exporting, setExporting] = useState(false)

  // Status kunci ikut dibaca ulang di setiap pemuatan: halaman inilah yang dibuka orang setelah
  // membayar, dan banner merah yang masih tergantung di atasnya membuat pembayaran terasa gagal.
  const load = () =>
    getMySubscription()
      .then((it) => {
        setSub(it)
        void refreshSubscriptionLock()
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat langganan'))
      .finally(() => setLoading(false))

  useEffect(() => {
    if (!canView) {
      setLoading(false)
      return
    }
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canView])

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

  // Bayar tagihan langganan lewat halaman bayar publik — surface yang sama dengan tagihan
  // pelanggan, jadi cuma ada satu jalur bayar di seluruh aplikasi.
  const payUrl = (inv: SubscriptionInvoiceView) => payLink(user?.tenantSlug ?? '', inv.id)

  // Alat uji sandbox: paksa sesi bayar tagihan jadi lunas/kedaluwarsa. Pelunasan tiba lewat
  // webhook gateway, jadi muat ulang ditunda sejenak agar status yang tampil sudah terbarui.
  const simulate = async (inv: SubscriptionInvoiceView, status: SimulatedChargeStatus) => {
    if (busy) return
    setBusy(true)
    try {
      await simulateMyInvoicePayment(inv.id, status)
      toast.success(
        `Simulasi ${status === 'SUCCESS' ? 'lunas' : 'kedaluwarsa'} dikirim untuk ${inv.number} — ` +
          'status menyusul dari gateway.',
      )
      window.setTimeout(() => void load(), SIMULATION_SETTLE_MS)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengirim simulasi pembayaran')
    } finally {
      setBusy(false)
    }
  }

  // Arsipnya di-stream dari server dan bisa besar; tak ada progres yang bisa ditampilkan
  // (panjangnya memang tak diketahui), jadi tombolnya dikunci selama unduhan berjalan agar
  // tak ada yang mengklik dua kali dan menarik salinan kedua.
  const exportData = async () => {
    if (exporting) return
    setExporting(true)
    try {
      const blob = await downloadTenantArchive()
      const today = new Date().toISOString().slice(0, 10)
      downloadBlob(blob, `netops-${user?.tenantSlug ?? 'tenant'}-${today}.zip`)
      toast.success('Arsip data tenant selesai diunduh.')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengunduh arsip data')
    } finally {
      setExporting(false)
    }
  }

  // Staf tanpa izin billing hanya sampai di sini karena kunci baca-saja melemparnya. Yang ia
  // butuhkan bukan angka langganan, melainkan tahu APA yang terjadi dan HARUS menghubungi siapa.
  if (!canView) {
    return (
      <div className="stack" style={{ gap: '1.25rem' }}>
        <Header />
        {readOnly ? (
          <LockedPanel lock={subscriptionLock} canRenew={false} />
        ) : (
          <EmptyState
            title="Akses ditolak"
            hint="Kamu tidak punya izin billing.subscription.view untuk melihat langganan aplikasi."
            icon={<IconGauge size={30} />}
          />
        )}
      </div>
    )
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

      {/* Paling atas, di atas hero: saat terkunci, inilah satu-satunya hal yang perlu dibaca. */}
      {readOnly && <LockedPanel lock={subscriptionLock} canRenew={canRenew} />}

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
          <Text as="span" className="muted" size={200} weight="semibold" style={{ textTransform: 'uppercase' }}>
            Paket aktif
          </Text>
          <div className="row" style={{ gap: '0.5rem', alignItems: 'baseline', flexWrap: 'wrap' }}>
            <Text as="strong" size={700} weight="bold">{fmtIdr(sub.monthlyFee)}</Text>
            <Text as="span" className="muted" size={300}>/ bulan</Text>
          </div>
          <div>
            <Badge tone={STATUS_TONE[sub.status]}>{SUBSCRIPTION_STATUS_LABEL[sub.status]}</Badge>
          </div>
        </div>

        <div className="stack" style={{ gap: '0.5rem' }}>
          <div className="spread" style={{ alignItems: 'baseline', gap: '0.5rem' }}>
            <Text as="span" className="muted" size={200}>Masa aktif s/d</Text>
            <Text as="strong" size={300} weight="semibold">{fmtDate(sub.activeUntil)}</Text>
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
            <Text as="span" size={300}  style={{ color: expiringSoon ? 'var(--warning-ink)' : 'var(--muted)' }} >{remaining === 0 ? 'Habis hari ini' : <><strong style={{ color: 'var(--text)' }}>{remaining}</strong> hari lagi</>}</Text>
          )}
        </div>

        <div className="stack" style={{ gap: '0.5rem', alignItems: 'stretch', minWidth: 190 }}>
          {canPrepay && (
            <div className="row" style={{ gap: '0.3rem', justifyContent: 'flex-end', flexWrap: 'wrap' }}>
              {PREPAY_OPTIONS.map((m) => (
                <Button
                  key={m}
                  variant={m === months ? 'primary' : 'subtle'}
                  onClick={() => setMonths(m)}
                  disabled={busy}
                  style={{ padding: '0.28rem 0.5rem', minWidth: 44 }}
                >
                  {m} bln
                </Button>
              ))}
            </div>
          )}
          {canRenew && sub.status !== 'CANCELLED' && (
            <Button variant="primary" onClick={() => void renew()} disabled={busy} style={{ padding: '0.6rem 1.1rem' }}>
              {renewLabel}
            </Button>
          )}
          <Text as="span" size={300} className="muted" style={{ textAlign: 'center' }} >{canPrepay ? (
            <>Total <strong style={{ color: 'var(--text)' }}>{fmtIdr(sub.monthlyFee * months)}</strong> · masa aktif +{months} bln</>
          ) : (
            <>Tagihan berikutnya {fmtDate(sub.nextInvoiceAt)}</>
          )}</Text>
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
            <Text as="h2" size={400} weight="semibold" style={{ margin: 0 }}>Riwayat tagihan</Text>
            <Text as="span" className="muted" size={200}>{sub.invoices.length} tagihan</Text>
          </div>
          {sub.invoices.length === 0 ? (
            <p className="muted" style={{ margin: 0 }}>Belum ada tagihan.</p>
          ) : (
            <div className="stack" style={{ gap: '0.4rem' }}>
              {sub.invoices.map((inv) => (
                <InvoiceRow
                  key={inv.id}
                  inv={inv}
                  payUrl={payUrl(inv)}
                  onSimulate={canRenew ? simulate : undefined}
                  busy={busy}
                />
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
              <Text as="strong" size={300}  >Ada tagihan menunggu pembayaran</Text>
              <Text as="span" size={300} className="muted" >{outstanding.number} · {fmtIdr(outstanding.amount)}. Klik tombol <strong>Bayar</strong> di Riwayat
              tagihan. Masa aktif bertambah setelah pembayaran <strong>LUNAS</strong>.
                            </Text>
            </div>
          )}

          <div className="card stack" style={{ gap: '0.8rem' }}>
            <Text as="h2" size={400} weight="semibold" style={{ margin: 0 }}>Cara perpanjangan</Text>
            <Step n={1} title="Pilih durasi">
              Pilih <strong>1 / 3 / 6 / 12 bulan</strong> lalu klik <strong>Perpanjang</strong> — tagihan sejumlah itu terbit.
            </Step>
            <Step n={2} title="Bayar">
              Klik <strong>Bayar ↗</strong> pada tagihan di <strong>Riwayat tagihan</strong> — halaman bayar
              tagihan itu terbuka di tab baru (bisa juga disalin lewat <strong>Salin link</strong>).
            </Step>
            <Step n={3} title="Masa aktif bertambah">
              Setelah pembayaran <strong>LUNAS</strong>, masa aktif memanjang sesuai jumlah bulan — menumpuk bila belum habis.
            </Step>
            <p className="muted" style={{ margin: 0,  }}>
              Tak perlu ditunggu: tagihan bulanan terbit otomatis menjelang masa aktif habis. Perpanjang di sini
              hanya bila ingin membayar lebih awal / beberapa bulan sekaligus.
            </p>
            {sub.status === 'CANCELLED' && (
              <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
                Langganan dibatalkan. Hubungi admin platform untuk mengaktifkan kembali.
              </Text>
            )}
          </div>

          {/* Portabilitas data bertetangga dengan langganan bukan karena kemiripan teknis,
              melainkan karena di sinilah orang berada saat mempertimbangkan berhenti. */}
          {canExport && (
            <div className="card stack" style={{ gap: '0.6rem' }}>
              <Text as="h2" size={400} weight="semibold" style={{ margin: 0 }}>Data Anda tetap milik Anda</Text>
              <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
                Unduh seluruh data tenant ini — pelanggan, langganan, tagihan, perangkat jaringan,
                tiket, work order — sebagai satu arsip ZIP berisi berkas CSV yang bisa dibuka di
                Excel atau diimpor ke sistem lain. Kata sandi dan kunci tidak ikut serta.
              </Text>
              <div>
                <Button onClick={() => void exportData()} disabled={exporting}>
                  {exporting ? 'Menyiapkan arsip…' : 'Unduh arsip data (ZIP)'}
                </Button>
              </div>
              <p className="muted" style={{ margin: 0,  }}>
                Arsip berukuran besar bisa perlu waktu. Setiap pengunduhan tercatat di Jejak Audit.
              </p>
            </div>
          )}
        </div>
      </div>

    </div>
  )
}

function Header() {
  return (
    <PageHeader
      title="Langganan Aplikasi"
      subtitle="Masa aktif, pemakaian, dan tagihan langganan Anda ke aplikasi."
    />
  )
}

/**
 * Penjelasan kunci baca-saja: berapa yang harus dibayar, sejak kapan menunggak, dan langkah
 * berikutnya. [canRenew] menentukan langkah itu — yang boleh membayar diarahkan ke Riwayat
 * tagihan di bawah, yang tidak diarahkan menghubungi admin ISP-nya. Sengaja tak ada tombol
 * bayar di sini: satu-satunya jalur bayar tetap tombol per-tagihan, supaya tak ada dua pintu
 * yang bisa berbeda perilaku.
 */
function LockedPanel({ lock, canRenew }: { lock: SubscriptionLockView | null; canRenew: boolean }) {
  return (
    <div
      role="alert"
      className="stack"
      style={{
        gap: '0.6rem',
        padding: '1.1rem 1.25rem',
        borderRadius: 'var(--radius-lg)',
        border: '1px solid color-mix(in srgb, var(--danger) 45%, transparent)',
        background: 'color-mix(in srgb, var(--danger) 8%, var(--surface))',
      }}
    >
      <strong style={{ color: 'var(--danger)' }}>Konsol dalam mode baca-saja</strong>
      <p className="muted" style={{ margin: 0,  }}>
        Langganan aplikasi belum dilunasi hingga melewati masa tenggang. Semua data tetap bisa
        dibuka dan dibaca, tapi perubahan — menambah pelanggan, menutup work order, menerbitkan
        tagihan — ditolak sampai pembayaran masuk. Portal pelanggan Anda tetap berjalan penuh.
      </p>
      {lock && (
        <div className="row" style={{ gap: '1.5rem', flexWrap: 'wrap',  }}>
          <Fact label="Total tertunggak" value={fmtIdr(lock.amountDue)} />
          <Fact label="Jatuh tempo" value={fmtDate(lock.dueDate)} />
          <Fact label="Menunggak" value={lock.daysOverdue > 0 ? `${lock.daysOverdue} hari` : '—'} />
        </div>
      )}
      <Text as="p" className="muted" size={300} style={{ margin: 0 }}>{canRenew
        ? 'Lunasi lewat tombol Bayar pada tagihan tertunggak di Riwayat tagihan bawah. Begitu ' +
          'pembayaran masuk, konsol terbuka kembali tanpa perlu keluar-masuk aplikasi.'
        : 'Akun Anda tak berwenang membayar langganan. Hubungi admin ISP Anda agar melunasi ' +
          'tagihan ini — setelah lunas, konsol Anda otomatis terbuka lagi.'}</Text>
    </div>
  )
}

/** Sepasang label kecil + nilai tebal; dipakai baris ringkasan tunggakan. */
function Fact({ label, value }: { label: string; value: string }) {
  return (
    <span className="stack" style={{ gap: '0.15rem' }}>
      <Text as="span" size={300} className="muted" >{label}</Text>
      <strong>{value}</strong>
    </span>
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
        <Text as="span" size={300} className="muted" >{metric.label}</Text>
      </div>
      <div className="row" style={{ gap: '0.35rem', alignItems: 'baseline' }}>
        <Text as="strong" size={300}  style={{  }} >{metric.used.toLocaleString('id-ID')}</Text>
        <Text as="span" size={300} className="muted" >/ {cap}</Text>
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
          flexShrink: 0,
        }}
      >
        {n}
      </span>
      <div className="stack" style={{ gap: '0.1rem' }}>
        <Text as="strong" size={300}  >{title}</Text>
        <Text as="span" size={300} className="muted" >{children}</Text>
      </div>
    </div>
  )
}

/**
 * Satu baris riwayat tagihan. `onSimulate` hanya diisi bila pengguna boleh memperpanjang; kontrol
 * simulasinya sendiri baru muncul saat server menandai `inv.simulatable` (Pivot sandbox + sesi
 * bayar sudah ada), jadi di produksi baris ini tampil apa adanya.
 */
function InvoiceRow({
  inv,
  payUrl,
  onSimulate,
  busy,
}: {
  inv: SubscriptionInvoiceView
  /** Tautan halaman bayar publik tagihan ini — sekaligus yang disalin tombol "Salin link". */
  payUrl: string
  onSimulate?: (inv: SubscriptionInvoiceView, status: SimulatedChargeStatus) => void
  busy: boolean
}) {
  const toast = useToast()
  const outstanding = inv.status === 'ISSUED' || inv.status === 'OVERDUE'
  const copySession = () =>
    navigator.clipboard
      ?.writeText(inv.paymentSessionId ?? '')
      .then(() => toast.success('Payment session ID disalin'))
      .catch(() => toast.error('Gagal menyalin payment session ID'))
  const copyPayLink = () =>
    navigator.clipboard
      ?.writeText(payUrl)
      .then(() => toast.success('Link bayar disalin'))
      .catch(() => toast.error('Gagal menyalin link bayar'))
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
          <Text as="strong" size={300}  style={{  }} >{inv.number}</Text>
          <Badge tone={INVOICE_TONE[inv.status]}>{INVOICE_STATUS_LABEL[inv.status]}</Badge>
          {inv.grant && <Badge tone="accent">Bonus</Badge>}
        </span>
        <Text as="span" size={300} className="muted" >{fmtDate(inv.periodStart)}–{fmtDate(inv.periodEnd)} · jatuh tempo {fmtDate(inv.dueDate)}
        {inv.paidAt && ` · lunas ${fmtDate(inv.paidAt)}`}</Text>
        {inv.paymentSessionId && (
          <span className="row muted" style={{ gap: '0.3rem', alignItems: 'center',  }}>
            <span>Session:</span>
            <code style={{  }}>{inv.paymentSessionId}</code>
            <Button
              variant="subtle"
              icon={<Copy size={12} />}
              onClick={() => void copySession()}
              title="Salin payment session ID"
              aria-label="Salin payment session ID"
              style={{ minWidth: 'auto', padding: '0.1rem 0.25rem' }}
            />
          </span>
        )}
      </div>
      <Text as="span" size={300}  style={{  }} >{fmtIdr(inv.amount)}</Text>
      {inv.simulatable && onSimulate && (
        <div className="row" style={{ gap: '0.25rem' }}>
          <Button
            variant="subtle"
            icon={<FlaskConical size={14} />}
            onClick={() => onSimulate(inv, 'SUCCESS')}
            disabled={busy}
            title="Sandbox: paksa sesi bayar jadi berhasil"
            style={{ padding: '0.3rem 0.5rem', whiteSpace: 'nowrap' }}
          >
            Sim. lunas
          </Button>
          <Button
            variant="subtle"
            icon={<FlaskConical size={14} />}
            onClick={() => onSimulate(inv, 'EXPIRED')}
            disabled={busy}
            title="Sandbox: paksa sesi bayar jadi kedaluwarsa"
            style={{ padding: '0.3rem 0.5rem', whiteSpace: 'nowrap' }}
          >
            Sim. kedaluwarsa
          </Button>
        </div>
      )}
      {outstanding && (
        <div className="row" style={{ gap: '0.25rem' }}>
          <Button
            variant="subtle"
            icon={<Copy size={14} />}
            onClick={() => void copyPayLink()}
            title="Salin tautan halaman bayar tagihan ini"
            style={{ padding: '0.3rem 0.5rem', whiteSpace: 'nowrap' }}
          >
            Salin link
          </Button>
          <Button
            variant="primary"
            onClick={() => window.open(payUrl, '_blank', 'noopener')}
            disabled={busy}
            style={{ padding: '0.35rem 0.7rem', whiteSpace: 'nowrap' }}
          >
            Bayar ↗
          </Button>
        </div>
      )}
    </div>
  )
}
