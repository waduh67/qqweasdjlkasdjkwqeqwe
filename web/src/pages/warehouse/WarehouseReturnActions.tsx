import { useState, type FormEvent } from 'react'
import { Checkbox } from '@fluentui/react-components'
import type { WarehouseLocation, WarehouseSupplier } from '@/api/warehouse/models'
import { dispatchRepair, inspectReturn, receiveRepair, type RepairReceipt, type ReturnDetails, type ReturnInspection, type WarehouseReturn } from '@/api/warehouse/returns'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, SelectField, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { buildRepairDispatch, buildRepairReceipt, buildReturnInspection, needsPostRepairInspection } from './returnDraft'
import { locationLabel, receiptLocations, receiptSuppliers } from './receiptChoices'
import { returnItemLabel } from './returnPresentation'

export type ReturnAction = 'inspect' | 'repair-dispatch' | 'repair-receive'
const labels = { inspect: 'Inspeksi retur', 'repair-dispatch': 'Kirim ke servis', 'repair-receive': 'Terima dari servis' }
export function WarehouseReturnActions({ details, action, onDone, onClose }: { details: ReturnDetails; action: ReturnAction; onDone: () => void; onClose: () => void }) {
  const { returnCase: view, references: refs } = details
  const [destination, setDestination] = useState<WarehouseLocation | null>(null), [vendor, setVendor] = useState<WarehouseSupplier | null>(null)
  const [measured, setMeasured] = useState(''), [condition, setCondition] = useState<ReturnInspection['condition']>('QUARANTINE')
  const [observed, setObserved] = useState(''), [reset, setReset] = useState(false), [resetProof, setResetProof] = useState('')
  const [result, setResult] = useState<RepairReceipt['result']>('UNREPAIRED'), [vendorReference, setVendorReference] = useState(''), [evidence, setEvidence] = useState('')
  const [error, setError] = useState<string | null>(null), [operation, setOperation] = useState<WarehouseCommand<WarehouseReturn> | null>(null)
  const isSerial = refs.item.tracking === 'SERIAL', release = action === 'inspect' && condition === 'SERVICEABLE' && view.legalOwner === 'ISP'
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      if (action === 'inspect') setOperation(inspectReturn(view.id, buildReturnInspection(details, destination, measured, condition, evidence, observed, reset, resetProof)))
      else if (action === 'repair-dispatch') setOperation(dispatchRepair(view.id, buildRepairDispatch(details, vendor, destination, observed, vendorReference, evidence)))
      else setOperation(receiveRepair(view.id, buildRepairReceipt(details, destination, observed, result, vendorReference, evidence)))
      setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa rincian retur.') }
  }
  return <><form className="card stack" aria-label={labels[action]} onSubmit={prepare}>
    <h2>{labels[action]}</h2><p>{refs.code} · Revisi {view.revision} · {returnItemLabel(refs.item)}</p>
    <p>Jumlah retur: <WarehouseQuantity value={view.quantityBase} unit={view.baseUnit} /></p>
    {view.legalOwner === 'CUSTOMER' && <p role="status">Tetap milik pelanggan. Kondisi layak pakai dan hasil servis tidak mengubahnya menjadi stok tersedia ISP.</p>}
    {action === 'inspect' ? <>
      {needsPostRepairInspection(details) && <p role="status">Perangkat baru kembali dari servis. Lakukan inspeksi dan reset ulang; pemeriksaan sebelumnya belum memenuhi penerimaan ini.</p>}
      <WarehouseQuantityField label="Hasil ukur fisik" value={measured} unit={view.baseUnit} onChange={setMeasured} />
      <p className="muted">Ukur seluruh potongan atau hitung unit yang sama. Selisih perlu dicatat lewat penanganan tersendiri.</p>
      <SelectField label="Kondisi hasil inspeksi" value={condition} onChange={(_, data) => { setCondition(data.value as ReturnInspection['condition']); setDestination(null); setReset(false); setResetProof('') }}>
        <option value="QUARANTINE">Tetap karantina</option><option value="DAMAGED">Rusak</option><option value="SERVICEABLE">Layak pakai</option></SelectField>
    </> : action === 'repair-dispatch' ? <><p>Serahkan perangkat yang sama kepada penyedia servis. Pengiriman belum berarti barang kembali atau tersedia.</p>
      <WarehousePicker label="Penyedia servis" load={receiptSuppliers} value={vendor} name={row => `${row.name} · ${row.code}`} onChange={setVendor} /></>
      : <><p>Terima serial yang sama ke karantina. Perangkat pengganti dengan serial berbeda harus memakai penerimaan pengganti terpisah.</p>
        <SelectField label="Hasil servis" value={result} onChange={(_, data) => setResult(data.value as RepairReceipt['result'])}><option value="UNREPAIRED">Belum diperbaiki</option><option value="REPAIRED">Diperbaiki oleh penyedia</option></SelectField></>}
    <WarehousePicker label={action === 'repair-dispatch' ? 'Lokasi penguasaan servis' : release ? 'Rak barang layak pakai' : 'Karantina tujuan'} load={receiptLocations}
      value={destination} name={locationLabel} onChange={setDestination} eligible={row => action === 'repair-dispatch' ? row.kind === 'TRANSIT' && !row.issueEligible : release ? row.kind === 'BIN' && row.issueEligible : row.kind === 'QUARANTINE' && !row.issueEligible} />
    {isSerial && <TextField label="Serial fisik yang dipindai" value={observed} required maxLength={128} onChange={(_, data) => setObserved(data.value)}
      hint="Pindai atau ketik serial perangkat yang sama. Enter hanya menyelesaikan pemindaian."
      onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); event.stopPropagation() } }} />}
    {isSerial && action === 'inspect' && condition === 'SERVICEABLE' && <><Checkbox label="Reset perangkat dan hapus konfigurasi lama sudah dilakukan" checked={reset} onChange={(_, data) => setReset(data.checked === true)} />
      <TextField label="Referensi bukti reset" required maxLength={500} value={resetProof} onChange={(_, data) => setResetProof(data.value)} /></>}
    {action !== 'inspect' && <TextField label="Referensi servis penyedia" required maxLength={500} value={vendorReference} onChange={(_, data) => setVendorReference(data.value)} />}
    <TextField label="Referensi bukti tindakan retur" required maxLength={500} value={evidence} onChange={(_, data) => setEvidence(data.value)} />
    {error && <p role="alert" className="error">{error}</p>}
    <div className="row wrap"><Button type="button" onClick={onClose}>Batal</Button><Button type="submit" variant="primary">Tinjau tindakan retur</Button></div>
  </form>
    {operation && <WarehouseCommandDialog title={`Konfirmasi ${labels[action].toLowerCase()}`} confirmLabel="Catat tindakan retur" command={operation} onDone={onDone} onReload={onDone} onClose={() => setOperation(null)}
      summary={<><p>{refs.code} · Revisi {view.revision} · {returnItemLabel(refs.item)}</p><p><WarehouseQuantity value={view.quantityBase} unit={view.baseUnit} /> → {destination && locationLabel(destination)}</p>
        {action === 'inspect' ? <p>Hasil ukur: {measured} {view.baseUnit === 'MM' ? 'm' : 'unit'} · {condition === 'SERVICEABLE' ? 'Layak pakai' : condition === 'DAMAGED' ? 'Rusak' : 'Karantina'}. {reset && `Reset dikonfirmasi: ${resetProof}.`}</p>
          : <p>{action === 'repair-dispatch' ? vendor?.name : refs.vendor?.name} · {vendorReference} {action === 'repair-receive' && `· ${result === 'REPAIRED' ? 'Diperbaiki' : 'Belum diperbaiki'}`}</p>}
        {isSerial && <p>Serial: {observed}</p>}<p>Bukti: {evidence}</p>
        <p>{release ? 'Jumlah yang diperiksa masuk rak tersedia milik ISP.' : action === 'repair-dispatch' ? 'Barang berada dalam penguasaan servis dan belum tersedia.' : 'Barang tetap di karantina. Kepemilikan asal dipertahankan.'}</p></>} />}
  </>
}
