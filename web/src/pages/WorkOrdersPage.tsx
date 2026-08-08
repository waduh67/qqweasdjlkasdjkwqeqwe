import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { PageResponse, User } from '../api/types'
import type { CustomerView } from '../api/network'
import type {
  WorkOrderApprovalStatus,
  WorkOrderDashboardView,
  WorkOrderPriority,
  WorkOrderStatus,
  WorkOrderType,
  WorkOrderView,
} from '../api/workorder'
import { useCan } from '../auth/useCan'
import { DataTable, type Column } from '@/components/organisms'
import { CommandBar, type CommandAction } from '@/components/molecules'
import { PageHeader } from '@/components/molecules'
import { Badge, Button, EmptyState, SelectField, TextField, TextareaField, Toolbar } from '@/components/atoms'
import { SearchInput } from '@/components/molecules'
import { useToast } from '@/system'
import { Blade } from '@/components/organisms'
import { Combobox } from '@/components/molecules'
import { MultiCombobox } from '@/components/molecules'
import { IconPlus, IconWorkOrder } from '@/components/atoms/icons'
import {
  APPROVAL_LABEL,
  PRIORITIES,
  PRIORITY_LABEL,
  STATUSES,
  STATUS_LABEL,
  STATUS_TONE,
  TYPE_LABEL,
  TYPES,
  assigneeLabel,
  fmt,
  priorityTone,
} from '@/utils/woLabels'
import { AssigneeChips, WoStatusBadge } from '@/components/organisms/workorder/views'
import { useTechnicians } from '@/hooks/useTechnicians'

type Draft = {
  type: WorkOrderType
  title: string
  description: string
  priority: WorkOrderPriority
  customerId: string
  scheduledAt: string
  assignees: string[]
}

const EMPTY_DRAFT: Draft = {
  type: 'PSB',
  title: '',
  description: '',
  priority: 'NORMAL',
  customerId: '',
  scheduledAt: '',
  assignees: [],
}

const toInstant = (local: string): string | null => (local ? new Date(local).toISOString() : null)

/**
 * Work order sisi operator/dispatcher: buat tugas lapangan, tugaskan ke teknisi,
 * lalu kelola lifecycle-nya (draft → ditugaskan → dikerjakan → selesai / batal).
 * Pengerjaan di lapangan (mulai/selesai) dilayani klien teknisi terpisah nanti;
 * di sini penekanannya pada penjadwalan, penugasan, dan meninjau/mengkurasi bukti
 * pengerjaan (foto & tanda tangan) yang diunggah teknisi.
 */
