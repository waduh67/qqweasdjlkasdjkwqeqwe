import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { api, ApiError } from '../api/client'
import type { PageResponse, User } from '../api/types'
import type { CustomerView } from '../api/network'
import type {
  EvidenceKind,
  EvidenceView,
  SignatureView,
  WorkOrderDashboardView,
  WorkOrderDetail,
  WorkOrderPriority,
  WorkOrderStatus,
  WorkOrderType,
  WorkOrderView,
} from '../api/workorder'
import { useCan } from '../auth/useCan'
import { Badge, Drawer, EmptyState, SkeletonRows, useToast } from '../components/ui'
import { IconPlus, IconWorkOrder } from '../components/icons'

const TYPE_LABEL: Record<WorkOrderType, string> = {
  PSB: 'Pasang Baru',
  REPAIR: 'Perbaikan',
  MIGRATION: 'Migrasi',
  DISMANTLE: 'Bongkar',
  PREVENTIVE: 'Preventif',
}

const STATUS_LABEL: Record<WorkOrderStatus, string> = {
  DRAFT: 'Draft',
  ASSIGNED: 'Ditugaskan',
  IN_PROGRESS: 'Dikerjakan',
  DONE: 'Selesai',
  CANCELLED: 'Batal',
}

const STATUS_TONE: Record<WorkOrderStatus, 'neutral' | 'accent' | 'warning' | 'good'> = {
  DRAFT: 'neutral',
  ASSIGNED: 'accent',
  IN_PROGRESS: 'warning',
  DONE: 'good',
  CANCELLED: 'neutral',
}

const PRIORITY_LABEL: Record<WorkOrderPriority, string> = {
  LOW: 'Rendah',
  NORMAL: 'Normal',
  HIGH: 'Tinggi',
  URGENT: 'Mendesak',
}

const EVENT_LABEL: Record<string, string> = {
  CREATED: 'Dibuat',
  UPDATED: 'Diperbarui',
  ASSIGNED: 'Ditugaskan',
  STARTED: 'Mulai dikerjakan',
  COMPLETED: 'Selesai',
  CANCELLED: 'Dibatalkan',
}

const KIND_LABEL: Record<EvidenceKind, string> = {
  BEFORE: 'Sebelum',
  AFTER: 'Sesudah',
  LOCATION: 'Lokasi',
  SERIAL: 'Serial ONU',
  OTHER: 'Lainnya',
}

const KINDS = Object.keys(KIND_LABEL) as EvidenceKind[]

const TYPES = Object.keys(TYPE_LABEL) as WorkOrderType[]
const PRIORITIES = Object.keys(PRIORITY_LABEL) as WorkOrderPriority[]
const STATUSES = Object.keys(STATUS_LABEL) as WorkOrderStatus[]

type Draft = {
  type: WorkOrderType
  title: string
  description: string
  priority: WorkOrderPriority
  customerId: string
  scheduledAt: string
  assignedTo: string
}

const EMPTY_DRAFT: Draft = {
  type: 'PSB',
  title: '',
  description: '',
  priority: 'NORMAL',
  customerId: '',
  scheduledAt: '',
  assignedTo: '',
}

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString('id-ID') : '—')
const toInstant = (local: string): string | null => (local ? new Date(local).toISOString() : null)
const fmtDbm = (v: number | null) => (v == null ? '—' : `${v.toFixed(2)} dBm`)

type RxTone = 'good' | 'warning' | 'critical'

/** Klasifikasi kasar redaman Rx ONU GPON (dBm): makin mendekati 0 makin kuat, di bawah −28 makin lemah. */
function rxHealth(dbm: number): { tone: RxTone; label: string } {
  if (dbm > -8) return { tone: 'critical', label: 'terlalu kuat' }
  if (dbm >= -25) return { tone: 'good', label: 'sehat' }
  if (dbm >= -28) return { tone: 'warning', label: 'waspada' }
  return { tone: 'critical', label: 'lemah' }
}

function WoStatusBadge({ status }: { status: WorkOrderStatus }) {
  return <Badge tone={STATUS_TONE[status]}>{STATUS_LABEL[status]}</Badge>
}

/**
 * Work order sisi operator/dispatcher: buat tugas lapangan, tugaskan ke teknisi,
 * lalu kelola lifecycle-nya (draft → ditugaskan → dikerjakan → selesai / batal).
 * Pengerjaan di lapangan (mulai/selesai) dilayani klien teknisi terpisah nanti;
 * di sini penekanannya pada penjadwalan, penugasan, dan meninjau/mengkurasi bukti
 * pengerjaan (foto & tanda tangan) yang diunggah teknisi.
 */
