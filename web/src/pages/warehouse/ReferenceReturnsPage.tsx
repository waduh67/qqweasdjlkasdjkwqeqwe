import { useCallback, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { listReferenceReturns, RETURN_LABELS, RETURN_STATES } from '@/api/warehouse/referenceReturns'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, SelectField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { ReferenceReturnEditor } from './ReferenceReturnEditor'
import { ReferenceReturnDetail } from './ReferenceReturnDetail'

export function ReferenceReturnsPage() {
  const [params, setParams] = useSearchParams(), id = params.get('id'), { can, hasPermission } = useCan()
  const [search, setSearch] = useState(''), [page, setPage] = useState(0)
  const [state, setState] = useState<typeof RETURN_STATES[number] | undefined>(), [creating, setCreating] = useState(false)
  const load = useCallback(() => listReferenceReturns(search.trim(), page, state), [search, page, state]), result = useWarehouseQuery(load)
  return <div className="stack"><PageHeader title="Retur Material" subtitle={hasPermission('warehouse.return.manage') ? 'Terima barang dari teknisi atau catat alasan penolakan.' : 'Kembalikan material dari saldo Anda dan pantau penerimaan Admin.'}
    actions={can('warehouse.return.own') && !id ? <Button variant="primary" onClick={() => setCreating(true)}>Retur baru</Button> : undefined} />
    {id ? <ReferenceReturnDetail key={id} id={id} onBack={() => setParams({})} onChanged={result.reload} /> : <>
      <div className="row wrap"><TextField label="Cari retur" maxLength={200} value={search} placeholder="Material, teknisi, atau alasan" onChange={(_, data) => { setSearch(data.value); setPage(0) }} />
        <SelectField label="Status retur" value={state ?? ''} onChange={(_, data) => { setState(RETURN_STATES.find(value => value === data.value)); setPage(0) }}>
          <option value="">Semua status</option>{RETURN_STATES.map(value => <option key={value} value={value}>{RETURN_LABELS[value]}</option>)}
        </SelectField><Button onClick={result.reload}>Muat ulang retur</Button>
      </div>
      <WarehouseState {...result}>{data => <><DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id}
        empty={<EmptyState title="Belum ada retur" hint="Retur dalam akses Anda akan muncul di sini. Material tetap di tangan teknisi sampai Admin menerimanya." />}
        columns={[
          { key: 'material', header: 'Material', cell: row => row.skuName, description: row => row.reason },
          { key: 'quantity', header: 'Jumlah retur', cell: row => <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> },
          { key: 'technician', header: 'Teknisi', cell: row => row.technicianName },
          { key: 'warehouse', header: 'Gudang tujuan', cell: row => row.warehouseName },
          { key: 'state', header: 'Status', cell: row => RETURN_LABELS[row.state] },
          { key: 'time', header: 'Diajukan', cell: row => new Date(row.createdAt).toLocaleString('id-ID') },
          { key: 'open', header: 'Tindakan', cell: row => <Button onClick={() => setParams({ id: row.id })}>Lihat retur {row.id.slice(-8)}</Button> },
        ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
    </>}
    {creating && can('warehouse.return.own') && <ReferenceReturnEditor onClose={() => setCreating(false)} onSaved={row => { setCreating(false); result.reload(); setParams({ id: row.id }) }} />}
  </div>
}
