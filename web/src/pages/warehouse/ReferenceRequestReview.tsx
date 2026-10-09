import { useId, useState, type FormEvent } from 'react'
import { referenceSkus } from '@/api/warehouse/reference'
import { decideRequest, reviewRequest, type ReferenceRequest } from '@/api/warehouse/referenceRequests'
import { saveReferenceSku } from '@/api/warehouse/referenceCatalog'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { formatBaseQuantity } from '@/api/warehouse/quantity'
import { useCan } from '@/auth/useCan'
import { Button, TextareaField } from '@/components/atoms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { buildRequestReview, type RequestReviewRow } from './referenceRequestDraft'
import { WarehouseSkuEditor } from './WarehouseSkuEditor'
import { ReferenceRequestStock } from './ReferenceRequestStock'

interface Props { readonly request: ReferenceRequest; readonly onSaved: (row: ReferenceRequest) => void; readonly onClose: () => void; readonly onReload: () => void }
export function ReferenceRequestReview({ request, onSaved, onClose, onReload }: Props) {
  const formId = useId(), { can } = useCan()
  const [rows, setRows] = useState<RequestReviewRow[]>(() => request.lines.map(line => ({ lineId: line.id, quantity: formatBaseQuantity(line.requestedBase, line.baseUnit), mapped: null })))
  const [notes, setNotes] = useState(''), [error, setError] = useState<string | null>(null), [creating, setCreating] = useState<string | null>(null)
  const [operation, setOperation] = useState<WarehouseCommand<ReferenceRequest> | null>(null)
  const update = (lineId: string, patch: Partial<RequestReviewRow>) => setRows(current => current.map(row => row.lineId === lineId ? { ...row, ...patch } : row))
  function prepare(event: FormEvent) {
    event.preventDefault()
    try { setOperation(reviewRequest(request.id, { expectedRevision: request.revision, lines: buildRequestReview(request, rows), notes: notes.trim() })); setError(null) }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa jumlah persetujuan.') }
  }
  return <><ResourceForm editing title="Tinjau jumlah permintaan" onClose={onClose} onBack={() => setOperation(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau persetujuan</Button></>}
    review={operation && <WarehouseCommandDialog embedded title="Simpan tinjauan Admin" confirmLabel="Simpan tinjauan" command={operation} onDone={onSaved} onClose={() => setOperation(null)} onReload={onReload}
      summary={<><p>{request.technicianName ?? request.warehouseName} · Revisi {request.revision}</p><ul>{request.lines.map((line, index) => <li key={line.id}>{rows[index].mapped?.name ?? line.name}: {rows[index].quantity} {line.baseUnit === 'MM' ? 'm' : 'unit'}</li>)}</ul>
        <p>{request.requiresManagerApproval ? 'Lanjut ke keputusan Manager.' : 'Permintaan langsung disetujui.'} Stok belum dipindahkan atau dipesan.</p>{notes && <p>{notes}</p>}</>} />}>
    <form id={formId} className="stack" onSubmit={prepare}>
      <p>{request.reason}</p><p className="muted">Jumlah yang disetujui boleh dikurangi sampai nol. Minimal satu material harus disetujui.</p>
      {request.lines.map((line, index) => <fieldset key={line.id} className="card stack" style={{ minWidth: 0 }}><legend>{line.name}</legend>
        <p>Diminta: <WarehouseQuantity value={line.requestedBase} unit={line.baseUnit} /></p>
        {!line.skuId && <WarehousePicker label={'Barang katalog untuk ' + line.name} load={referenceSkus} value={rows[index].mapped} name={row => row.name + ' · ' + row.code}
          eligible={row => row.state === 'ACTIVE' && row.baseUnit === line.baseUnit} onChange={mapped => update(line.id, { mapped })}
          create={can('warehouse.catalog.manage') ? { label: 'Tambah barang katalog', onClick: () => setCreating(line.id) } : undefined} />}
        <WarehouseQuantityField allowZero label={'Jumlah disetujui ' + (index + 1)} unit={line.baseUnit} value={rows[index].quantity} onChange={quantity => update(line.id, { quantity })} />
        {(rows[index].mapped?.id ?? line.skuId) && <ReferenceRequestStock key={rows[index].mapped?.id ?? line.skuId} skuId={rows[index].mapped?.id ?? line.skuId ?? ''} destination={{ requestId: request.id }} />}
      </fieldset>)}
      <TextareaField label="Catatan tinjauan" maxLength={1000} value={notes} onChange={(_, data) => setNotes(data.value)} />
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
    {creating && <WarehouseSkuEditor reference save={saveReferenceSku} row={null} readOnly={false} onReload={() => setCreating(null)} onClose={() => setCreating(null)} onSaved={mapped => { update(creating, { mapped }); setCreating(null) }} />}
  </>
}
export function ReferenceRequestDecision({ request, approved, onSaved, onClose, onReload }: Props & { readonly approved: boolean }) {
  const formId = useId(), [reason, setReason] = useState(''), [operation, setOperation] = useState<WarehouseCommand<ReferenceRequest> | null>(null)
  return <ResourceForm editing title={approved ? 'Setujui permintaan' : 'Tolak permintaan'} onClose={onClose} onBack={() => setOperation(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau keputusan</Button></>}
    review={operation && <WarehouseCommandDialog embedded title="Simpan keputusan" confirmLabel={approved ? 'Setujui permintaan' : 'Tolak permintaan'} command={operation} onDone={onSaved} onClose={() => setOperation(null)} onReload={onReload}
      summary={<><p>{request.technicianName ?? request.warehouseName} · Revisi {request.revision}</p><p>{approved ? 'Permintaan disetujui untuk jumlah yang ditinjau Admin.' : 'Permintaan ditolak tanpa perubahan stok.'}</p>
        <ul>{request.lines.map(line => <li key={line.id}>{line.name}: <WarehouseQuantity value={line.approvedBase} unit={line.baseUnit} /></li>)}</ul>{reason && <p>{reason}</p>}</>} />}>
    <form id={formId} className="stack" onSubmit={event => { event.preventDefault(); setOperation(decideRequest(request.id, { expectedRevision: request.revision, approved, reason: reason.trim() })) }}>
      <p>{request.reason}</p><TextareaField label={approved ? 'Catatan keputusan' : 'Alasan penolakan'} required={!approved} maxLength={1000} value={reason} onChange={(_, data) => setReason(data.value)} />
    </form>
  </ResourceForm>
}
