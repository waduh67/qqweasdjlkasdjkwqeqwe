import { useCallback, useState } from 'react'
import { getMaterialUsage } from '@/api/warehouse/materialExecution'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

export function WorkOrderMaterialHistory({ id }: { id: string }) {
  const [page, setPage] = useState(0), loader = useCallback(() => getMaterialUsage(id, page), [id, page]), result = useWarehouseQuery(loader)
  return <section className="stack" aria-label="Riwayat pemakaian material"><h3>Riwayat pemakaian tersimpan</h3><p>Setiap revisi berisi pemakaian baru. Pemeriksaan QA dan pengiriman ulang tidak menambah jumlah terpakai.</p>
    <WarehouseState {...result}>{data => <>{!data.items.length && <p>Belum ada catatan pemakaian dalam cakupan Anda.</p>}{data.items.map(row => <article className="card stack" key={row.id}>
      <h4>Pemakaian {row.useRevision} · Rencana {row.planRevision}</h4><p>{row.actor?.name ?? 'Nama tidak tersedia'} · <WarehouseTime value={row.recordedAt} /></p>
      {row.materialMode === 'NONE' ? <p>Tanpa material: {row.reason}</p> : <ul>{row.lines.map(line => <li key={line.id}>{line.sku.name}: <WarehouseQuantity value={line.quantityBase} unit={line.baseUnit} /> · sisa pada saat dicatat <WarehouseQuantity value={line.residualBase} unit={line.baseUnit} /></li>)}</ul>}
      <p>Bukti: {row.evidenceReference}</p>{row.materialMode !== 'NONE' && row.reason && <p>{row.reason}</p>}
    </article>)}<WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
  </section>
}
