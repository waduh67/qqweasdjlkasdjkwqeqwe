import { Disclosure } from '@/components/molecules/Disclosure'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { useId, useState, type FormEvent } from 'react'
import { Checkbox } from '@fluentui/react-components'
import { WarehouseLocationEditor } from './WarehouseLocationEditor'
import { WarehouseSupplierEditor } from './WarehouseSupplierEditor'
import { WarehouseSkuEditor } from './WarehouseSkuEditor'
import type { WarehouseLocation } from '@/api/warehouse/models'
import { saveReceipt, type WarehouseReceipt } from '@/api/warehouse/receipts'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { formatBaseQuantity, displayUnit } from '@/api/warehouse/quantity'
import { useCan } from '@/auth/useCan'
import { Button, TextareaField, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseDenied } from '@/components/organisms/warehouse/WarehouseState'
import { buildReceiptLines, emptyReceiptRow, rowsFromReceipt, type ReceiptDraftRow, type ReceiptSkuChoice } from './receiptDraft'
import { locationLabel, receiptLocations, receiptSkus, receiptSuppliers } from './receiptChoices'

type LocationChoice = Pick<WarehouseLocation, 'id' | 'name' | 'code' | 'kind' | 'issueEligible'>
const grid = { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(16rem, 100%), 1fr))', gap: '1rem' }

