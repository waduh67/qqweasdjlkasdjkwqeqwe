import { useCallback, useId, useState, type FormEvent } from 'react'
import type { WarehouseLocation, WarehouseSku } from '@/api/warehouse/models'
import { referenceLocations, referenceSkus, type ReferencePosition } from '@/api/warehouse/reference'
import { listReferencePositions } from '@/api/warehouse/referenceStock'
import { postReferenceTransfer, type ReferenceMovementPosted } from '@/api/warehouse/referenceMovements'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { formatBaseQuantity, displayUnit } from '@/api/warehouse/quantity'
import { useCan } from '@/auth/useCan'
import { Button, TextareaField } from '@/components/atoms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseDenied } from '@/components/organisms/warehouse/WarehouseState'
import { buildReferenceTransferLines, type ReferenceTransferRow } from './referenceTransferDraft'

const emptyRow = (): ReferenceTransferRow => ({ key: crypto.randomUUID(), position: null, quantity: '' })
export function ReferenceTransferEditor({ onSaved, onClose }: { readonly onSaved: (row: ReferenceMovementPosted) => void; readonly onClose: () => void }) {
  const { can } = useCan(), formId = useId()
  const [source, setSource] = useState<WarehouseLocation | null>(null), [destination, setDestination] = useState<WarehouseLocation | null>(null)
  const [rows, setRows] = useState<ReferenceTransferRow[]>(() => [emptyRow()]), [notes, setNotes] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [operation, setOperation] = useState<WarehouseCommand<ReferenceMovementPosted> | null>(null)
  if (!can('warehouse.stock.manage')) return <WarehouseDenied />
  function prepare(event: FormEvent) {
    event.preventDefault()
    if (!source || !destination) { setError('Pilih gudang asal dan tujuan.'); return }
    try {
      const lines = buildReferenceTransferLines(rows, source.id, destination.id)
      setOperation(postReferenceTransfer({ sourceWarehouseId: source.id, warehouseId: destination.id, lines, notes: notes.trim() })); setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa tujuan dan jumlah transfer.') }
  }
  return <ResourceForm title="Transfer baru" onClose={onClose} onBack={() => setOperation(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau transfer</Button></>}
    review={operation && <WarehouseCommandDialog embedded title="Pindahkan stok" confirmLabel="Pindahkan stok" command={operation} onDone={onSaved} onClose={() => setOperation(null)}
      summary={<><p><strong>{source?.name ?? source?.code}</strong> → <strong>{destination?.name ?? destination?.code}</strong></p>
        <ul>{rows.map(row => <li key={row.key}>{row.position?.skuName}{row.position?.serial && ' · ' + row.position.serial}: {row.quantity} {row.position && displayUnit(row.position.baseUnit)}</li>)}</ul>
        {notes.trim() && <p>{notes.trim()}</p>}<p>Jumlah tersebut langsung berpindah dari gudang asal ke gudang tujuan setelah disimpan.</p></>} />}>
    <form id={formId} className="stack" onSubmit={prepare}>
      <p className="muted">Pilih posisi stok yang tersedia. Transfer langsung memindahkan jumlah yang Anda tinjau.</p>
      <WarehousePicker label="Gudang asal" load={referenceLocations} value={source} name={row => (row.name ?? row.code) + ' · ' + row.code}
        eligible={row => row.state === 'ACTIVE' && row.issueEligible} onChange={row => { setSource(row); setRows([emptyRow()]) }} />
      <WarehousePicker label="Gudang tujuan" load={referenceLocations} value={destination} name={row => (row.name ?? row.code) + ' · ' + row.code}
        eligible={row => row.state === 'ACTIVE' && row.issueEligible && row.id !== source?.id} onChange={setDestination} />
      {source ? rows.map((row, index) => <ReferenceTransferLine key={row.key} sourceId={source.id} number={index + 1} row={row}
        onChange={patch => setRows(current => current.map(item => item.key === row.key ? { ...item, ...patch } : item))}
        onRemove={rows.length > 1 ? () => setRows(current => current.filter(item => item.key !== row.key)) : undefined} />) : <p>Pilih gudang asal untuk mencari stok.</p>}
      <Button type="button" disabled={!source || rows.length >= 100} onClick={() => setRows(current => [...current, emptyRow()])}>Tambah barang transfer</Button>
      <TextareaField label="Catatan transfer" maxLength={1000} value={notes} onChange={(_, data) => setNotes(data.value)} />
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  </ResourceForm>
}

function ReferenceTransferLine({ sourceId, row, number, onChange, onRemove }: { readonly sourceId: string; readonly row: ReferenceTransferRow; readonly number: number;
  readonly onChange: (patch: Partial<ReferenceTransferRow>) => void; readonly onRemove?: () => void }) {
  const [sku, setSku] = useState<WarehouseSku | null>(null)
  const load = useCallback(async (search: string, page: number) => {
    if (!sku) return { items: [], page, size: 25, totalElements: 0 }
    const result = await listReferencePositions(sku.id, { search, page, locationId: sourceId, holderKind: 'WAREHOUSE', availableOnly: true })
    return { ...result, items: result.items.map(position => ({ ...position, id: position.stockIdentityId })) }
  }, [sku, sourceId])
  const position = row.position
  return <fieldset className="card stack" style={{ minWidth: 0 }}><legend>Barang transfer {number}</legend>
    <WarehousePicker label={'Barang transfer ' + number} load={referenceSkus} value={sku} name={item => item.name + ' · ' + item.code}
      onChange={item => { setSku(item); onChange({ position: null, quantity: '' }) }} />
    {sku && <WarehousePicker<ReferencePosition & { readonly id: string }> label={'Stok asal ' + number} load={load} value={position ? { ...position, id: position.stockIdentityId } : null}
      name={item => [item.skuName, item.serial ?? 'Tanpa serial', item.locationName, formatBaseQuantity(item.quantityBase, item.baseUnit) + ' ' + displayUnit(item.baseUnit), item.stockIdentityId.slice(-8)].join(' · ')}
      onChange={item => onChange({ position: item, quantity: item?.tracking === 'SERIAL' ? '1' : '' })} />}
    {position && <><p className="muted">Tersedia: {formatBaseQuantity(position.quantityBase, position.baseUnit)} {displayUnit(position.baseUnit)}{position.mac && ' · MAC ' + position.mac}</p>
      <WarehouseQuantityField label="Jumlah dipindahkan" unit={position.baseUnit} value={row.quantity} disabled={position.tracking === 'SERIAL'} onChange={quantity => onChange({ quantity })} /></>}
    {onRemove && <Button type="button" onClick={onRemove}>Hapus barang transfer {number}</Button>}
  </fieldset>
}