export function WorkOrdersPage() {
  const { can } = useCan()
  const toast = useToast()
  const [orders, setOrders] = useState<WorkOrderView[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<WorkOrderStatus | ''>('')
  const [type, setType] = useState<WorkOrderType | ''>('')
  const [draft, setDraft] = useState<Draft | null>(null)
  const [detail, setDetail] = useState<WorkOrderDetail | null>(null)
  // Ditambah tiap ada perubahan (buat/tugaskan/lifecycle) agar dashboard menghitung ulang.
  const [dashVersion, setDashVersion] = useState(0)

  // Pelanggan & teknisi untuk pemilih di form — best-effort; bila operator tak
  // punya izin melihatnya, pemilihnya cukup dikosongkan (tidak menggagalkan halaman).
  const [customers, setCustomers] = useState<CustomerView[]>([])
  const [technicians, setTechnicians] = useState<User[]>([])

  const reload = useCallback(async () => {
    const params = new URLSearchParams({ size: '100' })
    if (query.trim()) params.set('query', query.trim())
    if (status) params.set('status', status)
    if (type) params.set('type', type)
    try {
      const page = await api.get<PageResponse<WorkOrderView>>(`/api/work-orders?${params}`)
      setOrders(page.content)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat work order')
    } finally {
      setLoading(false)
    }
  }, [query, status, type, toast])

  useEffect(() => {
    void reload()
  }, [reload])

  useEffect(() => {
    void api
      .get<PageResponse<CustomerView>>('/api/customers?size=200')
      .then((p) => setCustomers(p.content))
      .catch(() => setCustomers([]))
    void api
      .get<PageResponse<User>>('/api/users?size=200')
      .then((p) => setTechnicians(p.content.filter((u) => u.status === 'ACTIVE')))
      .catch(() => setTechnicians([]))
  }, [])

  const openDetail = useCallback(
    async (id: string) => {
      try {
        setDetail(await api.get<WorkOrderDetail>(`/api/work-orders/${id}`))
      } catch (err) {
        toast.error(err instanceof ApiError ? err.message : 'Gagal memuat detail work order')
      }
    },
    [toast],
  )

  const run = async (action: () => Promise<unknown>, ok?: string, refreshId?: string) => {
    try {
      await action()
      await reload()
      setDashVersion((v) => v + 1)
      if (refreshId) await openDetail(refreshId)
      if (ok) toast.success(ok)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
    }
  }

  const submitCreate = () =>
    void run(async () => {
      if (!draft) return
      if (!draft.title.trim()) {
        toast.error('Judul tidak boleh kosong')
        throw new Error('validasi')
      }
      await api.post('/api/work-orders', {
        type: draft.type,
        title: draft.title.trim(),
        description: draft.description.trim() || null,
        priority: draft.priority,
        customerId: draft.customerId || null,
        scheduledAt: toInstant(draft.scheduledAt),
        assignedTo: draft.assignedTo || null,
      })
      setDraft(null)
    }, 'Work order dibuat')

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div className="spread">
        <div>
          <h1 className="page-title">Work Order</h1>
          <p className="page-sub">Tugas lapangan — penjadwalan, penugasan teknisi, dan lifecycle-nya.</p>
        </div>
        {can('workorder.order.create') && (
          <button className="primary" onClick={() => setDraft({ ...EMPTY_DRAFT })}>
            <IconPlus size={15} /> Buat work order
          </button>
        )}
      </div>

      {can('workorder.dashboard.view') && (
        <DispatchDashboard version={dashVersion} activeStatus={status} onPickStatus={setStatus} />
      )}

      <div className="row wrap" style={{ gap: '0.6rem' }}>
        <input
          placeholder="Cari kode atau judul…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ flex: 2, minWidth: 200 }}
        />
        <select value={status} onChange={(e) => setStatus(e.target.value as WorkOrderStatus | '')} style={{ flex: 1, minWidth: 140 }}>
          <option value="">Semua status</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {STATUS_LABEL[s]}
            </option>
          ))}
        </select>
        <select value={type} onChange={(e) => setType(e.target.value as WorkOrderType | '')} style={{ flex: 1, minWidth: 140 }}>
          <option value="">Semua tipe</option>
          {TYPES.map((t) => (
            <option key={t} value={t}>
              {TYPE_LABEL[t]}
            </option>
          ))}
        </select>
      </div>

      {draft && (
        <WorkOrderForm
          draft={draft}
          customers={customers}
          technicians={technicians}
          onChange={setDraft}
          onSubmit={submitCreate}
          onCancel={() => setDraft(null)}
        />
      )}

      {loading ? (
        <div className="card">
          <SkeletonRows rows={4} />
        </div>
      ) : orders.length === 0 ? (
        <div className="card">
          <EmptyState
            title={query || status || type ? 'Tidak ada work order yang cocok' : 'Belum ada work order'}
            hint={
              query || status || type
                ? 'Coba ubah filter atau kata kunci.'
                : 'Buat work order pertama untuk menjadwalkan pekerjaan lapangan.'
            }
            icon={<IconWorkOrder size={32} />}
          />
        </div>
      ) : (
        <div className="stack" style={{ gap: '0.6rem' }}>
          {orders.map((wo) => (
            <button key={wo.id} className="incident-row" onClick={() => void openDetail(wo.id)}>
              <span className="stack" style={{ gap: '0.3rem', minWidth: 0, alignItems: 'flex-start', flex: 1 }}>
                <span className="row" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
                  <span className="badge accent">{wo.code}</span>
                  <span className="badge">{TYPE_LABEL[wo.type]}</span>
                  {wo.priority !== 'NORMAL' && (
                    <Badge tone={wo.priority === 'URGENT' || wo.priority === 'HIGH' ? 'warning' : 'neutral'}>
                      {PRIORITY_LABEL[wo.priority]}
                    </Badge>
                  )}
                  <strong style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{wo.title}</strong>
                </span>
                <span className="muted" style={{ fontSize: '0.82rem' }}>
                  {wo.customerName ? `${wo.customerName} · ` : ''}
                  {wo.assignedToName ? `👷 ${wo.assignedToName}` : 'belum ditugaskan'}
                  {wo.scheduledAt ? ` · jadwal ${fmt(wo.scheduledAt)}` : ''}
                </span>
              </span>
              <WoStatusBadge status={wo.status} />
            </button>
          ))}
        </div>
      )}

      {detail && (
        <Drawer title={`${detail.workOrder.code} · ${detail.workOrder.title}`} onClose={() => setDetail(null)}>
          <WorkOrderDetailBody
            key={detail.workOrder.id}
            detail={detail}
            technicians={technicians}
            onAct={(action, ok, keepOpen) =>
              void run(action, ok, keepOpen ? detail.workOrder.id : undefined).then(() => {
                if (!keepOpen) setDetail(null)
              })
            }
          />
        </Drawer>
      )}
    </div>
  )
}

