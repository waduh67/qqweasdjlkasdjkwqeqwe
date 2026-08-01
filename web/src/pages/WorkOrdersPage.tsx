import { useCallback, useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { api, ApiError } from '../api/client'
import type { PageResponse, Role, User } from '../api/types'
import type { CustomerView } from '../api/network'
import type {
  EvidenceKind,
  EvidenceView,
  SignatureView,
  WorkOrderApprovalStatus,
  WorkOrderDashboardView,
  WorkOrderDetail,
  WorkOrderPriority,
  WorkOrderStatus,
  WorkOrderType,
  WorkOrderView,
} from '../api/workorder'
import { useCan } from '../auth/useCan'
import { DataTable, type Column } from '../components/DataTable'
import { Badge, Drawer, EmptyState, Modal, SearchInput, SkeletonRows, Tabs, Toolbar, useToast } from '../components/ui'
import { Combobox } from '../components/Combobox'
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
  APPROVED: 'Disetujui',
  REJECTED: 'Ditolak',
}

const APPROVAL_LABEL: Record<WorkOrderApprovalStatus, string> = {
  PENDING: 'Menunggu persetujuan',
  APPROVED: 'Disetujui',
  REJECTED: 'Ditolak',
}

const APPROVAL_TONE: Record<WorkOrderApprovalStatus, 'warning' | 'good' | 'critical'> = {
  PENDING: 'warning',
  APPROVED: 'good',
  REJECTED: 'critical',
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

/** Satu pasang label/nilai dalam grid ringkasan (`.wo-grid`). */
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </>
  )
}

