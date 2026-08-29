import { useEffect, useState } from 'react'
import { Text, typographyStyles } from '@fluentui/react-components'
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

  if (loading) return <Text as="p" className="muted">Memeriksa ketersediaan simulasi…</Text>
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
          <Text as="strong" size={400} weight="semibold">Kirim simulasi</Text>
          <Badge tone={availability.available ? 'good' : 'warning'}>
            {availability.available ? 'Sandbox' : 'Tidak tersedia'}
          </Badge>
        </div>

        {!availability.available && (
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>{availability.reason ?? 'Simulasi tidak tersedia saat ini.'} Ubah di{' '}
          <Text as="strong" weight="semibold" >Billing Langganan Platform → Akun master Pivot</Text>.
                    </Text>
        )}
        {availability.available && !manage && (
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
            Anda tak punya izin <code>platform.billing.manage</code> untuk mengirim simulasi.
          </Text>
        )}

        <TextField
          label="Payment session ID"
          value={sessionId}
          onChange={(_, data) => setSessionId(data.value)}
          disabled={!enabled}
          placeholder="mis. ps_01HZX…"
        />
        <Text as="span" className="muted" size={200} style={{ marginTop: '-0.5rem' }}>
          ID sesi bayar dari Pivot (<code>data.id</code> saat charge dibuat) — nilai yang sama tersimpan
          sebagai referensi gateway pada tagihan.
        </Text>

        <TextField
          label="Sub-merchant ID (opsional)"
          value={subMerchantId}
          onChange={(_, data) => setSubMerchantId(data.value)}
          disabled={!enabled}
          placeholder="kosongkan untuk sesi langganan SaaS"
        />
        <Text as="span" className="muted" size={200} style={{ marginTop: '-0.5rem' }}>
          Isi hanya bila sesi dibuat atas nama sub-account tenant (tagihan pelanggan). Sesi langganan
          aplikasi dibuat langsung di akun master, jadi biarkan kosong.
        </Text>

        <div className="stack" style={{ gap: '0.35rem' }}>
          <Text as="span" size={300} weight="semibold">Status akhir</Text>
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

        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Hasilnya <Text as="strong" weight="semibold" >tidak seketika</Text>: Pivot memproses simulasi lalu memanggil webhook kita —
          tagihan baru berubah status setelah callback itu diterima. Pastikan URL callback bisa dijangkau
          Pivot sandbox (perlu tunnel bila menjalankan di lokal).
        </Text>
      </div>

      {sent.length > 0 && (
        <div className="card stack" style={{ gap: '0.5rem' }}>
          <Text as="strong" size={400} weight="semibold">Terkirim di sesi ini</Text>
          {sent.map((e, i) => (
            <div
              key={`${e.sessionId}-${i}`}
              className="row"
              style={{ ...typographyStyles.caption1, gap: '0.6rem', alignItems: 'center', flexWrap: 'wrap' }}
            >
              <Text as="span" className="muted">{e.at}</Text>
              <Badge tone={e.status === 'SUCCESS' ? 'good' : 'neutral'}>{e.status}</Badge>
              <Text as="span" font="monospace">{e.sessionId}</Text>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
