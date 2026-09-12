import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Text, typographyStyles } from '@fluentui/react-components'
import { useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '@/api/client'
import {
  ORDER_PORTAL_FLAG_LABEL,
  ORDER_STATUS_LABEL,
  ORDER_TRANSITION_LABEL,
  canTransition,
  derivePortalFlag,
  flagOrder,
  getOrder,
  getOrderTimeline,
  markOrderUnreachable,
  newOrderOperation,
  transitionOrder,
  type OrderOperation,
  type OrderPortalFlag,
  type OrderTimelineEntryView,
  type OrderTransition,
  type OrderView,
} from '@/api/order'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, SelectField, Spinner, TextField, TextareaField } from '@/components/atoms'
import { IconAlert, IconInbox } from '@/components/atoms/icons'
import { ConfirmDialog, Modal, PageHeader } from '@/components/molecules'
import { useToast } from '@/system'
import { ORDER_STATUS_TONE } from './OrdersPage'

const TRANSITIONS: readonly OrderTransition[] = ['SUBMIT', 'ACCEPT', 'SCHEDULE', 'START_FULFILLING', 'FULFILL', 'CANCEL', 'REJECT']

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString('id-ID') : '—')

const toInstant = (local: string): string | null => (local ? new Date(local).toISOString() : null)

/** Kalimat penjelas tiap transisi — operator harus tahu apa yang terjadi SETELAH tombolnya ditekan. */
const TRANSITION_EFFECT: Record<OrderTransition, string> = {
  SUBMIT: 'Pesanan masuk ke antrean peninjauan dan mulai terlihat di halaman lacak pelanggan.',
  ACCEPT:
    'Menerima pesanan sekaligus mempromosikan calon pelanggan menjadi pelanggan dan membuka work order PSB. ' +
    'Tiga hal ini terjadi dalam satu langkah dan tidak bisa dibatalkan sebagian.',
  SCHEDULE: 'Menetapkan janji temu pemasangan pada pesanan.',
  START_FULFILLING: 'Menandai pemasangan sedang dikerjakan.',
  FULFILL: 'Menutup pesanan sebagai selesai. Biasanya ini terjadi otomatis saat work order PSB disetujui.',
  CANCEL: 'Membatalkan pesanan. Alasan wajib diisi dan tercatat di riwayat.',
  REJECT: 'Menolak pesanan. Alasan wajib diisi dan tercatat di riwayat.',
}

type Pending = { transition: OrderTransition; reason: string; startsAt: string; endsAt: string }

type AttentionDraft = { flag: OrderPortalFlag; reason: string }

/**
 * Detail satu pesanan: data pemesan, riwayat, transisi status, dan penanda portal.
 *
 * Dua hal di halaman ini yang mudah tertukar dan berakibat nyata:
 *  - `Terima` BUKAN sekadar pindah status (lihat [TRANSITION_EFFECT]);
 *  - alasan pada "Tandai perhatian" ikut DIBACA PELANGGAN, sedangkan catatan pada "tidak bisa
 *    dihubungi" tidak. Keduanya diberi label yang menyatakan itu di layar.
 */