export function WorkOrdersPage() {
  const { can } = useCan()
  const navigate = useNavigate()
  const toast = useToast()
  const [orders, setOrders] = useState<WorkOrderView[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<WorkOrderStatus | ''>('')
  const [type, setType] = useState<WorkOrderType | ''>('')
  const [approval, setApproval] = useState<WorkOrderApprovalStatus | ''>('')
  const [assignedTo, setAssignedTo] = useState('')
  const [draft, setDraft] = useState<Draft | null>(null)
  const [initialDraft, setInitialDraft] = useState<Draft | null>(null)
  // Ditambah tiap ada perubahan (buat/tugaskan/lifecycle) agar dashboard menghitung ulang.
  const [dashVersion, setDashVersion] = useState(0)

  // Buka/tutup Blade form + lacak "dirty" via snapshot draft awal.
  const openDraft = (d: Draft) => {
    setDraft(d)
    setInitialDraft(d)
  }
  const closeDraft = () => {
    setDraft(null)
    setInitialDraft(null)
  }
  const dirty = draft != null && JSON.stringify(draft) !== JSON.stringify(initialDraft)

  // Teknisi (pemegang role "Teknisi") untuk pemilih penugasan & filter — best-effort lewat
  // hook; bila operator tak berizin melihatnya, daftarnya kosong dan halaman tetap jalan.
  const { technicians, fetchTechnicians } = useTechnicians()

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

  const run = async (action: () => Promise<unknown>, ok?: string) => {
    try {
      await action()
      await reload()
      setDashVersion((v) => v + 1)
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
        assignees: draft.assignees,
      })
      closeDraft()
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
      sortValue: (wo) => assigneeLabel(wo),
      cell: (wo) => <AssigneeChips wo={wo} />,
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

  // CommandBar ala Azure: primary `+ Buat work order` dipatok kiri, seragam dengan Pelanggan.
  const primary: CommandAction | undefined = can('workorder.order.create')
    ? {
        key: 'create',
        label: 'Buat work order',
        icon: <IconPlus size={16} />,
        onClick: () => openDraft({ ...EMPTY_DRAFT }),
      }
    : undefined

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader title="Work Order" subtitle="Tugas lapangan — penjadwalan, penugasan teknisi, dan lifecycle-nya." />
      <CommandBar primary={primary} />

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
        <SelectField value={status} onChange={(_, data) => setStatus(data.value as WorkOrderStatus | '')}>
          <option value="">Semua status</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {STATUS_LABEL[s]}
            </option>
          ))}
        </SelectField>
        <SelectField value={type} onChange={(_, data) => setType(data.value as WorkOrderType | '')}>
          <option value="">Semua tipe</option>
          {TYPES.map((t) => (
            <option key={t} value={t}>
              {TYPE_LABEL[t]}
            </option>
          ))}
        </SelectField>
        <SelectField value={approval} onChange={(_, data) => setApproval(data.value as WorkOrderApprovalStatus | '')}>
          <option value="">Semua persetujuan</option>
          {(Object.keys(APPROVAL_LABEL) as WorkOrderApprovalStatus[]).map((a) => (
            <option key={a} value={a}>
              {APPROVAL_LABEL[a]}
            </option>
          ))}
        </SelectField>
        <SelectField value={assignedTo} onChange={(_, data) => setAssignedTo(data.value)}>
          <option value="">Semua teknisi</option>
          {technicians.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </SelectField>
      </Toolbar>

      <WorkOrderForm
        open={draft != null}
        draft={draft}
        dirty={dirty}
        fetchCustomers={fetchCustomers}
        fetchTechnicians={fetchTechnicians}
        onChange={setDraft}
        onSubmit={submitCreate}
        onCancel={closeDraft}
      />

      <DataTable
        columns={columns}
        rows={orders}
        rowKey={(wo) => wo.id}
        onRowClick={(wo) => navigate(`/work-orders/${wo.id}`)}
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
      style={{ font: 'inherit', color: 'inherit', textAlign: 'left', cursor: 'pointer', outline: active ? '2px solid var(--accent, #4c8bf5)' : undefined }}
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
            <Button
              key={s}
              onClick={() => onPickStatus(selected ? '' : s)}
              variant={selected ? 'primary' : 'subtle'}
              style={{ display: 'flex', gap: '0.4rem', alignItems: 'center', fontSize: '0.82rem', padding: '0.3rem 0.6rem' }}
              title={`Filter: ${STATUS_LABEL[s]}`}
            >
              {STATUS_LABEL[s]}
              <span className={`badge ${STATUS_TONE[s]}`}>{data.byStatus[s] ?? 0}</span>
            </Button>
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
  open,
  draft,
  dirty,
  fetchCustomers,
  fetchTechnicians,
  onChange,
  onSubmit,
  onCancel,
}: {
  open: boolean
  draft: Draft | null
  dirty: boolean
  fetchCustomers: (term: string) => Promise<CustomerView[]>
  fetchTechnicians: (term: string) => Promise<User[]>
  onChange: (d: Draft) => void
  onSubmit: () => void
  onCancel: () => void
}) {
  return (
    <Blade
      open={open}
      title="Buat work order"
      subtitle="Tugas lapangan baru — jadwalkan dan tugaskan ke teknisi."
      size="lg"
      dirty={dirty}
      onClose={onCancel}
      footer={
        <>
          <Button variant="primary" onClick={onSubmit}>Simpan</Button>
          <Button onClick={onCancel}>Batal</Button>
        </>
      }
    >
      {draft && (
        <div className="stack">
          <TextField
            label="Judul"
            autoFocus
            value={draft.title}
            onChange={(_, data) => onChange({ ...draft, title: data.value })}
            placeholder="mis. Ganti drop core putus"
          />
          <div className="row wrap">
            <SelectField
              label="Tipe"
              value={draft.type}
              onChange={(_, data) => onChange({ ...draft, type: data.value as WorkOrderType })}
              style={{ flex: 1, minWidth: 140 }}
            >
              {TYPES.map((t) => (
                <option key={t} value={t}>
                  {TYPE_LABEL[t]}
                </option>
              ))}
            </SelectField>
            <SelectField
              label="Prioritas"
              value={draft.priority}
              onChange={(_, data) => onChange({ ...draft, priority: data.value as WorkOrderPriority })}
              style={{ flex: 1, minWidth: 140 }}
            >
              {PRIORITIES.map((p) => (
                <option key={p} value={p}>
                  {PRIORITY_LABEL[p]}
                </option>
              ))}
            </SelectField>
          </div>
          <TextareaField
            label="Deskripsi (opsional)"
            rows={3}
            maxLength={2000}
            value={draft.description}
            onChange={(_, data) => onChange({ ...draft, description: data.value })}
          />
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
              <span>Teknisi (opsional, bisa lebih dari satu)</span>
              <MultiCombobox
                values={draft.assignees}
                onChange={(ids) => onChange({ ...draft, assignees: ids })}
                fetchOptions={fetchTechnicians}
                toId={(t) => t.id}
                toLabel={(t) => t.name}
                debounceMs={0}
                placeholder="Cari teknisi…"
                emptyText="Tak ada teknisi"
              />
            </label>
            <TextField
              label="Jadwal (opsional)"
              type="datetime-local"
              value={draft.scheduledAt}
              onChange={(_, data) => onChange({ ...draft, scheduledAt: data.value })}
              style={{ flex: 1, minWidth: 180 }}
            />
          </div>
        </div>
      )}
    </Blade>
  )
}