export function WarehouseReceiptEditor({ receipt, onSaved, onClose, onReload }: { receipt?: WarehouseReceipt; onSaved: (row: WarehouseReceipt) => void; onClose: () => void; onReload: () => void }) {
  const { can } = useCan()
  const formId = useId()
  const [reference, setReference] = useState(receipt?.externalReference ?? '')
  const [supplier, setSupplier] = useState<{ id: string; name: string; code?: string } | null>(receipt ? { id: receipt.supplierId, name: receipt.supplierName } : null)
  const [source, setSource] = useState<LocationChoice | null>(receipt ? { id: receipt.sourceLocationId, name: receipt.sourceLocationName, code: 'RECEIPT_SOURCE', kind: 'TRANSIT', issueEligible: false } : null)
  const [inspection, setInspection] = useState<LocationChoice | null>(receipt ? { id: receipt.inspectionLocationId, name: receipt.inspectionLocationName, code: '', kind: 'QUARANTINE', issueEligible: false } : null)
  const [rows, setRows] = useState<ReceiptDraftRow[]>(() => receipt ? rowsFromReceipt(receipt) : [emptyReceiptRow()])
  const [error, setError] = useState<string | null>(null)
  const [operation, setOperation] = useState<WarehouseCommand<WarehouseReceipt> | null>(null)
  const [creatingMaster, setCreatingMaster] = useState<'supplier' | 'source' | 'inspection' | null>(null)
  const costVisible = can('inventory.cost.view')
  if (!can('inventory.receipt.manage')) return <WarehouseDenied />
  if (receipt && receipt.draftEditability !== 'EDITABLE') return <div className="card stack" role="alert"><p>Penerimaan ini tidak dapat diubah melalui editor draft. Muat ulang detail; usulan pengganti yang sudah tercatat memerlukan usulan baru dari kasus retur asal.</p><Button onClick={onClose}>Kembali</Button></div>
  if (!can('inventory.sku.view') || !can('inventory.location.view')) return <div className="card stack" role="alert"><p>Izin lihat barang dan lokasi diperlukan untuk menyusun penerimaan.</p><Button onClick={onClose}>Kembali</Button></div>
  if (receipt && (!costVisible || !receipt.costVisible)) return <div className="card stack" role="alert"><p>Pengubahan draft memerlukan akses rincian biaya agar biaya tersimpan tetap terjaga. Penerimaan dan pemeriksaan tetap dapat dikerjakan dari detail dokumen.</p><Button onClick={onClose}>Kembali</Button></div>
  function update(key: string, patch: Partial<ReceiptDraftRow>) { setRows(current => current.map(row => row.key === key ? { ...row, ...patch } : row)) }
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      if (!reference.trim() || !supplier || !source || !inspection) throw new Error('Lengkapi referensi, pemasok, batas penerimaan dan lokasi pemeriksaan.')
      const lines = buildReceiptLines(rows, costVisible)
      setOperation(saveReceipt({ supplierId: supplier.id, externalReference: reference.trim(), sourceLocationId: source.id, inspectionLocationId: inspection.id, lines, ...(receipt ? { expectedRevision: receipt.revision } : {}) }, receipt?.id))
      setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa rincian penerimaan.') }
  }
  return <>
    <ResourceForm editing={!!receipt} title={<>{receipt ? 'Ubah draft penerimaan' : 'Draft penerimaan baru'}</>} onClose={onClose} onBack={() => setOperation(null)} review={operation && <WarehouseCommandDialog embedded title="Simpan draft penerimaan" confirmLabel="Simpan draft" command={operation} onDone={onSaved} onClose={() => setOperation(null)} onReload={onReload}
      summary={<><p><strong>{reference}</strong> · {supplier?.name}{receipt && ` · Revisi ${receipt.revision}`}</p><p>{source?.name ?? source?.code} → {inspection?.name}</p>
        <ul>{rows.map(row => <li key={row.key}>{row.sku?.name}: {row.sku && `${formatBaseQuantity(buildReceiptLines([row], costVisible)[0].quantityBase, row.sku.baseUnit)} ${displayUnit(row.sku.baseUnit)}`}</li>)}</ul><p>Perubahan stok: belum ada. Lanjutkan Terima barang dari detail setelah draft tersimpan.</p></>} />} footer={<><Button type="button" onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau draft</Button></>}><form id={formId} className="stack" onSubmit={prepare}>

      <p className="muted">Menyimpan draft belum menambah stok. Lampiran draft lama perlu diunggah ulang setelah isi draft berubah.</p>
      <div style={grid}>
        <TextField label="Referensi surat jalan" required maxLength={500} value={reference} onChange={(_, data) => setReference(data.value)} />
        <WarehousePicker create={can('inventory.receipt.manage') ? { label: 'Tambah pemasok', onClick: () => setCreatingMaster('supplier') } : undefined} label="Pemasok" load={receiptSuppliers} value={supplier} onChange={setSupplier} name={row => row.code ? `${row.name} · ${row.code}` : row.name} />
        <WarehousePicker<LocationChoice> create={can('inventory.location.manage') ? { label: 'Tambah batas penerimaan', onClick: () => setCreatingMaster('source') } : undefined} label="Batas penerimaan" load={receiptLocations} value={source} onChange={setSource} name={locationLabel} eligible={row => row.kind === 'TRANSIT' && row.code === 'RECEIPT_SOURCE'} />
        <WarehousePicker<LocationChoice> create={can('inventory.location.manage') ? { label: 'Tambah lokasi pemeriksaan', onClick: () => setCreatingMaster('inspection') } : undefined} label="Lokasi pemeriksaan" load={receiptLocations} value={inspection} onChange={setInspection} name={locationLabel} eligible={row => row.kind === 'QUARANTINE' && !row.issueEligible} />
      </div>
      <p className="muted">Barang diterima di lokasi pemeriksaan sebelum ditempatkan ke gudang.</p>
      {rows.map((row, index) => <ReceiptLineEditor key={row.key} row={row} number={index + 1} costVisible={costVisible} onChange={patch => update(row.key, patch)} onRemove={rows.length > 1 ? () => setRows(current => current.filter(item => item.key !== row.key)) : undefined} />)}
      <div className="row wrap"><Button type="button" disabled={rows.length >= 100} onClick={() => setRows(current => [...current, emptyReceiptRow()])}>Tambah baris barang</Button>
        </div>
      {error && <p className="error" role="alert">{error}</p>}
    </form>
      {creatingMaster === 'supplier' && <WarehouseSupplierEditor row={null} readOnly={false} onClose={() => setCreatingMaster(null)} onReload={() => setCreatingMaster(null)} onSaved={saved => { setSupplier(saved); setCreatingMaster(null) }} />}
      {(creatingMaster === 'source' || creatingMaster === 'inspection') && <WarehouseLocationEditor row={null} readOnly={false} allowedKinds={creatingMaster === 'source' ? ['TRANSIT'] : ['QUARANTINE']} fixedCode={creatingMaster === 'source' ? 'RECEIPT_SOURCE' : undefined}
        preset={creatingMaster === 'source' ? { code: 'RECEIPT_SOURCE', name: 'Penerimaan pemasok', kind: 'TRANSIT', issueEligible: false } : { code: '', name: '', kind: 'QUARANTINE', issueEligible: false }}
        onClose={() => setCreatingMaster(null)} onReload={() => setCreatingMaster(null)} onSaved={saved => { if (creatingMaster === 'source' && saved.kind === 'TRANSIT' && saved.code === 'RECEIPT_SOURCE') setSource(saved); if (creatingMaster === 'inspection' && saved.kind === 'QUARANTINE' && !saved.issueEligible) setInspection(saved); setCreatingMaster(null) }} />}
    </ResourceForm>

  </>
}

