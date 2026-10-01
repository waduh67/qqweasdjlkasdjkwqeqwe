import { useCallback, useState } from 'react'
import { ClipboardCheck } from 'lucide-react'
import { getPendingMaterialReturns, type MyMaterialResidual } from '@/api/warehouse/myMaterials'
import { useAuth } from '@/auth/useAuth'
import { EmptyState } from '@/components/atoms'
import { CommandBar } from '@/components/molecules/CommandBar'
import { DataTable } from '@/components/organisms/DataTable'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseFacts } from '@/components/organisms/warehouse/WarehouseFacts'
import { WarehouseListActions } from '@/components/organisms/warehouse/WarehouseListActions'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useFieldConnection } from '@/hooks/useFieldConnection'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { MaterialResidualAcknowledgementForm } from '../MyMaterialResidualAcknowledgement'
import './warehouseWorkspaces.css'

export function WarehousePendingMaterialReturns({ onReceived }: { onReceived?: (row: MyMaterialResidual) => void }) {
  const [page, setPage] = useState(0), [selected, setSelected] = useState<MyMaterialResidual | null>(null), [receiving, setReceiving] = useState(false)
  const result = useWarehouseQuery(useCallback(() => getPendingMaterialReturns(page), [page]))
  const { readOnly } = useAuth(), online = useFieldConnection()
  const enabled = online && !readOnly
  function received() {
    if (!selected) return
    onReceived?.(selected); setReceiving(false); setSelected(null)
    if (page > 0 && result.state.status === 'ready' && result.state.data.items.length === 1) setPage(page - 1)
    else result.reload()
  }
  return <div className="warehouse-workspace-panel">
    <WarehouseListActions onRefresh={result.reload} />
    <p className="warehouse-workspace-note">Material dari teknisi yang belum diterima gudang.</p>
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id}
        empty={<EmptyState title="Tidak ada material menunggu penerimaan" />} columns={[
          { key: 'code', header: 'Dokumen', cell: row => row.code, onCellClick: setSelected, minWidth: 160 },
          { key: 'item', header: 'Barang', cell: row => row.sku.name, minWidth: 170 },
          { key: 'identity', header: 'Serial / lot', cell: row => row.serial ?? row.lotCode ?? '—', minWidth: 150 },
          { key: 'amount', header: 'Jumlah', cell: row => <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} />, align: 'right', minWidth: 100 },
          { key: 'sender', header: 'Pengirim', cell: row => row.sender?.name ?? '—', minWidth: 150 },
          { key: 'destination', header: 'Tujuan', cell: row => row.location.name, minWidth: 160 },
          { key: 'sent', header: 'Dikirim', cell: row => <WarehouseTime value={row.recordedAt} />, minWidth: 160 },
        ]} />
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
    {selected && <ResourceForm readOnly title={selected.code} onClose={() => setSelected(null)} onBack={() => {}}>
      <div className="warehouse-record">
        <CommandBar primary={{ key: 'receive', label: 'Terima material', icon: <ClipboardCheck size={16} />, disabled: !enabled, onClick: () => setReceiving(true) }} />
        <WarehouseFacts items={[
          { label: 'Status', value: 'Menunggu penerimaan' }, { label: 'Barang', value: selected.sku.name },
          { label: 'Serial / lot', value: selected.serial ?? selected.lotCode }, { label: 'Jumlah', value: <WarehouseQuantity value={selected.quantityBase} unit={selected.baseUnit} /> },
          { label: 'Pengirim', value: selected.sender?.name }, { label: 'Tujuan', value: selected.location.name },
          { label: 'Dikirim', value: <WarehouseTime value={selected.recordedAt} /> },
        ]} />
        <p className="warehouse-workspace-note">Konfirmasikan penerimaan setelah material tiba. Material diterima di karantina sebelum diperiksa.</p>
        {!online && <p role="status">Hubungkan perangkat ke internet untuk menerima material.</p>}
        {readOnly && <p role="status">Penerimaan tidak tersedia dalam mode baca saja.</p>}
      </div>
      {receiving && <MaterialResidualAcknowledgementForm row={selected} enabled={enabled} onClose={() => setReceiving(false)} onDone={received} onReload={() => { setReceiving(false); setSelected(null); result.reload() }} />}
    </ResourceForm>}
  </div>
}
