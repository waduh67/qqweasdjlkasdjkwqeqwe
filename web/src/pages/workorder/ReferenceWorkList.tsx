import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
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

export const REFERENCE_WORK_LABELS = { PENDING: 'Belum selesai', BLOCKED: 'Ada kendala', COMPLETED: 'Selesai', CANCELLED: 'Dibatalkan' } as const
export function ReferenceWorkStatus({ state }: { state: ReferenceWorkOrder['state'] }) {
  return <StatusBadge status={state} label={REFERENCE_WORK_LABELS[state]} tone={state === 'BLOCKED' ? 'serious' : state === 'COMPLETED' ? 'good' : state === 'PENDING' ? 'warning' : 'neutral'} />
}

export function ReferenceWorkList({ field = true }: { field?: boolean }) {
  const { user } = useAuth()
  const [search, setSearch] = useState(''), [page, setPage] = useState(0)
  const [state, setState] = useState<ReferenceWorkOrder['state'] | ''>('PENDING')
  const load = useCallback(() => listReferenceWork(search.trim(), page, state || undefined), [search, page, state])
  const result = useWarehouseQuery(load, `${user?.id}:${user?.tenantId}`), base = field ? '/my-work-orders' : '/work-orders'
  return <div className="stack"><PageHeader title={field ? 'Tugas Saya' : 'Work Order'} subtitle={field ? 'Buka tugas, ikuti instruksi, lalu kirim foto dan material yang terpakai.' : 'Penugasan dan hasil pekerjaan teknisi.'} actions={<Button onClick={result.reload}>Muat ulang</Button>} />
    <div className="workspace-safety-grid">
      <TextField label="Cari tugas" value={search} placeholder="Nomor atau judul pekerjaan" onChange={(_, data) => { setSearch(data.value); setPage(0) }} />
      <SelectField label="Status tugas" value={state} onChange={(_, data) => { setState(data.value === '' ? '' : oneOf(data.value, WORK_STATES)); setPage(0) }}>
        <option value="">Semua status</option>{WORK_STATES.map(value => <option key={value} value={value}>{REFERENCE_WORK_LABELS[value]}</option>)}
      </SelectField>
    </div>
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Tidak ada tugas pada pilihan ini" hint="Coba status lain atau cari nomor pekerjaan. Tugas baru muncul setelah Anda ditugaskan." />} columns={[
        { key: 'task', header: 'Pekerjaan', cell: row => <Link to={base + '/' + row.id}>{row.code} · {row.title}</Link>, description: row => row.type.name },
        { key: 'state', header: 'Status', cell: row => <ReferenceWorkStatus state={row.state} />, description: row => row.blockedReason },
        { key: 'technician', header: 'Teknisi', cell: row => row.technicianName },
        { key: 'scheduled', header: 'Jadwal', cell: row => row.scheduledAt ? <WarehouseTime value={row.scheduledAt} /> : 'Belum dijadwalkan' },
      ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
  </div>
}
