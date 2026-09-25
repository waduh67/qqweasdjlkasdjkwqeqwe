import { useCallback, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { acknowledgeMyRma, getMyMaterialRma, getMyMaterialRmas, reinstallMyRma } from '@/api/warehouse/myMaterialRma'
import { getMyMaterialContext, type MyMaterialContext } from '@/api/warehouse/myMaterials'
import type { RmaDetails } from '@/api/warehouse/returns'
import { sameSerialIdentity } from '@/api/warehouse/serialIdentity'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, TextField } from '@/components/atoms'
import { CustomerAssetTopology } from '@/components/organisms/customer/CustomerAssetTopology'
import { assetTopologyInput, emptyAssetTopology } from '@/components/organisms/customer/customerAssetDraft'
import { MaterialScanner } from '@/components/organisms/warehouse/MaterialScanner'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

export function MyMaterialRma({ context, actor, enabled, onReload }: { context: MyMaterialContext; actor: string; enabled: boolean; onReload: () => void }) {
  const [open, setOpen] = useState(false)
  return <section className="stack" aria-label="Perangkat servis milik pelanggan"><h3>Perangkat servis milik pelanggan</h3>
    {open ? <RmaList context={context} actor={actor} enabled={enabled} onReload={onReload} /> : <Button onClick={() => setOpen(true)}>Lihat perangkat servis</Button>}
  </section>
}
function RmaList({ context, actor, enabled, onReload }: { context: MyMaterialContext; actor: string; enabled: boolean; onReload: () => void }) {
  const [page, setPage] = useState(0), [selected, setSelected] = useState<RmaDetails | null>(null), [installed, setInstalled] = useState<string | null>(null)
  const { can } = useCan(), load = useCallback(() => getMyMaterialRmas(context.id, page), [context.id, page]), result = useWarehouseQuery(load)
  const canInstall = can('customer.onu.assign'), canOpenCustomer = can('customer.onu.view') && can('customer.customer.view')
  function done() { if (selected?.handover.state === 'RECEIVED') setInstalled(selected.handover.customerId); setSelected(null); result.reload() }
  return <>{installed && <p role="status">Perangkat sudah dipasang kembali. Lengkapi serah-terima dengan bukti tanda tangan pelanggan.
    {canOpenCustomer && <> <Link to="/customers" state={{ openCustomerId: installed }}>Buka aset pelanggan</Link></>}</p>}
    {selected ? <RmaAction key={selected.handover.id} context={context} row={selected} actor={actor} enabled={enabled && (selected.handover.state === 'DISPATCHED' || canInstall)} onClose={() => setSelected(null)} onDone={done} onReload={onReload} />
      : <WarehouseState {...result}>{data => <>
        {data.items.length === 0 && <p>Belum ada perangkat servis yang menunggu diterima atau dipasang kembali.</p>}
        {data.items.map(row => <article key={row.handover.id} className="card stack">
          <h4>{row.handover.serial}</h4><p>{row.workOrderCode} · {row.workOrderTitle}</p>
          <p>Milik pelanggan · kembali ke pelanggan asal. {row.handover.state === 'DISPATCHED' ? 'Dalam perjalanan — belum diterima' : 'Sudah diterima — di tangan Anda'}</p>
          <p>{row.senderName ?? 'Pengirim'} → {row.technicianName ?? 'Teknisi'} · {row.locations.find(location => location.id === row.handover.locationId)?.name}</p>
          {row.handover.state === 'DISPATCHED' && row.handover.workOrderRevision !== context.workOrderRevision && <p role="status">WO berubah sejak pengiriman. Minta petugas memeriksa pengiriman ini.</p>}
          {row.handover.technicianId === actor && <Button disabled={!enabled || !context.currentAssignee || !context.active || (row.handover.state === 'DISPATCHED' ? row.handover.workOrderRevision !== context.workOrderRevision : !canInstall)} onClick={() => setSelected(row)}>
            {row.handover.state === 'DISPATCHED' ? 'Terima perangkat servis' : 'Pasang kembali perangkat'}</Button>}
          {canOpenCustomer && <Link to="/customers" state={{ openCustomerId: row.handover.customerId }}>Riwayat aset pelanggan</Link>}
        </article>)}<WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} />
      </>}</WarehouseState>}
  </>
}
function RmaAction({ context, row, actor, enabled, onClose, onDone, onReload }: { context: MyMaterialContext; row: RmaDetails; actor: string; enabled: boolean; onClose: () => void; onDone: () => void; onReload: () => void }) {
  const [observed, setObserved] = useState(''), [reference, setReference] = useState(''), [topology, setTopology] = useState(emptyAssetTopology)
  const [review, setReview] = useState<WarehouseCommand<unknown> | null>(null), [error, setError] = useState<string | null>(null), [busy, setBusy] = useState(false), active = useRef(false)
  const receiving = row.handover.state === 'DISPATCHED', disabled = busy || !!review
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!enabled || disabled || active.current) return
    try {
      if (!sameSerialIdentity(observed, row.handover.serial)) throw new Error('Cocokkan serial fisik dengan perangkat servis ini.')
      const nextTopology = receiving ? null : assetTopologyInput(topology)
      active.current = true; setBusy(true); setError(null)
      const [freshContext, freshRow] = await Promise.all([getMyMaterialContext(context.id), getMyMaterialRma(context.id, row.handover.id)])
      if (freshContext.workOrderRevision !== context.workOrderRevision || JSON.stringify(freshRow) !== JSON.stringify(row)) throw new Error('WO atau perangkat servis berubah. Muat ulang sebelum melanjutkan.')
      setReview(receiving ? acknowledgeMyRma(freshContext, freshRow, actor, observed, reference) : reinstallMyRma(freshContext, freshRow, actor, observed, nextTopology))
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Perangkat servis belum dapat diperiksa.') }
    finally { active.current = false; setBusy(false) }
  }
  return <><form className="card stack" aria-label="Tindak lanjut perangkat servis" onSubmit={event => void submit(event)}>
    <h4>{receiving ? 'Terima' : 'Pasang kembali'} {row.handover.serial}</h4><p>{row.workOrderCode} · {row.workOrderTitle}</p>
    <p>Perangkat tetap milik pelanggan asal. Cocokkan serial fisik sebelum melanjutkan.</p>
    <MaterialScanner disabled={disabled} onScan={value => { setObserved(value.trim().toUpperCase()); setError(null) }} />
    {observed && <p role="status">Serial diperiksa: {observed}{!sameSerialIdentity(observed, row.handover.serial) ? ' — tidak cocok' : ''}</p>}
    {receiving ? <TextField label="Referensi bukti penerimaan servis" value={reference} required maxLength={500} disabled={disabled} onChange={(_, data) => setReference(data.value)} />
      : <CustomerAssetTopology value={topology} onChange={setTopology} disabled={disabled || !enabled} />}
    {error && <p role="alert">{error}</p>}<div className="row wrap"><Button onClick={onClose} disabled={disabled}>Batal</Button><Button type="submit" disabled={disabled || !enabled}>{busy ? 'Memeriksa perangkat…' : receiving ? 'Tinjau penerimaan servis' : 'Tinjau pemasangan kembali'}</Button></div>
  </form>{review && <WarehouseCommandDialog title={receiving ? 'Konfirmasi penerimaan servis' : 'Konfirmasi pemasangan kembali'} command={review} disabled={!enabled} confirmLabel={receiving ? 'Terima perangkat' : 'Pasang kembali'} onDone={onDone} onReload={onReload} onClose={() => setReview(null)} summary={<>
    <p>{row.workOrderCode} · {row.handover.serial} · Milik pelanggan asal.</p>
    {receiving ? <p>Bukti: {reference}. Perangkat diterima di tangan Anda.</p> : <><p>{topology.odp ? `${topology.odp.code} port ${topology.port}` : 'Belum ditempel ke port ODP.'}</p><p>Perangkat dipasang kembali pada pelanggan asal. Hak milik tetap pada pelanggan; lengkapi serah-terima setelah pemasangan.</p></>}
  </>} />}</>
}
