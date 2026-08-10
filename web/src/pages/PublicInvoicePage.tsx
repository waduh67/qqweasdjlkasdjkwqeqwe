import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { ApiError } from '@/api/client'
import {
  getPublicInvoice,
  getPublicPaymentMethods,
  payPublicInvoice,
  publicQrisImageUrl,
  type PublicInvoiceView,
  type PublicPaymentMethodOption,
} from '@/api/publicPayment'
import { GatewayPayPanel } from '@/components/organisms'
import { Spinner, StatusBadge } from '@/components/atoms'

/**
 * HALAMAN BAYAR PUBLIK — satu tagihan, satu URL yang bisa dibagikan: `/bayar/<slug>/<uuid>`.
 * Tanpa login, di luar `AuthProvider`, jadi bisa dibuka pelanggan dari tautan WhatsApp di ponsel
 * mana pun. Inilah SATU-SATUNYA jalur bayar in-app sekarang — modal bayar di konsol operator,
 * halaman langganan, dan portal pelanggan semuanya mengarah ke sini.
 *
 * Slug tenant ikut di URL karena tabel tagihan ber-RLS: dari UUID saja server tak bisa
 * menyimpulkan tenant-nya. Satu bentuk halaman melayani dua jenis tagihan (tagihan pelanggan &
 * langganan SaaS) — server yang membedakan.
 *
 * Membuka tautan TIDAK pernah memanggil gateway: instruksi yang sudah tersimpan ditampilkan apa
 * adanya (`initialInstruction`), charge baru hanya dibuat saat pengunjung menekan tombolnya.
 */

const SETTLED = new Set(['PAID', 'SETTLED', 'SUCCESS'])
const CLOSED = new Set(['VOID'])
/** Sudah lunas lalu uangnya dikembalikan penuh — bukan tagihan batal, dan tak boleh dibayar lagi. */
const REFUNDED = new Set(['REFUNDED'])

const rupiah = (amount: number): string =>
  new Intl.NumberFormat('id-ID', { style: 'currency', currency: 'IDR', maximumFractionDigits: 0 }).format(
    Number.isFinite(amount) ? amount : 0,
  )

/** Tanggal lokal (YYYY-MM-DD) → "5 Agu 2026". */
const fmtDate = (value: string | null): string => {
  if (!value) return '—'
  const d = new Date(value.length <= 10 ? `${value}T00:00:00` : value)
  return Number.isNaN(d.getTime())
    ? value
    : d.toLocaleDateString('id-ID', { day: 'numeric', month: 'short', year: 'numeric' })
}

