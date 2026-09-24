import { useCallback, useRef, useState, type FormEvent } from 'react'
import type { MaterialCustody } from '@/api/warehouse/materialExecution'
import { getMyMaterialContext, getMyMaterialSource, getMyReturnLocation, getMyReturnLocations, returnMyMaterial, type MyMaterialContext, type myMaterialLocation } from '@/api/warehouse/myMaterials'
import { warehouseError } from '@/api/warehouse/errors'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, TextareaField, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { myReturnInput } from './myMaterialDraft'

export function MyMaterialReturn({ context, source, online, onDone, onClose }: {
  context: MyMaterialContext; source: MaterialCustody; online: boolean; onDone: () => void; onClose: () => void
}) {
  const [quantity, setQuantity] = useState(''), [target, setTarget] = useState<ReturnType<typeof myMaterialLocation> | null>(null)
  const [reason, setReason] = useState(''), [reference, setReference] = useState(''), [error, setError] = useState<string | null>(null)
  const [review, setReview] = useState<WarehouseCommand<unknown> | null>(null), [busy, setBusy] = useState(false), active = useRef(false)
  const load = useCallback((_: string, page: number) => getMyReturnLocations(context.id, page), [context.id])
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!online || active.current) return
    try {
      myReturnInput(context, source, quantity, target?.id ?? null, reason, reference)
      active.current = true; setBusy(true); setError(null)
      const [current, fresh, location] = await Promise.all([getMyMaterialContext(context.id), getMyMaterialSource(context.id, source.id), getMyReturnLocation(context.id, target!.id)])
      if (fresh.stockRevision !== source.stockRevision || fresh.quantityBase !== source.quantityBase || fresh.receiptId !== source.receiptId || fresh.sourceUsageId !== source.sourceUsageId || current.workOrderRevision !== context.workOrderRevision)
        throw new Error('Sisa material atau WO berubah. Muat ulang sebelum mengembalikan.')
      setReview(returnMyMaterial(context.id, myReturnInput(current, fresh, quantity, location.id, reason, reference)))
    } catch (caught) { setError(caught instanceof Error ? caught.message : warehouseError(caught)) }
    finally { active.current = false; setBusy(false) }
  }
  return <><form className="card stack" aria-label="Pengembalian material saya" onSubmit={event => void submit(event)}>
    <h3>Kembalikan sisa {source.sku.name}</h3><p>{source.lotCode ?? source.issueCode} · <WarehouseQuantity value={source.quantityBase} unit={source.baseUnit} /> · {source.location.name ?? source.location.code}</p>
    <p>Draf di tab ini. Sisa tetap dapat dikembalikan setelah penugasan berubah. Barang yang dikirim belum menjadi stok gudang tersedia.</p>
    <WarehouseQuantityField label="Jumlah dikembalikan" value={quantity} unit={source.baseUnit} onChange={setQuantity} disabled={busy || !!review} />
    <WarehousePicker label="Karantina tujuan" searchable={false} load={load} value={target} onChange={setTarget} name={row => `${row.code} · ${row.name}`} disabled={busy || !!review || !online} />
    <TextField label="Referensi bukti pengembalian" required value={reference} maxLength={500} disabled={busy || !!review} onChange={(_, data) => setReference(data.value)} />
    <TextareaField label="Alasan pengembalian" required value={reason} maxLength={1000} disabled={busy || !!review} onChange={(_, data) => setReason(data.value)} />
    {error && <p role="alert">{error}</p>}<div className="row wrap"><Button disabled={busy || !!review} onClick={onClose}>Batal</Button><Button disabled={busy || !!review || !online} onClick={onDone}>Muat ulang material</Button><Button type="submit" variant="primary" disabled={busy || !!review || !online}>{busy ? 'Memeriksa sisa…' : 'Tinjau pengembalian'}</Button></div>
  </form>{review && <WarehouseCommandDialog title="Konfirmasi pengembalian material" command={review} confirmLabel="Kirim pengembalian" disabled={!online} onDone={onDone} onReload={onDone} onClose={() => setReview(null)} summary={<>
    <p>{context.code} · WO revisi {context.workOrderRevision} · {source.sku.name} · {source.lotCode}</p><p>{quantity} {source.baseUnit === 'MM' ? 'm' : 'unit'} ke {target?.code} · {target?.name}.</p>
    <p>Petugas gudang masih perlu mengakui penerimaan dan memeriksa barang. Bukti: {reference}</p>
  </>} />}</>
}
