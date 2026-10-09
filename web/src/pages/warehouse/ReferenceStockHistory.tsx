import { useCallback, useState } from 'react'
import { listReferenceHistory } from '@/api/warehouse/referenceStock'
import { Button, EmptyState } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

const kinds: Readonly<Record<string, string>> = { RECEIVE: 'Penerimaan', TRANSFER: 'Transfer / penyerahan', ISSUE: 'Penyerahan', RETURN: 'Retur', CONSUME: 'Pemakaian', COUNT_VARIANCE: 'Stock opname', LOSS: 'Kehilangan', ADJUSTMENT: 'Penyesuaian' }
export function ReferenceStockHistory({ skuId }: { readonly skuId: string }) {
  const [page, setPage] = useState(0)
  const load = useCallback(() => listReferenceHistory(skuId, page), [skuId, page])
  const result = useWarehouseQuery(load)
  return <section className="stack" aria-label="Riwayat stok"><p className="muted">Setiap baris mencatat stok masuk atau keluar di satu lokasi. Transfer memiliki baris keluar dan masuk.</p><Button onClick={result.reload}>Muat ulang riwayat</Button>
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Belum ada riwayat stok" hint="Penerimaan, penyerahan, transfer, retur, dan pemakaian akan tercatat di sini." />} columns={[
        { key: 'when', header: 'Waktu', cell: row => <WarehouseTime value={row.recordedAt} /> },
        { key: 'kind', header: 'Kegiatan', cell: row => kinds[row.kind] ?? row.kind, description: row => row.notes },
        { key: 'actor', header: 'Pelaku', cell: row => row.actorName },
        { key: 'location', header: 'Lokasi / pemegang', cell: row => row.locationName, description: row => row.holderName },
        { key: 'quantity', header: 'Perubahan', cell: row => <>{row.direction === 'IN' ? 'Masuk ' : 'Keluar '}<WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /></> },
        { key: 'serial', header: 'Serial / MAC', cell: row => row.serial ?? 'Tanpa serial', description: row => row.mac },
      ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
  </section>
}
