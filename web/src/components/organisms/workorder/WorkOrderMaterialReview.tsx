import { useCallback, useState } from 'react'
import { getMaterialApprovalReview, getMaterialObligationRows } from '@/api/warehouse/materialReview'
import { Button } from '@/components/atoms'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

export function WorkOrderMaterialReview({ id }: { id: string }) {
  const [open, setOpen] = useState(false)
  return <section className="stack" aria-label="Material persetujuan QA"><Button onClick={() => setOpen(value => !value)}>{open ? 'Tutup material persetujuan QA' : 'Lihat material persetujuan QA'}</Button>{open && <FrozenReview id={id} />}</section>
}
function FrozenReview({ id }: { id: string }) {
  const loader = useCallback(() => getMaterialApprovalReview(id), [id]), result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{review => review ? <div className="card stack"><h3>Material yang dikunci saat persetujuan</h3>
    <p>WO revisi {review.workOrderRevision}{review.usage && <> · Rencana {review.usage.planRevision} · Pemakaian {review.usage.useRevision}</>}</p>
    {review.usage && <><p>{review.usage.actor?.name ?? 'Nama tidak tersedia'} · <WarehouseTime value={review.usage.recordedAt} /></p>
      {review.usage.materialMode === 'NONE' ? <p>Tanpa material: {review.usage.reason}</p> : <ul>{review.usage.lines.map(line => <li key={line.id}>{line.sku.name}: <WarehouseQuantity value={line.quantityBase} unit={line.baseUnit} /></li>)}</ul>}
      <p>Bukti: {review.usage.evidenceReference}</p></>}
    {!!review.deployments.length && <section aria-label="Perangkat dalam persetujuan"><h4>Pemasangan perangkat</h4><ul>{review.deployments.map(row => <li key={row.authorizationId}>
      <strong>{row.sku.name} · {row.serial}</strong><p>{row.actor?.name ?? 'Nama tidak tersedia'} · <WarehouseTime value={row.recordedAt} /></p>
    </li>)}</ul></section>}
    <p>Persetujuan memakai sumber tersimpan ini. Lihat riwayat pemakaian untuk revisi tambahan sebelumnya dan sisa kewajiban untuk keadaan sekarang.</p>
  </div> : <p>Belum ada catatan material persetujuan QA yang berlaku. Catatan pemakaian tetap tersimpan pada riwayat.</p>}</WarehouseState>
}
export function WorkOrderMaterialObligations({ id }: { id: string }) {
  const [page, setPage] = useState(0), loader = useCallback(() => getMaterialObligationRows(id, page), [id, page]), result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{data => <>{!data.items.length && <p>Tidak ada baris kewajiban dalam cakupan lokasi Anda.</p>}<ul>{data.items.map(row => <li key={row.id}>
    <strong>{row.sku.name} · {row.serial ?? row.lotCode ?? row.sku.code}</strong> · {row.issueCode}<p>
      Dipakai <WarehouseQuantity value={row.obligation.usedBase} unit={row.obligation.baseUnit} />, masih menjadi tanggung jawab <WarehouseQuantity value={row.obligation.stillAccountableBase} unit={row.obligation.baseUnit} />, dalam perjalanan <WarehouseQuantity value={row.obligation.transitBase} unit={row.obligation.baseUnit} />, retur selesai diperiksa <WarehouseQuantity value={row.obligation.settledReturnBase} unit={row.obligation.baseUnit} />.
    </p></li>)}</ul><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
}
