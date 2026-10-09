import { useCallback, useId, useState, type FormEvent } from 'react'
import type { WarehouseLocation, WarehouseSku, WarehouseSupplier } from '@/api/warehouse/models'
import { referenceLocations, referenceSuppliers, type ReferencePosition } from '@/api/warehouse/reference'
import { getReferenceSku } from '@/api/warehouse/referenceCatalog'
import { handoverRequest, receiveRequest, type ReferenceRequest, type ReferenceRequestLine } from '@/api/warehouse/referenceRequests'
import { listReferencePositions } from '@/api/warehouse/referenceStock'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { displayUnit, formatBaseQuantity } from '@/api/warehouse/quantity'
import { useCan } from '@/auth/useCan'
import { Button, TextareaField, TextField } from '@/components/atoms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { buildReceiptLines, emptyReceiptRow, type ReceiptDraftRow } from './receiptDraft'
import { ReceiptLineEditor } from './WarehouseReceiptEditor'
import { buildRequestHandover, requestHandoverRemaining } from './referenceRequestMovementDraft'
import type { ReferenceTransferRow } from './referenceTransferDraft'

interface Props { readonly request: ReferenceRequest; readonly line: ReferenceRequestLine; readonly onSaved: (row: ReferenceRequest) => void; readonly onClose: () => void; readonly onReload: () => void }
export function ReferenceRequestReceipt(props: Props) {
  const skuLoad = useCallback(async () => {
    if (!props.line.skuId) throw new Error('Material belum dipetakan ke katalog.')
    return getReferenceSku(props.line.skuId)
  }, [props.line.skuId])
  const result = useWarehouseQuery(skuLoad)
  if (result.state.status === 'ready') return <RequestReceiptForm {...props} sku={result.state.data} />
  return <ResourceForm readOnly title={'Terima ' + props.line.name} onClose={props.onClose} onBack={props.onClose}>
    <WarehouseState {...result}>{() => null}</WarehouseState>
  </ResourceForm>
}
function RequestReceiptForm({ request, line, sku, onSaved, onClose, onReload }: Props & { readonly sku: WarehouseSku }) {
  const { can } = useCan(), formId = useId(), remaining = (BigInt(line.approvedBase) - BigInt(line.receivedBase)).toString()
  const [warehouse, setWarehouse] = useState<WarehouseLocation | null>(null)
  const [supplier, setSupplier] = useState<WarehouseSupplier | null>(null), [reference, setReference] = useState(''), [notes, setNotes] = useState('')
  const [row, setRow] = useState<ReceiptDraftRow>(() => ({ ...emptyReceiptRow(), sku, quantity: formatBaseQuantity(remaining, line.baseUnit) }))
  const [error, setError] = useState<string | null>(null), [operation, setOperation] = useState<WarehouseCommand<ReferenceRequest> | null>(null)
  const costVisible = can('inventory.cost.view')
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      const warehouseId = request.warehouseId ?? warehouse?.id
      if (!warehouseId) throw new Error('Pilih gudang penerimaan.')
      const receipt = buildReceiptLines([row], costVisible, true)[0]
      if (receipt.skuId !== line.skuId || BigInt(receipt.quantityBase) > BigInt(remaining)) throw new Error('Penerimaan harus sesuai material dan sisa persetujuan.')
      setOperation(receiveRequest(request.id, { quantityBase: receipt.quantityBase, serials: receipt.serials, lotCode: receipt.lotCode, conversion: receipt.conversion, cost: receipt.cost, expectedRevision: request.revision, lineId: line.id, warehouseId, notes: notes.trim(),
        ...(supplier ? { supplierId: supplier.id } : {}), ...(reference.trim() ? { reference: reference.trim() } : {}) })); setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa penerimaan.') }
  }
  return <ResourceForm editing title={'Terima ' + line.name} onClose={onClose} onBack={() => setOperation(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau barang datang</Button></>}
    review={operation && <WarehouseCommandDialog embedded title="Catat penerimaan pengadaan" confirmLabel="Terima barang pengadaan" command={operation} onDone={onSaved} onClose={() => setOperation(null)} onReload={onReload}
      summary={<><p>{line.name} · Revisi {request.revision}</p><p>Gudang: <strong>{request.warehouseName ?? warehouse?.name ?? warehouse?.code}</strong> · Jumlah: {row.quantity} {displayUnit(line.baseUnit)}</p>
        {row.serials && <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{row.serials}</p>}<p>{request.warehouseId ? 'Penerimaan langsung memenuhi permintaan gudang.' : 'Barang tersedia di gudang. Lanjutkan serah terima untuk menambah material teknisi.'}</p>{notes && <p>{notes}</p>}</>} />}>
    <form id={formId} className="stack" onSubmit={prepare}>
      <p>Sisa yang dapat diterima: <WarehouseQuantity value={remaining} unit={line.baseUnit} />. Penerimaan sebagian diperbolehkan.</p>
      {request.warehouseId ? <p>Gudang penerima: {request.warehouseName}</p> : <WarehousePicker label="Gudang penerimaan" load={referenceLocations} value={warehouse} name={item => (item.name ?? item.code) + ' · ' + item.code} eligible={item => item.state === 'ACTIVE' && item.issueEligible} onChange={setWarehouse} />}
      <ReceiptLineEditor reference row={row} number={1} costVisible={costVisible} fixedSku onChange={patch => setRow(current => ({ ...current, ...patch }))} />
      <WarehousePicker optional label="Pemasok" load={referenceSuppliers} value={supplier} name={item => item.name + ' · ' + item.code} eligible={item => item.state === 'ACTIVE'} onChange={setSupplier} />
      <TextField label="Referensi surat jalan" maxLength={500} value={reference} onChange={(_, data) => setReference(data.value)} />
      <TextareaField label="Catatan penerimaan" maxLength={1000} value={notes} onChange={(_, data) => setNotes(data.value)} />
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
const emptyPositionRow = (): ReferenceTransferRow => ({ key: crypto.randomUUID(), position: null, quantity: '' })
export function ReferenceRequestHandover({ request, line, onSaved, onClose, onReload }: Props) {
  const formId = useId(), [warehouse, setWarehouse] = useState<WarehouseLocation | null>(null)
  const [rows, setRows] = useState<ReferenceTransferRow[]>(() => [emptyPositionRow()]), [notes, setNotes] = useState(''), [error, setError] = useState<string | null>(null)
  const [operation, setOperation] = useState<WarehouseCommand<ReferenceRequest> | null>(null)
  const load = useCallback(async (search: string, page: number) => {
    if (!warehouse || !line.skuId) return { items: [], page, size: 25, totalElements: 0 }
    const result = await listReferencePositions(line.skuId, { search, page, locationId: warehouse.id, holderKind: 'WAREHOUSE', availableOnly: true })
    return { ...result, items: result.items.map(position => ({ ...position, id: position.stockIdentityId })) }
  }, [warehouse, line.skuId])
  const remaining = requestHandoverRemaining(request, line)
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      if (!warehouse) throw new Error('Pilih gudang penyerahan.')
      setOperation(handoverRequest(request.id, { expectedRevision: request.revision, lineId: line.id, warehouseId: warehouse.id, lines: buildRequestHandover(request, line, rows, warehouse.id), notes: notes.trim() })); setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa serah terima.') }
  }
  return <ResourceForm editing title={'Serahkan ' + line.name} onClose={onClose} onBack={() => setOperation(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau serah terima</Button></>}
    review={operation && <WarehouseCommandDialog embedded title="Serahkan material" confirmLabel="Serahkan ke teknisi" command={operation} onDone={onSaved} onClose={() => setOperation(null)} onReload={onReload}
      summary={<><p><strong>{warehouse?.name ?? warehouse?.code}</strong> → <strong>{request.technicianName}</strong> · Revisi {request.revision}</p><ul>{rows.map(row => <li key={row.key}>{line.name}{row.position?.serial && ' · ' + row.position.serial}: {row.quantity} {displayUnit(line.baseUnit)}</li>)}</ul>
        <p>Material langsung menjadi stok di tangan teknisi setelah disimpan.</p>{notes && <p>{notes}</p>}</>} />}>
    <form id={formId} className="stack" onSubmit={prepare}>
      <p>Sisa yang dapat diserahkan: <WarehouseQuantity value={remaining} unit={line.baseUnit} /> · Penerima: {request.technicianName}</p>
      <WarehousePicker label="Gudang penyerahan" load={referenceLocations} value={warehouse} name={item => (item.name ?? item.code) + ' · ' + item.code} eligible={item => item.state === 'ACTIVE' && item.issueEligible} onChange={item => { setWarehouse(item); setRows([emptyPositionRow()]) }} />
      {warehouse && rows.map((row, index) => <fieldset className="card stack" style={{ minWidth: 0 }} key={row.key}><legend>Stok penyerahan {index + 1}</legend>
        <WarehousePicker<ReferencePosition & { readonly id: string }> label={'Stok penyerahan ' + (index + 1)} load={load} value={row.position ? { ...row.position, id: row.position.stockIdentityId } : null}
          name={item => [item.skuName, item.serial ?? 'Tanpa serial', formatBaseQuantity(item.quantityBase, item.baseUnit) + ' ' + displayUnit(item.baseUnit), item.stockIdentityId.slice(-8)].join(' · ')}
          onChange={position => setRows(current => current.map(item => item.key === row.key ? { ...item, position, quantity: position?.tracking === 'SERIAL' ? '1' : '' } : item))} />
        {row.position && <WarehouseQuantityField label={'Jumlah diserahkan ' + (index + 1)} unit={line.baseUnit} value={row.quantity} disabled={row.position.tracking === 'SERIAL'} onChange={quantity => setRows(current => current.map(item => item.key === row.key ? { ...item, quantity } : item))} />}
        {rows.length > 1 && <Button type="button" onClick={() => setRows(current => current.filter(item => item.key !== row.key))}>Hapus stok {index + 1}</Button>}
      </fieldset>)}
      <Button type="button" disabled={!warehouse || rows.length >= 100} onClick={() => setRows(current => [...current, emptyPositionRow()])}>Tambah posisi stok</Button>
      <TextareaField label="Catatan serah terima" maxLength={1000} value={notes} onChange={(_, data) => setNotes(data.value)} />
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
