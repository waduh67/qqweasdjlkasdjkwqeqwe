import { useEffect, useState, type FormEvent } from 'react'
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

const CATEGORY_LABEL: Record<string, string> = {
  KONEKSI_PUTUS: 'Internet mati total',
  KONEKSI_LAMBAT: 'Internet lambat',
  PERANGKAT: 'Perangkat/modem bermasalah',
  TAGIHAN: 'Tagihan & pembayaran',
  LAINNYA: 'Lainnya',
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
        <strong style={{ fontSize: '0.95rem' }}>Ada gangguan?</strong>
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Laporkan di sini, dan pantau penanganannya langsung dari halaman ini — tanpa perlu
          menelepon berulang kali.
        </p>
        <Button variant="primary" style={{ alignSelf: 'flex-start' }} onClick={() => setComposing(true)}>
          Buat laporan
        </Button>
      </div>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Laporan saya</strong>
        {tickets === null ? (
          <span className="muted" style={{ fontSize: '0.85rem' }}>Memuat…</span>
        ) : tickets.length === 0 ? (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada laporan.</p>
        ) : (
          tickets.map((t) => (
            <button
              key={t.id}
              type="button"
              className="spread"
              onClick={() => setOpenId(t.id)}
              style={{
                background: 'none',
                border: 'none',
                borderTop: '1px solid var(--border)',
                padding: '0.6rem 0 0',
                textAlign: 'left',
                cursor: 'pointer',
                alignItems: 'center',
                gap: '0.5rem',
                flexWrap: 'wrap',
                color: 'inherit',
              }}
            >
              <div className="stack" style={{ gap: 2, minWidth: 0 }}>
                <span style={{ fontWeight: 600 }}>{t.subject}</span>
                <span className="muted" style={{ fontSize: '0.8rem' }}>
                  {CATEGORY_LABEL[t.category] ?? t.category} · {fmtWhen(t.lastActivityAt)}
                  {t.workOrderCode ? ' · teknisi dijadwalkan' : ''}
                </span>
              </div>
              <span className="badge" style={{ color: STATUS_TONE[t.status] }}>
                {STATUS_LABEL[t.status] ?? t.status}
              </span>
            </button>
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
      <strong style={{ fontSize: '0.95rem' }}>Laporan baru</strong>
      <SelectField label="Jenis gangguan" value={category} onChange={(_, data) => setCategory(data.value)}>
        {Object.entries(CATEGORY_LABEL).map(([value, label]) => (
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
      {error && <p className="error" style={{ margin: 0, fontSize: '0.85rem' }}>{error}</p>}
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
        <span className="muted">Memuat…</span>
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
            <strong style={{ fontSize: '0.95rem' }}>{t.subject}</strong>
            <span className="muted tnum" style={{ fontSize: '0.78rem' }}>
              {t.code} · dilaporkan {fmtWhen(t.openedAt)}
            </span>
          </div>
          <span className="badge" style={{ color: STATUS_TONE[t.status] }}>
            {STATUS_LABEL[t.status] ?? t.status}
          </span>
        </div>

        {t.workOrderCode && (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Teknisi sudah dijadwalkan untuk keluhan ini (nomor tugas {t.workOrderCode}).
          </p>
        )}
        {t.status === 'RESOLVED' && (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Tim menyatakan gangguan sudah beres. Kalau masih bermasalah, cukup balas di bawah —
            laporan ini terbuka lagi otomatis.
          </p>
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
          {error && <p className="error" style={{ margin: 0, fontSize: '0.85rem' }}>{error}</p>}
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
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Laporan ini sudah ditutup. Kalau gangguannya muncul lagi, buat laporan baru.
        </p>
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
      <p className="muted" style={{ margin: 0, fontSize: '0.78rem', textAlign: 'center' }}>
        {body} · {fmtWhen(at)}
      </p>
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
      <span className="muted" style={{ fontSize: '0.74rem' }}>{authorName} · {fmtWhen(at)}</span>
      <span style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{body}</span>
    </div>
  )
}
