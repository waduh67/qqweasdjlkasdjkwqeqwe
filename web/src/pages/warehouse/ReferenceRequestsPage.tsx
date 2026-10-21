import { useCallback, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { listReferenceRequests, REQUEST_LABELS, REQUEST_STATES, type ReferenceRequestState } from '@/api/warehouse/referenceRequests'
import { useCan } from '@/auth/useCan'
import { useAuth } from '@/auth/useAuth'
import { tokenStore } from '@/api/client'
import { Button, EmptyState, SelectField, TextField } from '@/components/atoms'
import { PageHeader, Tabs } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { ReferenceRequestEditor } from './ReferenceRequestEditor'
import { ReferenceRequestDetail } from './ReferenceRequestDetail'

export function ReferenceRequestsPage() {
  const [params, setParams] = useSearchParams(), id = params.get('id'), { can, hasPermission } = useCan()
  const { user } = useAuth()
  const [search, setSearch] = useState(''), [page, setPage] = useState(0), [creating, setCreating] = useState(false)
  const state: ReferenceRequestState | undefined = REQUEST_STATES.find(value => value === params.get('state'))
  function filter(key: string, value?: string) {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value); else next.delete(key)
    setParams(next)
    setPage(0)
  }
  const actionsAllowed = ['warehouse.request.review', 'warehouse.request.approve', 'warehouse.request.receive', 'warehouse.request.handover'].some(can)
  const actionOnly = actionsAllowed && params.get('actions') === 'true'
  const load = useCallback(() => listReferenceRequests(search.trim(), page, state, actionOnly), [search, page, state, actionOnly]), result = useWarehouseQuery(load, user?.tenantId + ':' + user?.id + ':' + tokenStore.getSessionVersion())
  const create = can('warehouse.request.review') || can('warehouse.request.own')
  return <div className="stack"><PageHeader title="Permintaan Material" subtitle={hasPermission('warehouse.request.view') ? 'Tinjau kebutuhan, setujui jumlah, lalu terima atau serahkan material.' : 'Ajukan kebutuhan Anda dan ikuti persetujuan sampai material diserahkan.'}
    actions={create && !id ? <Button variant="primary" onClick={() => setCreating(true)}>Permintaan baru</Button> : undefined} />
    {id ? <ReferenceRequestDetail key={id} id={id} onBack={() => filter('id')} onChanged={result.reload} /> : <>
      {actionsAllowed && <Tabs tabs={[{ key: 'all', label: 'Semua Request' }, { key: 'actions', label: 'Perlu Tindakan' }]} active={actionOnly ? 'actions' : 'all'} onChange={value => filter('actions', value === 'actions' ? 'true' : undefined)} />}
      <div className="row wrap">
        <TextField label="Cari permintaan" maxLength={200} value={search} placeholder="Alasan atau nama material" onChange={(_, data) => { setSearch(data.value); setPage(0) }} />
        <SelectField label="Status permintaan" value={state ?? ''} onChange={(_, data) => filter('state', data.value)}>
          <option value="">Semua status</option>{REQUEST_STATES.map(value => <option key={value} value={value}>{REQUEST_LABELS[value]}</option>)}
        </SelectField>
        <Button onClick={result.reload}>Muat ulang permintaan</Button>
      </div>
      <WarehouseState {...result}>{data => <><DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id}
        empty={<EmptyState title="Belum ada permintaan" hint="Permintaan dalam akses Anda akan muncul di sini. Ajukan kebutuhan material untuk memulai." />}
        columns={[
          { key: 'destination', header: 'Penerima', cell: row => row.technicianName ?? row.warehouseName, description: row => row.kind === 'RESTOCK' ? 'Restock gudang' : 'Pengadaan' },
          { key: 'reason', header: 'Kebutuhan', cell: row => row.reason, description: row => row.lines.map(line => line.name).join(', ') },
          { key: 'state', header: 'Status', cell: row => REQUEST_LABELS[row.state] },
          { key: 'requester', header: 'Diajukan oleh', cell: row => row.requesterName },
          { key: 'time', header: 'Diajukan', cell: row => new Date(row.createdAt).toLocaleString('id-ID') },
          { key: 'open', header: 'Tindakan', cell: row => <Button onClick={() => filter('id', row.id)}>Lihat permintaan {row.id.slice(-8)}</Button> },
        ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
    </>}
    {creating && create && <ReferenceRequestEditor onClose={() => setCreating(false)} onSaved={row => { setCreating(false); result.reload(); filter('id', row.id) }} />}
  </div>
}
