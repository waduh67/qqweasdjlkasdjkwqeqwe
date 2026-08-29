import { type ReactNode } from 'react'
import { Text } from '@fluentui/react-components'
import { Link, useSearchParams } from 'react-router-dom'

/**
 * Halaman balik gateway Pivot (mode REDIRECT). Pivot mengarahkan pembayar ke sini setelah
 * transaksi: `/paid` (sukses), `/failed` (gagal/batal), `/expired` (sesi kedaluwarsa) — URL
 * ini yang dikirim `PivotPaymentGateway.createCharge` sebagai success/failure/expiration
 * ReturnUrl (butuh `FTTH_BILLING_PIVOT_REDIRECT_BASE_URL` di server).
 *
 * PENTING: halaman ini murni informatif. Pelunasan yang OTORITATIF datang dari callback
 * server-side (`PivotCallbackController` → `invoice.markPaid`), bukan dari redirect ini —
 * pembayar bisa saja menutup tab sebelum kembali, atau kembali padahal bank belum settle.
 * Karena itu masa aktif langganan diperbarui oleh callback, bukan oleh tombol di sini.
 *
 * Publik (di luar `RequireAuth`): pembayar yang kembali dari halaman eksternal belum tentu
 * masih memegang sesi login. Tombol menuju `/subscription` akan lewat guard login bila perlu.
 */

type Variant = 'paid' | 'failed' | 'expired'

interface Copy {
  tone: string
  toneInk: string
  icon: ReactNode
  title: string
  body: string
  cta: string
  ctaTo: string
}

const COPY: Record<Variant, Copy> = {
  paid: {
    tone: 'var(--good)',
    toneInk: 'var(--good-ink)',
    icon: <IconCheck />,
    title: 'Pembayaran diterima',
    body:
      'Terima kasih. Pembayaran sedang kami konfirmasi ke bank/penyedia. ' +
      'Masa aktif langganan diperbarui otomatis begitu pelunasan terkonfirmasi — ' +
      'biasanya beberapa saat. Tak perlu membayar ulang.',
    cta: 'Lihat langganan',
    ctaTo: '/subscription',
  },
  failed: {
    tone: 'var(--critical)',
    toneInk: 'var(--critical-ink)',
    icon: <IconCross />,
    title: 'Pembayaran gagal',
    body:
      'Transaksi tidak selesai atau dibatalkan. Tak ada dana yang terpotong. ' +
      'Kamu bisa mencoba lagi dari halaman langganan — tagihannya masih menunggu pembayaran.',
    cta: 'Coba bayar lagi',
    ctaTo: '/subscription',
  },
  expired: {
    tone: 'var(--warning)',
    toneInk: 'var(--warning-ink)',
    icon: <IconClock />,
    title: 'Sesi pembayaran kedaluwarsa',
    body:
      'Batas waktu halaman pembayaran ini sudah lewat. Tak ada dana yang terpotong. ' +
      'Buka lagi tagihan dari halaman langganan untuk memperoleh tautan bayar baru.',
    cta: 'Kembali ke langganan',
    ctaTo: '/subscription',
  },
}

function PaymentReturnPage({ variant }: { variant: Variant }) {
  const [params] = useSearchParams()
  const copy = COPY[variant]
  // Pivot bisa menyertakan referensi di query string; tampilkan bila ada (tak diandalkan).
  const ref =
    params.get('clientReferenceId') ??
    params.get('reference') ??
    params.get('ref') ??
    params.get('invoiceNumber')

  return (
    <div className="login-shell">
      <div className="card login-card stack" style={{ gap: '1.1rem', textAlign: 'center', alignItems: 'center' }}>
        <span
          style={{
            display: 'grid',
            placeItems: 'center',
            width: 64,
            height: 64,
            borderRadius: 999,
            background: `color-mix(in srgb, ${copy.tone} 16%, var(--surface))`,
            color: copy.toneInk,
          }}
        >
          {copy.icon}
        </span>

        <div className="stack" style={{ gap: '0.4rem' }}>
          <Text as="h2" size={400} weight="semibold" style={{ margin: 0 }}>{copy.title}</Text>
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>{copy.body}</Text>
        </div>

        {ref && (
          <Text as="span" className="muted" size={200}>
            Ref: <Text as="strong" weight="semibold" font="monospace" style={{ color: 'var(--text)' }}>{ref}</Text>
          </Text>
        )}

        <Link
          to={copy.ctaTo}
          style={{
            display: 'block',
            width: '100%',
            padding: '0.6rem',
            textAlign: 'center',
            textDecoration: 'none',
            color: 'var(--accent-ink)',
            background: 'var(--accent)',
            borderRadius: 'var(--radius)',
          }}
        >
          {copy.cta}
        </Link>
      </div>
    </div>
  )
}

/** Halaman balik pembayaran BERHASIL (Pivot `successReturnUrl`). */
export function PaymentPaidPage() {
  return <PaymentReturnPage variant="paid" />
}

/** Halaman balik pembayaran GAGAL/dibatalkan (Pivot `failureReturnUrl`). */
export function PaymentFailedPage() {
  return <PaymentReturnPage variant="failed" />
}

/** Halaman balik sesi pembayaran KEDALUWARSA (Pivot `expirationReturnUrl`). */
export function PaymentExpiredPage() {
  return <PaymentReturnPage variant="expired" />
}

// --- ikon inline (stroke currentColor, tak menambah dependensi) ---

function IconCheck() {
  return (
    <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M20 6 9 17l-5-5" />
    </svg>
  )
}

function IconCross() {
  return (
    <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  )
}

function IconClock() {
  return (
    <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </svg>
  )
}