/** Nada prioritas: tinggi/mendesak menonjol (warning), sisanya netral. */
const priorityTone = (p: WorkOrderPriority): 'warning' | 'neutral' =>
  p === 'URGENT' || p === 'HIGH' ? 'warning' : 'neutral'

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
  const [approval, setApproval] = useState<WorkOrderApprovalStatus | ''>('')
  const [assignedTo, setAssignedTo] = useState('')
  const [draft, setDraft] = useState<Draft | null>(null)
  const [detail, setDetail] = useState<WorkOrderDetail | null>(null)
  // Ditambah tiap ada perubahan (buat/tugaskan/lifecycle) agar dashboard menghitung ulang.
  const [dashVersion, setDashVersion] = useState(0)

  // Teknisi untuk pemilih & filter — best-effort; bila operator tak punya izin
  // melihatnya, pemilihnya cukup dikosongkan (tidak menggagalkan halaman). Pelanggan
  // TIDAK dimuat borongan: dicari sisi-server lewat combobox agar tahan ribuan baris.
  const [technicians, setTechnicians] = useState<User[]>([])

  // Pencarian pelanggan sisi-server untuk combobox — ambil sedikit per ketikan, bukan
  // menarik ratusan/ribuan ke klien. Gagal (mis. tak berizin) → daftar kosong, form tetap jalan.
  const fetchCustomers = useCallback(async (term: string): Promise<CustomerView[]> => {
    const params = new URLSearchParams({ size: '8' })
    if (term) params.set('query', term)
    try {
      const p = await api.get<PageResponse<CustomerView>>(`/api/customers?${params}`)
      return p.content
    } catch {
      return []
    }
  }, [])

  // Teknisi difilter lokal (jumlahnya dibatasi role) — bungkus jadi Promise agar
  // antarmuka combobox seragam dengan pencarian pelanggan sisi-server.
  const fetchTechnicians = useCallback(
    async (term: string): Promise<User[]> => {
      const t = term.toLowerCase()
      return t ? technicians.filter((u) => u.name.toLowerCase().includes(t)) : technicians
    },
    [technicians],
  )

  const reload = useCallback(async () => {
    const params = new URLSearchParams({ size: '100' })
    if (query.trim()) params.set('query', query.trim())
    if (status) params.set('status', status)
    if (type) params.set('type', type)
    if (approval) params.set('approval', approval)
    if (assignedTo) params.set('assignedTo', assignedTo)
    try {
      const page = await api.get<PageResponse<WorkOrderView>>(`/api/work-orders?${params}`)
      setOrders(page.content)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat work order')
    } finally {
      setLoading(false)
    }
  }, [query, status, type, approval, assignedTo, toast])

  useEffect(() => {
    void reload()
  }, [reload])

  useEffect(() => {
    // Pemilih teknisi disaring ke pemegang role "Teknisi" (bukan semua user aktif) agar
    // penugasan hanya jatuh ke petugas lapangan. Bila role belum ada / tak berizin lihat
    // roles, jatuh balik ke semua user aktif supaya form tetap bisa dipakai.
    void Promise.all([
      api.get<PageResponse<User>>('/api/users?size=200'),
      api.get<Role[]>('/api/roles').catch(() => [] as Role[]),
    ])
      .then(([users, roles]) => {
        const active = users.content.filter((u) => u.status === 'ACTIVE')
        const technicianRole = roles.find((r) => r.name === 'Teknisi')
        setTechnicians(
          technicianRole
            ? active.filter((u) => u.roleIds.includes(technicianRole.id))
            : active,
        )
      })
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

  const columns: Column<WorkOrderView>[] = [
    {
      key: 'code',
      header: 'Kode',
      sortValue: (wo) => wo.code,
      cell: (wo) => <span className="badge accent">{wo.code}</span>,
    },
    {
      key: 'type',
      header: 'Tipe',
      sortValue: (wo) => TYPE_LABEL[wo.type],
      cell: (wo) => <span className="badge">{TYPE_LABEL[wo.type]}</span>,
    },
    {
      key: 'title',
      header: 'Judul',
      sortValue: (wo) => wo.title,
      cell: (wo) => (
        <div className="row" style={{ gap: '0.4rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <strong>{wo.title}</strong>
          {wo.priority !== 'NORMAL' && <Badge tone={priorityTone(wo.priority)}>{PRIORITY_LABEL[wo.priority]}</Badge>}
        </div>
      ),
    },
    {
      key: 'customer',
      header: 'Pelanggan',
      sortValue: (wo) => wo.customerName,
      cell: (wo) => wo.customerName ?? <span className="muted">—</span>,
    },
    {
      key: 'assignee',
      header: 'Teknisi',
      sortValue: (wo) => wo.assignedToName,
      cell: (wo) => wo.assignedToName ?? <span className="muted">belum ditugaskan</span>,
    },
    {
      key: 'scheduledAt',
      header: 'Jadwal',
      sortValue: (wo) => wo.scheduledAt,
      cell: (wo) => (wo.scheduledAt ? fmt(wo.scheduledAt) : <span className="muted">—</span>),
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (wo) => wo.status,
      cell: (wo) => <WoStatusBadge status={wo.status} />,
    },
  ]

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
        <DispatchDashboard
          version={dashVersion}
          activeStatus={status}
          onPickStatus={setStatus}
          activeApproval={approval}
          onToggleApproval={() => setApproval((a) => (a === 'PENDING' ? '' : 'PENDING'))}
        />
      )}

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari kode atau judul…" />
        <select value={status} onChange={(e) => setStatus(e.target.value as WorkOrderStatus | '')}>
          <option value="">Semua status</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {STATUS_LABEL[s]}
            </option>
          ))}
        </select>
        <select value={type} onChange={(e) => setType(e.target.value as WorkOrderType | '')}>
          <option value="">Semua tipe</option>
          {TYPES.map((t) => (
            <option key={t} value={t}>
              {TYPE_LABEL[t]}
            </option>
          ))}
        </select>
        <select value={approval} onChange={(e) => setApproval(e.target.value as WorkOrderApprovalStatus | '')}>
          <option value="">Semua persetujuan</option>
          {(Object.keys(APPROVAL_LABEL) as WorkOrderApprovalStatus[]).map((a) => (
            <option key={a} value={a}>
              {APPROVAL_LABEL[a]}
            </option>
          ))}
        </select>
        <select value={assignedTo} onChange={(e) => setAssignedTo(e.target.value)}>
          <option value="">Semua teknisi</option>
          {technicians.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
      </Toolbar>

      {draft && (
        <WorkOrderForm
          draft={draft}
          fetchCustomers={fetchCustomers}
          fetchTechnicians={fetchTechnicians}
          onChange={setDraft}
          onSubmit={submitCreate}
          onCancel={() => setDraft(null)}
        />
      )}

      <DataTable
        columns={columns}
        rows={orders}
        rowKey={(wo) => wo.id}
        onRowClick={(wo) => void openDetail(wo.id)}
        loading={loading}
        initialSort={{ key: 'scheduledAt', dir: 'desc' }}
        empty={
          <EmptyState
            title={query || status || type || approval || assignedTo ? 'Tidak ada work order yang cocok' : 'Belum ada work order'}
            hint={
              query || status || type || approval || assignedTo
                ? 'Coba ubah filter atau kata kunci.'
                : 'Buat work order pertama untuk menjadwalkan pekerjaan lapangan.'
            }
            icon={<IconWorkOrder size={32} />}
          />
        }
      />

      {detail && (
        <Drawer title={`${detail.workOrder.code} · ${detail.workOrder.title}`} onClose={() => setDetail(null)}>
          <WorkOrderDetailBody
            key={detail.workOrder.id}
            detail={detail}
            fetchTechnicians={fetchTechnicians}
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

function Stat({
  label,
  value,
  accent,
  onClick,
  active,
}: {
  label: string
  value: number
  accent?: 'crit' | 'warn'
  onClick?: () => void
  active?: boolean
}) {
  const cls = `stat ${accent === 'crit' ? 'crit-bar' : accent === 'warn' ? 'warn-bar' : 'accent-bar'}`
  const body = (
    <>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value.toLocaleString('id-ID')}</div>
    </>
  )
  if (!onClick) return <div className={cls}>{body}</div>
  // Bisa diklik → jadikan tombol; saat aktif ditandai garis luar agar filter terlihat.
  return (
    <button
      type="button"
      className={cls}
      onClick={onClick}
      style={{ textAlign: 'left', cursor: 'pointer', outline: active ? '2px solid var(--accent, #4c8bf5)' : undefined }}
    >
      {body}
    </button>
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
  activeApproval,
  onToggleApproval,
}: {
  version: number
  activeStatus: WorkOrderStatus | ''
  onPickStatus: (s: WorkOrderStatus | '') => void
  activeApproval: WorkOrderApprovalStatus | ''
  onToggleApproval: () => void
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
        <Stat
          label="Menunggu persetujuan"
          value={data.pendingApproval}
          accent={data.pendingApproval > 0 ? 'warn' : undefined}
          onClick={onToggleApproval}
          active={activeApproval === 'PENDING'}
        />
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
  fetchCustomers,
  fetchTechnicians,
  onChange,
  onSubmit,
  onCancel,
}: {
  draft: Draft
  fetchCustomers: (term: string) => Promise<CustomerView[]>
  fetchTechnicians: (term: string) => Promise<User[]>
  onChange: (d: Draft) => void
  onSubmit: () => void
  onCancel: () => void
}) {
  return (
    <Modal
      title="Buat work order"
      onClose={onCancel}
      wide
      footer={
        <>
          <button onClick={onCancel}>Batal</button>
          <button className="primary" onClick={onSubmit}>Simpan</button>
        </>
      }
    >
      <div className="stack">
        <label className="stack" style={{ gap: '0.25rem' }}>
          <span>Judul</span>
          <input
            autoFocus
            value={draft.title}
            onChange={(e) => onChange({ ...draft, title: e.target.value })}
            placeholder="mis. Ganti drop core putus"
          />
        </label>
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
          <label style={{ flex: 1, minWidth: 140 }}>
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
          <span>Deskripsi (opsional)</span>
          <textarea rows={3} maxLength={2000} value={draft.description} onChange={(e) => onChange({ ...draft, description: e.target.value })} />
        </label>
        <label className="stack" style={{ gap: '0.25rem' }}>
          <span>Pelanggan (opsional)</span>
          <Combobox
            value={draft.customerId}
            onChange={(id) => onChange({ ...draft, customerId: id })}
            fetchOptions={fetchCustomers}
            toId={(c) => c.id}
            toLabel={(c) => c.name}
            toMeta={(c) => [c.code, c.phone, c.address].filter(Boolean).join(' · ')}
            placeholder="Cari nama, kode, telepon, atau alamat pelanggan…"
            emptyText="Pelanggan tak ditemukan"
          />
        </label>
        <div className="row wrap">
          <label className="stack" style={{ flex: 1, minWidth: 200, gap: '0.25rem' }}>
            <span>Teknisi (opsional)</span>
            <Combobox
              value={draft.assignedTo}
              onChange={(id) => onChange({ ...draft, assignedTo: id })}
              fetchOptions={fetchTechnicians}
              toId={(t) => t.id}
              toLabel={(t) => t.name}
              debounceMs={0}
              placeholder="Cari teknisi…"
              emptyText="Tak ada teknisi"
            />
          </label>
          <label style={{ flex: 1, minWidth: 180 }}>
            <span>Jadwal (opsional)</span>
            <input type="datetime-local" value={draft.scheduledAt} onChange={(e) => onChange({ ...draft, scheduledAt: e.target.value })} />
          </label>
        </div>
      </div>
    </Modal>
  )
}

type ActFn = (action: () => Promise<unknown>, ok: string, keepOpen: boolean) => void

/** Detail + aksi lifecycle. Tombol yang muncul mengikuti status & izin. */
function WorkOrderDetailBody({
  detail,
  fetchTechnicians,
  onAct,
}: {
  detail: WorkOrderDetail
  fetchTechnicians: (term: string) => Promise<User[]>
  onAct: ActFn
}) {
  const { can } = useCan()
  const wo = detail.workOrder
  // State awal cukup dari prop: komponen ini di-`key` pada id work order, jadi
  // berganti work order me-remount dan mereset pilihan ini dengan sendirinya.
  const [tab, setTab] = useState<'ringkasan' | 'bukti' | 'riwayat'>('ringkasan')
  const [assignee, setAssignee] = useState(wo.assignedTo ?? '')
  const [note, setNote] = useState('')
  const [reason, setReason] = useState('')

  // Satu kolom catatan dipakai bersama: opsional saat menyetujui, wajib saat menolak.
  const [decisionNote, setDecisionNote] = useState('')

  const id = wo.id
  const canAssign = can('workorder.order.assign')
  const canUpdate = can('workorder.order.update')
  const canClose = can('workorder.order.close')
  const canApprove = can('workorder.order.approve')
  const terminal = wo.status === 'DONE' || wo.status === 'CANCELLED'
  const awaitingApproval = wo.status === 'DONE' && wo.approvalStatus === 'PENDING'

  const showOptical = canUpdate || wo.rxBeforeDbm != null || wo.rxAfterDbm != null
  const showEvidence = can('workorder.evidence.view')

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      {/* Baris status (keadaan saja) di atas tab — data rinci pindah ke grid Ringkasan
          agar tak dobel tampil. Prioritas hanya muncul di sini saat perlu perhatian. */}
      <div className="row wrap" style={{ gap: '0.4rem' }}>
        <WoStatusBadge status={wo.status} />
        {wo.approvalStatus && <Badge tone={APPROVAL_TONE[wo.approvalStatus]}>{APPROVAL_LABEL[wo.approvalStatus]}</Badge>}
        {(wo.priority === 'URGENT' || wo.priority === 'HIGH') && (
          <Badge tone="warning">Prioritas {PRIORITY_LABEL[wo.priority]}</Badge>
        )}
      </div>

      <Tabs
        active={tab}
        onChange={setTab}
        tabs={[
          { key: 'ringkasan', label: 'Ringkasan' },
          { key: 'bukti', label: 'Bukti & optik' },
          { key: 'riwayat', label: 'Riwayat', badge: detail.timeline.length },
        ]}
      />

      {tab === 'ringkasan' && (
        <div className="stack" style={{ gap: '1.1rem' }}>
          {wo.description && <p className="wo-desc">{wo.description}</p>}

          <dl className="wo-grid">
            <Field label="Tipe">{TYPE_LABEL[wo.type]}</Field>
            <Field label="Pelanggan">{wo.customerName ?? <span className="muted">—</span>}</Field>
            <Field label="Prioritas">{PRIORITY_LABEL[wo.priority]}</Field>
            <Field label="Teknisi">{wo.assignedToName ?? <span className="muted">belum ditugaskan</span>}</Field>
            <Field label="Jadwal">{wo.scheduledAt ? fmt(wo.scheduledAt) : <span className="muted">—</span>}</Field>
            {wo.destinationLat != null && wo.destinationLng != null && (
              <Field label="Lokasi">
                <a
                  href={`https://www.google.com/maps/search/?api=1&query=${wo.destinationLat},${wo.destinationLng}`}
                  target="_blank"
                  rel="noreferrer"
                >
                  Navigasi ke pelanggan ↗
                </a>
              </Field>
            )}
            <Field label="Dibuat">{fmt(wo.createdAt)}</Field>
            {wo.completedAt && <Field label="Selesai">{fmt(wo.completedAt)}</Field>}
            {wo.resolutionNote && <Field label="Catatan">{wo.resolutionNote}</Field>}
            {wo.approvedByName && (
              <Field label={wo.approvalStatus === 'REJECTED' ? 'Ditolak oleh' : 'Disetujui oleh'}>
                {wo.approvedByName}
                {wo.approvedAt ? ` · ${fmt(wo.approvedAt)}` : ''}
              </Field>
            )}
            {wo.approvalNote && (
              <Field label={wo.approvalStatus === 'REJECTED' ? 'Alasan penolakan' : 'Catatan persetujuan'}>
                {wo.approvalNote}
              </Field>
            )}
            {wo.cancelReason && <Field label="Alasan batal">{wo.cancelReason}</Field>}
          </dl>

          {/* Penugasan — selagi work order belum selesai/batal. */}
          {canAssign && !terminal && (
            <section className="stack" style={{ gap: '0.4rem' }}>
              <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Penugasan</h3>
              <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-end' }}>
                <label className="stack" style={{ flex: 1, gap: '0.25rem' }}>
                  <span>Teknisi</span>
                  <Combobox
                    value={assignee}
                    onChange={(id) => setAssignee(id)}
                    fetchOptions={fetchTechnicians}
                    toId={(t) => t.id}
                    toLabel={(t) => t.name}
                    initialLabel={wo.assignedToName ?? ''}
                    debounceMs={0}
                    placeholder="Cari teknisi…"
                    emptyText="Tak ada teknisi"
                  />
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
          {canUpdate && (wo.status === 'ASSIGNED' || wo.status === 'DRAFT') && (
            <div className="row wrap" style={{ gap: '0.5rem' }}>
              {wo.status === 'ASSIGNED' && (
                <button onClick={() => onAct(() => api.post(`/api/work-orders/${id}/start`), 'Pengerjaan dimulai', true)}>Mulai</button>
              )}
              {wo.status === 'DRAFT' && (
                <button
                  className="ghost danger"
                  onClick={() => onAct(() => api.del(`/api/work-orders/${id}`), 'Work order dihapus', false)}
                >
                  Hapus
                </button>
              )}
            </div>
          )}

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

          {/* Persetujuan hasil kerja — hanya untuk WO selesai yang menunggu dikurasi. */}
          {canApprove && awaitingApproval && (
            <section className="stack" style={{ gap: '0.5rem' }}>
              <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Persetujuan hasil kerja</h3>
              <label className="stack" style={{ gap: '0.25rem' }}>
                <span>Catatan (opsional untuk setuju, wajib bila menolak)</span>
                <textarea
                  rows={2}
                  maxLength={500}
                  value={decisionNote}
                  onChange={(e) => setDecisionNote(e.target.value)}
                  placeholder="mis. redaman OK, pemasangan rapi"
                />
              </label>
              <div className="row" style={{ gap: '0.5rem' }}>
                <button
                  className="primary"
                  onClick={() => onAct(() => api.post(`/api/work-orders/${id}/approve`, { note: decisionNote.trim() || null }), 'Hasil kerja disetujui', true)}
                >
                  Setujui
                </button>
                <button
                  className="ghost danger"
                  disabled={!decisionNote.trim()}
                  onClick={() => onAct(() => api.post(`/api/work-orders/${id}/reject`, { reason: decisionNote.trim() }), 'Hasil kerja ditolak, WO dibuka kembali', true)}
                  title={decisionNote.trim() ? undefined : 'Isi alasan penolakan dulu'}
                >
                  Tolak &amp; buka kembali
                </button>
              </div>
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
        </div>
      )}

      {tab === 'bukti' && (
        <div className="stack" style={{ gap: '1.1rem' }}>
          {/* Redaman optik — bukti kualitas; disembunyikan hanya bila belum ada & tak boleh mengubah. */}
          {showOptical && <OpticalSection wo={wo} canUpdate={canUpdate} onAct={onAct} />}
          {showEvidence && <EvidenceSection workOrderId={id} status={wo.status} />}
          {!showOptical && !showEvidence && (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Tak ada bukti yang bisa ditampilkan.</p>
          )}
        </div>
      )}

      {tab === 'riwayat' && (
        <section className="stack" style={{ gap: '0.5rem' }}>
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
      )}
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
