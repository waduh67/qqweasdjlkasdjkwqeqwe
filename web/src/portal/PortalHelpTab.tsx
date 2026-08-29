import { useEffect, useState, type FormEvent } from 'react'
import { Text } from '@fluentui/react-components'
import { PortalApiError } from './portalClient'
import {
  closePortalTicket,
  getPortalTicket,
  getPortalTickets,
  replyPortalTicket,
  submitPortalTicket,
  type PortalTicket,
  type PortalTicketDetail,
  type PortalTicketMessage,
} from './portalApi'
import { Button, SelectField, TextField, TextareaField } from '@/components/atoms'

/**
 * Menu "Bantuan" di portal: pelanggan melaporkan gangguan sendiri, lalu mengikuti
 * penanganannya di layar yang sama — bukan menelepon lalu menunggu tanpa kabar.
 *
 * Kata-katanya sengaja dari sudut pandang pelanggan: tak ada "tiket", "eskalasi", atau
 * nama staf. Yang ia lihat cuma laporannya, jawabannya, dan kapan teknisi dijadwalkan.
 */

const STATUS_LABEL: Record<string, string> = {
  OPEN: 'Menunggu ditangani',
  IN_PROGRESS: 'Sedang ditangani',
  RESOLVED: 'Dinyatakan selesai',
  CLOSED: 'Ditutup',
}

const STATUS_TONE: Record<string, string> = {
  OPEN: 'var(--warning-ink)',
  IN_PROGRESS: 'var(--accent)',
  RESOLVED: 'var(--good-ink)',
  CLOSED: 'var(--muted)',
}

/** Jenis gangguan yang boleh dipilih sendiri pelanggan di formulir laporan baru. */
const REPORTABLE_CATEGORY: Record<string, string> = {
  KONEKSI_PUTUS: 'Internet mati total',
  KONEKSI_LAMBAT: 'Internet lambat',
  PERANGKAT: 'Perangkat/modem bermasalah',
  TAGIHAN: 'Tagihan & pembayaran',
  LAINNYA: 'Lainnya',
}

/**
 * Label untuk MENAMPILKAN laporan yang sudah ada. Lebih luas dari daftar di atas karena
 * `GANTI_PAKET` lahir dari menu Profil (isinya dirakit server dari harga katalog), jadi ia
 * muncul di daftar ini tapi tak boleh bisa dipilih sebagai "gangguan" di formulir.
 */
const CATEGORY_LABEL: Record<string, string> = {
  ...REPORTABLE_CATEGORY,
  GANTI_PAKET: 'Ajuan ganti paket',
}

function fmtWhen(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleString('id-ID', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
}

export function BantuanTab() {
  const [tickets, setTickets] = useState<PortalTicket[] | null>(null)
  const [openId, setOpenId] = useState<string | null>(null)
  const [composing, setComposing] = useState(false)

  const reload = () => getPortalTickets().then(setTickets).catch(() => setTickets([]))

  useEffect(() => {
    void reload()
  }, [])

  if (openId) {
    return (
      <TicketThread
        ticketId={openId}
        onBack={() => {
          setOpenId(null)
          void reload()
        }}
      />
    )
  }

  if (composing) {
    return (
      <NewTicketForm
        onCancel={() => setComposing(false)}
        onCreated={(detail) => {
          setComposing(false)
          setOpenId(detail.ticket.id)
        }}
      />
    )
  }

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="card stack" style={{ gap: '0.6rem' }}>
        <Text as="h2" size={400} weight="semibold">Ada gangguan?</Text>
        <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
          Laporkan di sini, dan pantau penanganannya langsung dari halaman ini — tanpa perlu
          menelepon berulang kali.
        </Text>
        <Button variant="primary" style={{ alignSelf: 'flex-start' }} onClick={() => setComposing(true)}>
          Buat laporan
        </Button>
      </div>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <Text as="h2" size={400} weight="semibold">Laporan saya</Text>
        {tickets === null ? (
          <Text as="span" className="muted" size={300}>Memuat…</Text>
        ) : tickets.length === 0 ? (
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>Belum ada laporan.</Text>
        ) : (
          tickets.map((t) => (
            <Button
              key={t.id}
              type="button"
              variant="subtle"
              className="spread"
              onClick={() => setOpenId(t.id)}
              style={{
                borderTop: '1px solid var(--border)',
                padding: '0.6rem 0 0',
                textAlign: 'left',
                alignItems: 'center',
                gap: '0.5rem',
                flexWrap: 'wrap',
              }}
            >
              <div className="stack" style={{ gap: 2, minWidth: 0 }}>
                <Text as="span" weight="semibold">{t.subject}</Text>
                <Text as="span" className="muted" size={200}>{CATEGORY_LABEL[t.category] ?? t.category} · {fmtWhen(t.lastActivityAt)}
                {t.workOrderCode ? ' · teknisi dijadwalkan' : ''}</Text>
              </div>
              <Text as="span" className="badge" style={{ color: STATUS_TONE[t.status] }}>{STATUS_LABEL[t.status] ?? t.status}</Text>
            </Button>
          ))
        )}
      </div>
    </div>
  )
}

