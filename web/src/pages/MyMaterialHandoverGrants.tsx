import { useCallback, useRef, useState } from 'react'
import { dispatchMaterialHandover, getMaterialHandoverGrant, getMaterialHandoverGrants, type MaterialHandoverGrant } from '@/api/warehouse/materialHandover'
import { getMyMaterialContext, getMyMaterialSource, type MyMaterialContext } from '@/api/warehouse/myMaterials'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button } from '@/components/atoms'
import { MaterialScanner } from '@/components/organisms/warehouse/MaterialScanner'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

export function MyMaterialHandoverGrants({ context, actor, enabled, onDone }: { context: MyMaterialContext; actor: string; enabled: boolean; onDone: () => void }) {
  const [open, setOpen] = useState(false)
  return <section className="card stack"><h3>Persetujuan pemindahan antarteknisi</h3><p>Pengiriman membutuhkan persetujuan dispatcher atas barang yang masih di tangan Anda. Penerima baru mengakui barang setelah benar-benar menerimanya.</p>
    <Button onClick={() => setOpen(!open)}>{open ? 'Tutup persetujuan serah-terima' : 'Lihat persetujuan serah-terima'}</Button>{open && <GrantList context={context} actor={actor} enabled={enabled} onDone={onDone} />}</section>
}
function GrantList({ context, actor, enabled, onDone }: { context: MyMaterialContext; actor: string; enabled: boolean; onDone: () => void }) {
  const [page, setPage] = useState(0), load = useCallback(() => getMaterialHandoverGrants(context.id, page), [context.id, page]), result = useWarehouseQuery(load)
  return <WarehouseState {...result}>{data => <>{data.items.length === 0 && <p>Belum ada persetujuan pengiriman yang dapat digunakan dalam cakupan Anda.</p>}
    {data.items.map(grant => <Grant key={grant.id} grant={grant} context={context} actor={actor} enabled={enabled} onDone={onDone} />)}
    <WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState>
}
function Grant({ grant, context, actor, enabled, onDone }: { grant: MaterialHandoverGrant; context: MyMaterialContext; actor: string; enabled: boolean; onDone: () => void }) {
  const [serial, setSerial] = useState<string | null>(null), [review, setReview] = useState<WarehouseCommand<unknown> | null>(null)
  const [error, setError] = useState<string | null>(null), [busy, setBusy] = useState(false), active = useRef(false)
  const stale = grant.request.workOrderRevision !== context.workOrderRevision || BigInt(grant.request.quantityBase) > BigInt(grant.currentQuantityBase)
  async function prepare() {
    if (!enabled || active.current || review) return
    try {
      if (stale || grant.workOrderId !== context.id || grant.sender.id !== actor) throw new Error('Persetujuan ini tidak lagi cocok dengan sumber atau WO. Minta dispatcher memeriksanya.')
      if (grant.serial && serial !== grant.serial) throw new Error('Pindai atau ketik serial perangkat yang akan diserahkan.')
      active.current = true; setBusy(true); setError(null)
      const [fresh, current, source] = await Promise.all([getMaterialHandoverGrant(context.id, grant.id), getMyMaterialContext(context.id), getMyMaterialSource(context.id, grant.request.stockIdentityId)])
      if (fresh.sender.id !== actor || current.workOrderRevision !== fresh.request.workOrderRevision || JSON.stringify(fresh.request) !== JSON.stringify(grant.request) ||
        source.stockRevision !== fresh.stockRevision || source.quantityBase !== fresh.currentQuantityBase || source.receiptId !== fresh.request.receiptId ||
        source.issueLineId !== fresh.request.issueLineId || source.sourceUsageId !== (fresh.request.usageId ?? null) || source.serial !== fresh.serial ||
        BigInt(source.quantityBase) < BigInt(fresh.request.quantityBase)) throw new Error('Sumber, penugasan, atau persetujuan berubah. Muat ulang sebelum mengirim.')
      setReview(dispatchMaterialHandover(fresh))
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Serah-terima belum dapat diperiksa.') }
    finally { active.current = false; setBusy(false) }
  }
  return <article className="card stack"><h4>{grant.sku.name} · {grant.serial ?? grant.lotCode}</h4>
    <p>Disetujui: <WarehouseQuantity value={grant.request.quantityBase} unit={grant.request.baseUnit} /> · Masih di tangan: <WarehouseQuantity value={grant.currentQuantityBase} unit={grant.request.baseUnit} /></p>
    <p>{grant.sender.name} → {grant.receiver.name} di {grant.location.name}. Dispatcher: {grant.dispatcher.name}.</p>
    <p>{grant.request.reason} · Bukti persetujuan: {grant.request.evidenceReference}</p>
    {stale && <p role="status">Sumber atau revisi WO berubah sejak persetujuan. Minta dispatcher memeriksa serah-terima ini.</p>}
    {grant.serial && <MaterialScanner disabled={busy || !!review} onScan={value => {
      if (value.trim().toUpperCase() !== grant.serial) { setSerial(null); setError('Serial tidak cocok dengan persetujuan.'); return }
      setSerial(grant.serial); setError(null)
    }} />}
    {error && <p role="alert">{error}</p>}<div className="row wrap"><Button disabled={!enabled || busy || !!review} onClick={onDone}>Muat ulang persetujuan</Button>
      <Button disabled={!enabled || stale || busy || !!review} onClick={() => void prepare()}>{busy ? 'Memeriksa persetujuan…' : 'Tinjau pengiriman antarteknisi'}</Button></div>
    {review && <WarehouseCommandDialog title="Konfirmasi pengiriman antarteknisi" command={review} disabled={!enabled} confirmLabel="Kirim sesuai persetujuan" onDone={onDone} onReload={onDone} onClose={() => setReview(null)} summary={<>
      <p>{grant.sku.name} · {grant.serial ?? grant.lotCode} · <WarehouseQuantity value={grant.request.quantityBase} unit={grant.request.baseUnit} /></p>
      <p>{grant.sender.name} → {grant.receiver.name} di {grant.location.name}.</p><p>Barang masih dalam perjalanan sampai {grant.receiver.name} mengakui penerimaannya.</p>
    </>} />}
  </article>
}
