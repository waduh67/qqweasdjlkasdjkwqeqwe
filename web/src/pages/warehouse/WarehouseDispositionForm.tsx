import { useState, type FormEvent } from 'react'
import { createCompensation, createDisposition, type Compensation, type Disposition } from '@/api/warehouse/dispositions'
import type { WarehouseLocation } from '@/api/warehouse/models'
import type { ReturnDetails } from '@/api/warehouse/returns'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, SelectField, TextareaField, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { locationLabel, receiptLocations } from './receiptChoices'
import { returnItemLabel } from './returnPresentation'

export function WarehouseDispositionForm({ details, original, onClose, onDone, onReload }: { details: ReturnDetails; original?: Disposition; onClose: () => void; onDone: () => void; onReload: () => void }) {
  const { returnCase: returned } = details
  const [action, setAction] = useState<'LOSS' | 'SCRAP'>('LOSS'), [target, setTarget] = useState<WarehouseLocation | null>(null)
  const [reason, setReason] = useState(''), [reference, setReference] = useState(''), [error, setError] = useState('')
  const [operation, setOperation] = useState<WarehouseCommand<Disposition | Compensation> | null>(null)
  const title = original ? 'Koreksi disposisi ke karantina' : 'Disposisi retur'
  const targetKind = original ? 'QUARANTINE' : action === 'LOSS' ? 'LOST' : 'DISPOSED'
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      if (!target || target.state !== 'ACTIVE' || target.issueEligible || target.kind !== targetKind) throw new Error('Pilih lokasi tujuan yang sesuai penanganan.')
      if (!reason.trim() || reason.trim().length > 1000 || !reference.trim() || reference.trim().length > 500) throw new Error('Isi alasan dan referensi bukti pemeriksaan.')
      if (returned.legalOwner !== 'ISP' || details.references.rmaHandoverId) throw new Error('Barang ini belum memenuhi syarat disposisi ISP.')
      const common = { destinationLocationId: target.id, reason: reason.trim(), evidenceReference: reference.trim() }
      if (original) {
        if (original.sourceDocumentId !== returned.id || original.stockIdentityId !== returned.stockIdentityId || original.state !== 'POSTED' || !['LOST', 'SCRAP'].includes(returned.state)) throw new Error('Muat ulang disposisi dan retur sebelum koreksi.')
        setOperation(createCompensation(original.id, { ...common, expectedRevision: original.revision, expectedReturnRevision: returned.revision }))
      } else {
        if (returned.state !== 'RECEIVED_IN_INSPECTION' || (action === 'SCRAP' && returned.condition !== 'DAMAGED')) throw new Error('Scrap hanya dapat diajukan untuk barang rusak yang masih dalam pemeriksaan.')
        setOperation(createDisposition({ ...common, sourceDocumentId: returned.id, expectedRevision: returned.revision, stockIdentityId: returned.stockIdentityId,
          quantityBase: returned.quantityBase, baseUnit: returned.baseUnit, action }))
      }
      setError('')
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa permintaan disposisi.') }
  }
  return <><form className="stack" aria-label={title} onSubmit={prepare}><h3>{title}</h3>
    <p>{details.references.code} · Retur revisi {returned.revision} · {returnItemLabel(details.references.item)}</p>
    <p>Seluruh barang pada retur: <WarehouseQuantity value={returned.quantityBase} unit={returned.baseUnit} /></p>
    {original ? <p>Disposisi sumber {original.code} · Revisi {original.revision}. Koreksi membuat catatan baru yang merujuk pembukuan lama.</p>
      : <SelectField label="Jenis disposisi" value={action} onChange={(_, data) => { setAction(data.value as 'LOSS' | 'SCRAP'); setTarget(null) }}><option value="LOSS">Kehilangan</option><option value="SCRAP" disabled={returned.condition !== 'DAMAGED'}>Scrap barang rusak</option></SelectField>}
    <WarehousePicker label="Lokasi tujuan disposisi" load={receiptLocations} value={target} name={locationLabel} eligible={row => !row.issueEligible && row.kind === targetKind} onChange={setTarget} />
    <TextareaField label="Alasan disposisi / koreksi" required maxLength={1000} value={reason} onChange={(_, data) => setReason(data.value)} />
    <TextField label="Referensi bukti disposisi" required maxLength={500} value={reference} onChange={(_, data) => setReference(data.value)} />
    <p>Permintaan ini memerlukan persetujuan independen. {original ? 'Setelah disetujui, barang kembali ke karantina untuk pemeriksaan; stok tersedia belum bertambah.' : 'Barang tetap pada lokasi sekarang sampai keputusan disetujui dan dibukukan.'}</p>
    {error && <p role="alert" className="error">{error}</p>}
    <div className="row wrap"><Button type="button" onClick={onClose}>Batal disposisi</Button><Button type="submit" variant="primary">Tinjau permintaan disposisi</Button></div>
  </form>{operation && <WarehouseCommandDialog title="Konfirmasi permintaan disposisi" confirmLabel="Simpan permintaan disposisi" command={operation} onDone={onDone} onReload={onReload} onClose={() => setOperation(null)}
    summary={<><p>{details.references.code} · Retur revisi {returned.revision}</p><p>{original ? `Koreksi ${original.code} revisi ${original.revision}` : action === 'LOSS' ? 'Kehilangan' : 'Scrap barang rusak'}</p>
      <p>{returnItemLabel(details.references.item)} · <WarehouseQuantity value={returned.quantityBase} unit={returned.baseUnit} /> → {target && locationLabel(target)}</p><p>{reason}</p><p>Bukti: {reference}</p>
      <p>Dokumen diajukan untuk persetujuan independen. Menyimpan permintaan belum membukukan perpindahan barang.</p></>} />}</>
}