/** Formulir laporan baru. Kategori membantu operator memilah antrean, bukan menyaring pelanggan. */
function NewTicketForm({
  onCancel,
  onCreated,
}: {
  onCancel: () => void
  onCreated: (detail: PortalTicketDetail) => void
}) {
  const [category, setCategory] = useState('KONEKSI_PUTUS')
  const [subject, setSubject] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      onCreated(await submitPortalTicket(category, subject.trim(), description.trim()))
    } catch (err) {
      setError(err instanceof PortalApiError ? err.message : 'Laporan gagal dikirim')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="card stack" style={{ gap: '0.6rem' }} onSubmit={onSubmit}>
      <Text as="h2" size={400} weight="semibold">Laporan baru</Text>
      <SelectField label="Jenis gangguan" value={category} onChange={(_, data) => setCategory(data.value)}>
        {Object.entries(REPORTABLE_CATEGORY).map(([value, label]) => (
          <option key={value} value={value}>{label}</option>
        ))}
      </SelectField>
      <TextField
        label="Judul singkat"
        value={subject}
        onChange={(_, data) => setSubject(data.value)}
        required
        maxLength={150}
        placeholder="Mis. Internet mati sejak pagi"
      />
      <TextareaField
        label="Ceritakan detailnya"
        rows={4}
        maxLength={2000}
        value={description}
        onChange={(_, data) => setDescription(data.value)}
        required
        placeholder="Sejak kapan, lampu modem warna apa, sudah dicoba restart atau belum…"
      />
      {error && <Text as="p" className="error" size={300} style={{ margin: 0 }}>{error}</Text>}
      <div className="row" style={{ gap: '0.5rem' }}>
        <Button variant="primary" type="submit" disabled={busy}>
          {busy ? 'Mengirim…' : 'Kirim laporan'}
        </Button>
        <Button variant="subtle" type="button" onClick={onCancel} disabled={busy}>
          Batal
        </Button>
      </div>
    </form>
  )
}

