import { useId, useState, type FormEvent } from 'react'
import type { WarehouseLocation, WarehouseSupplier } from '@/api/warehouse/models'
import { referenceLocations, referenceSuppliers } from '@/api/warehouse/reference'
import { postReferenceReceipt, type ReferenceMovementPosted } from '@/api/warehouse/referenceMovements'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, TextareaField, TextField } from '@/components/atoms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseDenied } from '@/components/organisms/warehouse/WarehouseState'
import { buildReceiptLines, emptyReceiptRow, type ReceiptDraftRow } from './receiptDraft'
import { ReceiptLineEditor } from './WarehouseReceiptEditor'

export function ReferenceReceiptEditor({ onSaved, onClose }: { readonly onSaved: (row: ReferenceMovementPosted) => void; readonly onClose: () => void }) {
  const { can } = useCan(), formId = useId()
  const [warehouse, setWarehouse] = useState<WarehouseLocation | null>(null)
  const [supplier, setSupplier] = useState<WarehouseSupplier | null>(null)
  const [reference, setReference] = useState(''), [notes, setNotes] = useState('')
  const [rows, setRows] = useState<ReceiptDraftRow[]>(() => [emptyReceiptRow()])
  const [error, setError] = useState<string | null>(null)
  const [operation, setOperation] = useState<WarehouseCommand<ReferenceMovementPosted> | null>(null)
  const costVisible = can('inventory.cost.view')
  if (!can('warehouse.stock.manage')) return <WarehouseDenied />
  function prepare(event: FormEvent) {
    event.preventDefault()
    if (!warehouse) { setError('Pilih gudang tujuan.'); return }
    try {
      const lines = buildReceiptLines(rows, costVisible, true)
      setOperation(postReferenceReceipt({ warehouseId: warehouse.id, lines, notes: notes.trim(),
        ...(supplier ? { supplierId: supplier.id } : {}), ...(reference.trim() ? { reference: reference.trim() } : {}) }))
      setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa barang dan jumlah penerimaan.') }
  }
  return <ResourceForm title="Penerimaan baru" onClose={onClose} onBack={() => setOperation(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau penerimaan</Button></>}
    review={operation && <WarehouseCommandDialog embedded title="Simpan penerimaan" confirmLabel="Terima barang" command={operation} onDone={onSaved} onClose={() => setOperation(null)}
      summary={<><p>Gudang tujuan: <strong>{warehouse?.name ?? warehouse?.code}</strong></p>
        <p>Pemasok: {supplier?.name ?? 'Tidak diisi'} · Surat jalan: {reference.trim() || 'Tidak diisi'}</p>
        <ul>{rows.map(row => <li key={row.key}>{row.sku?.name}: {row.sku && <WarehouseQuantity value={buildReceiptLines([row], costVisible, true)[0].quantityBase} unit={row.sku.baseUnit} />}
          {row.sku?.tracking === 'SERIAL' ? <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{row.serials}</p> : <p>Lot / reel: {row.lotCode.trim() || 'Dibuat otomatis'}</p>}
          {row.useConversion && <p>{row.packageQuantity} kemasan · konversi {row.numerator} / {row.denominator} dalam satuan dasar</p>}
          {costVisible && row.useCost && <p>Total biaya kelompok: {row.totalMinor} {row.currency} (satuan terkecil)</p>}
        </li>)}</ul>
        {notes.trim() && <p>{notes.trim()}</p>}<p>Barang langsung menjadi stok tersedia setelah penerimaan disimpan.</p></>} />}>
    <form id={formId} className="stack" onSubmit={prepare}>
      <p className="muted">Pilih gudang dan barang yang benar-benar datang. Simpan untuk langsung menambah stok tersedia.</p>
      <WarehousePicker label="Gudang tujuan" load={referenceLocations} value={warehouse} name={row => (row.name ?? row.code) + ' · ' + row.code} onChange={setWarehouse} eligible={row => row.state === 'ACTIVE' && row.issueEligible} />
      <WarehousePicker optional label="Pemasok" load={referenceSuppliers} value={supplier} name={row => row.name + ' · ' + row.code} onChange={setSupplier} eligible={row => row.state === 'ACTIVE'} />
      <TextField label="Referensi surat jalan" maxLength={500} value={reference} onChange={(_, data) => setReference(data.value)} />
      {rows.map((row, index) => <ReceiptLineEditor reference key={row.key} row={row} number={index + 1} costVisible={costVisible}
        onChange={patch => setRows(current => current.map(item => item.key === row.key ? { ...item, ...patch } : item))}
        onRemove={rows.length > 1 ? () => setRows(current => current.filter(item => item.key !== row.key)) : undefined} />)}
      <Button type="button" disabled={rows.length >= 100} onClick={() => setRows(current => [...current, emptyReceiptRow()])}>Tambah baris barang</Button>
      <TextareaField label="Catatan penerimaan" maxLength={1000} value={notes} onChange={(_, data) => setNotes(data.value)} />
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  </ResourceForm>
}
