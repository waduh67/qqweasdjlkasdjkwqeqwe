import { useCallback, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '@/api/client'
import { getAssetExceptionContext, proposeAssetException, type AssetExceptionContext, type AssetExceptionKind, type AssetExceptionProposal } from '@/api/warehouse/customerAssetExceptions'
import type { AssetHistory } from '@/api/warehouse/customerAssets'
import { warehouseError } from '@/api/warehouse/errors'
import { getLocation, listLocations } from '@/api/warehouse/masters'
import type { WarehouseLocation } from '@/api/warehouse/models'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, TextareaField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

export const assetExceptionLabel = { loss: 'Ajukan kehilangan perangkat', title: 'Ajukan koreksi kepemilikan' }
const locations = (search: string, page: number) => listLocations({ search, page, state: 'ACTIVE' })
const locationName = (row: WarehouseLocation) => row.name ? `${row.name} · ${row.code}` : row.code
const failureMessage = (error: unknown) => error instanceof ApiError ? warehouseError(error) : error instanceof Error ? error.message : warehouseError(error)
export function CustomerAssetException({ row, kind, enabled, onDone, onClose }: {
  row: AssetHistory; kind: AssetExceptionKind; enabled: boolean; onDone: () => void; onClose: () => void
}) {
  const { can } = useCan()
  const load = useCallback(() => getAssetExceptionContext(row), [row])
  const { state, reload } = useWarehouseQuery(load)
  const [reason, setReason] = useState(''), [destination, setDestination] = useState<WarehouseLocation | null>(null)
  const [busy, setBusy] = useState(false), active = useRef(false), allowed = useRef(enabled)
  allowed.current = enabled
  const [error, setError] = useState<string | null>(null)
  const [review, setReview] = useState<{ command: WarehouseCommand<AssetExceptionProposal>; context: AssetExceptionContext } | null>(null)
  const [saved, setSaved] = useState<AssetExceptionProposal | null>(null)
  const context = state.status === 'ready' ? state.data : null
  const eligible = can('inventory.approval.view') && !!context?.workOrder.signature && (kind === 'title' ? context.canRequestTitleCorrection : context.canRequestLoss && can('inventory.location.view'))
  const disabled = !enabled || busy || !!review || !!saved
  async function prepare(event: FormEvent) {
    event.preventDefault()
    if (disabled || active.current || !eligible || !context) return
    active.current = true; setBusy(true); setError(null)
    try {
      const fresh = await load()
      if (JSON.stringify(fresh) !== JSON.stringify(context)) throw new Error('Kepemilikan, WO, izin, atau bukti berubah. Muat ulang riwayat pelanggan untuk meninjau pengajuan kembali.')
      const target = kind === 'loss' && destination ? await getLocation(destination.id) : null
      if (kind === 'loss' && JSON.stringify(target) !== JSON.stringify(destination)) throw new Error('Lokasi kehilangan berubah. Pilih kembali lokasi yang berlaku.')
      const command = proposeAssetException(kind, fresh, reason, target)
      if (allowed.current) setReview({ command, context: fresh })
    } catch (caught) { setError(failureMessage(caught)) }
    finally { active.current = false; setBusy(false) }
  }
  if (saved) return <section className="card stack" role="status"><h3>{saved.label} sudah tercatat</h3>
    <p>Pengajuan memerlukan persetujuan petugas independen. Stok, kepemilikan, dan kewajiban pengembalian belum berubah.</p>
    {can('inventory.approval.view') ? <Link to={`/warehouse/approvals?sourceDocumentId=${saved.documentId}`}>Lanjutkan ke persetujuan</Link>
      : <p>Pemohon pengajuan ini memerlukan akses lihat persetujuan untuk melanjutkan. Minta pengelola memberikan akses tersebut kepada pemohon; petugas lain tidak dapat mengajukan proposal ini atas namanya.</p>}
    <Button onClick={onDone}>Kembali ke riwayat aset</Button>
  </section>
  return <><form className="card stack" aria-label={assetExceptionLabel[kind]} onSubmit={event => void prepare(event)}>
    <h3>{assetExceptionLabel[kind]}</h3><p>{row.asset.sku.name} · {row.asset.serial} · Asal {row.asset.origin?.code ?? 'terverifikasi'}.</p>
    <p>Gunakan bukti tanda tangan WO pemasangan yang sudah tersimpan. Akses bukti WO atau penugasan teknisi pada WO tersebut diperlukan.</p>
    {can('workorder.order.view') && <Link to={`/work-orders/${row.asset.workOrderId}#work-order-evidence`}>Buka bukti WO asal</Link>}
    {state.status === 'loading' && <p role="status">Memeriksa aset, kepemilikan, dan bukti…</p>}
    {state.status === 'error' && <div role="alert"><p>{failureMessage(state.error)}</p><Button onClick={onDone}>Muat ulang riwayat pelanggan</Button></div>}
    {context && <><p>{context.customerLabel} · {context.workOrder.code} · Milik {context.ownership.legalOwner === 'ISP' ? 'ISP' : 'pelanggan'}.</p>
      {context.workOrder.signature ? <p>Tanda tangan {context.workOrder.signature.signerName} · {new Date(context.workOrder.signature.recordedAt).toLocaleString('id-ID')}.</p>
        : <p role="status">Lengkapi bukti tanda tangan pada WO asal, lalu muat ulang pengajuan.</p>}
      {kind === 'title' ? <p>Usulan pemilik baru: {context.ownership.legalOwner === 'ISP' ? 'pelanggan' : 'ISP'}. Riwayat pinjam pakai atau penjualan awal tetap tersimpan.</p>
        : <p>Kehilangan hanya berlaku bagi perangkat pinjam pakai milik ISP. Ini belum mencatat pemulihan fisik atau menutup kewajiban pengembalian.</p>}
      {!eligible && context.workOrder.signature && <p role="status">Aset atau izin belum memenuhi syarat pengajuan ini.{kind === 'loss' && !can('inventory.location.view') ? ' Akses daftar lokasi diperlukan untuk memilih lokasi kehilangan.' : ''}</p>}
    </>}
    {kind === 'loss' && can('inventory.location.view') && <WarehousePicker label="Lokasi kehilangan" load={locations} value={destination} onChange={setDestination} name={locationName}
      eligible={location => location.state === 'ACTIVE' && location.kind === 'LOST' && !location.issueEligible} disabled={disabled} />}
    <TextareaField label="Alasan pengajuan" value={reason} required maxLength={kind === 'title' ? 500 : 1000} disabled={disabled} onChange={(_, data) => setReason(data.value)} />
    {error && <div role="alert"><p>{error}</p><Button disabled={disabled} onClick={onDone}>Muat ulang riwayat pelanggan</Button></div>}
    <div className="row wrap"><Button disabled={busy || !!review} onClick={onClose}>Batal</Button><Button disabled={disabled} onClick={() => { setError(null); reload() }}>Muat ulang pengajuan</Button>
      <Button type="submit" disabled={disabled || !eligible || !reason.trim() || (kind === 'loss' && !destination)}>Tinjau pengajuan</Button></div>
  </form>{review && <WarehouseCommandDialog title={assetExceptionLabel[kind]} command={review.command} disabled={!enabled} confirmLabel="Catat pengajuan"
    onDone={value => { setSaved(value); setReview(null) }} onClose={() => setReview(null)} onReload={onDone} summary={<>
      <p>{review.context.customerLabel} · {row.asset.sku.name} · {row.asset.serial} · {review.context.workOrder.code}.</p>
      <p>{kind === 'title' ? `Kepemilikan ${review.context.ownership.legalOwner === 'ISP' ? 'ISP → pelanggan' : 'pelanggan → ISP'}` : `Kehilangan → ${destination && locationName(destination)}`}.</p>
      <p>Bukti tanda tangan: {review.context.workOrder.signature?.signerName}. Alasan: {reason.trim()}.</p>
      <p>Persetujuan independen diperlukan. Pencatatan pengajuan belum mengubah stok, kepemilikan, atau kewajiban pengembalian.</p>
    </>} />}</>
}