export function OrderDetailPage() {
  const { id = '' } = useParams<{ id: string }>()
  const { can } = useCan()
  const navigate = useNavigate()
  const toast = useToast()
  const [order, setOrder] = useState<OrderView | null>(null)
  const [timeline, setTimeline] = useState<OrderTimelineEntryView[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [pending, setPending] = useState<Pending | null>(null)
  const [attention, setAttention] = useState<AttentionDraft | null>(null)
  const [unreachable, setUnreachable] = useState<string | null>(null)

  /**
   * Identitas operasi yang sedang dicoba, per (aksi, revisi).
   *
   * Disimpan dan DIPAKAI ULANG sampai permintaannya benar-benar berhasil. Kalau kuncinya dibuat
   * baru tiap klik, operator yang menekan "Terima" lagi karena responsnya hilang di jaringan
   * akan membuka work order PSB KEDUA untuk pemasangan yang sama — dan teknisi berangkat dua
   * kali ke rumah yang sama.
   */
  const operations = useRef(new Map<string, OrderOperation>())
  const operationFor = (action: string, revision: number): OrderOperation => {
    const slot = `${action}:${revision}`
    const existing = operations.current.get(slot)
    if (existing) return existing
    const fresh = newOrderOperation(`order.${action.toLowerCase()}`)
    operations.current.set(slot, fresh)
    return fresh
  }

  const canView = can('order.order.view')
  const canManage = can('order.order.manage')

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const [detail, history] = await Promise.all([getOrder(id), getOrderTimeline(id)])
      setOrder(detail)
      setTimeline(history)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Gagal memuat pesanan')
    } finally {
      setLoading(false)
    }
  }, [id, toast])

  useEffect(() => {
    if (canView) void reload()
  }, [canView, reload])

  /**
   * @return true kalau aksinya benar-benar berhasil. Pemanggil memakai nilai ini untuk menutup
   * dialognya: menutup dialog setelah GAGAL berarti alasan pembatalan yang baru saja diketik
   * operator hilang, dan ia harus mengarangnya ulang.
   */
  const run = async (action: string, call: (operation: OrderOperation) => Promise<unknown>, success: string): Promise<boolean> => {
    if (!order || busy) return false
    setBusy(true)
    const operation = operationFor(action, order.revision)
    try {
      await call(operation)
      operations.current.delete(`${action}:${order.revision}`)
      await reload()
      toast.success(success)
      return true
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Aksi pesanan gagal')
      return false
    } finally {
      setBusy(false)
    }
  }

  const submitTransition = () => {
    if (!order || !pending) return
    const { transition, reason, startsAt, endsAt } = pending
    if ((transition === 'CANCEL' || transition === 'REJECT') && !reason.trim()) {
      toast.error('Alasan wajib diisi')
      return
    }
    const appointmentStart = toInstant(startsAt)
    const appointmentEnd = toInstant(endsAt)
    if (transition === 'SCHEDULE' && (!appointmentStart || !appointmentEnd)) {
      toast.error('Janji temu wajib diisi lengkap')
      return
    }
    void run(
      transition,
      (operation) =>
        transitionOrder(order.id, transition, {
          expectedRevision: order.revision,
          operation,
          reason: reason.trim() || null,
          appointment: appointmentStart && appointmentEnd ? { startsAt: appointmentStart, endsAt: appointmentEnd } : null,
        }),
      `Pesanan: ${ORDER_TRANSITION_LABEL[transition]} berhasil`,
    ).then((ok) => ok && setPending(null))
  }

  if (!canView) {
    return (
      <div className="card">
        <EmptyState title="Tak berizin" hint="Anda memerlukan izin order.order.view untuk membuka pesanan." icon={<IconInbox size={32} />} />
      </div>
    )
  }

  if (loading && !order) return <div className="card"><Spinner /></div>

  if (!order) {
    return (
      <div className="card">
        <EmptyState title="Pesanan tidak ditemukan" hint="Pesanan mungkin sudah dihapus atau milik tenant lain." icon={<IconInbox size={32} />} />
      </div>
    )
  }

  const flag = derivePortalFlag(timeline)

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title={order.orderNumber ?? 'Pesanan'}
        subtitle={`${order.serviceAddress.address}, ${order.serviceAddress.city} ${order.serviceAddress.postalCode}`}
        actions={<Button variant="subtle" onClick={() => navigate('/orders')}>Kembali ke antrean</Button>}
      />

      <div className="card stack">
        <div className="row wrap" style={{ alignItems: 'center', gap: '0.5rem' }}>
          <Badge tone={ORDER_STATUS_TONE[order.status]}>{ORDER_STATUS_LABEL[order.status]}</Badge>
          {flag && <Badge tone={flag.flag === 'REQUIRES_ATTENTION' ? 'critical' : 'warning'}>{ORDER_PORTAL_FLAG_LABEL[flag.flag]}</Badge>}
          <Text as="span" className="muted" size={200}>Revisi {order.revision}</Text>
        </div>
        <dl className="stack" style={{ gap: '0.35rem', margin: 0 }}>
          <Row label="Pemesan">{order.leadId ? 'Calon pelanggan' : 'Pelanggan terdaftar'}</Row>
          <Row label="Janji temu">{order.appointment ? `${fmt(order.appointment.startsAt)} – ${fmt(order.appointment.endsAt)}` : '—'}</Row>
          {order.cancellationReason && <Row label="Alasan pembatalan">{order.cancellationReason}</Row>}
          {order.rejectionReason && <Row label="Alasan penolakan">{order.rejectionReason}</Row>}
        </dl>
        <div className="stack" style={{ gap: '0.25rem' }}>
          <Text as="span" weight="semibold" size={300}>Baris pesanan</Text>
          {order.lines.map((line) => (
            <Text as="span" key={`${line.catalogItemId}-${line.description}`} size={200}>
              {line.quantity} × {line.description}
            </Text>
          ))}
        </div>
      </div>

      <div className="card stack">
        <Text as="h3" style={{ ...typographyStyles.subtitle2, margin: 0 }}>Ubah status</Text>
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Tombol yang tidak sah untuk status {ORDER_STATUS_LABEL[order.status]} dimatikan; server menolaknya dengan 409.
        </Text>
        <div className="row wrap">
          {TRANSITIONS.map((transition) => (
            <Button
              key={transition}
              variant={transition === 'CANCEL' || transition === 'REJECT' ? 'danger' : 'default'}
              disabled={busy || !canManage || !canTransition(order.status, transition)}
              title={TRANSITION_EFFECT[transition]}
              onClick={() => setPending({ transition, reason: '', startsAt: '', endsAt: '' })}
            >
              {ORDER_TRANSITION_LABEL[transition]}
            </Button>
          ))}
        </div>
      </div>

      <div className="card stack">
        <Text as="h3" style={{ ...typographyStyles.subtitle2, margin: 0 }}>Penanda perhatian</Text>
        {flag ? (
          <Text as="p" size={200} style={{ margin: 0 }}>
            Terpasang {fmt(flag.occurredAt)}: <strong>{ORDER_PORTAL_FLAG_LABEL[flag.flag]}</strong>
            {flag.reason ? ` — "${flag.reason}"` : ''}
          </Text>
        ) : (
          <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Pesanan ini tidak sedang bertanda.</Text>
        )}
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Penanda juga dipasang OTOMATIS oleh sistem (kunjungan gagal karena pelanggan, pemenuhan yang macet).
          Server belum memulangkan sumber penandanya, jadi layar ini tidak bisa memastikan penanda di atas dipasang operator atau sistem.
        </Text>
        <div className="row wrap">
          <Button disabled={busy || !canManage} onClick={() => setAttention({ flag: 'WAITING_CUSTOMER', reason: '' })}>
            <IconAlert size={16} /> Tandai menunggu pelanggan
          </Button>
          <Button disabled={busy || !canManage} onClick={() => setAttention({ flag: 'REQUIRES_ATTENTION', reason: '' })}>
            <IconAlert size={16} /> Tandai perlu perhatian
          </Button>
          <Button
            disabled={busy || !canManage || order.status !== 'ACCEPTED' || order.appointment != null}
            title="Hanya untuk pesanan yang sudah diterima, belum punya janji temu, dan sudah lama didiamkan."
            onClick={() => setUnreachable('')}
          >
            Pelanggan tidak bisa dihubungi
          </Button>
          <Button
            variant="subtle"
            disabled={busy || !canManage || !flag}
            onClick={() =>
              void run(
                'unflag',
                (operation) => flagOrder(order.id, { flag: null, reason: null, operation }),
                'Penanda dilepas',
              )
            }
          >
            Lepas penanda
          </Button>
        </div>
      </div>

      <div className="card stack">
        <Text as="h3" style={{ ...typographyStyles.subtitle2, margin: 0 }}>Riwayat</Text>
        {timeline.length === 0 ? (
          <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Belum ada riwayat.</Text>
        ) : (
          <ol className="stack" style={{ gap: '0.4rem', margin: 0, paddingLeft: '1rem' }}>
            {[...timeline].reverse().map((entry) => (
              <li key={`${entry.revision}-${entry.eventType}`}>
                <Text as="span" size={200}>
                  {fmt(entry.occurredAt)} · {entry.eventType}
                  {entry.fromStatus ? ` · ${entry.fromStatus} → ${entry.toStatus}` : ` · ${entry.toStatus}`}
                  {entry.reason ? ` · ${entry.reason}` : ''}
                </Text>
              </li>
            ))}
          </ol>
        )}
      </div>

      {pending && pending.transition !== 'SCHEDULE' && pending.transition !== 'CANCEL' && pending.transition !== 'REJECT' && (
        <ConfirmDialog
          title={ORDER_TRANSITION_LABEL[pending.transition]}
          message={TRANSITION_EFFECT[pending.transition]}
          confirmLabel={ORDER_TRANSITION_LABEL[pending.transition]}
          busy={busy}
          onConfirm={submitTransition}
          onClose={() => setPending(null)}
        />
      )}

      {pending && (pending.transition === 'CANCEL' || pending.transition === 'REJECT') && (
        <Modal
          title={ORDER_TRANSITION_LABEL[pending.transition]}
          onClose={() => setPending(null)}
          footer={
            <>
              <Button variant="subtle" onClick={() => setPending(null)} disabled={busy}>Batal</Button>
              <Button variant="danger" onClick={submitTransition} disabled={busy}>{ORDER_TRANSITION_LABEL[pending.transition]}</Button>
            </>
          }
        >
          <TextareaField
            label="Alasan (wajib)"
            rows={3}
            maxLength={300}
            value={pending.reason}
            onChange={(_, data) => setPending({ ...pending, reason: data.value })}
          />
        </Modal>
      )}

      {pending && pending.transition === 'SCHEDULE' && (
        <Modal
          title="Jadwalkan pemasangan"
          onClose={() => setPending(null)}
          footer={
            <>
              <Button variant="subtle" onClick={() => setPending(null)} disabled={busy}>Batal</Button>
              <Button variant="primary" onClick={submitTransition} disabled={busy}>Jadwalkan</Button>
            </>
          }
        >
          <TextField
            label="Mulai"
            type="datetime-local"
            value={pending.startsAt}
            onChange={(_, data) => setPending({ ...pending, startsAt: data.value })}
          />
          <TextField
            label="Selesai"
            type="datetime-local"
            value={pending.endsAt}
            onChange={(_, data) => setPending({ ...pending, endsAt: data.value })}
          />
        </Modal>
      )}

      {attention && (
        <Modal
          title="Tandai pesanan"
          onClose={() => setAttention(null)}
          footer={
            <>
              <Button variant="subtle" onClick={() => setAttention(null)} disabled={busy}>Batal</Button>
              <Button
                variant="primary"
                disabled={busy}
                onClick={() =>
                  void run(
                    'attention',
                    (operation) => flagOrder(order.id, { flag: attention.flag, reason: attention.reason.trim() || null, operation }),
                    'Penanda dipasang',
                  ).then((ok) => ok && setAttention(null))
                }
              >
                Pasang penanda
              </Button>
            </>
          }
        >
          <SelectField
            label="Jenis penanda"
            value={attention.flag}
            onChange={(_, data) => setAttention({ ...attention, flag: data.value as OrderPortalFlag })}
          >
            <option value="WAITING_CUSTOMER">{ORDER_PORTAL_FLAG_LABEL.WAITING_CUSTOMER}</option>
            <option value="REQUIRES_ATTENTION">{ORDER_PORTAL_FLAG_LABEL.REQUIRES_ATTENTION}</option>
          </SelectField>
          <TextareaField
            label="Kalimat untuk pelanggan"
            hint="DIBACA PELANGGAN di halaman lacak. Tulis langkah yang harus ia ambil, bukan catatan internal."
            rows={3}
            maxLength={300}
            value={attention.reason}
            onChange={(_, data) => setAttention({ ...attention, reason: data.value })}
          />
        </Modal>
      )}

      {unreachable !== null && (
        <Modal
          title="Pelanggan tidak bisa dihubungi"
          onClose={() => setUnreachable(null)}
          footer={
            <>
              <Button variant="subtle" onClick={() => setUnreachable(null)} disabled={busy}>Batal</Button>
              <Button
                variant="primary"
                disabled={busy}
                onClick={() =>
                  void run(
                    'unreachable',
                    (operation) => markOrderUnreachable(order.id, { note: unreachable.trim() || null, operation }),
                    'Pesanan ditandai menunggu pelanggan',
                  ).then((ok) => ok && setUnreachable(null))
                }
              >
                Tandai
              </Button>
            </>
          }
        >
          <Text as="p" size={200} style={{ margin: 0 }}>
            Kalimat yang dibaca pelanggan ditulis SISTEM, bukan Anda. Catatan di bawah hanya masuk riwayat internal.
          </Text>
          <TextareaField
            label="Catatan internal (opsional)"
            rows={3}
            maxLength={300}
            value={unreachable}
            onChange={(_, data) => setUnreachable(data.value)}
          />
        </Modal>
      )}
    </div>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="row wrap" style={{ gap: '0.5rem' }}>
      <Text as="span" className="muted" size={200} style={{ minWidth: '10rem' }}>{label}</Text>
      <Text as="span" size={200}>{children}</Text>
    </div>
  )
}
