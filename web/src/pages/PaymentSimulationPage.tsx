import { useEffect, useState } from 'react'
import { FlaskConical } from 'lucide-react'
import { ApiError } from '../api/client'
import {
  getSimulationAvailability,
  simulatePayment,
  type SimulatedChargeStatus,
  type SimulationAvailability,
} from '../api/platformBilling'
import { useCan } from '../auth/useCan'
import { Badge, Button, EmptyState, Segmented, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { useToast } from '@/system'
import { IconAlert } from '@/components/atoms/icons'

/**
 * Alat uji super-admin: memaksa sebuah sesi bayar Pivot jadi `SUCCESS`/`EXPIRED` lewat endpoint
 * simulasi (`POST /v2/payments/simulations`). Pivot lalu mengirim webhook aslinya, sehingga jalur
 * pelunasan yang sesungguhnya (callback → pembayaran → tagihan lunas) ikut teruji.
 *
 * Panel ini bekerja pada **payment session ID mentah** — nilai yang sama disimpan sebagai
 * `gatewayRef` tagihan — jadi bisa dipakai untuk sesi apa pun, termasuk yang tak lagi terhubung ke
 * tagihan di aplikasi ini. Untuk sesi yang dibuat atas nama sub-account tenant (tagihan pelanggan),
 * isi `subMerchantId` agar permintaan dikirim dengan konteks sub-account yang benar.
 *
 * Hanya tersedia saat Pivot master dalam mode SANDBOX — server menolak selain itu.
 */

const STATUS_OPTIONS: { value: SimulatedChargeStatus; label: string }[] = [
  { value: 'SUCCESS', label: 'Berhasil (SUCCESS)' },
  { value: 'EXPIRED', label: 'Kedaluwarsa (EXPIRED)' },
]

/** Satu kiriman simulasi pada sesi ini (hanya di memori — sekadar jejak saat menguji). */
interface SentEntry {
  sessionId: string
  status: SimulatedChargeStatus
  at: string
}

export function PaymentSimulationPage() {
  const { can } = useCan()
  const toast = useToast()
  const manage = can('platform.billing.manage')

  const [availability, setAvailability] = useState<SimulationAvailability | null>(null)
  const [loading, setLoading] = useState(true)
  const [sessionId, setSessionId] = useState('')
  const [subMerchantId, setSubMerchantId] = useState('')
  const [status, setStatus] = useState<SimulatedChargeStatus>('SUCCESS')
  const [sending, setSending] = useState(false)
  const [sent, setSent] = useState<SentEntry[]>([])

  useEffect(() => {
    getSimulationAvailability()
      .then(setAvailability)
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memeriksa ketersediaan simulasi'))
      .finally(() => setLoading(false))
  }, [toast])

  if (loading) return <p className="muted">Memeriksa ketersediaan simulasi…</p>
  if (!availability) {
    return (
      <EmptyState
        title="Status simulasi tak diketahui"
        hint="Coba muat ulang halaman."
        icon={<IconAlert size={28} />}
      />
    )
  }

  const trimmed = sessionId.trim()
  const enabled = manage && availability.available
  const canSend = enabled && trimmed.length > 0 && !sending

  const send = async () => {
    if (!canSend) return
    setSending(true)
    try {
      const result = await simulatePayment({
        paymentSessionId: trimmed,
        status,
        subMerchantId: subMerchantId.trim() || null,
      })
      setSent((prev) => [
        { sessionId: result.paymentSessionId, status: result.status, at: new Date().toLocaleTimeString('id-ID') },
        ...prev,
      ])
      toast.success(`Simulasi ${result.status} dikirim ke ${result.provider} — tunggu webhook menyusul.`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengirim simulasi')
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="stack settings-page" style={{ gap: '1.5rem' }}>
      <PageHeader
        title="Simulasi Pembayaran"
        subtitle="Paksa sebuah sesi bayar Pivot jadi berhasil atau kedaluwarsa untuk menguji jalur pelunasan tanpa transaksi bank sungguhan. Hanya mode sandbox."
      />

      <div className="card stack" style={{ gap: '0.85rem' }}>
        <div className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
          <FlaskConical size={16} />
          <strong style={{ fontSize: '0.95rem' }}>Kirim simulasi</strong>
          <Badge tone={availability.available ? 'good' : 'warning'}>
            {availability.available ? 'Sandbox' : 'Tidak tersedia'}
          </Badge>
        </div>

        {!availability.available && (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            {availability.reason ?? 'Simulasi tidak tersedia saat ini.'} Ubah di{' '}
            <strong>Billing Langganan Platform → Akun master Pivot</strong>.
          </p>
        )}
        {availability.available && !manage && (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Anda tak punya izin <code>platform.billing.manage</code> untuk mengirim simulasi.
          </p>
        )}

        <TextField
          label="Payment session ID"
          value={sessionId}
          onChange={(_, data) => setSessionId(data.value)}
          disabled={!enabled}
          placeholder="mis. ps_01HZX…"
        />
        <span className="muted" style={{ fontSize: '0.82rem', marginTop: '-0.5rem' }}>
          ID sesi bayar dari Pivot (<code>data.id</code> saat charge dibuat) — nilai yang sama tersimpan
          sebagai referensi gateway pada tagihan.
        </span>

        <TextField
          label="Sub-merchant ID (opsional)"
          value={subMerchantId}
          onChange={(_, data) => setSubMerchantId(data.value)}
          disabled={!enabled}
          placeholder="kosongkan untuk sesi langganan SaaS"
        />
        <span className="muted" style={{ fontSize: '0.82rem', marginTop: '-0.5rem' }}>
          Isi hanya bila sesi dibuat atas nama sub-account tenant (tagihan pelanggan). Sesi langganan
          aplikasi dibuat langsung di akun master, jadi biarkan kosong.
        </span>

        <div className="stack" style={{ gap: '0.35rem' }}>
          <span style={{ fontSize: '0.9rem', fontWeight: 600 }}>Status akhir</span>
          <Segmented
            options={STATUS_OPTIONS}
            value={status}
            onChange={setStatus}
            disabled={!enabled}
            ariaLabel="Status akhir simulasi"
          />
        </div>

        <div className="hr" />
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          <Button variant="primary" onClick={() => void send()} disabled={!canSend}>
            {sending ? 'Mengirim…' : 'Kirim simulasi'}
          </Button>
        </div>

        <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
          Hasilnya <strong>tidak seketika</strong>: Pivot memproses simulasi lalu memanggil webhook kita —
          tagihan baru berubah status setelah callback itu diterima. Pastikan URL callback bisa dijangkau
          Pivot sandbox (perlu tunnel bila menjalankan di lokal).
        </p>
      </div>

      {sent.length > 0 && (
        <div className="card stack" style={{ gap: '0.5rem' }}>
          <strong style={{ fontSize: '0.95rem' }}>Terkirim di sesi ini</strong>
          {sent.map((e, i) => (
            <div
              key={`${e.sessionId}-${i}`}
              className="row"
              style={{ gap: '0.6rem', alignItems: 'center', fontSize: '0.82rem', flexWrap: 'wrap' }}
            >
              <span className="muted">{e.at}</span>
              <Badge tone={e.status === 'SUCCESS' ? 'good' : 'neutral'}>{e.status}</Badge>
              <span style={{ fontFamily: 'monospace' }}>{e.sessionId}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
