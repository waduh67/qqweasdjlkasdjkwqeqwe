import { useState, type FormEvent } from 'react'
import { acknowledgeMaterialResidual, type MyMaterialResidual } from '@/api/warehouse/myMaterials'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'

export function MyMaterialResidualAcknowledgement({ row, enabled, onDone }: { row: MyMaterialResidual; enabled: boolean; onDone: () => void }) {
  const [open, setOpen] = useState(false), [reference, setReference] = useState(''), [review, setReview] = useState<WarehouseCommand<unknown> | null>(null)
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!enabled || !reference.trim() || reference.trim().length > 500 || review) return
    setReview(acknowledgeMaterialResidual(row.workOrderId, { documentId: row.id, expectedRevision: row.revision, evidenceReference: reference.trim() }))
  }
  if (!open) return <Button disabled={!enabled} onClick={() => setOpen(true)}>{row.purpose === 'RETURN' ? 'Akui penerimaan sisa' : 'Terima serah-terima'}</Button>
  return <><form className="stack" aria-label="Bukti penerimaan serah-terima" onSubmit={submit}>
    <TextField label="Bukti penerimaan serah-terima" required value={reference} maxLength={500} disabled={!!review} onChange={(_, data) => setReference(data.value)} />
    <div className="row wrap"><Button disabled={!!review} onClick={() => setOpen(false)}>Batal penerimaan</Button><Button type="submit" disabled={!enabled || !!review}>Tinjau serah-terima</Button></div>
  </form>{review && <WarehouseCommandDialog title="Konfirmasi penerimaan serah-terima" command={review} confirmLabel="Akui penerimaan" disabled={!enabled} onDone={onDone} onReload={onDone} onClose={() => setReview(null)} summary={<>
    <p>{row.sku.name} · {row.serial ?? row.lotCode} · <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /></p>
    <p>Dari {row.sender?.name ?? 'pengirim'} ke {row.location.name}. Dokumen revisi {row.revision}.</p>
    {row.purpose === 'RETURN' && <p>Barang masuk karantina dan masih perlu dibuatkan penerimaan retur serta diperiksa.</p>}<p>Bukti: {reference}</p>
  </>} />}</>
}