function ReceiptLineEditor({ row, number, costVisible, onChange, onRemove }: { row: ReceiptDraftRow; number: number; costVisible: boolean; onChange: (patch: Partial<ReceiptDraftRow>) => void; onRemove?: () => void }) {
  const [scan, setScan] = useState(''), [creatingSku, setCreatingSku] = useState(false)
  const { can } = useCan()
  function addScan() { if (scan.trim()) { onChange({ serials: [row.serials.trim(), scan.trim()].filter(Boolean).join('\n') }); setScan('') } }
  return <><fieldset className="card stack receipt-line" style={{ minWidth: 0 }}><legend>Barang {number}</legend>
    <WarehousePicker<ReceiptSkuChoice> create={can('inventory.sku.manage') ? { label: 'Tambah barang baru', onClick: () => setCreatingSku(true) } : undefined} label={`Barang ${number}`} load={receiptSkus} value={row.sku} name={sku => `${sku.name} · ${sku.code}`} onChange={sku => onChange({ sku, quantity: '', serials: '', lotCode: '', useConversion: false, useCost: false, totalMinor: '' })} />
    {row.sku && <>
      <WarehouseQuantityField label={row.sku.baseUnit === 'MM' ? 'Panjang reel aktual' : 'Jumlah aktual'} unit={row.sku.baseUnit} value={row.quantity} onChange={quantity => onChange({ quantity })} />
      {row.sku.tracking === 'SERIAL' ? <>
        <TextareaField label="Serial dan MAC" required rows={5} maxLength={100000} value={row.serials} onChange={(_, data) => onChange({ serials: data.value })} hint="Satu serial per baris. MAC opsional setelah koma: ONU-001, AA:BB:CC:DD:EE:FF" />
        <TextField label="Pindai serial baru" value={scan} maxLength={128} onChange={(_, data) => setScan(data.value)} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); addScan() } }} hint="Enter hanya menambahkan serial ke daftar." />
        <Button type="button" onClick={addScan}>Tambahkan hasil pindai</Button>
      </> : <TextField label="Kode lot / reel" required maxLength={120} value={row.lotCode} onChange={(_, data) => onChange({ lotCode: data.value })} />}
      <Disclosure title={<>Konversi kemasan dan biaya</>}><div className="stack">
        <Checkbox label="Catat konversi kemasan" checked={row.useConversion} onChange={(_, data) => onChange({ useConversion: data.checked === true })} />
        {row.useConversion && <div style={grid}>
          <TextField label={`Isi kemasan dalam ${row.sku.baseUnit} (pembilang)`} required value={row.numerator} onChange={(_, data) => onChange({ numerator: data.value })} />
          <TextField label="Penyebut konversi" required value={row.denominator} onChange={(_, data) => onChange({ denominator: data.value })} />
          <TextField label="Jumlah kemasan" required value={row.packageQuantity} onChange={(_, data) => onChange({ packageQuantity: data.value })} />
          <p className="muted">Jumlah kemasan × pembilang ÷ penyebut harus tepat sama dengan jumlah aktual, tanpa pembulatan.</p>
        </div>}
        {costVisible ? <><Checkbox label="Catat biaya kelompok barang" checked={row.useCost} onChange={(_, data) => onChange({ useCost: data.checked === true })} />
          {row.useCost && <div style={grid}><TextField label="Total biaya (satuan terkecil)" required value={row.totalMinor} onChange={(_, data) => onChange({ totalMinor: data.value })} hint="Total biaya seluruh baris, dalam satuan terkecil mata uang." />
            <TextField label="Mata uang" required maxLength={3} value={row.currency} onChange={(_, data) => onChange({ currency: data.value.toUpperCase() })} /></div>}
        </> : <p className="muted">Rincian biaya memerlukan izin lihat biaya.</p>}
      </div></Disclosure>
    </>}
    {onRemove && <Button type="button" onClick={onRemove}>Hapus baris {number}</Button>}
  </fieldset>
    {creatingSku && <WarehouseSkuEditor row={null} readOnly={false} onClose={() => setCreatingSku(false)} onReload={() => setCreatingSku(false)} onSaved={sku => { onChange({ sku, quantity: '', serials: '', lotCode: '', useConversion: false, useCost: false, totalMinor: '' }); setCreatingSku(false) }} />}
  </>
}
