import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { getMaterials, submitMaterialRequest } from '@/api/warehouse/materials'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { MaterialPlanEditor } from '@/pages/warehouse/MaterialPlanEditor'

export function WorkOrderMaterialPlanning({ id, active, onChanged }: { id: string; active: boolean; onChanged: () => void }) {
  const loader = useCallback(() => getMaterials(id), [id]), result = useWarehouseQuery(loader)
  const { can } = useCan(), [editing, setEditing] = useState(false), [operation, setOperation] = useState<WarehouseCommand<unknown> | null>(null)
  function refresh() { setEditing(false); setOperation(null); result.reload(); onChanged() }
  const manage = can('inventory.request.manage') && (can('workorder.order.update') || can('workorder.order.assign'))
  return <WarehouseState {...result}>{summary => {
    const locked = summary.lines.some(row => BigInt(row.issuedBase) + BigInt(row.reservedPickedBase) + BigInt(row.reservedUnpickedBase) > 0n)
    const override = !summary.plan?.lines.some(row => row.substitution) || can('inventory.request.override')
    return <section className="stack" aria-label="Perencanaan material WO">
      {editing ? <MaterialPlanEditor summary={summary} onSaved={refresh} onReload={refresh} onClose={() => setEditing(false)} /> : <>
        {manage && <Button disabled={!active || locked} onClick={() => setEditing(true)}>{summary.plan ? 'Revisi rencana material' : 'Susun rencana material'}</Button>}
        {locked && manage && <p>Reservasi atau pengeluaran mengunci rencana. Lepas reservasi yang belum dikirim; setelah pemakaian, perubahan memakai alur pengerjaan ulang.</p>}
        {manage && active && summary.plan && summary.demandState === 'DRAFT' && <Button disabled={!override} onClick={() => setOperation(submitMaterialRequest(id, { expectedRevision: summary.revisions.planRevision, workOrderRevision: summary.revisions.workOrderRevision }))}>Ajukan rencana material</Button>}
        {summary.lines.length > 0 && <ul>{summary.lines.map(line => <li key={line.planLineId}>{summary.plan?.lines.find(row => row.id === line.planLineId)?.sku.name ?? 'Material rencana sebelumnya'}: diminta <WarehouseQuantity value={line.requestedBase} unit={line.baseUnit} />, dikirim <WarehouseQuantity value={line.issuedBase} unit={line.baseUnit} />, terpakai <WarehouseQuantity value={line.physicallyUsedBase} unit={line.baseUnit} />, kekurangan <WarehouseQuantity value={line.backorderBase} unit={line.baseUnit} />
          {can('inventory.item.view') && <p><Link to={`/warehouse/stock?skuId=${line.skuId}`}>Periksa stok barang</Link></p>}</li>)}</ul>}
      </>}
      {can('workorder.order.view') && <Link to={`/warehouse/requests?workOrderId=${id}`}>Buka permintaan dan pengeluaran WO ini</Link>}
      {operation && <WarehouseCommandDialog title="Konfirmasi pengajuan rencana" command={operation} confirmLabel="Ajukan rencana" onDone={refresh} onReload={refresh} onClose={() => setOperation(null)} summary={<><p>Rencana {summary.revisions.planRevision} · WO revisi {summary.revisions.workOrderRevision}</p>{summary.materialMode === 'NONE' ? <p>Tanpa material: {summary.noMaterialReason}</p> : <ul>{summary.plan?.lines.map(line => <li key={line.id}>{line.sku.name}: <WarehouseQuantity value={line.quantityBase} unit={line.sku.baseUnit} /></li>)}</ul>}<p>Pengajuan rencana belum memindahkan stok.</p></>} />}
    </section>
  }}</WarehouseState>
}
