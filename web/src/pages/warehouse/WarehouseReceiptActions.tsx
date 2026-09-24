import { useCallback, useId, useState, type FormEvent } from 'react'
import { Checkbox } from '@fluentui/react-components'
import { inspectReceipt, listReceiptEvidence, putawayReceipt, type ReceiptEvidence, type ReceiptLine, type WarehouseReceipt } from '@/api/warehouse/receipts'
import type { WarehouseLocation } from '@/api/warehouse/models'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { formatBaseQuantity, quantityFromInput } from '@/api/warehouse/quantity'
import { Button, SelectField, TextareaField } from '@/components/atoms'
import { Modal } from '@/components/molecules/Modal'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { evidenceLabel } from './receiptFiles'
import { receiptCandidates } from './receiptActions'
import { locationLabel, receiptLocations } from './receiptChoices'

type Props = { receipt: WarehouseReceipt; onClose: () => void; onChanged: () => void }
export function WarehouseReceiptActions({ receipt, mode, onClose, onChanged }: Props & { mode: 'inspect' | 'putaway' }) {
  const formId = useId()
  const lines = receipt.lines.map(line => ({ line, pieces: receiptCandidates(receipt, line, mode) })).filter(row => row.pieces.length > 0)
  const [values, setValues] = useState(() => Object.fromEntries(lines.map(({ line, pieces }) => [line.id, { piece: pieces[0].stockIdentityId, selected: false, accepted: '0', rejected: '0', quantity: formatBaseQuantity(pieces[0].quantityBase, line.baseUnit) }])))
  const [proof, setProof] = useState<ReceiptEvidence | null>(null)
  const [reason, setReason] = useState('')
  const [rejection, setRejection] = useState<'QUARANTINE' | 'SUPPLIER_RETURN'>('QUARANTINE')
  const [destination, setDestination] = useState<WarehouseLocation | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [operation, setOperation] = useState<WarehouseCommand<WarehouseReceipt> | null>(null)
  const [summary, setSummary] = useState<{ name: string; line: ReceiptLine; accepted: string; rejected: string }[]>([])
  const title = mode === 'inspect' ? 'Periksa penerimaan' : 'Tempatkan ke bin'
  function patch(id: string, value: Partial<typeof values[string]>) { setValues(current => ({ ...current, [id]: { ...current[id], ...value } })) }
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      if (mode === 'inspect' && (!proof?.matchesCurrentIntake || !reason.trim())) throw new Error('Pilih bukti yang sesuai dan isi alasan pemeriksaan.')
      if (mode === 'putaway' && !destination) throw new Error('Pilih bin tujuan yang memenuhi syarat.')
      const selected = lines.flatMap(({ line, pieces }) => {
        const value = values[line.id], piece = pieces.find(p => p.stockIdentityId === value.piece)
        if (!piece) throw new Error('Posisi barang berubah. Muat ulang dokumen.')
        if (mode === 'putaway' && !value.selected) return []
        const accepted = quantityFromInput(mode === 'inspect' ? value.accepted : value.quantity, line.baseUnit, mode === 'inspect')
        const rejected = mode === 'inspect' ? quantityFromInput(value.rejected, line.baseUnit, true) : '0'
        if (BigInt(accepted) + BigInt(rejected) === 0n) return []
        if (BigInt(accepted) + BigInt(rejected) > BigInt(piece.quantityBase)) throw new Error(`Jumlah ${line.skuName}${line.serial ? ` ${line.serial}` : ''} melebihi sisa bagian.`)
        return [{ line, piece, accepted, rejected }]
      })
      if (!selected.length) throw new Error('Pilih dan isi jumlah barang yang akan diproses.')
      setSummary(selected.map(row => ({ ...row, name: [row.line.skuName, row.line.serial ?? row.line.lotCode].filter(Boolean).join(' · ') })))
      setOperation(mode === 'inspect' ? inspectReceipt(receipt.id, receipt.revision, selected.map(({ line, piece, accepted, rejected }) => ({ lineId: line.id, stockIdentityId: piece.stockIdentityId, baseUnit: line.baseUnit,
        acceptedBase: accepted, rejectedBase: rejected, evidenceId: proof!.id, reason: reason.trim(), rejectedDisposition: rejection })))
        : putawayReceipt(receipt.id, receipt.revision, destination!.id, selected.map(({ line, piece, accepted }) => ({ lineId: line.id, stockIdentityId: piece.stockIdentityId, baseUnit: line.baseUnit, quantityBase: accepted }))))
      setError(null)
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa jumlah barang.') }
  }
  return <>
    <Modal title={title} wide onClose={onClose} footer={<><Button onClick={onClose}>Batal</Button><Button variant="primary" type="submit" form={formId}>Tinjau {mode === 'inspect' ? 'pemeriksaan' : 'penempatan'}</Button></>}>
      <form id={formId} className="stack" onSubmit={prepare}>
        <p>{receipt.externalReference} · Revisi {receipt.revision}</p>
        {mode === 'inspect' ? <><EvidenceChoice id={receipt.id} value={proof} onChange={setProof} />
          <TextareaField label="Alasan / hasil pemeriksaan" required maxLength={1000} value={reason} onChange={(_, data) => setReason(data.value)} />
          <SelectField label="Penanganan bagian ditolak" value={rejection} onChange={(_, data) => setRejection(data.value as typeof rejection)}><option value="QUARANTINE">Tetap karantina</option><option value="SUPPLIER_RETURN">Untuk retur pemasok</option></SelectField>
          <p className="muted">Bagian diterima tetap karantina sampai ditempatkan ke bin. Bagian ditolak tidak menjadi stok tersedia.</p>
        </> : <WarehousePicker label="Bin tujuan" load={receiptLocations} value={destination} onChange={setDestination} name={locationLabel} eligible={row => row.kind === 'BIN' && row.issueEligible} />}
        <Button type="button" disabled={!lines.length} onClick={() => setValues(current => Object.fromEntries(lines.map(({ line, pieces }) => {
          const old = current[line.id], piece = pieces.find(p => p.stockIdentityId === old.piece)!
          return [line.id, { ...old, selected: true, accepted: formatBaseQuantity(piece.quantityBase, line.baseUnit), rejected: '0' }]
        })))}>{mode === 'inspect' ? 'Terima semua sisa yang ditampilkan' : 'Pilih semua yang memenuhi syarat'}</Button>
        {lines.length === 0 && <p>Tidak ada bagian yang memenuhi syarat dalam dokumen ini.</p>}
        {lines.map(({ line, pieces }) => {
          const value = values[line.id], piece = pieces.find(p => p.stockIdentityId === value.piece)!
          return <fieldset key={line.id} className="card stack" style={{ minWidth: 0 }}><legend>{line.skuName} · {line.serial ?? line.lotCode ?? line.skuCode}</legend>
            <p>Sisa bagian: <WarehouseQuantity value={piece.quantityBase} unit={line.baseUnit} /></p>
            {pieces.length > 1 && <SelectField label="Bagian barang" value={value.piece} onChange={(_, data) => { const next = pieces.find(p => p.stockIdentityId === data.value)!; patch(line.id, { piece: next.stockIdentityId, selected: false, accepted: '0', rejected: '0', quantity: formatBaseQuantity(next.quantityBase, line.baseUnit) }) }}>
              {pieces.map(p => <option key={p.stockIdentityId} value={p.stockIdentityId}>{formatBaseQuantity(p.quantityBase, line.baseUnit)} {line.baseUnit === 'MM' ? 'm' : 'unit'} · {p.stockIdentityId.slice(0, 8)}</option>)}
            </SelectField>}
            {mode === 'inspect' ? line.tracking === 'SERIAL' ? <SelectField label={`Keputusan ${line.serial}`} value={value.accepted === '1' ? 'accept' : value.rejected === '1' ? 'reject' : 'pending'} onChange={(_, data) => patch(line.id, { accepted: data.value === 'accept' ? '1' : '0', rejected: data.value === 'reject' ? '1' : '0' })}>
              <option value="pending">Belum diperiksa</option><option value="accept">Diterima</option><option value="reject">Ditolak</option>
            </SelectField> : <><WarehouseQuantityField label="Diterima" unit={line.baseUnit} value={value.accepted} allowZero onChange={accepted => patch(line.id, { accepted })} />
              <WarehouseQuantityField label="Ditolak" unit={line.baseUnit} value={value.rejected} allowZero onChange={rejected => patch(line.id, { rejected })} /></>
              : <><Checkbox label={`Tempatkan ${line.serial ?? line.lotCode ?? line.skuName}`} checked={value.selected} onChange={(_, data) => patch(line.id, { selected: data.checked === true })} />
                <WarehouseQuantityField label="Jumlah penempatan" unit={line.baseUnit} value={value.quantity} disabled={!value.selected} onChange={quantity => patch(line.id, { quantity })} /></>}
          </fieldset>
        })}
        {error && <p className="error" role="alert">{error}</p>}
      </form>
    </Modal>
    {operation && <WarehouseCommandDialog title={title} confirmLabel={mode === 'inspect' ? 'Simpan pemeriksaan' : 'Tempatkan barang'} command={operation} onClose={() => setOperation(null)} onDone={onChanged} onReload={onChanged}
      summary={<><p>{receipt.externalReference} · Revisi {receipt.revision}</p><p>{mode === 'putaway' ? `Tujuan: ${destination?.name ?? destination?.code}` : proof && evidenceLabel(proof)}</p>
        <ul>{summary.map(row => <li key={row.line.id}>{row.name}: {mode === 'inspect' ? 'diterima ' : 'ditempatkan '}<WarehouseQuantity value={row.accepted} unit={row.line.baseUnit} />{mode === 'inspect' && <>, ditolak <WarehouseQuantity value={row.rejected} unit={row.line.baseUnit} /></>}</li>)}</ul>
        <p>{mode === 'inspect' ? 'Stok tersedia belum bertambah. Hasil pemeriksaan tercatat pada bagian yang dipilih.' : 'Jumlah ini berpindah dari karantina menjadi stok tersedia di bin tujuan.'}</p></>} />}
  </>
}

function EvidenceChoice({ id, value, onChange }: { id: string; value: ReceiptEvidence | null; onChange: (row: ReceiptEvidence | null) => void }) {
  const [page, setPage] = useState(0)
  const loader = useCallback(() => listReceiptEvidence(id, page), [id, page])
  const result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{data => {
    const choices = value && !data.items.some(row => row.id === value.id) ? [value, ...data.items] : data.items
    return <><SelectField label="Bukti pemeriksaan" value={value?.id ?? ''} required onChange={(_, option) => onChange(choices.find(row => row.id === option.value) ?? null)}>
      <option value="">Pilih bukti…</option>{choices.map(row => <option key={row.id} value={row.id} disabled={!row.matchesCurrentIntake}>{evidenceLabel(row)}{!row.matchesCurrentIntake && ' — draft lama'}</option>)}
    </SelectField><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
      {data.items.length === 0 && <p>Unggah bukti dari detail penerimaan sebelum mencatat inspeksi.</p>}</>
  }}</WarehouseState>
}
