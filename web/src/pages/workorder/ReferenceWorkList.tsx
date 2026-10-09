import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Checkbox } from '@fluentui/react-components'
import { oneOf } from '@/api/warehouse/codec'
import { listReferenceWork, WORK_STATES, type ReferenceWorkOrder } from '@/api/warehouse/reference'
import { Button, EmptyState, SelectField, StatusBadge, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { warehouseError } from '@/api/warehouse/errors'
import { useWarehouseWorkflow } from '@/pages/warehouse/WarehouseWorkflowContext'
import { ReferenceWorkEditor } from './ReferenceWorkEditor'
import { workSeed, WorkDraftError, type WorkSeed } from './referenceWorkDraft'

export const REFERENCE_WORK_LABELS = { PENDING: 'Belum selesai', BLOCKED: 'Ada kendala', COMPLETED: 'Selesai', CANCELLED: 'Dibatalkan' } as const
export function ReferenceWorkStatus({ state }: { state: ReferenceWorkOrder['state'] }) {
  return <StatusBadge status={state} label={REFERENCE_WORK_LABELS[state]} tone={state === 'BLOCKED' ? 'serious' : state === 'COMPLETED' ? 'good' : state === 'PENDING' ? 'warning' : 'neutral'} />
}

export function ReferenceWorkList({ field = true }: { field?: boolean }) {
  const { user } = useAuth()
  const { can } = useCan(), location = useLocation(), navigate = useNavigate(), workflow = useWarehouseWorkflow()
  const [creating, setCreating] = useState(false), [seed, setSeed] = useState<WorkSeed | undefined>(), [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState(''), [page, setPage] = useState(0)
  const [overdue, setOverdue] = useState(false)
  const [state, setState] = useState<ReferenceWorkOrder['state'] | ''>('PENDING')
  const load = useCallback(() => listReferenceWork(search.trim(), page, state || undefined, overdue), [search, page, state, overdue])
  const result = useWarehouseQuery(load, `${user?.id}:${user?.tenantId}`), base = field ? '/my-work-orders' : '/work-orders'
  useEffect(() => {
    if (field || location.state == null) return
    try {
      const parsed = workSeed(location.state)
      if (parsed && can('workorder.order.create')) { setSeed(parsed); setCreating(true) }
    } catch (caught) { setError(caught instanceof WorkDraftError ? caught.message : warehouseError(caught)) }
    navigate(location.pathname, { replace: true, state: null })
  }, [field, location.state, location.pathname, navigate, can])
  return <div className="stack"><PageHeader title={field ? 'Tugas Saya' : 'Work Order'} subtitle={field ? 'Buka tugas, ikuti instruksi, lalu kirim foto dan material yang terpakai.' : 'Buat penugasan, pantau kendala, dan periksa hasil pekerjaan teknisi.'} actions={<>
    <Button onClick={result.reload}>Muat ulang</Button>{!field && can('workorder.order.create') && <Button variant="primary" onClick={() => { setSeed(undefined); setCreating(true) }}>Work order baru</Button>}
  </>} />
    {!field && workflow.state.status === 'ready' && workflow.state.data.owner && <Link to="/work-orders/types">Kelola jenis pekerjaan</Link>}
    {error && <p role="alert" className="error">{error}</p>}
    <div className="workspace-safety-grid">
      <TextField label="Cari tugas" value={search} placeholder="Nomor atau judul pekerjaan" onChange={(_, data) => { setSearch(data.value); setPage(0) }} />
      <SelectField label="Status tugas" value={state} onChange={(_, data) => { setState(data.value === '' ? '' : oneOf(data.value, WORK_STATES)); setPage(0) }}>
        <option value="">Semua status</option>{WORK_STATES.map(value => <option key={value} value={value}>{REFERENCE_WORK_LABELS[value]}</option>)}
      </SelectField>
    </div>
    <Checkbox label="Hanya tugas terlambat" checked={overdue} onChange={(_, data) => { setOverdue(data.checked === true); setPage(0) }} />
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Tidak ada tugas pada pilihan ini" hint="Coba status lain atau cari nomor pekerjaan. Tugas baru muncul setelah Anda ditugaskan." />} columns={[
        { key: 'task', header: 'Pekerjaan', cell: row => <Link to={base + '/' + row.id}>{row.code} · {row.title}</Link>, description: row => row.type.name },
        { key: 'state', header: 'Status', cell: row => <ReferenceWorkStatus state={row.state} />, description: row => row.blockedReason },
        { key: 'technician', header: 'Teknisi', cell: row => row.technicianName },
        { key: 'scheduled', header: 'Jadwal', cell: row => row.scheduledAt ? <WarehouseTime value={row.scheduledAt} /> : 'Belum dijadwalkan' },
      ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
    {creating && !field && can('workorder.order.create') && <ReferenceWorkEditor {...(seed ? { seed } : {})} onClose={() => setCreating(false)} onSaved={work => { setCreating(false); navigate('/work-orders/' + work.id) }} />}
  </div>
}
