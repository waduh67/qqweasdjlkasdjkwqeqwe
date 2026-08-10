import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../api/client'
import {
  changeTicketStatus,
  escalateTicket,
  getTicket,
  getTicketSummary,
  listTickets,
  replyTicket,
  TICKET_CATEGORY_LABEL,
  TICKET_STATUS_LABEL,
  type TicketCategory,
  type TicketDetail,
  type TicketMessageView,
  type TicketPriority,
  type TicketStatus,
  type TicketSummaryView,
  type TicketView,
} from '../api/helpdesk'
import { useCan } from '../auth/useCan'
import { DataTable, type Column } from '@/components/organisms'
import { Button, EmptyState, SelectField, TextareaField, Toolbar } from '@/components/atoms'
import { Drawer, PageHeader, SearchInput } from '@/components/molecules'
import { useToast } from '@/system'
import { IconChat } from '@/components/atoms/icons'

/** Nada lencana status; tak lewat `StatusBadge` karena istilahnya khas helpdesk. */
const STATUS_TONE: Record<TicketStatus, string> = {
  OPEN: 'warning',
  IN_PROGRESS: 'accent',
  RESOLVED: 'good',
  CLOSED: 'neutral',
}

const AUTHOR_LABEL: Record<TicketMessageView['author'], string> = {
  CUSTOMER: 'Pelanggan',
  OPERATOR: 'Tim',
  SYSTEM: 'Sistem',
}

