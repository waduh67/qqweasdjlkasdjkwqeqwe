import { useCallback, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { listReferenceRequests, REQUEST_LABELS, REQUEST_STATES, type ReferenceRequestState } from '@/api/warehouse/referenceRequests'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, SelectField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { ReferenceRequestEditor } from './ReferenceRequestEditor'
import { ReferenceRequestDetail } from './ReferenceRequestDetail'

export function ReferenceRequestsPage() {
  const [params, setParams] = useSearchParams(), id = params.get('id'), { can, hasPermission } = useCan()
  const [search, setSearch] = useState(''), [page, setPage] = useState(0), [state, setState] = useState<ReferenceRequestState | undefined>(), [creating, setCreating] = useState(false)
  const load = useCallback(() => listReferenceRequests(search.trim(), page, state), [search, page, state]), result = useWarehouseQuery(load)
  const create = can('warehouse.request.review') || can('warehouse.request.own')
  return <div className="stack"><PageHeader title="Permintaan Material" subtitle={hasPermission('warehouse.request.view') ? 'Tinjau kebutuhan, setujui jumlah, lalu terima atau serahkan material.' : 'Ajukan kebutuhan Anda dan ikuti persetujuan sampai material diserahkan.'}
    actions={create && !id ? <Button variant="primary" onClick={() => setCreating(true)}>Permintaan baru</Button> : undefined} />
    {id ? <ReferenceRequestDetail key={id} id={id} onBack={() => setParams({})} onChanged={result.reload} /> : <>
      <div className="row wrap">
        <TextField label="Cari permintaan" maxLength={200} value={search} placeholder="Alasan atau nama material" onChange={(_, data) => { setSearch(data.value); setPage(0) }} />
        <SelectField label="Status permintaan" value={state ?? ''} onChange={(_, data) => { setState(REQUEST_STATES.find(value => value === data.value)); setPage(0) }}>
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
          { key: 'open', header: 'Tindakan', cell: row => <Button onClick={() => setParams({ id: row.id })}>Lihat permintaan {row.id.slice(-8)}</Button> },
        ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
    </>}
    {creating && create && <ReferenceRequestEditor onClose={() => setCreating(false)} onSaved={row => { setCreating(false); result.reload(); setParams({ id: row.id }) }} />}
  </div>
}
