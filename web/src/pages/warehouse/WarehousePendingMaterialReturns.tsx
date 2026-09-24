import { useCallback, useState } from 'react'
import { getPendingMaterialReturns } from '@/api/warehouse/myMaterials'
import { useAuth } from '@/auth/useAuth'
import { Button } from '@/components/atoms'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useFieldConnection } from '@/hooks/useFieldConnection'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { MyMaterialResidualAcknowledgement } from '../MyMaterialResidualAcknowledgement'

export function WarehousePendingMaterialReturns() {
  const [open, setOpen] = useState(false)
  return <section className="card stack"><h2>Sisa material dalam perjalanan</h2><p>Akui barang yang benar-benar diterima dari teknisi. Setelah itu, pilih sumbernya melalui Terima retur baru untuk pemeriksaan.</p>
    <Button onClick={() => setOpen(!open)}>{open ? 'Tutup daftar sisa' : 'Lihat sisa menunggu penerimaan'}</Button>{open && <PendingReturns />}</section>
}
function PendingReturns() {
  const [page, setPage] = useState(0), load = useCallback(() => getPendingMaterialReturns(page), [page]), result = useWarehouseQuery(load)
  const { readOnly } = useAuth(), online = useFieldConnection()
  return <><Button disabled={!online} onClick={result.reload}>Segarkan sisa dalam perjalanan</Button><WarehouseState {...result}>{data => <>
    {data.items.length === 0 && <p>Tidak ada sisa menunggu penerimaan dalam cakupan Anda.</p>}{data.items.map(row => <article className="card stack" key={row.id}>
      <h3>{row.sku.name} · {row.serial ?? row.lotCode}</h3><p><WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> · {row.sender?.name ?? 'Pengirim'} → {row.location.code} · {row.location.name}</p>
      <p style={{ overflowWrap: 'anywhere' }}>{row.code} · Revisi {row.revision}</p><MyMaterialResidualAcknowledgement row={row} enabled={online && !readOnly} onDone={result.reload} />
    </article>)}<WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState></>
}
