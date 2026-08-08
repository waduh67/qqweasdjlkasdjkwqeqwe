import { useEffect, useRef, useState } from 'react'
import QRCode from 'react-qr-code'
import { Button, SelectField } from '@/components/atoms'

/**
 * Panel bayar in-app (mode API Pivot) — dipakai seragam di TIGA surface: langganan tenant
 * (`SubscriptionPage`), konsol pelanggan (`CustomerDetailPage`), dan portal pelanggan
 * (`PortalDashboard`). Alih-alih me-*redirect* ke halaman ter-host gateway, tenant/pelanggan
 * memilih metode (QRIS / Virtual Account + bank) di dalam aplikasi ini, lalu instruksi bayar
 * (string QRIS dirender jadi kode QR, atau nomor VA + tombol Salin) tampil di sini.
 *
 * Sengaja realm-agnostik: TAK mengimpor klien HTTP atau context toast mana pun. Pemanggil
 * menyuntik `createCharge` (panggil endpoint pay realm-nya) & `pollStatus` (ambil ulang status
 * tagihan) lewat props, sehingga komponen yang sama jalan di realm operator maupun portal
 * pelanggan yang terisolasi. Umpan-balik "Tersalin" pakai state lokal, bukan toast.
 *
 * Deteksi lunas: selama panel terbuka & instruksi sudah dibuat, polling `pollStatus` tiap 5 dtk
 * sampai status settled (webhook Pivot yang menyetel) — tak perlu polling status gateway baru.
 */

/** Satu metode bayar yang ditawarkan; [channels] kosong bila tak perlu pilih bank (QRIS). */
export interface GatewayPaymentMethod {
  type: string
  label: string
  channels: { code: string; label: string }[]
}

/** Instruksi bayar yang dikembalikan endpoint pay (subset field instruksi tagihan). */
export interface GatewayPayInstruction {
  status?: string | null
  payMethod?: string | null
  vaChannel?: string | null
  vaNumber?: string | null
  vaName?: string | null
  vaExpiresAt?: string | null
  qrContent?: string | null
  qrUrl?: string | null
  qrExpiresAt?: string | null
}

/** Status tagihan yang dianggap sudah lunas (cermin himpunan settled webhook Pivot). */
const SETTLED = new Set(['PAID', 'SETTLED', 'SUCCESS'])