function Stat({ label, value, accent }: { label: string; value: number; accent?: 'crit' | 'warn' }) {
  return (
    <div className={`stat ${accent === 'crit' ? 'crit-bar' : accent === 'warn' ? 'warn-bar' : 'accent-bar'}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value.toLocaleString('id-ID')}</div>
    </div>
  )
}

/**
 * Papan dispatch: sekilas kondisi antrean kerja. Kartu ringkas (total, terbuka,
 * belum ditugaskan), pipeline per-status yang bisa diklik untuk memfilter daftar,
 * dan beban tiap teknisi (WO terbuka) agar penugasan bisa diseimbangkan.
 */
function DispatchDashboard({
  version,
  activeStatus,
  onPickStatus,
}: {
  version: number
  activeStatus: WorkOrderStatus | ''
  onPickStatus: (s: WorkOrderStatus | '') => void
}) {
  const [data, setData] = useState<WorkOrderDashboardView | null>(null)

  useEffect(() => {
    let active = true
    void api
      .get<WorkOrderDashboardView>('/api/work-orders/dashboard')
      .then((d) => active && setData(d))
      .catch(() => active && setData(null))
    return () => {
      active = false
    }
  }, [version])

  if (!data) return null

  return (
    <div className="card stack" style={{ gap: '0.9rem' }}>
      <div className="stat-grid">
        <Stat label="Total" value={data.total} />
        <Stat label="Terbuka" value={data.open} />
        <Stat label="Belum ditugaskan" value={data.unassignedOpen} accent={data.unassignedOpen > 0 ? 'warn' : undefined} />
      </div>

      {/* Pipeline status — klik untuk memfilter daftar; klik lagi untuk melepas filter. */}
      <div className="row wrap" style={{ gap: '0.4rem' }}>
        {STATUSES.map((s) => {
          const selected = activeStatus === s
          return (
            <button
              key={s}
              onClick={() => onPickStatus(selected ? '' : s)}
              className={selected ? 'primary' : 'ghost'}
              style={{ display: 'flex', gap: '0.4rem', alignItems: 'center', fontSize: '0.82rem', padding: '0.3rem 0.6rem' }}
              title={`Filter: ${STATUS_LABEL[s]}`}
            >
              {STATUS_LABEL[s]}
              <span className={`badge ${STATUS_TONE[s]}`}>{data.byStatus[s] ?? 0}</span>
            </button>
          )
        })}
      </div>

      {data.workloads.length > 0 && (
        <div className="stack" style={{ gap: '0.35rem' }}>
          <span className="muted" style={{ fontSize: '0.8rem' }}>Beban teknisi (WO terbuka)</span>
          {data.workloads.slice(0, 6).map((w) => (
            <div key={w.technicianId} className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
              <span style={{ flex: 1, fontSize: '0.85rem', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                👷 {w.technicianName ?? '—'}
              </span>
              <Badge tone="accent">{w.openCount}</Badge>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/** Form buat work order — tipe & teknisi hanya relevan saat pembuatan. */
function WorkOrderForm({
  draft,
  customers,
  technicians,
  onChange,
  onSubmit,
  onCancel,
}: {
  draft: Draft
  customers: CustomerView[]
  technicians: User[]
  onChange: (d: Draft) => void
  onSubmit: () => void
  onCancel: () => void
}) {
  return (
    <div className="card stack">
      <div className="row wrap">
        <label style={{ flex: 1, minWidth: 140 }}>
          <span>Tipe</span>
          <select value={draft.type} onChange={(e) => onChange({ ...draft, type: e.target.value as WorkOrderType })}>
            {TYPES.map((t) => (
              <option key={t} value={t}>
                {TYPE_LABEL[t]}
              </option>
            ))}
          </select>
        </label>
        <label style={{ flex: 3, minWidth: 200 }}>
          <span>Judul</span>
          <input value={draft.title} onChange={(e) => onChange({ ...draft, title: e.target.value })} placeholder="mis. Ganti drop core putus" />
        </label>
        <label style={{ flex: 1, minWidth: 120 }}>
          <span>Prioritas</span>
          <select value={draft.priority} onChange={(e) => onChange({ ...draft, priority: e.target.value as WorkOrderPriority })}>
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>
                {PRIORITY_LABEL[p]}
              </option>
            ))}
          </select>
        </label>
      </div>
      <label className="stack" style={{ gap: '0.25rem' }}>
        <span>Deskripsi</span>
        <textarea rows={2} maxLength={2000} value={draft.description} onChange={(e) => onChange({ ...draft, description: e.target.value })} />
      </label>
      <div className="row wrap">
        <label style={{ flex: 2, minWidth: 180 }}>
          <span>Pelanggan (opsional)</span>
          <select value={draft.customerId} onChange={(e) => onChange({ ...draft, customerId: e.target.value })}>
            <option value="">— tanpa pelanggan —</option>
            {customers.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} ({c.code})
              </option>
            ))}
          </select>
        </label>
        <label style={{ flex: 2, minWidth: 180 }}>
          <span>Teknisi (opsional)</span>
          <select value={draft.assignedTo} onChange={(e) => onChange({ ...draft, assignedTo: e.target.value })}>
            <option value="">— belum ditugaskan —</option>
            {technicians.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
        </label>
        <label style={{ flex: 1, minWidth: 180 }}>
          <span>Jadwal (opsional)</span>
          <input type="datetime-local" value={draft.scheduledAt} onChange={(e) => onChange({ ...draft, scheduledAt: e.target.value })} />
        </label>
      </div>
      <div className="row">
        <button className="primary" onClick={onSubmit}>
          Simpan
        </button>
        <button onClick={onCancel}>Batal</button>
      </div>
    </div>
  )
}

type ActFn = (action: () => Promise<unknown>, ok: string, keepOpen: boolean) => void

/** Detail + aksi lifecycle. Tombol yang muncul mengikuti status & izin. */
function WorkOrderDetailBody({
  detail,
  technicians,
  onAct,
}: {
  detail: WorkOrderDetail
  technicians: User[]
  onAct: ActFn
}) {
  const { can } = useCan()
  const wo = detail.workOrder
  // State awal cukup dari prop: komponen ini di-`key` pada id work order, jadi
  // berganti work order me-remount dan mereset pilihan ini dengan sendirinya.
  const [assignee, setAssignee] = useState(wo.assignedTo ?? '')
  const [note, setNote] = useState('')
  const [reason, setReason] = useState('')

  const id = wo.id
  const canAssign = can('workorder.order.assign')
  const canUpdate = can('workorder.order.update')
  const canClose = can('workorder.order.close')
  const terminal = wo.status === 'DONE' || wo.status === 'CANCELLED'

  return (
    <div className="stack" style={{ gap: '1.1rem' }}>
      <div className="row wrap" style={{ gap: '0.4rem' }}>
        <WoStatusBadge status={wo.status} />
        <span className="badge">{TYPE_LABEL[wo.type]}</span>
        <Badge tone={wo.priority === 'URGENT' || wo.priority === 'HIGH' ? 'warning' : 'neutral'}>
          {PRIORITY_LABEL[wo.priority]}
        </Badge>
        {wo.customerName && <span className="badge">{wo.customerName}</span>}
      </div>

      {wo.description && <p style={{ margin: 0, fontSize: '0.9rem' }}>{wo.description}</p>}

      <dl className="kv" style={{ margin: 0, display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '0.3rem 0.8rem', fontSize: '0.85rem' }}>
        <dt className="muted">Teknisi</dt>
        <dd style={{ margin: 0 }}>{wo.assignedToName ?? 'belum ditugaskan'}</dd>
        <dt className="muted">Jadwal</dt>
        <dd style={{ margin: 0 }}>{fmt(wo.scheduledAt)}</dd>
        <dt className="muted">Dibuat</dt>
        <dd style={{ margin: 0 }}>{fmt(wo.createdAt)}</dd>
        {wo.completedAt && (
          <>
            <dt className="muted">Selesai</dt>
            <dd style={{ margin: 0 }}>{fmt(wo.completedAt)}</dd>
          </>
        )}
        {wo.resolutionNote && (
          <>
            <dt className="muted">Catatan</dt>
            <dd style={{ margin: 0 }}>{wo.resolutionNote}</dd>
          </>
        )}
        {wo.cancelReason && (
          <>
            <dt className="muted">Alasan batal</dt>
            <dd style={{ margin: 0 }}>{wo.cancelReason}</dd>
          </>
        )}
      </dl>

      {/* Penugasan — selagi work order belum selesai/batal. */}
      {canAssign && !terminal && (
        <section className="stack" style={{ gap: '0.4rem' }}>
          <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Penugasan</h3>
          <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-end' }}>
            <label style={{ flex: 1 }}>
              <span>Teknisi</span>
              <select value={assignee} onChange={(e) => setAssignee(e.target.value)}>
                <option value="">— pilih teknisi —</option>
                {technicians.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
              </select>
            </label>
            <button
              className="primary"
              disabled={!assignee || assignee === wo.assignedTo}
              onClick={() => onAct(() => api.post(`/api/work-orders/${id}/assign`, { technicianId: assignee }), 'Teknisi ditugaskan', true)}
            >
              {wo.assignedTo ? 'Tugaskan ulang' : 'Tugaskan'}
            </button>
          </div>
        </section>
      )}

      {/* Aksi lifecycle. */}
      <div className="row wrap" style={{ gap: '0.5rem' }}>
        {canUpdate && wo.status === 'ASSIGNED' && (
          <button onClick={() => onAct(() => api.post(`/api/work-orders/${id}/start`), 'Pengerjaan dimulai', true)}>Mulai</button>
        )}
        {canUpdate && wo.status === 'DRAFT' && (
          <button
            className="ghost danger"
            onClick={() => onAct(() => api.del(`/api/work-orders/${id}`), 'Work order dihapus', false)}
          >
            Hapus
          </button>
        )}
      </div>

      {/* Selesaikan — hanya saat sedang dikerjakan. */}
      {canClose && wo.status === 'IN_PROGRESS' && (
        <section className="stack" style={{ gap: '0.4rem' }}>
          <label className="stack" style={{ gap: '0.25rem' }}>
            <span>Catatan penyelesaian (opsional)</span>
            <textarea rows={2} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
          <button
            className="primary"
            onClick={() => onAct(() => api.post(`/api/work-orders/${id}/complete`, { resolutionNote: note.trim() || null }), 'Work order selesai', true)}
          >
            Selesaikan
          </button>
        </section>
      )}

      {/* Pembatalan — selagi belum selesai/batal. */}
      {canClose && !terminal && (
        <section className="stack" style={{ gap: '0.4rem' }}>
          <label className="stack" style={{ gap: '0.25rem' }}>
            <span>Batalkan work order</span>
            <input placeholder="Alasan (opsional)" value={reason} onChange={(e) => setReason(e.target.value)} />
          </label>
          <button
            className="ghost danger"
            onClick={() => onAct(() => api.post(`/api/work-orders/${id}/cancel`, { reason: reason.trim() || null }), 'Work order dibatalkan', true)}
          >
            Batalkan
          </button>
        </section>
      )}

      {/* Redaman optik — bukti kualitas; disembunyikan hanya bila belum ada & tak boleh mengubah. */}
      {(canUpdate || wo.rxBeforeDbm != null || wo.rxAfterDbm != null) && (
        <OpticalSection wo={wo} canUpdate={canUpdate} onAct={onAct} />
      )}

      {can('workorder.evidence.view') && <EvidenceSection workOrderId={id} status={wo.status} />}

      <section className="stack" style={{ gap: '0.5rem' }}>
        <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Timeline</h3>
        <ol className="timeline">
          {detail.timeline.map((ev, i) => (
            <li key={i}>
              <span className="tl-dot" aria-hidden="true" />
              <div className="stack" style={{ gap: '0.15rem' }}>
                <strong style={{ fontSize: '0.85rem' }}>{EVENT_LABEL[ev.type] ?? ev.type}</strong>
                <span className="muted" style={{ fontSize: '0.82rem' }}>{ev.message}</span>
                <span className="muted" style={{ fontSize: '0.75rem' }}>{fmt(ev.at)}</span>
              </div>
            </li>
          ))}
        </ol>
      </section>
    </div>
  )
}

/** Satu angka redaman + indikator sehat/waspada/lemah. */
function RxStat({ label, value }: { label: string; value: number | null }) {
  const health = value != null ? rxHealth(value) : null
  return (
    <div className="stack" style={{ gap: '0.15rem' }}>
      <span className="muted" style={{ fontSize: '0.78rem' }}>{label}</span>
      <span className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
        <strong>{fmtDbm(value)}</strong>
        {health && <Badge tone={health.tone}>{health.label}</Badge>}
      </span>
    </div>
  )
}

/**
 * Redaman optik (Rx, dBm) sebelum & sesudah pengerjaan sebagai bukti kualitas.
 * Selisihnya menunjukkan perbaikan/penurunan sinyal; posisi terhadap ambang sehat
 * memberi indikasi cepat. Nilai GPON selalu negatif (rentang wajar −40..0 dBm).
 * Bisa direkam bertahap (before saat datang, after setelah selesai) selama WO belum batal.
 */
function OpticalSection({ wo, canUpdate, onAct }: { wo: WorkOrderView; canUpdate: boolean; onAct: ActFn }) {
  const [editing, setEditing] = useState(false)
  const [before, setBefore] = useState(wo.rxBeforeDbm?.toString() ?? '')
  const [after, setAfter] = useState(wo.rxAfterDbm?.toString() ?? '')

  const hasReading = wo.rxBeforeDbm != null || wo.rxAfterDbm != null
  const delta = wo.rxBeforeDbm != null && wo.rxAfterDbm != null ? wo.rxAfterDbm - wo.rxBeforeDbm : null

  const parse = (s: string): number | null => {
    const t = s.trim()
    if (!t) return null
    const n = Number(t)
    return Number.isFinite(n) ? n : null
  }

  const save = () => {
    onAct(
      () => api.put(`/api/work-orders/${wo.id}/optical`, { rxBeforeDbm: parse(before), rxAfterDbm: parse(after) }),
      'Pengukuran redaman optik disimpan',
      true,
    )
    setEditing(false)
  }

  const cancelEdit = () => {
    setBefore(wo.rxBeforeDbm?.toString() ?? '')
    setAfter(wo.rxAfterDbm?.toString() ?? '')
    setEditing(false)
  }

  return (
    <section className="stack" style={{ gap: '0.5rem' }}>
      <div className="spread" style={{ alignItems: 'center' }}>
        <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Redaman optik</h3>
        {canUpdate && !editing && (
          <button className="ghost" style={{ fontSize: '0.78rem', padding: '0.2rem 0.5rem' }} onClick={() => setEditing(true)}>
            {hasReading ? 'Ubah' : 'Catat'}
          </button>
        )}
      </div>

      {editing ? (
        <div className="stack" style={{ gap: '0.5rem' }}>
          <div className="row wrap" style={{ gap: '0.6rem' }}>
            <label style={{ flex: 1, minWidth: 130 }}>
              <span>Rx sebelum (dBm)</span>
              <input type="number" step="0.01" min={-40} max={0} value={before} onChange={(e) => setBefore(e.target.value)} placeholder="mis. -24.5" />
            </label>
            <label style={{ flex: 1, minWidth: 130 }}>
              <span>Rx sesudah (dBm)</span>
              <input type="number" step="0.01" min={-40} max={0} value={after} onChange={(e) => setAfter(e.target.value)} placeholder="mis. -20.1" />
            </label>
          </div>
          <div className="row" style={{ gap: '0.5rem' }}>
            <button className="primary" onClick={save}>Simpan</button>
            <button onClick={cancelEdit}>Batal</button>
          </div>
          <p className="muted" style={{ margin: 0, fontSize: '0.75rem' }}>GPON selalu negatif; rentang wajar −40..0 dBm. Kosongkan bila belum diukur.</p>
        </div>
      ) : hasReading ? (
        <div className="row wrap" style={{ gap: '1.2rem', alignItems: 'flex-start' }}>
          <RxStat label="Sebelum" value={wo.rxBeforeDbm} />
          <RxStat label="Sesudah" value={wo.rxAfterDbm} />
          {delta != null && (
            <div className="stack" style={{ gap: '0.15rem' }}>
              <span className="muted" style={{ fontSize: '0.78rem' }}>Selisih</span>
              <Badge tone={delta >= 0 ? 'good' : 'warning'}>
                {delta >= 0 ? '▲ membaik' : '▼ menurun'} {Math.abs(delta).toFixed(2)} dB
              </Badge>
            </div>
          )}
        </div>
      ) : (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada pengukuran.</p>
      )}
    </section>
  )
}

/**
 * Gambar berkonten terautentikasi. `<img src>` biasa tak bisa mengirim header
 * Bearer, jadi byte-nya diambil sebagai blob lalu dijadikan object URL; URL-nya
 * dicabut saat unmount / ganti sumber agar tak bocor memori.
 */
function AuthedImage({ path, alt, size }: { path: string; alt: string; size: number }) {
  const [url, setUrl] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let active = true
    let objectUrl: string | null = null
    setUrl(null)
    setFailed(false)
    api
      .blob(path)
      .then((b) => {
        if (!active) return
        objectUrl = URL.createObjectURL(b)
        setUrl(objectUrl)
      })
      .catch(() => active && setFailed(true))
    return () => {
      active = false
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [path])

  const box: CSSProperties = {
    width: size,
    height: size,
    borderRadius: 8,
    objectFit: 'cover',
    background: 'var(--surface-2, #1e2530)',
    border: '1px solid var(--border, #2a3340)',
  }
  if (failed) return <div style={{ ...box, display: 'grid', placeItems: 'center', fontSize: '0.7rem' }} className="muted">gagal</div>
  if (!url) return <div style={box} aria-busy="true" />
  return (
    <a href={url} target="_blank" rel="noreferrer" title={alt}>
      <img src={url} alt={alt} style={box} />
    </a>
  )
}

/**
 * Bukti pengerjaan sebuah work order: galeri foto + tanda tangan. Operator meninjau
 * (dan bila perlu mengkurasi) bukti yang diunggah teknisi. Unggah/hapus hanya untuk
 * yang berizin `workorder.evidence.manage` dan selama work order sudah dikerjakan
 * (bukan draft/batal — server juga menegakkan ini).
 */
function EvidenceSection({ workOrderId, status }: { workOrderId: string; status: WorkOrderStatus }) {
  const { can } = useCan()
  const toast = useToast()
  const canManage = can('workorder.evidence.manage')
  const documentable = status !== 'DRAFT' && status !== 'CANCELLED'

  const [photos, setPhotos] = useState<EvidenceView[]>([])
  const [signature, setSignature] = useState<SignatureView | null>(null)
  const [loading, setLoading] = useState(true)
  const [kind, setKind] = useState<EvidenceKind>('AFTER')
  const [caption, setCaption] = useState('')
  const [busy, setBusy] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)

  const reload = useCallback(async () => {
    try {
      const [ph, sg] = await Promise.all([
        api.get<EvidenceView[]>(`/api/work-orders/${workOrderId}/evidence`),
        // 204 (belum ada tanda tangan) → api.get mengembalikan undefined.
        api.get<SignatureView | undefined>(`/api/work-orders/${workOrderId}/signature`),
      ])
      setPhotos(ph)
      setSignature(sg ?? null)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat bukti pengerjaan')
    } finally {
      setLoading(false)
    }
  }, [workOrderId, toast])

  useEffect(() => {
    void reload()
  }, [reload])

  const upload = async () => {
    const file = fileRef.current?.files?.[0]
    if (!file) {
      toast.error('Pilih berkas foto dulu')
      return
    }
    const form = new FormData()
    form.set('file', file)
    form.set('kind', kind)
    if (caption.trim()) form.set('caption', caption.trim())
    setBusy(true)
    try {
      await api.postForm(`/api/work-orders/${workOrderId}/evidence`, form)
      setCaption('')
      if (fileRef.current) fileRef.current.value = ''
      await reload()
      toast.success('Foto bukti diunggah')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengunggah foto')
    } finally {
      setBusy(false)
    }
  }

  const removePhoto = async (evidenceId: string) => {
    try {
      await api.del(`/api/work-orders/${workOrderId}/evidence/${evidenceId}`)
      await reload()
      toast.success('Foto dihapus')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus foto')
    }
  }

  const removeSignature = async () => {
    try {
      await api.del(`/api/work-orders/${workOrderId}/signature`)
      setSignature(null)
      toast.success('Tanda tangan dihapus')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus tanda tangan')
    }
  }

  return (
    <section className="stack" style={{ gap: '0.6rem' }}>
      <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Bukti pengerjaan</h3>

      {loading ? (
        <SkeletonRows rows={1} />
      ) : (
        <>
          {photos.length === 0 && !signature ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada bukti diunggah.</p>
          ) : (
            <div className="stack" style={{ gap: '0.6rem' }}>
              {photos.length > 0 && (
                <div className="row wrap" style={{ gap: '0.6rem' }}>
                  {photos.map((ph) => (
                    <div key={ph.id} className="stack" style={{ gap: '0.25rem', width: 96 }}>
                      <AuthedImage path={`/api/work-orders/${workOrderId}/evidence/${ph.id}/content`} alt={ph.caption ?? KIND_LABEL[ph.kind]} size={96} />
                      <span className="badge" style={{ fontSize: '0.7rem' }}>{KIND_LABEL[ph.kind]}</span>
                      {ph.caption && <span className="muted" style={{ fontSize: '0.72rem' }}>{ph.caption}</span>}
                      {ph.uploadedByName && <span className="muted" style={{ fontSize: '0.68rem' }}>oleh {ph.uploadedByName}</span>}
                      {canManage && (
                        <button className="ghost danger" style={{ fontSize: '0.72rem', padding: '0.15rem 0.4rem' }} onClick={() => void removePhoto(ph.id)}>
                          Hapus
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              )}

              {signature && (
                <div className="stack" style={{ gap: '0.25rem', alignItems: 'flex-start' }}>
                  <span className="muted" style={{ fontSize: '0.82rem' }}>Tanda tangan · {signature.signerName}</span>
                  <AuthedImage path={`/api/work-orders/${workOrderId}/signature/content`} alt={`Tanda tangan ${signature.signerName}`} size={140} />
                  <span className="muted" style={{ fontSize: '0.72rem' }}>{fmt(signature.signedAt)}</span>
                  {canManage && (
                    <button className="ghost danger" style={{ fontSize: '0.72rem', padding: '0.15rem 0.4rem' }} onClick={() => void removeSignature()}>
                      Hapus tanda tangan
                    </button>
                  )}
                </div>
              )}
            </div>
          )}

          {canManage && documentable && (
            <div className="row wrap" style={{ gap: '0.5rem', alignItems: 'flex-end' }}>
              <label style={{ minWidth: 130 }}>
                <span>Jenis</span>
                <select value={kind} onChange={(e) => setKind(e.target.value as EvidenceKind)}>
                  {KINDS.map((k) => (
                    <option key={k} value={k}>
                      {KIND_LABEL[k]}
                    </option>
                  ))}
                </select>
              </label>
              <label style={{ flex: 1, minWidth: 160 }}>
                <span>Keterangan (opsional)</span>
                <input value={caption} onChange={(e) => setCaption(e.target.value)} placeholder="mis. sambungan core setelah splice" />
              </label>
              <input ref={fileRef} type="file" accept="image/*" style={{ maxWidth: 200 }} />
              <button className="primary" disabled={busy} onClick={() => void upload()}>
                {busy ? 'Mengunggah…' : 'Unggah foto'}
              </button>
            </div>
          )}
        </>
      )}
    </section>
  )
}
