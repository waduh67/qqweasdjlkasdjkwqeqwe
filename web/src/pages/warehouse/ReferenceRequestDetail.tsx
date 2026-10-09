import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { getReferenceRequest, REQUEST_LABELS, type ReferenceRequest, type ReferenceRequestLine } from '@/api/warehouse/referenceRequests'
import { useCan } from '@/auth/useCan'
import { Button } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { ReferenceRequestDecision, ReferenceRequestReview } from './ReferenceRequestReview'
import { ReferenceRequestHandover, ReferenceRequestReceipt } from './ReferenceRequestMovements'
import { requestHandoverRemaining } from './referenceRequestMovementDraft'
import { ReferenceRequestStock } from './ReferenceRequestStock'

type Action = { readonly kind: 'REVIEW' | 'APPROVE' | 'REJECT' } | { readonly kind: 'RECEIVE' | 'HANDOVER'; readonly line: ReferenceRequestLine }
const eventNames = { SUBMIT: 'Permintaan diajukan', REVIEW: 'Jumlah ditinjau Admin', DECIDE: 'Keputusan dicatat', RECEIVE: 'Barang pengadaan diterima', HANDOVER: 'Material diserahkan ke teknisi' } as const
export function ReferenceRequestDetail({ id, onBack, onChanged }: { readonly id: string; readonly onBack: () => void; readonly onChanged: () => void }) {
  const load = useCallback(() => getReferenceRequest(id), [id]), result = useWarehouseQuery(load)
  const [action, setAction] = useState<Action | null>(null), [linePage, setLinePage] = useState(0), [eventPage, setEventPage] = useState(0)
  const { can } = useCan()
  const saved = () => { setAction(null); result.reload(); onChanged() }
  return <><div className="row wrap"><Button onClick={onBack}>Kembali ke daftar</Button><Button onClick={result.reload}>Muat ulang detail</Button></div>
    <WarehouseState {...result}>{({ request, timeline }) => {
      const movable = ['APPROVED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'PARTIALLY_FULFILLED'].includes(request.state)
      return <>
        <div className="card stack"><h2>Permintaan {request.id.slice(-8)}</h2>
          <p><strong>{REQUEST_LABELS[request.state]}</strong> · {request.kind === 'RESTOCK' ? 'Restock dari gudang' : 'Pengadaan'} · Revisi {request.revision}</p>
          <p>Penerima: <strong>{request.technicianName ?? request.warehouseName}</strong> · Diajukan oleh {request.requesterName}</p>
          <p>{request.reason}</p><p className="muted">{request.requiresManagerApproval ? 'Memerlukan keputusan Manager setelah tinjauan Admin.' : 'Tinjauan Admin langsung menyetujui permintaan.'} Kebijakan saat pengajuan: revisi {request.policyRevision}.</p>
          <div className="row wrap">
            {request.state === 'SUBMITTED' && can('warehouse.request.review') && <><Button variant="primary" onClick={() => setAction({ kind: 'REVIEW' })}>Tinjau jumlah</Button><Button onClick={() => setAction({ kind: 'REJECT' })}>Tolak permintaan</Button></>}
            {request.state === 'MANAGER_REVIEW' && can('warehouse.request.approve') && <><Button variant="primary" onClick={() => setAction({ kind: 'APPROVE' })}>Setujui permintaan</Button><Button onClick={() => setAction({ kind: 'REJECT' })}>Tolak permintaan</Button></>}
          </div>
        </div>
        <section className="stack" aria-label="Jumlah permintaan"><h2>Material dan pemenuhan</h2>
          <DataTable presentation="warehouse" rows={request.lines.slice(linePage * 25, (linePage + 1) * 25)} rowKey={line => line.id} columns={[
            { key: 'name', header: 'Material', cell: line => line.name, description: line => line.skuId ? 'Barang katalog' : 'Usulan barang baru' },
            { key: 'requested', header: 'Diminta', cell: line => <WarehouseQuantity value={line.requestedBase} unit={line.baseUnit} /> },
            { key: 'approved', header: 'Disetujui', cell: line => <WarehouseQuantity value={line.approvedBase} unit={line.baseUnit} /> },
            { key: 'received', header: 'Pengadaan diterima', cell: line => request.kind === 'PROCUREMENT' ? <WarehouseQuantity value={line.receivedBase} unit={line.baseUnit} /> : 'Dari stok gudang' },
            { key: 'fulfilled', header: 'Terpenuhi', cell: line => <WarehouseQuantity value={line.fulfilledBase} unit={line.baseUnit} /> },
            { key: 'actions', header: 'Tindakan', cell: line => <div className="row wrap">
              {movable && request.kind === 'PROCUREMENT' && BigInt(line.receivedBase) < BigInt(line.approvedBase) && can('warehouse.request.receive') && <Button onClick={() => setAction({ kind: 'RECEIVE', line })}>Terima {line.name}</Button>}
              {movable && request.technicianId && BigInt(requestHandoverRemaining(request, line)) > 0n && can('warehouse.request.handover') && <Button onClick={() => setAction({ kind: 'HANDOVER', line })}>Serahkan {line.name}</Button>}
            </div> },
          ]} /><WarehousePagination page={linePage} size={25} total={request.lines.length} onChange={setLinePage} />
          {request.lines.slice(linePage * 25, (linePage + 1) * 25).map(line => line.skuId && <fieldset key={line.id + request.revision} className="card stack" style={{ minWidth: 0 }}><legend>{line.name}</legend><ReferenceRequestStock skuId={line.skuId} destination={{ requestId: request.id }} /></fieldset>)}
        </section>
        <section className="stack" aria-label="Riwayat permintaan"><h2>Riwayat permintaan</h2>
          <DataTable presentation="warehouse" rows={timeline.slice(eventPage * 25, (eventPage + 1) * 25)} rowKey={event => event.operationId} columns={[
            { key: 'action', header: 'Aktivitas', cell: event => eventNames[event.action], description: event => 'Revisi ' + event.revision },
            { key: 'actor', header: 'Oleh', cell: event => event.actorName },
            { key: 'notes', header: 'Catatan', cell: event => event.notes || 'Tanpa catatan' },
            { key: 'time', header: 'Waktu', cell: event => new Date(event.recordedAt).toLocaleString('id-ID') },
            { key: 'movement', header: 'Dokumen', cell: event => event.movementId && event.action === 'RECEIVE' && can('warehouse.stock.view') ? <Link to={'/warehouse/receipts?id=' + event.movementId}>Lihat penerimaan</Link> : 'Tercatat di permintaan' },
          ]} /><WarehousePagination page={eventPage} size={25} total={timeline.length} onChange={setEventPage} />
        </section>
        {action && <RequestAction action={action} request={request} onSaved={saved} onClose={() => setAction(null)} onReload={() => { setAction(null); result.reload() }} />}
      </>
    }}</WarehouseState>
  </>
}
function RequestAction({ action, request, ...events }: { readonly action: Action; readonly request: ReferenceRequest; readonly onSaved: () => void; readonly onClose: () => void; readonly onReload: () => void }) {
  const { can } = useCan()
  switch (action.kind) {
    case 'REVIEW': return can('warehouse.request.review') ? <ReferenceRequestReview request={request} {...events} /> : null
    case 'APPROVE': return can('warehouse.request.approve') ? <ReferenceRequestDecision request={request} approved {...events} /> : null
    case 'REJECT': return can(request.state === 'SUBMITTED' ? 'warehouse.request.review' : 'warehouse.request.approve') ? <ReferenceRequestDecision request={request} approved={false} {...events} /> : null
    case 'RECEIVE': return can('warehouse.request.receive') ? <ReferenceRequestReceipt request={request} line={action.line} {...events} /> : null
    case 'HANDOVER': return can('warehouse.request.handover') ? <ReferenceRequestHandover request={request} line={action.line} {...events} /> : null
    default: { const remaining: never = action; return remaining }
  }
}
