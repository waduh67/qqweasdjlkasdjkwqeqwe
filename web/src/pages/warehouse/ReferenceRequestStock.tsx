import { useCallback, useState } from 'react'
import { getRequestStockPreview, type RequestStockDestination } from '@/api/warehouse/referenceRequestStock'
import { Button } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

export function ReferenceRequestStock({ skuId, destination = {} }: { readonly skuId: string; readonly destination?: RequestStockDestination }) {
  const [page, setPage] = useState(0), { requestId, technicianId, warehouseId } = destination
  const load = useCallback(() => getRequestStockPreview(skuId, { requestId, technicianId, warehouseId }, page), [skuId, requestId, technicianId, warehouseId, page])
  const result = useWarehouseQuery(load)
  return <section className="stack" aria-label="Stok saat ini">
    <div className="row wrap"><strong>Stok saat ini</strong><Button type="button" disabled={result.state.status === 'loading'} onClick={result.reload}>Perbarui stok</Button></div>
    <WarehouseState {...result}>{stock => <>
      <p>Stok gudang {warehouseId ? 'penerima' : 'dalam cakupan akses'}: <strong><WarehouseQuantity value={stock.totalWarehouseBase} unit={stock.baseUnit} /></strong></p>
      {stock.technicianId && stock.technicianName && stock.technicianQuantityBase !== null && <p>Stok {stock.technicianName}: <strong><WarehouseQuantity value={stock.technicianQuantityBase} unit={stock.baseUnit} /></strong></p>}
      <DataTable presentation="warehouse" rows={stock.warehouses.items} rowKey={row => row.warehouseId} columns={[
        { key: 'warehouse', header: 'Gudang', cell: row => row.warehouseName },
        { key: 'stock', header: 'Stok tersedia', cell: row => <WarehouseQuantity value={row.quantityBase} unit={stock.baseUnit} /> },
      ]} empty={<p>Belum ada gudang dalam cakupan akses.</p>} />
      <WarehousePagination page={stock.warehouses.page} size={stock.warehouses.size} total={stock.warehouses.totalElements} onChange={setPage} />
      <p className="muted">Saldo ini informasi saat dibaca. Persetujuan belum memesan stok dan saldo teknisi tidak otomatis menghalangi permintaan.</p>
    </>}</WarehouseState>
  </section>
}
