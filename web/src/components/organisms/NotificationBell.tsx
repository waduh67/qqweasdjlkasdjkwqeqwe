import { useCallback, useEffect, useState, type ComponentType } from 'react'
import { useNavigate } from 'react-router-dom'
import { Popover, PopoverSurface, PopoverTrigger } from '@fluentui/react-components'
import {
  getInboxFeed,
  getInboxUnreadCount,
  markAllInboxRead,
  markInboxRead,
  type InboxNotification,
  type NotificationKind,
  type NotificationSeverity,
} from '@/api/inbox'
import { Button, IconAlert, IconBell, IconChat, IconWorkOrder, type IconProps } from '@/components/atoms'
import { timeAgo } from '@/utils/timeAgo'

/**
 * Lonceng pemberitahuan di header konsol.
 *
 * Sebelum ada ini, peristiwa yang menuntut tindakan — tiket lewat SLA, gangguan baru, work
 * order yang ditugaskan — hanya terlihat kalau seseorang kebetulan membuka halaman yang
 * tepat. Lonceng memindahkan bebannya: operator tak lagi harus rajin memeriksa, aplikasinya
 * yang memberi tahu.
 *
 * Isinya milik pengguna yang login; server yang menyaring audiens dari izin di token, jadi
 * komponen ini tak perlu (dan tak bisa) memutuskan siapa boleh melihat apa.
 */
const KIND_ICON: Record<NotificationKind, ComponentType<IconProps>> = {
  HELPDESK_SLA: IconChat,
  INCIDENT_OPENED: IconAlert,
  WORK_ORDER_ASSIGNED: IconWorkOrder,
}

/** INFO sengaja tak berwarna: kalau semuanya menyala, tak ada yang menonjol. */
const SEVERITY_TONE: Record<NotificationSeverity, string | undefined> = {
  INFO: undefined,
  WARNING: 'var(--warning-ink)',
  CRITICAL: 'var(--critical-ink)',
}

/**
 * Selang jemput angka lencana. Satu menit cukup cepat untuk pekerjaan yang tenggatnya
 * berjam-jam, dan cukup jarang untuk tak membebani server dengan sejumlah operator online.
 */
const POLL_MS = 60_000

export function NotificationBell() {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [unread, setUnread] = useState(0)
  const [items, setItems] = useState<InboxNotification[]>([])
  const [loading, setLoading] = useState(false)

  // Berhenti menjemput saat tab tersembunyi: laptop teknisi yang ditinggal semalaman tak
  // perlu menembak server 480 kali untuk angka yang tak dilihat siapa pun.
  useEffect(() => {
    let alive = true
    const tick = () => {
      if (document.visibilityState === 'hidden') return
      getInboxUnreadCount()
        .then((r) => {
          if (alive) setUnread(r.unread)
        })
        .catch(() => {})
    }
    tick()
    const id = window.setInterval(tick, POLL_MS)
    return () => {
      alive = false
      window.clearInterval(id)
    }
  }, [])

  const load = useCallback(() => {
    setLoading(true)
    getInboxFeed()
      .then((feed) => {
        setItems(feed.items)
        setUnread(feed.unread)
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  const onOpenChange = (_: unknown, data: { open: boolean }) => {
    setOpen(data.open)
    if (data.open) load()
  }

  /**
   * Diklik = dibaca. Keadaan lokal diperbarui lebih dulu supaya lencana turun seketika;
   * kegagalan menandai di server sengaja tak dibatalkan di layar — ronde jemput berikutnya
   * yang akan mengoreksinya, dan sementara itu tak ada yang rusak selain sebuah angka.
   */
  const openItem = (n: InboxNotification) => {
    setOpen(false)
    if (!n.readAt) {
      setItems((prev) => prev.map((i) => (i.id === n.id ? { ...i, readAt: new Date().toISOString() } : i)))
      setUnread((u) => Math.max(0, u - 1))
      void markInboxRead([n.id]).catch(() => {})
    }
    if (n.link) navigate(n.link)
  }

  const readAll = () => {
    const now = new Date().toISOString()
    setItems((prev) => prev.map((i) => (i.readAt ? i : { ...i, readAt: now })))
    setUnread(0)
    void markAllInboxRead().catch(() => {})
  }

  const label = unread > 0 ? `Pemberitahuan (${unread} belum dibaca)` : 'Pemberitahuan'

  return (
    <Popover open={open} onOpenChange={onOpenChange} positioning="below-end" withArrow>
      <PopoverTrigger disableButtonEnhancement>
        <span className="inbox-bell">
          <Button variant="subtle" icon={<IconBell size={18} />} aria-label={label} title={label} />
          {unread > 0 && (
            <span className="inbox-badge" aria-hidden>
              {unread > 99 ? '99+' : unread}
            </span>
          )}
        </span>
      </PopoverTrigger>

      <PopoverSurface>
        <div className="inbox-panel">
          <div className="spread" style={{ paddingBottom: '0.5rem' }}>
            <strong style={{ fontSize: '0.9rem' }}>Pemberitahuan</strong>
            {unread > 0 && (
              <Button variant="subtle" size="small" onClick={readAll}>
                Tandai semua terbaca
              </Button>
            )}
          </div>

          {loading && items.length === 0 && <p className="muted">Memuat…</p>}
          {!loading && items.length === 0 && (
            <p className="muted" style={{ padding: '0.75rem 0' }}>
              Belum ada pemberitahuan. Tiket yang lewat tenggat, gangguan baru, dan work order yang ditugaskan
              ke Anda akan muncul di sini.
            </p>
          )}

          <ul className="inbox-list">
            {items.map((n) => {
              const Icon = KIND_ICON[n.kind]
              return (
                <li key={n.id}>
                  <button
                    type="button"
                    className={n.readAt ? 'inbox-item' : 'inbox-item unread'}
                    onClick={() => openItem(n)}
                  >
                    <span className="inbox-item-icon" style={{ color: SEVERITY_TONE[n.severity] }} aria-hidden>
                      <Icon size={16} />
                    </span>
                    <span className="inbox-item-text">
                      <span className="inbox-item-title">{n.title}</span>
                      <span className="muted">{n.body}</span>
                      <span className="muted" style={{ fontSize: '0.72rem' }}>
                        {timeAgo(n.createdAt)}
                      </span>
                    </span>
                    {!n.readAt && <span className="inbox-item-dot" aria-label="Belum dibaca" />}
                  </button>
                </li>
              )
            })}
          </ul>
        </div>
      </PopoverSurface>
    </Popover>
  )
}
