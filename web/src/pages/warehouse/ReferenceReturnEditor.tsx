import { useCallback, useId, useState, type FormEvent } from 'react'
import type { WarehouseLocation } from '@/api/warehouse/models'
import { ownMaterials, referenceLocations, type ReferencePosition } from '@/api/warehouse/reference'
import { submitReferenceReturn, decideReferenceReturn, type ReferenceReturn } from '@/api/warehouse/referenceReturns'
import { displayUnit, formatBaseQuantity } from '@/api/warehouse/quantity'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useAuth } from '@/auth/useAuth'
import { Button, TextareaField } from '@/components/atoms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { buildReferenceReturn, type ReferenceReturnRow } from './referenceReturnDraft'

const empty = (): ReferenceReturnRow => ({ key: crypto.randomUUID(), position: null, quantity: '' })
export function ReferenceReturnEditor({ onClose, onSaved }: { readonly onClose: () => void; readonly onSaved: (row: ReferenceReturn) => void }) {
  const { user } = useAuth(), formId = useId()
  const [rows, setRows] = useState<ReferenceReturnRow[]>(() => [empty()]), [warehouse, setWarehouse] = useState<WarehouseLocation | null>(null)
  const [reason, setReason] = useState(''), [error, setError] = useState<string | null>(null), [operation, setOperation] = useState<WarehouseCommand<ReferenceReturn> | null>(null)
  const load = useCallback(async (search: string, page: number) => {
    const result = await ownMaterials(search, page)
    return { ...result, items: result.items.map(item => ({ ...item, id: item.stockIdentityId })) }
  }, [])
  function prepare(event: FormEvent) {
    event.preventDefault()
    if (!user || !warehouse || !reason.trim()) { setError('Pilih gudang tujuan dan isi alasan retur.'); return }
    try {
      setOperation(submitReferenceReturn({ ...buildReferenceReturn(rows, user.id), warehouseId: warehouse.id, reason: reason.trim() })); setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa material dan jumlah retur.') }
  }
  const first = rows[0]?.position
  return <ResourceForm title="Retur material baru" onClose={onClose} onBack={() => setOperation(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau retur</Button></>}
    review={operation && <WarehouseCommandDialog embedded title="Ajukan retur" confirmLabel="Ajukan retur" command={operation} onDone={onSaved} onClose={() => setOperation(null)}
      summary={<><p>Tujuan: <strong>{warehouse?.name ?? warehouse?.code}</strong></p><ul>{rows.map(row => <li key={row.key}>{row.position?.skuName}{row.position?.serial && ' · ' + row.position.serial}: {row.quantity} {row.position && displayUnit(row.position.baseUnit)}</li>)}</ul><p>{reason.trim()}</p><p>Material tetap di tangan Anda sampai Admin menerima barang secara fisik.</p></>} />}>
    <form id={formId} className="stack" onSubmit={prepare}>
      <p>Satu retur berisi satu jenis material. Pengajuan tidak mengurangi atau memesan saldo Anda.</p>
      {rows.map((row, index) => <fieldset key={row.key} className="card stack" style={{ minWidth: 0 }}><legend>Material retur {index + 1}</legend>
        <WarehousePicker<ReferencePosition & { readonly id: string }> label={'Material milik saya ' + (index + 1)} load={load}
          value={row.position ? { ...row.position, id: row.position.stockIdentityId } : null}
          name={item => [item.skuName, item.serial ?? 'Tanpa serial', formatBaseQuantity(item.quantityBase, item.baseUnit) + ' ' + displayUnit(item.baseUnit), item.locationName, item.stockIdentityId.slice(-8)].join(' · ')}
          eligible={item => !first || index === 0 || item.skuId === first.skuId && item.locationId === first.locationId}
          onChange={item => setRows(current => index === 0 ? [{ ...row, position: item, quantity: item?.tracking === 'SERIAL' ? '1' : '' }] : current.map(value => value.key === row.key ? { ...value, position: item, quantity: item?.tracking === 'SERIAL' ? '1' : '' } : value))} />
        {row.position && <WarehouseQuantityField label={'Jumlah dikembalikan ' + (index + 1)} unit={row.position.baseUnit} value={row.quantity} disabled={row.position.tracking === 'SERIAL'}
          onChange={quantity => setRows(current => current.map(value => value.key === row.key ? { ...value, quantity } : value))} />}
        {index > 0 && <Button type="button" onClick={() => setRows(current => current.filter(value => value.key !== row.key))}>Hapus material retur {index + 1}</Button>}
      </fieldset>)}
      <Button type="button" disabled={!first || rows.length >= 100} onClick={() => setRows(current => [...current, empty()])}>Tambah posisi material</Button>
      <WarehousePicker label="Gudang tujuan retur" load={referenceLocations} value={warehouse} name={item => (item.name ?? item.code) + ' · ' + item.code}
        eligible={item => item.state === 'ACTIVE' && item.issueEligible} onChange={setWarehouse} />
      <TextareaField label="Alasan retur" required maxLength={1000} value={reason} onChange={(_, data) => setReason(data.value)} />
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}

export function ReferenceReturnDecision({ row, received, onClose, onSaved, onReload }: { readonly row: ReferenceReturn; readonly received: boolean; readonly onClose: () => void; readonly onSaved: () => void; readonly onReload: () => void }) {
  const formId = useId(), [notes, setNotes] = useState(''), [error, setError] = useState<string | null>(null), [operation, setOperation] = useState<WarehouseCommand<ReferenceReturn> | null>(null)
  const title = received ? 'Terima retur' : 'Tolak retur'
  function prepare(event: FormEvent) {
    event.preventDefault()
    if (!received && !notes.trim()) { setError('Isi alasan penolakan.'); return }
    setOperation(decideReferenceReturn(row.id, { expectedRevision: row.revision, received, notes: notes.trim() })); setError(null)
  }
  return <ResourceForm title={title} onClose={onClose} onBack={() => setOperation(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau keputusan retur</Button></>}
    review={operation && <WarehouseCommandDialog embedded title={title} confirmLabel={title} command={operation} onDone={onSaved} onClose={() => setOperation(null)} onReload={onReload}
      summary={<><p>{row.technicianName} → {row.warehouseName}</p><p>{row.skuName}: <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> · Revisi {row.revision}</p><p>{notes.trim()}</p><p>{received ? 'Pastikan barang sudah diterima secara fisik. Saldo teknisi berkurang dan saldo gudang bertambah setelah disimpan.' : 'Penolakan membiarkan saldo teknisi tetap sama.'}</p></>} />}>
    <form id={formId} className="stack" onSubmit={prepare}><p>{row.skuName} dari {row.technicianName}, tujuan {row.warehouseName}.</p>
      <TextareaField label={received ? 'Catatan penerimaan retur' : 'Alasan penolakan retur'} required={!received} maxLength={1000} value={notes} onChange={(_, data) => setNotes(data.value)} />
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
