import { useId as useResourceFormId } from 'react'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { useState, type FormEvent } from 'react'
import { acknowledgeMaterialResidual, type MyMaterialResidual } from '@/api/warehouse/myMaterials'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseFacts } from '@/components/organisms/warehouse/WarehouseFacts'

export function MyMaterialResidualAcknowledgement({ row, enabled, onDone }: { row: MyMaterialResidual; enabled: boolean; onDone: () => void }) {
  const [open, setOpen] = useState(false)
  return <>{!open && <Button disabled={!enabled} onClick={() => setOpen(true)}>{row.purpose === 'RETURN' ? 'Akui penerimaan sisa' : 'Terima serah-terima'}</Button>}
    {open && <MaterialResidualAcknowledgementForm row={row} enabled={enabled} onClose={() => setOpen(false)} onReload={() => { setOpen(false); onDone() }} onDone={() => { setOpen(false); onDone() }} />}</>
}

export function MaterialResidualAcknowledgementForm({ row, enabled, onDone, onClose, onReload }: { row: MyMaterialResidual; enabled: boolean; onDone: () => void; onClose: () => void; onReload: () => void }) {
  const resourceFormId = useResourceFormId()
  const [reference, setReference] = useState(''), [review, setReview] = useState<WarehouseCommand<unknown> | null>(null)
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!enabled || !reference.trim() || reference.trim().length > 500 || review) return
    setReview(acknowledgeMaterialResidual(row.workOrderId, { documentId: row.id, expectedRevision: row.revision, evidenceReference: reference.trim() }))
  }
  const facts = [{ label: 'Dokumen', value: row.code }, { label: 'Barang', value: row.sku.name },
    { label: 'Serial / lot', value: row.serial ?? row.lotCode }, { label: 'Jumlah', value: <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> },
    { label: 'Pengirim', value: row.sender?.name }, { label: 'Tujuan', value: row.location.name }]
  return <ResourceForm title="Terima material" onClose={onClose} onBack={() => setReview(null)} review={review && <WarehouseCommandDialog embedded title="Konfirmasi penerimaan material" command={review} confirmLabel="Terima material" disabled={!enabled} onDone={onDone} onReload={onReload} onClose={() => setReview(null)} summary={<>
    <WarehouseFacts items={[...facts, { label: 'Bukti penerimaan', value: reference }]} />
    {row.purpose === 'RETURN' && <p>Material diterima di karantina. Lanjutkan pencatatan retur untuk pemeriksaan.</p>}
  </>} />} footer={<><Button onClick={onClose}>Batal</Button><Button variant="primary" form={resourceFormId} type="submit" disabled={!enabled || !!review}>Tinjau penerimaan</Button></>}>
    <form id={resourceFormId} className="warehouse-record" aria-label="Penerimaan material" onSubmit={submit}>
      <WarehouseFacts items={facts} />
      <TextField label="Bukti penerimaan" hint="Nomor berita acara atau bukti serah terima." required value={reference} maxLength={500} disabled={!!review} onChange={(_, data) => setReference(data.value)} />
    </form>
  </ResourceForm>
}
