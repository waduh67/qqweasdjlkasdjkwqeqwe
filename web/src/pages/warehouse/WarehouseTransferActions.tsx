import { useState, type FormEvent } from 'react'
import { Checkbox } from '@fluentui/react-components'
import type { WarehouseLocation } from '@/api/warehouse/models'
import { formatBaseQuantity } from '@/api/warehouse/quantity'
import { receiveTransfer, reportTransferDiscrepancy, type TransferDetails, type WarehouseTransfer } from '@/api/warehouse/transfers'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, SelectField, TextareaField, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { buildTransferReceipt, type TransferReceiptLine } from './transferDraft'
import { locationLabel, receiptLocations } from './receiptChoices'
import { transferLineLabel, transferLocationLabel, transferPersonLabel } from './transferPresentation'

export function WarehouseTransferActions({ details, action, onDone, onClose }: { details: TransferDetails; action: 'receive' | 'discrepancy'; onDone: () => void; onClose: () => void }) {
  const { transfer } = details
  const [rows, setRows] = useState<TransferReceiptLine[]>(() => transfer.lines.filter(line => BigInt(line.inTransitBase) > 0n).map(line => ({ lineId: line.id, selected: false, quantity: formatBaseQuantity(line.inTransitBase, line.baseUnit) })))
  const [evidence, setEvidence] = useState('')
  const [reason, setReason] = useState('')
  const [kind, setKind] = useState<'LOST' | 'REJECTED'>('LOST')
  const [destination, setDestination] = useState<WarehouseLocation | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [operation, setOperation] = useState<WarehouseCommand<WarehouseTransfer> | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      if (action === 'receive') setOperation(receiveTransfer(transfer.id, buildTransferReceipt(transfer, evidence, rows)))
      else {
        if (!destination || destination.state !== 'ACTIVE' || destination.issueEligible || destination.kind !== (kind === 'LOST' ? 'LOST' : 'QUARANTINE')) throw new Error('Pilih lokasi kehilangan atau karantina yang sesuai.')
        if (!evidence.trim() || evidence.trim().length > 500 || !reason.trim() || reason.trim().length > 1000) throw new Error('Isi alasan (maksimal 1000 karakter) dan referensi bukti (maksimal 500 karakter).')
        setOperation(reportTransferDiscrepancy(transfer.id, { expectedRevision: transfer.revision, action: kind, destinationLocationId: destination.id, reason: reason.trim(), evidenceReference: evidence.trim() }))
      }
      setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa rincian transaksi.') }
  }
  return <><form className="card stack" aria-label={action === 'receive' ? 'Penerimaan transfer' : 'Selisih transfer'} onSubmit={prepare}>
    <h2>{action === 'receive' ? 'Terima barang transfer' : 'Laporkan sisa hilang / ditolak'}</h2>
    <p>{transfer.code} · Revisi {transfer.revision} · Penerima: {transferPersonLabel(details, transfer.receiverId)}</p>
    {action === 'receive' ? <><p>Isi hanya jumlah fisik yang diterima. Sisanya tetap dalam perjalanan. Kondisi dan pemilik barang mengikuti pengiriman.</p>
      {rows.map(row => {
        const line = transfer.lines.find(line => line.id === row.lineId)!
        return <fieldset key={row.lineId} className="stack" style={{ minWidth: 0 }}><legend>{transferLineLabel(details, row.lineId)}</legend>
          <p>Dalam perjalanan: <WarehouseQuantity value={line.inTransitBase} unit={line.baseUnit} /></p>
          <Checkbox label={`Terima ${transferLineLabel(details, row.lineId)}`} checked={row.selected} onChange={(_, data) => setRows(current => current.map(item => item.lineId === row.lineId ? { ...item, selected: data.checked === true } : item))} />
          {row.selected && <WarehouseQuantityField label="Jumlah diterima" value={row.quantity} unit={line.baseUnit} onChange={quantity => setRows(current => current.map(item => item.lineId === row.lineId ? { ...item, quantity } : item))} />}
        </fieldset>
      })}</> : <><p>Seluruh sisa transfer diajukan untuk penanganan. Laporan ini belum mengurangi transit; perpindahan memerlukan persetujuan petugas independen.</p>
      <SelectField label="Jenis selisih" value={kind} onChange={(_, data) => { setKind(data.value as 'LOST' | 'REJECTED'); setDestination(null) }}><option value="LOST">Hilang</option><option value="REJECTED">Ditolak / karantina</option></SelectField>
      <WarehousePicker label="Tujuan penanganan selisih" load={receiptLocations} value={destination} name={locationLabel} eligible={row => !row.issueEligible && row.kind === (kind === 'LOST' ? 'LOST' : 'QUARANTINE')} onChange={setDestination} />
      <TextareaField label="Alasan selisih" value={reason} required maxLength={1000} onChange={(_, data) => setReason(data.value)} />
    </>}
    <TextField label="Referensi bukti transfer" required maxLength={500} value={evidence} onChange={(_, data) => setEvidence(data.value)} hint="Misalnya nomor berita acara atau referensi bukti penerimaan fisik." />
    {error && <p className="error" role="alert">{error}</p>}
    <div className="row wrap"><Button type="button" onClick={onClose}>Batal</Button><Button type="submit" variant="primary">{action === 'receive' ? 'Tinjau penerimaan' : 'Tinjau selisih'}</Button></div>
  </form>
    {operation && <WarehouseCommandDialog title={action === 'receive' ? 'Konfirmasi penerimaan transfer' : 'Konfirmasi selisih transfer'} confirmLabel={action === 'receive' ? 'Catat penerimaan' : 'Catat selisih'} command={operation} onDone={onDone} onReload={onDone} onClose={() => setOperation(null)}
      summary={<><p>{transfer.code} · Revisi {transfer.revision}</p>
        <p>{action === 'receive' ? `Diterima ${transferPersonLabel(details, transfer.receiverId)} di ${transferLocationLabel(details, transfer.destinationLocationId)}` : `${kind === 'LOST' ? 'Hilang' : 'Ditolak'} → ${destination && locationLabel(destination)}`}</p>
        <ul>{(action === 'receive' ? rows.filter(row => row.selected) : rows).map(row => { const line = transfer.lines.find(line => line.id === row.lineId)!; return <li key={row.lineId}>{transferLineLabel(details, row.lineId)}: {action === 'receive' ? `${row.quantity} ${line.baseUnit === 'MM' ? 'm' : 'unit'}` : <WarehouseQuantity value={line.inTransitBase} unit={line.baseUnit} />}</li> })}</ul>
        <p>Bukti: {evidence}</p><p>{action === 'receive' ? 'Jumlah yang belum diterima tetap dalam transit. Kepemilikan dan kondisi tidak berubah.' : `${reason}. Stok tetap di transit sampai keputusan independen dibukukan.`}</p></>} />}
  </>
}