export function PublicInvoicePage() {
  const { tenantSlug = '', invoiceId = '' } = useParams()
  const [invoice, setInvoice] = useState<PublicInvoiceView | null>(null)
  const [methods, setMethods] = useState<PublicPaymentMethodOption[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let alive = true
    setLoading(true)
    getPublicInvoice(tenantSlug, invoiceId)
      .then(async (inv) => {
        if (!alive) return
        setInvoice(inv)
        setError(null)
        // Katalog metode hanya perlu saat tagihan memang bisa dibayar online.
        if (inv.payableOnline) {
          const list = await getPublicPaymentMethods(tenantSlug, invoiceId).catch(() => [])
          if (alive) setMethods(list)
        }
      })
      .catch((err) => {
        if (!alive) return
        setInvoice(null)
        setError(
          err instanceof ApiError && err.status === 404
            ? 'Tagihan tidak ditemukan atau tautannya sudah tidak berlaku.'
            : err instanceof Error
              ? err.message
              : 'Gagal memuat tagihan.',
        )
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [tenantSlug, invoiceId])

  const createCharge = useCallback(
    async (method: string, channel: string | null) => {
      const updated = await payPublicInvoice(tenantSlug, invoiceId, method, channel)
      setInvoice(updated)
      return updated
    },
    [tenantSlug, invoiceId],
  )

  // Polling status: panel yang memanggilnya tiap 5 dtk sampai webhook penyedia menyetel lunas.
  const pollStatus = useCallback(async () => {
    const fresh = await getPublicInvoice(tenantSlug, invoiceId)
    setInvoice(fresh)
    return fresh.status
  }, [tenantSlug, invoiceId])

  if (loading) {
    return (
      <div className="login-shell">
        <div className="card login-card stack" style={{ alignItems: 'center', gap: '0.8rem' }}>
          <Spinner />
          <span className="muted" style={{ fontSize: '0.85rem' }}>Memuat tagihan…</span>
        </div>
      </div>
    )
  }

  if (!invoice) {
    return (
      <div className="login-shell">
        <div className="card login-card stack" style={{ gap: '0.6rem', textAlign: 'center' }}>
          <h2 style={{ margin: 0, fontSize: '1.1rem' }}>Tagihan tidak tersedia</h2>
          <p className="muted" style={{ margin: 0, fontSize: '0.88rem', lineHeight: 1.5 }}>{error}</p>
        </div>
      </div>
    )
  }

  const paid = SETTLED.has(invoice.status.toUpperCase())
  const closed = CLOSED.has(invoice.status.toUpperCase())
  const refunded = REFUNDED.has(invoice.status.toUpperCase())

  return (
    <div className="login-shell">
      <div className="card login-card stack" style={{ gap: '1rem', maxWidth: 480 }}>
        <div className="stack" style={{ gap: '0.25rem' }}>
          <span className="muted" style={{ fontSize: '0.78rem', textTransform: 'uppercase', letterSpacing: '0.06em' }}>
            {invoice.tenantName}
          </span>
          <div className="spread" style={{ alignItems: 'center' }}>
            <strong style={{ fontFamily: 'monospace' }}>{invoice.number}</strong>
            <StatusBadge status={invoice.status} label={STATUS_LABEL[invoice.status] ?? invoice.status} />
          </div>
        </div>

        <div className="stack" style={{ gap: '0.3rem' }}>
          <span style={{ fontSize: '1.6rem', fontWeight: 700 }}>{rupiah(invoice.amount)}</span>
          <span className="muted" style={{ fontSize: '0.82rem' }}>
            {invoice.payerName} · periode {fmtDate(invoice.periodStart)} – {fmtDate(invoice.periodEnd)}
          </span>
          <span className="muted" style={{ fontSize: '0.82rem' }}>
            {paid ? `Lunas ${fmtDate(invoice.paidAt)}` : `Jatuh tempo ${fmtDate(invoice.dueDate)}`}
          </span>
        </div>

        {paid && <Banner tone="var(--good)" ink="var(--good-ink)" title="Pembayaran diterima ✓" body="Tagihan ini sudah lunas. Terima kasih." />}

        {closed && <Banner tone="var(--muted)" ink="var(--text)" title="Tagihan dibatalkan" body="Tagihan ini sudah dibatalkan penerbitnya — tak perlu dibayar." />}

        {refunded && (
          <Banner
            tone="var(--muted)"
            ink="var(--text)"
            title="Dana sudah dikembalikan"
            body="Tagihan ini pernah lunas lalu uangnya dikembalikan penuh — tak perlu dibayar lagi."
          />
        )}

        {!paid && !closed && !refunded && invoice.payableOnline && methods.length > 0 && (
          <div style={{ paddingTop: '0.4rem', borderTop: '1px solid var(--border)' }}>
            <GatewayPayPanel
              title="Bayar tagihan"
              subtitle={`${invoice.number} · ${rupiah(invoice.amount)}`}
              methods={methods}
              createCharge={createCharge}
              pollStatus={pollStatus}
              dismissible={false}
              initialInstruction={{
                payMethod: invoice.payMethod,
                vaChannel: invoice.vaChannel,
                vaNumber: invoice.vaNumber,
                vaName: invoice.vaName,
                vaExpiresAt: invoice.vaExpiresAt,
                qrContent: invoice.qrContent,
                qrExpiresAt: invoice.qrExpiresAt,
              }}
            />
          </div>
        )}

        {!paid && !closed && !invoice.payableOnline && invoice.manual && (
          <ManualInstructions
            manual={invoice.manual}
            qrisUrl={publicQrisImageUrl(invoice.tenantSlug, invoice.id)}
          />
        )}

        {!paid && !closed && !invoice.payableOnline && !invoice.manual && (
          <Banner
            tone="var(--warning)"
            ink="var(--warning-ink)"
            title="Pembayaran online belum tersedia"
            body="Hubungi penyedia layanan Anda untuk cara pembayaran tagihan ini."
          />
        )}

        <span className="muted" style={{ fontSize: '0.75rem', textAlign: 'center' }}>
          Halaman ini memperbarui sendiri saat pembayaran diterima.
        </span>
      </div>
    </div>
  )
}

const STATUS_LABEL: Record<string, string> = {
  ISSUED: 'Belum dibayar',
  OVERDUE: 'Jatuh tempo',
  PAID: 'Lunas',
  VOID: 'Batal',
  REFUNDED: 'Dikembalikan',
}

function Banner({ tone, ink, title, body }: { tone: string; ink: string; title: string; body: string }) {
  return (
    <div
      className="stack"
      style={{
        gap: '0.3rem',
        padding: '0.9rem',
        borderRadius: 'var(--radius)',
        border: `1px solid ${tone}`,
        background: `color-mix(in srgb, ${tone} 12%, var(--surface))`,
        textAlign: 'center',
      }}
    >
      <strong style={{ color: ink }}>{title}</strong>
      <span className="muted" style={{ fontSize: '0.82rem' }}>{body}</span>
    </div>
  )
}

/**
 * Gateway tenant MANUAL: tak ada VA/QRIS dinamis, jadi tampilkan rekening transfer & gambar QRIS
 * statis tenant. Gambarnya diambil lewat `<img src>` biasa — endpointnya publik seperti halaman ini,
 * jadi tak perlu `AuthedImage`.
 */
function ManualInstructions({
  manual,
  qrisUrl,
}: {
  manual: NonNullable<PublicInvoiceView['manual']>
  qrisUrl: string
}) {
  const nothingConfigured = !manual.transferEnabled && !(manual.qrisEnabled && manual.qrisImageAvailable)
  if (nothingConfigured) {
    return (
      <Banner
        tone="var(--warning)"
        ink="var(--warning-ink)"
        title="Instruksi pembayaran belum disetel"
        body="Hubungi penyedia layanan Anda untuk cara pembayaran tagihan ini."
      />
    )
  }
  return (
    <div className="stack" style={{ gap: '0.7rem', paddingTop: '0.4rem', borderTop: '1px solid var(--border)' }}>
      <strong style={{ fontSize: '0.95rem' }}>Cara pembayaran</strong>
      {manual.transferEnabled && (
        <div className="stack" style={{ gap: '0.2rem' }}>
          <span className="muted" style={{ fontSize: '0.8rem' }}>Transfer bank</span>
          <strong>{manual.bankName ?? '—'}</strong>
          <strong style={{ fontFamily: 'monospace', fontSize: '1.15rem' }}>{manual.accountNumber ?? '—'}</strong>
          {manual.accountHolder && (
            <span className="muted" style={{ fontSize: '0.8rem' }}>a.n. {manual.accountHolder}</span>
          )}
        </div>
      )}
      {manual.qrisEnabled && manual.qrisImageAvailable && (
        <div className="stack" style={{ gap: '0.4rem', alignItems: 'center' }}>
          <span className="muted" style={{ fontSize: '0.8rem', alignSelf: 'flex-start' }}>
            Atau pindai QRIS berikut
          </span>
          <img src={qrisUrl} alt="Kode QRIS pembayaran" style={{ maxWidth: 240, width: '100%', borderRadius: 8 }} />
        </div>
      )}
      <span className="muted" style={{ fontSize: '0.78rem' }}>
        Setelah membayar, kirim bukti transfer ke penyedia layanan Anda agar tagihan segera ditandai lunas.
      </span>
    </div>
  )
}