const fmtExpiry = (iso: string | null | undefined): string | null => {
  if (!iso) return null
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return null
  return d.toLocaleString('id-ID', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' })
}

export function GatewayPayPanel({
  title = 'Bayar tagihan',
  subtitle,
  methods,
  createCharge,
  pollStatus,
  onPaid,
  onClose,
  dismissible = true,
  initialInstruction,
}: {
  title?: string
  /** Baris keterangan opsional (mis. nomor tagihan + jumlah). */
  subtitle?: string
  methods: GatewayPaymentMethod[]
  /** Panggil endpoint pay realm; balikkan instruksi bayar tersimpan. */
  createCharge: (method: string, channel: string | null) => Promise<GatewayPayInstruction>
  /** Ambil ulang status tagihan untuk deteksi lunas; null → tanpa polling. */
  pollStatus?: () => Promise<string | null>
  /** Dipanggil sekali saat tagihan terdeteksi lunas. */
  onPaid?: () => void
  onClose?: () => void
  /**
   * Tampilkan tombol "Tutup". Panel yang dirender INLINE sebagai isi utama halaman (halaman bayar
   * publik) tak punya apa pun untuk ditutup, jadi setel `false` di sana.
   */
  dismissible?: boolean
  /**
   * Instruksi bayar yang sudah dibuat sebelumnya (mis. VA/QRIS tersimpan di tagihan). Bila diisi,
   * panel langsung menampilkannya saat dibuka — tak perlu buat ulang; metode & bank ikut ter-set
   * dari sini. Null/kosong → perilaku default (mulai dari metode pertama, tanpa instruksi).
   */
  initialInstruction?: GatewayPayInstruction | null
}) {
  const [method, setMethod] = useState(initialInstruction?.payMethod ?? methods[0]?.type ?? '')
  const selectedMethod = methods.find((m) => m.type === method) ?? methods[0]
  const [channel, setChannel] = useState(
    initialInstruction?.vaChannel ?? selectedMethod?.channels[0]?.code ?? '',
  )
  const [instruction, setInstruction] = useState<GatewayPayInstruction | null>(initialInstruction ?? null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [paid, setPaid] = useState(false)

  const needsChannel = (selectedMethod?.channels.length ?? 0) > 0

  // Buat charge untuk metode terpilih; ganti metode = charge baru menimpa instruksi.
  const submit = async () => {
    if (busy || !selectedMethod) return
    setBusy(true)
    setError(null)
    try {
      const result = await createCharge(selectedMethod.type, needsChannel ? channel : null)
      setInstruction(result)
      if (result.status && SETTLED.has(result.status.toUpperCase())) {
        setPaid(true)
        onPaid?.()
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Gagal membuat pembayaran')
    } finally {
      setBusy(false)
    }
  }

  const onPaidRef = useRef(onPaid)
  onPaidRef.current = onPaid
  const pollRef = useRef(pollStatus)
  pollRef.current = pollStatus

  // Polling deteksi lunas: hanya selagi instruksi ada & belum lunas.
  useEffect(() => {
    if (!instruction || paid || !pollRef.current) return
    let alive = true
    const timer = window.setInterval(async () => {
      try {
        const status = await pollRef.current?.()
        if (alive && status && SETTLED.has(status.toUpperCase())) {
          setPaid(true)
          onPaidRef.current?.()
        }
      } catch {
        /* transient — coba lagi tick berikutnya */
      }
    }, 5000)
    return () => {
      alive = false
      window.clearInterval(timer)
    }
  }, [instruction, paid])

  const copyVa = async () => {
    if (!instruction?.vaNumber) return
    try {
      await navigator.clipboard.writeText(instruction.vaNumber)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      /* clipboard ditolak — nomor tetap terlihat untuk disalin manual */
    }
  }

  return (
    <div className="stack" style={{ gap: '0.9rem' }}>
      <div className="spread" style={{ alignItems: 'flex-start' }}>
        <div className="stack" style={{ gap: '0.2rem' }}>
          <strong style={{ fontSize: '1rem' }}>{title}</strong>
          {subtitle && <span className="muted" style={{ fontSize: '0.82rem' }}>{subtitle}</span>}
        </div>
        {dismissible && (
          <Button type="button" variant="subtle" onClick={onClose} style={{ fontSize: '0.8rem' }}>
            Tutup
          </Button>
        )}
      </div>

      {paid ? (
        <div
          className="stack"
          style={{
            gap: '0.3rem',
            padding: '1rem',
            borderRadius: 'var(--radius)',
            border: '1px solid var(--good, #2ea043)',
            background: 'color-mix(in srgb, var(--good, #2ea043) 12%, var(--surface))',
            textAlign: 'center',
          }}
        >
          <strong style={{ color: 'var(--good-ink)' }}>Pembayaran diterima ✓</strong>
          <span className="muted" style={{ fontSize: '0.82rem' }}>Tagihan sudah lunas.</span>
        </div>
      ) : (
        <>
          {/* Pilih metode */}
          <div className="stack" style={{ gap: '0.4rem' }}>
            <span className="muted" style={{ fontSize: '0.8rem' }}>Metode pembayaran</span>
            <div className="row" style={{ gap: '0.4rem', flexWrap: 'wrap' }}>
              {methods.map((m) => (
                <Button
                  key={m.type}
                  type="button"
                  variant={m.type === method ? 'primary' : 'subtle'}
                  onClick={() => {
                    setMethod(m.type)
                    setChannel(m.channels[0]?.code ?? '')
                    setInstruction(null)
                    setError(null)
                  }}
                  disabled={busy}
                  style={{ fontSize: '0.82rem' }}
                >
                  {m.label}
                </Button>
              ))}
            </div>
          </div>

          {/* Pilih bank untuk Virtual Account */}
          {needsChannel && (
            <div className="stack" style={{ gap: '0.4rem' }}>
              <span className="muted" style={{ fontSize: '0.8rem' }}>Bank Virtual Account</span>
              <SelectField
                value={channel}
                onChange={(_, data) => {
                  setChannel(data.value)
                  setInstruction(null)
                }}
                disabled={busy}
                style={{ maxWidth: 260 }}
              >
                {selectedMethod?.channels.map((c) => (
                  <option key={c.code} value={c.code}>{c.label}</option>
                ))}
              </SelectField>
            </div>
          )}

          <Button variant="primary" onClick={() => void submit()} disabled={busy || !selectedMethod}>
            {busy ? 'Memproses…' : instruction ? 'Perbarui pembayaran' : 'Buat pembayaran'}
          </Button>

          {error && (
            <div
              style={{
                padding: '0.6rem 0.75rem',
                borderRadius: 'var(--radius-sm)',
                border: '1px solid var(--critical, #d1242f)',
                background: 'color-mix(in srgb, var(--critical, #d1242f) 10%, var(--surface))',
                fontSize: '0.82rem',
                color: 'var(--critical-ink)',
              }}
            >
              {error}
            </div>
          )}

          {/* Instruksi VA */}
          {instruction?.vaNumber && (
            <div className="stack" style={{ gap: '0.4rem', paddingTop: '0.4rem', borderTop: '1px solid var(--border)' }}>
              <span className="muted" style={{ fontSize: '0.8rem' }}>
                Nomor Virtual Account{instruction.vaChannel ? ` · ${instruction.vaChannel}` : ''}
              </span>
              <div className="row" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
                <strong style={{ fontFamily: 'monospace', fontSize: '1.25rem', letterSpacing: '0.03em' }}>
                  {instruction.vaNumber}
                </strong>
                <Button type="button" variant="subtle" onClick={() => void copyVa()} style={{ fontSize: '0.8rem' }}>
                  {copied ? 'Tersalin ✓' : 'Salin'}
                </Button>
              </div>
              {instruction.vaName && (
                <span className="muted" style={{ fontSize: '0.8rem' }}>a.n. {instruction.vaName}</span>
              )}
              {fmtExpiry(instruction.vaExpiresAt) && (
                <span className="muted" style={{ fontSize: '0.78rem' }}>
                  Berlaku s/d {fmtExpiry(instruction.vaExpiresAt)}
                </span>
              )}
            </div>
          )}

          {/* Instruksi QRIS */}
          {instruction?.qrContent && (
            <div
              className="stack"
              style={{ gap: '0.4rem', alignItems: 'center', paddingTop: '0.4rem', borderTop: '1px solid var(--border)' }}
            >
              <span className="muted" style={{ fontSize: '0.8rem', alignSelf: 'flex-start' }}>
                Pindai QRIS dengan aplikasi pembayaran Anda
              </span>
              <div style={{ background: '#fff', padding: '0.75rem', borderRadius: 8 }}>
                <QRCode value={instruction.qrContent} size={196} />
              </div>
              {fmtExpiry(instruction.qrExpiresAt) && (
                <span className="muted" style={{ fontSize: '0.78rem' }}>
                  Berlaku s/d {fmtExpiry(instruction.qrExpiresAt)}
                </span>
              )}
            </div>
          )}

          {instruction && pollStatus && (
            <span className="muted" style={{ fontSize: '0.78rem', textAlign: 'center' }}>
              Menunggu pembayaran… halaman ini memperbarui otomatis saat lunas.
            </span>
          )}
        </>
      )}
    </div>
  )
}
