import { useId, useState, type FormEvent } from 'react'
import type { WarehouseLocation } from '@/api/warehouse/models'
import { referenceLocations, referenceSkus } from '@/api/warehouse/reference'
import { submitRequest, type ReferenceRequest, type RequestSubmitInput } from '@/api/warehouse/referenceRequests'
import { requestTechnicians, type TechnicianChoice } from '@/api/warehouse/technicians'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, SelectField, TextareaField, TextField } from '@/components/atoms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { buildRequestLines, emptyRequestRow, type RequestDraftRow } from './referenceRequestDraft'
import { ReferenceRequestStock } from './ReferenceRequestStock'
import type { RequestStockDestination } from '@/api/warehouse/referenceRequestStock'

export function ReferenceRequestEditor({ onSaved, onClose }: { readonly onSaved: (row: ReferenceRequest) => void; readonly onClose: () => void }) {
  const { can } = useCan(), formId = useId(), managing = can('warehouse.request.review')
  const [kind, setKind] = useState<ReferenceRequest['kind']>('RESTOCK'), [destination, setDestination] = useState<'TECHNICIAN' | 'WAREHOUSE'>('TECHNICIAN')
  const [warehouse, setWarehouse] = useState<WarehouseLocation | null>(null), [technician, setTechnician] = useState<TechnicianChoice | null>(null)
  const [reason, setReason] = useState(''), [rows, setRows] = useState<RequestDraftRow[]>(() => [emptyRequestRow()])
  const [error, setError] = useState<string | null>(null), [review, setReview] = useState<{ readonly command: WarehouseCommand<ReferenceRequest>; readonly input: RequestSubmitInput } | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      if (!reason.trim()) throw new Error('Isi alasan permintaan.')
      if (managing && (destination === 'WAREHOUSE' ? !warehouse : !technician)) throw new Error('Pilih penerima permintaan.')
      const input: RequestSubmitInput = { kind, reason: reason.trim(), lines: buildRequestLines(rows, kind),
        ...(managing && destination === 'WAREHOUSE' && warehouse ? { warehouseId: warehouse.id } : {}),
        ...(managing && destination === 'TECHNICIAN' && technician ? { technicianId: technician.id } : {}) }
      setReview({ command: submitRequest(input), input }); setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa permintaan.') }
  }
  return <ResourceForm title="Permintaan baru" onClose={onClose} onBack={() => setReview(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau permintaan</Button></>}
    review={review && <WarehouseCommandDialog embedded title="Ajukan permintaan" confirmLabel="Ajukan permintaan" command={review.command} onDone={onSaved} onClose={() => setReview(null)}
      summary={<><p>{kind === 'RESTOCK' ? 'Restock dari stok gudang' : 'Pengadaan barang'}</p><p>Penerima: <strong>{managing ? destination === 'WAREHOUSE' ? warehouse?.name ?? warehouse?.code : technician?.name : 'Anda sendiri'}</strong></p>
        <ul>{review.input.lines.map((line, index) => <li key={rows[index].key}>{line.proposedName ?? rows[index].sku?.name}: <WarehouseQuantity value={line.requestedBase} unit={line.baseUnit} /></li>)}</ul>
        <p>{review.input.reason}</p><p>Permintaan masuk ke tinjauan Admin. Pengajuan dan persetujuan belum memindahkan atau memesan stok.</p></>} />}>
    <form id={formId} className="stack" onSubmit={prepare}>
      <SelectField label="Jenis permintaan" value={kind} disabled={destination === 'WAREHOUSE'} onChange={(_, data) => { if (data.value === 'RESTOCK' || data.value === 'PROCUREMENT') { setKind(data.value); setRows([emptyRequestRow()]) } }}>
        <option value="RESTOCK">Restock dari stok gudang</option><option value="PROCUREMENT">Pengadaan barang</option>
      </SelectField>
      {managing ? <><SelectField label="Penerima permintaan" value={destination} onChange={(_, data) => { if (data.value === 'TECHNICIAN' || data.value === 'WAREHOUSE') { setDestination(data.value); if (data.value === 'WAREHOUSE') setKind('PROCUREMENT') } }}>
        <option value="TECHNICIAN">Teknisi</option><option value="WAREHOUSE">Gudang</option></SelectField>
        {destination === 'TECHNICIAN' ? <WarehousePicker label="Teknisi penerima" load={requestTechnicians} value={technician} name={row => row.name + ' · ' + row.email} onChange={setTechnician} />
          : <WarehousePicker label="Gudang penerima" load={referenceLocations} value={warehouse} name={row => (row.name ?? row.code) + ' · ' + row.code} onChange={setWarehouse} eligible={row => row.state === 'ACTIVE' && row.issueEligible} />}
      </> : <p>Material diajukan untuk Anda sendiri. Admin akan menyerahkannya setelah permintaan disetujui.</p>}
      {rows.map((row, index) => <RequestLineEditor key={row.key} row={row} number={index + 1} kind={kind}
        stockDestination={!managing ? {} : destination === 'WAREHOUSE' ? warehouse ? { warehouseId: warehouse.id } : null : technician ? { technicianId: technician.id } : null}
        onChange={patch => setRows(current => current.map(item => item.key === row.key ? { ...item, ...patch } : item))}
        onRemove={rows.length > 1 ? () => setRows(current => current.filter(item => item.key !== row.key)) : undefined} />)}
      <Button type="button" disabled={rows.length >= 100} onClick={() => setRows(current => [...current, emptyRequestRow()])}>Tambah material</Button>
      <TextareaField label="Alasan permintaan" required maxLength={1000} value={reason} onChange={(_, data) => setReason(data.value)} />
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
function RequestLineEditor({ row, number, kind, stockDestination, onChange, onRemove }: { readonly row: RequestDraftRow; readonly number: number; readonly kind: ReferenceRequest['kind']; readonly stockDestination: RequestStockDestination | null; readonly onChange: (patch: Partial<RequestDraftRow>) => void; readonly onRemove?: () => void }) {
  return <fieldset className="card stack" style={{ minWidth: 0 }}><legend>Material {number}</legend>
    {kind === 'PROCUREMENT' && <SelectField label={'Sumber material ' + number} value={row.proposed ? 'NEW' : 'CATALOG'} onChange={(_, data) => onChange({ proposed: data.value === 'NEW', sku: null, quantity: '' })}>
      <option value="CATALOG">Barang katalog</option><option value="NEW">Usulkan barang baru</option></SelectField>}
    {row.proposed ? <><TextField label={'Nama material baru ' + number} required maxLength={200} value={row.proposedName} onChange={(_, data) => onChange({ proposedName: data.value })} />
      <SelectField label={'Satuan material ' + number} value={row.unit} onChange={(_, data) => { if (data.value === 'EA' || data.value === 'MM') onChange({ unit: data.value, quantity: '' }) }}><option value="EA">Unit</option><option value="MM">Meter</option></SelectField></>
      : <WarehousePicker label={'Material ' + number} load={referenceSkus} value={row.sku} name={item => item.name + ' · ' + item.code} eligible={item => item.state === 'ACTIVE'} onChange={sku => onChange({ sku, quantity: '' })} />}
    {(row.proposed || row.sku) && <WarehouseQuantityField label={'Jumlah diminta ' + number} unit={row.proposed ? row.unit : row.sku?.baseUnit ?? row.unit} value={row.quantity} onChange={quantity => onChange({ quantity })} />}
    {row.sku && (stockDestination ? <ReferenceRequestStock key={row.sku.id + JSON.stringify(stockDestination)} skuId={row.sku.id} destination={stockDestination} /> : <p className="muted">Pilih penerima untuk melihat stok material.</p>)}
    {onRemove && <Button type="button" onClick={onRemove}>Hapus material {number}</Button>}
  </fieldset>
}