/** Satu laporan beserta utasnya: balas, atau tutup sendiri kalau sudah beres. */
function TicketThread({ ticketId, onBack }: { ticketId: string; onBack: () => void }) {
  const [detail, setDetail] = useState<PortalTicketDetail | null>(null)
  const [body, setBody] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let alive = true
    void getPortalTicket(ticketId)
      .then((d) => alive && setDetail(d))
      .catch(() => alive && setDetail(null))
    return () => {
      alive = false
    }
  }, [ticketId])

  const run = async (action: () => Promise<PortalTicketDetail>) => {
    setError(null)
    setBusy(true)
    try {
      setDetail(await action())
      setBody('')
    } catch (err) {
      setError(err instanceof PortalApiError ? err.message : 'Gagal mengirim')
    } finally {
      setBusy(false)
    }
  }

  if (!detail) {
    return (
      <div className="card" style={{ display: 'grid', placeItems: 'center', minHeight: 120 }}>
        <Text as="span" className="muted" size={300}>Memuat…</Text>
      </div>
    )
  }

  const t = detail.ticket
  const open = t.status !== 'CLOSED'

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <Button variant="subtle" style={{ alignSelf: 'flex-start' }} onClick={onBack}>
        ← Semua laporan
      </Button>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <div className="spread" style={{ alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
          <div className="stack" style={{ gap: 2, minWidth: 0 }}>
            <Text as="h2" size={400} weight="semibold">{t.subject}</Text>
            <Text as="span" className="muted tnum" size={200}>{t.code} · dilaporkan {fmtWhen(t.openedAt)}</Text>
          </div>
          <Text as="span" className="badge" style={{ color: STATUS_TONE[t.status] }}>{STATUS_LABEL[t.status] ?? t.status}</Text>
        </div>

        {t.workOrderCode && (
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
            Teknisi sudah dijadwalkan untuk keluhan ini (nomor tugas {t.workOrderCode}).
          </Text>
        )}
        {t.status === 'RESOLVED' && (
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
            Tim menyatakan gangguan sudah beres. Kalau masih bermasalah, cukup balas di bawah —
            laporan ini terbuka lagi otomatis.
          </Text>
        )}

        <div className="stack" style={{ gap: '0.55rem' }}>
          <Bubble author="CUSTOMER" authorName="Anda" body={detail.description} at={t.openedAt} />
          {detail.messages.map((m, i) => (
            <Bubble key={i} author={m.author} authorName={m.authorName} body={m.body} at={m.at} />
          ))}
        </div>
      </div>

      {open ? (
        <div className="card stack" style={{ gap: '0.6rem' }}>
          <TextareaField
            label="Balas"
            rows={3}
            maxLength={2000}
            value={body}
            onChange={(_, data) => setBody(data.value)}
          />
          {error && <Text as="p" className="error" size={300} style={{ margin: 0 }}>{error}</Text>}
          <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap' }}>
            <Button
              variant="primary"
              disabled={busy || !body.trim()}
              onClick={() => void run(() => replyPortalTicket(t.id, body.trim()))}
            >
              {busy ? 'Mengirim…' : 'Kirim'}
            </Button>
            <Button variant="subtle" disabled={busy} onClick={() => void run(() => closePortalTicket(t.id))}>
              Sudah beres, tutup laporan
            </Button>
          </div>
        </div>
      ) : (
        <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
          Laporan ini sudah ditutup. Kalau gangguannya muncul lagi, buat laporan baru.
        </Text>
      )}
    </div>
  )
}

/** Pesan sistem tampil sebagai catatan tengah — bukan gelembung — supaya utas tetap terbaca. */
function Bubble({
  author,
  authorName,
  body,
  at,
}: {
  author: PortalTicketMessage['author']
  authorName: string
  body: string
  at: string
}) {
  if (author === 'SYSTEM') {
    return (
      <Text as="p" className="muted" size={200} style={{ margin: 0, textAlign: 'center' }}>
        {body} · {fmtWhen(at)}
      </Text>
    )
  }
  const mine = author === 'CUSTOMER'
  return (
    <div
      className="stack"
      style={{
        gap: '0.2rem',
        alignSelf: mine ? 'flex-end' : 'flex-start',
        maxWidth: '85%',
        padding: '0.5rem 0.7rem',
        borderRadius: 10,
        background: mine ? 'var(--accent-soft)' : 'var(--surface-2)',
      }}
    >
      <Text as="span" className="muted" size={100}>{authorName} · {fmtWhen(at)}</Text>
      <Text as="span" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{body}</Text>
    </div>
  )
}
