import { useCallback, useRef, useState, type FormEvent } from 'react'
import { authorizeMaterialHandover, getMaterialHandoverSources, getMaterialHandoverTargets, type MaterialHandoverSource, type MaterialHandoverTarget } from '@/api/warehouse/materialHandover'
import { getMaterialFieldContext, type MaterialFieldContext } from '@/api/warehouse/materialExecution'
import { quantityFromInput } from '@/api/warehouse/quantity'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useAuth } from '@/auth/useAuth'
import { Button, TextareaField, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { useFieldConnection } from '@/hooks/useFieldConnection'

export function WorkOrderMaterialHandover({ context, onDone, onClose }: { context: MaterialFieldContext; onDone: () => void; onClose: () => void }) {
  const [source, setSource] = useState<MaterialHandoverSource | null>(null), [target, setTarget] = useState<MaterialHandoverTarget | null>(null)
  const [quantity, setQuantity] = useState(''), [reason, setReason] = useState(''), [reference, setReference] = useState(''), [error, setError] = useState<string | null>(null)
  const [review, setReview] = useState<WarehouseCommand<unknown> | null>(null), [busy, setBusy] = useState(false), active = useRef(false)
  const online = useFieldConnection(), { user, readOnly } = useAuth()
  const sources = useCallback((_: string, page: number) => getMaterialHandoverSources(context.workOrderId, page), [context.workOrderId])
  const targets = useCallback((_: string, page: number) => getMaterialHandoverTargets(context.workOrderId, page), [context.workOrderId])
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (active.current || !online || readOnly) return
    try {
      if (!source || !target || !user || new Set([source.sender.id, target.receiver.id, user.id]).size !== 3) throw new Error('Pilih pemegang dan penerima berbeda. Dispatcher harus independen dari keduanya.')
      if (!reason.trim() || reason.trim().length > 1000 || !reference.trim() || reference.trim().length > 500) throw new Error('Isi alasan dan referensi bukti persetujuan.')
      const quantityBase = quantityFromInput(quantity, source.source.baseUnit)
      if (BigInt(quantityBase) > BigInt(source.source.quantityBase)) throw new Error('Jumlah melampaui barang yang masih di tangan pengirim.')
      active.current = true; setBusy(true); setError(null)
      const current = await getMaterialFieldContext(context.workOrderId)
      if (current.workOrderRevision !== context.workOrderRevision) throw new Error('Revisi WO berubah. Muat ulang sebelum mengatur serah-terima.')
      setReview(authorizeMaterialHandover(context.workOrderId, { workOrderRevision: context.workOrderRevision, receiptId: source.source.receiptId,
        issueLineId: source.source.issueLineId, stockIdentityId: source.id, quantityBase, baseUnit: source.source.baseUnit, targetLocationId: target.id,
        usageId: source.source.sourceUsageId ?? undefined, reason: reason.trim(), evidenceReference: reference.trim(), expectedSenderId: source.sender.id, expectedReceiverId: target.receiver.id }))
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa persetujuan serah-terima.') }
    finally { active.current = false; setBusy(false) }
  }
  return <><form className="card stack" aria-label="Persetujuan serah-terima teknisi" onSubmit={event => void submit(event)}>
    <h3>Atur serah-terima antarteknisi</h3><p>Persetujuan ini menentukan sumber, jumlah, dan penerima. Pengirim tetap harus menyerahkan barang melalui Material Saya, lalu penerima mengakuinya.</p>
    <WarehousePicker label="Barang dan pemegang saat ini" searchable={false} load={sources} value={source} onChange={value => { setSource(value); setQuantity(''); setTarget(null) }} name={row => `${row.source.sku.name} · ${row.source.serial ?? row.source.lotCode ?? row.source.issueCode} · ${row.sender.name}`} disabled={!online || readOnly || busy || !!review} />
    {source && <><p>Di tangan {source.sender.name}: <WarehouseQuantity value={source.source.quantityBase} unit={source.source.baseUnit} /></p><WarehouseQuantityField label="Jumlah diserahterimakan" value={quantity} unit={source.source.baseUnit} onChange={setQuantity} disabled={busy || !!review} /></>}
    <WarehousePicker label="Teknisi dan lokasi penerima" searchable={false} load={targets} value={target} onChange={setTarget} eligible={row => row.receiver.id !== source?.sender.id} name={row => `${row.receiver.name} · ${row.code} · ${row.name}`} disabled={!online || readOnly || busy || !!review} />
    <TextareaField label="Alasan serah-terima" required maxLength={1000} value={reason} disabled={busy || !!review} onChange={(_, data) => setReason(data.value)} />
    <TextField label="Bukti persetujuan serah-terima" required maxLength={500} value={reference} disabled={busy || !!review} onChange={(_, data) => setReference(data.value)} />
    {error && <p role="alert">{error}</p>}<div className="row wrap"><Button disabled={busy || !!review} onClick={onClose}>Batal</Button><Button disabled={!online || busy || !!review} onClick={onDone}>Muat ulang material</Button><Button type="submit" disabled={!online || readOnly || busy || !!review}>Tinjau persetujuan</Button></div>
  </form>{review && <WarehouseCommandDialog title="Konfirmasi persetujuan serah-terima" command={review} disabled={!online || readOnly} confirmLabel="Setujui serah-terima" onDone={onDone} onReload={onDone} onClose={() => setReview(null)} summary={<>
    <p>{source?.source.sku.name} · {source?.source.serial ?? source?.source.lotCode} · {quantity} {source?.source.baseUnit === 'MM' ? 'm' : 'unit'}</p>
    <p>{source?.sender.name} → {target?.receiver.name} di {target?.name}. WO revisi {context.workOrderRevision}.</p><p>{reason} · Bukti: {reference}</p><p>Stok belum berpindah setelah persetujuan ini.</p>
  </>} />}</>
}
