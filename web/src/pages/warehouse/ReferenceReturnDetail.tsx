import { useCallback, useState } from 'react'
import { getReferenceReturn, RETURN_LABELS } from '@/api/warehouse/referenceReturns'
import { useCan } from '@/auth/useCan'
import { Button } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { ReferenceReturnDecision } from './ReferenceReturnEditor'

export function ReferenceReturnDetail({ id, onBack, onChanged }: { readonly id: string; readonly onBack: () => void; readonly onChanged: () => void }) {
  const load = useCallback(() => getReferenceReturn(id), [id]), result = useWarehouseQuery(load), { can } = useCan()
  const [decision, setDecision] = useState<boolean | null>(null), [page, setPage] = useState(0)
  const reload = () => { setDecision(null); result.reload() }
  return <><div className="row wrap"><Button onClick={onBack}>Kembali ke daftar</Button><Button onClick={reload}>Muat ulang detail</Button></div>
    <WarehouseState {...result}>{({ request: row, timeline }) => <>
      <div className="card stack"><h2>Retur {row.id.slice(-8)}</h2><p><strong>{RETURN_LABELS[row.state]}</strong> · Revisi {row.revision}</p>
        <p>Teknisi: <strong>{row.technicianName}</strong> · Gudang tujuan: <strong>{row.warehouseName}</strong></p>
        <p>{row.skuName}: <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /></p><p>{row.reason}</p>
        {row.state === 'PENDING' ? <p className="muted">Material masih menjadi saldo teknisi. Admin harus memeriksa penerimaan fisik sebelum menyimpan keputusan.</p> : <p>Keputusan oleh {row.reviewerName}. {row.reviewNotes || 'Tanpa catatan tambahan.'}</p>}
        {row.state === 'PENDING' && can('warehouse.return.manage') && <div className="row wrap"><Button variant="primary" onClick={() => setDecision(true)}>Terima retur</Button><Button onClick={() => setDecision(false)}>Tolak retur</Button></div>}
      </div>
      <section className="stack" aria-label="Material retur"><h2>Posisi material</h2><DataTable presentation="warehouse" rows={row.lines.slice(page * 25, (page + 1) * 25)} rowKey={line => line.stockIdentityId}
        columns={[
          { key: 'name', header: 'Material', cell: () => row.skuName },
          { key: 'serial', header: 'Serial / MAC', cell: line => line.serial ?? 'Tanpa serial', description: line => line.mac },
          { key: 'quantity', header: 'Jumlah dikembalikan', cell: line => <WarehouseQuantity value={line.quantityBase} unit={row.baseUnit} /> },
          { key: 'identity', header: 'Referensi posisi', cell: line => line.stockIdentityId },
        ]} /><WarehousePagination page={page} size={25} total={row.lines.length} onChange={setPage} /></section>
      <section className="stack" aria-label="Riwayat retur"><h2>Riwayat retur</h2><DataTable presentation="warehouse" rows={timeline} rowKey={event => event.id} columns={[
        { key: 'action', header: 'Aktivitas', cell: event => event.action === 'SUBMIT' ? 'Retur diajukan' : 'Keputusan retur dicatat', description: event => 'Revisi ' + event.revision },
        { key: 'actor', header: 'Oleh', cell: event => event.actorName },
        { key: 'notes', header: 'Catatan', cell: event => event.notes || 'Tanpa catatan' },
        { key: 'time', header: 'Waktu', cell: event => new Date(event.recordedAt).toLocaleString('id-ID') },
      ]} /></section>
      {decision !== null && can('warehouse.return.manage') && <ReferenceReturnDecision row={row} received={decision} onClose={() => setDecision(null)} onReload={reload} onSaved={() => { reload(); onChanged() }} />}
    </>}</WarehouseState>
  </>
}