function timeAgo(iso: string): string {
  const secs = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000))
  if (secs < 60) return 'baru saja'
  const mins = Math.floor(secs / 60)
  if (mins < 60) return `${mins} menit lalu`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours} jam lalu`
  return `${Math.floor(hours / 24)} hari lalu`
}

/**
 * Meja bantuan: keluhan yang dilaporkan PELANGGAN SENDIRI dari portal — beda dari
 * Insiden, yang lahir dari alarm jaringan. Satu gangguan fiber bisa memunculkan satu
 * insiden plus belasan tiket di sini.
 *
 * Antrean diurut percakapan terakhir, bukan waktu buka: yang barusan dibalas pelanggan
 * naik ke atas, sehingga tak ada keluhan yang mengendap tanpa jawaban.
 */
export function HelpdeskPage() {
  const { can } = useCan()
  const toast = useToast()
  const [tickets, setTickets] = useState<TicketView[]>([])
  const [summary, setSummary] = useState<TicketSummaryView | null>(null)
  const [loading, setLoading] = useState(true)
  const [detail, setDetail] = useState<TicketDetail | null>(null)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<TicketStatus | ''>('')
  const [categoryFilter, setCategoryFilter] = useState<TicketCategory | ''>('')

  const canReply = can('helpdesk.ticket.reply')
  const canManage = can('helpdesk.ticket.manage')

  const reload = useCallback(async () => {
    try {
      const [page, sum] = await Promise.all([listTickets(), getTicketSummary()])
      setTickets(page.content)
      setSummary(sum)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat tiket bantuan')
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    void reload()
  }, [reload])

  const openDetail = async (id: string) => {
    try {
      setDetail(await getTicket(id))
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat detail tiket')
    }
  }

  /** Satu jalur untuk semua aksi: pakai detail yang dikembalikan server, lalu segarkan antrean. */
  const act = async (action: () => Promise<TicketDetail>, ok: string) => {
    try {
      setDetail(await action())
      toast.success(ok)
      await reload()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
    }
  }

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return tickets.filter(
      (t) =>
        (!statusFilter || t.status === statusFilter) &&
        (!categoryFilter || t.category === categoryFilter) &&
        (!q ||
          t.code.toLowerCase().includes(q) ||
          t.subject.toLowerCase().includes(q) ||
          t.customerName.toLowerCase().includes(q)),
    )
  }, [tickets, query, statusFilter, categoryFilter])

  const columns: Column<TicketView>[] = [
    {
      key: 'code',
      header: 'Tiket',
      sortValue: (t) => t.code,
      cell: (t) => (
        <div className="stack" style={{ gap: '0.2rem' }}>
          <strong>{t.subject}</strong>
          <span className="muted tnum" style={{ fontSize: '0.78rem' }}>{t.code}</span>
        </div>
      ),
    },
    {
      key: 'customer',
      header: 'Pelanggan',
      sortValue: (t) => t.customerName,
      cell: (t) => (
        <Link to={`/customers/${t.customerId}`} onClick={(e) => e.stopPropagation()}>
          {t.customerName}
        </Link>
      ),
    },
    {
      key: 'category',
      header: 'Kategori',
      sortValue: (t) => t.category,
      cell: (t) => <span className="badge">{TICKET_CATEGORY_LABEL[t.category] ?? t.category}</span>,
    },
    {
      key: 'workOrder',
      header: 'Work order',
      sortValue: (t) => t.workOrderCode ?? '',
      cell: (t) =>
        t.workOrderId ? (
          <Link to={`/work-orders/${t.workOrderId}`} onClick={(e) => e.stopPropagation()} className="tnum">
            {t.workOrderCode}
          </Link>
        ) : (
          <span className="muted">—</span>
        ),
    },
    {
      key: 'activity',
      header: 'Aktivitas terakhir',
      sortValue: (t) => t.lastActivityAt,
      cell: (t) => timeAgo(t.lastActivityAt),
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (t) => t.status,
      cell: (t) => <span className={`badge ${STATUS_TONE[t.status]}`}>{TICKET_STATUS_LABEL[t.status]}</span>,
    },
  ]

  const belumTerjawab = summary ? summary.open : 0

  return (
    <div className="stack" style={{ gap: '1.2rem' }}>
      <PageHeader
        title="Meja Bantuan"
        subtitle={
          <>
            Keluhan yang dilaporkan pelanggan sendiri dari portal — {belumTerjawab} menunggu jawaban
            pertama.
          </>
        }
      />

      {summary && (
        <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))' }}>
          <SummaryCard label="Baru" value={summary.open} tone="var(--warning-ink)" hint="belum dibalas" />
          <SummaryCard label="Ditangani" value={summary.inProgress} hint="sudah dibalas tim" />
          <SummaryCard
            label="Menunggu konfirmasi"
            value={summary.resolved}
            tone="var(--good-ink)"
            hint="pelanggan bisa membuka lagi"
          />
        </div>
      )}

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari kode, judul, atau pelanggan…" />
        <SelectField value={statusFilter} onChange={(_, data) => setStatusFilter(data.value as TicketStatus | '')}>
          <option value="">Semua status</option>
          {Object.entries(TICKET_STATUS_LABEL).map(([value, label]) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </SelectField>
        <SelectField
          value={categoryFilter}
          onChange={(_, data) => setCategoryFilter(data.value as TicketCategory | '')}
        >
          <option value="">Semua kategori</option>
          {Object.entries(TICKET_CATEGORY_LABEL).map(([value, label]) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(t) => t.id}
        onRowClick={(t) => void openDetail(t.id)}
        loading={loading}
        initialSort={{ key: 'activity', dir: 'desc' }}
        empty={
          <EmptyState
            title={query || statusFilter || categoryFilter ? 'Tidak ada tiket yang cocok' : 'Belum ada keluhan masuk'}
            hint={
              query || statusFilter || categoryFilter
                ? 'Coba ubah kata kunci atau filter.'
                : 'Laporan yang dikirim pelanggan dari menu Bantuan di portal muncul di sini.'
            }
            icon={<IconChat size={34} />}
          />
        }
      />

      {detail && (
        <Drawer title={detail.ticket.subject} onClose={() => setDetail(null)}>
          <TicketDetailBody
            detail={detail}
            canReply={canReply}
            canManage={canManage}
            onReply={(body) => act(() => replyTicket(detail.ticket.id, body), 'Balasan terkirim')}
            onStatus={(status) =>
              act(() => changeTicketStatus(detail.ticket.id, status), `Status jadi ${TICKET_STATUS_LABEL[status]}`)
            }
            onEscalate={(priority, note) =>
              act(() => escalateTicket(detail.ticket.id, priority, note), 'Work order diterbitkan')
            }
          />
        </Drawer>
      )}
    </div>
  )
}

function SummaryCard({
  label,
  value,
  hint,
  tone,
}: {
  label: string
  value: number
  hint: string
  tone?: string
}) {
  return (
    <div className="card stat">
      <div className="stat-label">{label}</div>
      <div className="tnum" style={{ fontSize: '1.4rem', fontWeight: 600, color: tone }}>{value}</div>
      <div className="muted" style={{ fontSize: '0.82rem' }}>{hint}</div>
    </div>
  )
}

function TicketDetailBody({
  detail,
  canReply,
  canManage,
  onReply,
  onStatus,
  onEscalate,
}: {
  detail: TicketDetail
  canReply: boolean
  canManage: boolean
  onReply: (body: string) => Promise<void>
  onStatus: (status: TicketStatus) => Promise<void>
  onEscalate: (priority: TicketPriority, note?: string) => Promise<void>
}) {
  const t = detail.ticket
  const open = t.status !== 'CLOSED'
  return (
    <div className="stack" style={{ gap: '1.1rem' }}>
      <div className="row wrap" style={{ gap: '0.4rem' }}>
        <span className={`badge ${STATUS_TONE[t.status]}`}>{TICKET_STATUS_LABEL[t.status]}</span>
        <span className="badge">{TICKET_CATEGORY_LABEL[t.category] ?? t.category}</span>
        <span className="badge tnum">{t.code}</span>
        {t.workOrderId && (
          <Link to={`/work-orders/${t.workOrderId}`} className="badge accent">{t.workOrderCode}</Link>
        )}
      </div>

      <div className="stack" style={{ gap: '0.15rem' }}>
        <Link to={`/customers/${t.customerId}`}>{t.customerName}</Link>
        <span className="muted" style={{ fontSize: '0.78rem' }}>
          Dilaporkan {new Date(t.openedAt).toLocaleString('id-ID')}
        </span>
      </div>

      {canManage && open && <ManageActions ticket={t} onStatus={onStatus} onEscalate={onEscalate} />}

      <section className="stack" style={{ gap: '0.5rem' }}>
        <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Percakapan</h3>
        <Message
          author="CUSTOMER"
          authorName={t.customerName}
          body={detail.description}
          at={t.openedAt}
        />
        {detail.messages.map((m, i) => (
          <Message key={i} author={m.author} authorName={m.authorName} body={m.body} at={m.at} />
        ))}
      </section>

      {canReply && open && <ReplyBox onSend={onReply} />}
      {!open && (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Tiket sudah ditutup — utasnya tak lagi menerima balasan.
        </p>
      )}
    </div>
  )
}

/** Ubah status & eskalasi; keduanya izin `helpdesk.ticket.manage`. */
function ManageActions({
  ticket,
  onStatus,
  onEscalate,
}: {
  ticket: TicketView
  onStatus: (status: TicketStatus) => Promise<void>
  onEscalate: (priority: TicketPriority, note?: string) => Promise<void>
}) {
  const [escalating, setEscalating] = useState(false)
  const [priority, setPriority] = useState<TicketPriority>('NORMAL')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)

  const run = async (fn: () => Promise<void>) => {
    setBusy(true)
    try {
      await fn()
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="stack" style={{ gap: '0.55rem' }}>
      <div className="row wrap" style={{ gap: '0.5rem' }}>
        {ticket.status !== 'IN_PROGRESS' && (
          <Button variant="subtle" disabled={busy} onClick={() => void run(() => onStatus('IN_PROGRESS'))}>
            Tandai ditangani
          </Button>
        )}
        {ticket.status !== 'RESOLVED' && (
          <Button variant="primary" disabled={busy} onClick={() => void run(() => onStatus('RESOLVED'))}>
            Tandai selesai
          </Button>
        )}
        <Button variant="subtle" disabled={busy} onClick={() => void run(() => onStatus('CLOSED'))}>
          Tutup tiket
        </Button>
        {!ticket.workOrderId && !escalating && (
          <Button variant="subtle" onClick={() => setEscalating(true)}>
            Eskalasi ke work order
          </Button>
        )}
      </div>

      {escalating && !ticket.workOrderId && (
        <section className="stack" style={{ gap: '0.5rem' }}>
          <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
            Menerbitkan work order perbaikan untuk pelanggan ini. Keluhannya ikut sebagai deskripsi
            tugas, dan satu tiket hanya bisa dieskalasi sekali.
          </p>
          <SelectField
            label="Prioritas"
            value={priority}
            onChange={(_, data) => setPriority(data.value as TicketPriority)}
          >
            <option value="LOW">Rendah</option>
            <option value="NORMAL">Normal</option>
            <option value="HIGH">Tinggi</option>
            <option value="URGENT">Mendesak</option>
          </SelectField>
          <TextareaField
            label="Catatan untuk teknisi (opsional)"
            rows={3}
            maxLength={500}
            value={note}
            onChange={(_, data) => setNote(data.value)}
          />
          <div className="row" style={{ gap: '0.5rem' }}>
            <Button
              variant="primary"
              disabled={busy}
              onClick={() =>
                void run(async () => {
                  await onEscalate(priority, note)
                  setEscalating(false)
                  setNote('')
                })
              }
            >
              {busy ? 'Menerbitkan…' : 'Terbitkan work order'}
            </Button>
            <Button variant="subtle" disabled={busy} onClick={() => setEscalating(false)}>
              Batal
            </Button>
          </div>
        </section>
      )}
    </div>
  )
}

function ReplyBox({ onSend }: { onSend: (body: string) => Promise<void> }) {
  const toast = useToast()
  const [body, setBody] = useState('')
  const [sending, setSending] = useState(false)

  const submit = async () => {
    if (!body.trim()) {
      toast.error('Balasan tidak boleh kosong')
      return
    }
    setSending(true)
    try {
      await onSend(body.trim())
      setBody('')
    } finally {
      setSending(false)
    }
  }

  return (
    <section className="stack" style={{ gap: '0.5rem' }}>
      <TextareaField
        label="Balas pelanggan"
        rows={3}
        maxLength={2000}
        value={body}
        onChange={(_, data) => setBody(data.value)}
      />
      <Button variant="primary" style={{ alignSelf: 'flex-start' }} disabled={sending} onClick={() => void submit()}>
        {sending ? 'Mengirim…' : 'Kirim balasan'}
      </Button>
    </section>
  )
}

/**
 * Satu gelembung percakapan. Pesan pelanggan dan pesan tim sengaja dibedakan
 * perataannya — pada utas panjang, "siapa bicara" harus terbaca tanpa dieja.
 */
function Message({
  author,
  authorName,
  body,
  at,
}: {
  author: TicketMessageView['author']
  authorName: string
  body: string
  at: string
}) {
  const mine = author === 'OPERATOR'
  if (author === 'SYSTEM') {
    return (
      <p className="muted" style={{ margin: 0, fontSize: '0.8rem', textAlign: 'center' }}>
        {body} · {new Date(at).toLocaleString('id-ID')}
      </p>
    )
  }
  return (
    <div
      className="card stack"
      style={{
        gap: '0.3rem',
        alignSelf: mine ? 'flex-end' : 'flex-start',
        maxWidth: '85%',
        background: mine ? 'var(--accent-soft)' : undefined,
      }}
    >
      <span className="muted" style={{ fontSize: '0.75rem' }}>
        {AUTHOR_LABEL[author]} · {authorName} · {new Date(at).toLocaleString('id-ID')}
      </span>
      <span style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{body}</span>
    </div>
  )
}
