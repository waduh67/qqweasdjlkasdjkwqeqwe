import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { getAssetHistory, getAssetWorkspace, type AssetHistory } from '@/api/warehouse/customerAssets'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button } from '@/components/atoms'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useFieldConnection } from '@/hooks/useFieldConnection'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { CustomerAssetAction, type CustomerAssetActionKind } from './CustomerAssetAction'
import { CustomerAssetInstallation } from './CustomerAssetInstallation'
import { CustomerAssetException, assetExceptionLabel } from './CustomerAssetException'
import type { AssetExceptionKind } from '@/api/warehouse/customerAssetExceptions'

export function CustomerAssetPanel({ customerId, onChanged }: { customerId: string; onChanged: () => void }) {
  const { user, readOnly } = useAuth(), { can } = useCan()
  if (!user || !can('customer.onu.view')) return null
  return <Assets key={`${user.tenantId}:${user.id}:${customerId}`} customerId={customerId} onChanged={onChanged} readOnly={readOnly} />
}
function Assets({ customerId, onChanged, readOnly }: { customerId: string; onChanged: () => void; readOnly: boolean }) {
  const [page, setPage] = useState(0), [install, setInstall] = useState(false)
  const [action, setAction] = useState<{ row: AssetHistory; kind: CustomerAssetActionKind | 'replace' } | null>(null)
  const [exception, setException] = useState<{ row: AssetHistory; kind: AssetExceptionKind } | null>(null)
  const load = useCallback(async () => { const [context, history] = await Promise.all([getAssetWorkspace(customerId), getAssetHistory(customerId, page)]); return { context, history } }, [customerId, page])
  const result = useWarehouseQuery(load), { can } = useCan(), online = useFieldConnection()
  const manage = can('customer.onu.assign') && can('workorder.order.field'), enabled = manage && online && !readOnly
  const idle = !install && !action && !exception
  const proposalAccess = (kind: AssetExceptionKind) => can('inventory.approval.request') && can('inventory.approval.view') && (kind === 'title' || can('inventory.custody.manage'))
  const done = () => { setInstall(false); setAction(null); setException(null); result.reload(); onChanged() }
  return <section className="card stack" id="customer-assets" aria-label="Aset perangkat pelanggan"><h2>Aset perangkat pelanggan</h2>
    <p>Riwayat pemasangan, asal barang, dan pemilik perangkat. Status koneksi jaringan tetap ditampilkan pada pemantauan.</p>
    {!online && <p role="status">Offline: data terakhir belum diperbarui; tindakan perangkat membutuhkan koneksi.</p>}
    <WarehouseState {...result}>{({ context, history }) => <>
      {context.unresolvedDevices > 0 && <div role="status"><p>{context.unresolvedDevices} perangkat lama belum memiliki asal gudang yang terverifikasi. Rekonsiliasi diperlukan sebelum dipakai untuk transaksi stok.</p>
        {can('inventory.provenance.manage') && <Link to="/warehouse/provenance">Rekonsiliasi perangkat lama</Link>}</div>}
      {manage && idle && <Button disabled={!enabled} onClick={() => setInstall(true)}>Pasang perangkat dari gudang</Button>}
      {history.items.length === 0 && <p>Belum ada pemasangan perangkat dengan sumber gudang terverifikasi.</p>}
      {history.items.map(row => <article key={row.asset.id} className="card stack"><h3>{row.asset.sku.name} · {row.asset.serial}</h3>
        <p><span className="badge">{row.asset.ownershipMode === 'LOAN' ? 'Pinjam pakai' : 'Jual'}</span>{' '}<span className="badge">Milik {row.asset.legalOwner === 'CUSTOMER' ? 'pelanggan' : 'ISP'}</span>{' '}<span className="badge">{row.asset.endedAt ? 'Sudah dilepas' : 'Terpasang'}</span></p>
        <p>Asal: {row.asset.origin?.code ?? 'Asal perlu diperiksa'} · {row.asset.provenance === 'RECEIPT' ? 'Penerimaan gudang' : row.asset.provenance === 'OPENING_BALANCE' ? 'Saldo awal terverifikasi' : 'Belum diketahui'}{row.asset.issueCode ? ` · ${row.asset.issueCode}` : ''}.</p>
        <p>Dipasang {new Date(row.asset.startedAt).toLocaleString('id-ID')}{row.asset.endedAt ? ` · Dilepas ${new Date(row.asset.endedAt).toLocaleString('id-ID')}` : ''}.</p>
        <p>Serah-terima: {row.asset.handoverState === 'ACCEPTED' ? 'Diterima pelanggan' : 'Menunggu bukti penerimaan'}{row.asset.recoveryRequired && !row.asset.endedAt ? ' · Wajib dikembalikan kepada ISP saat layanan berakhir' : ''}.</p>
        {row.asset.previousAssignmentId && <p>Melanjutkan riwayat perangkat yang diganti; episode lama tetap tersimpan.</p>}
        {row.asset.legalOwner === 'CUSTOMER' && <p>Pengembalian atau perbaikan tetap mempertahankan hak milik pelanggan.</p>}
        <div className="row wrap">{can('workorder.order.view') && <Link to={`/work-orders/${row.asset.workOrderId}#work-order-materials`}>WO pemasangan</Link>}
          {manage && !row.asset.endedAt && idle && <>
            {row.asset.handoverState === 'PENDING' && <Button disabled={!enabled} onClick={() => setAction({ row, kind: 'handover' })}>Terima serah-terima pelanggan</Button>}
            <Button disabled={!enabled} onClick={() => setAction({ row, kind: 'replace' })}>Ganti perangkat</Button>
            <Button disabled={!enabled} onClick={() => setAction({ row, kind: 'remove' })}>Lepas perangkat fisik</Button>
            {row.episode && <Button disabled={!enabled} onClick={() => setAction({ row, kind: 'relocate' })}>Pindah ODP</Button>}
          </>}
          {idle && !row.asset.endedAt && row.asset.handoverState === 'ACCEPTED' && row.asset.provenance !== 'UNKNOWN' && row.asset.positionStatus === 'CUSTOMER_INSTALLED' && <>
            {proposalAccess('title') && <Button disabled={!online || readOnly} onClick={() => setException({ row, kind: 'title' })}>{assetExceptionLabel.title}</Button>}
            {can('inventory.approval.request') && can('inventory.custody.manage') && row.asset.ownershipMode === 'LOAN' && row.asset.legalOwner === 'ISP' && <>
              <Button disabled={!online || readOnly || !proposalAccess('loss')} onClick={() => setException({ row, kind: 'loss' })}>{assetExceptionLabel.loss}</Button>
              {!can('inventory.approval.view') && <p>Akses lihat persetujuan diperlukan agar Anda dapat melanjutkan pengajuan kehilangan sendiri. Hubungi pengelola akses.</p>}
            </>}
          </>}
        </div>
      </article>)}
      <WarehousePagination page={page} size={history.size} total={history.totalElements} onChange={setPage} />
    </>}</WarehouseState>
    {install && <CustomerAssetInstallation customerId={customerId} onDone={done} onClose={() => setInstall(false)} />}
    {action?.kind === 'replace' && <CustomerAssetInstallation customerId={customerId} previous={action.row} onDone={done} onClose={() => setAction(null)} />}
    {action && action.kind !== 'replace' && <CustomerAssetAction row={action.row} kind={action.kind} enabled={enabled} onDone={done} onClose={() => setAction(null)} />}
    {exception && <CustomerAssetException row={exception.row} kind={exception.kind} enabled={online && !readOnly && proposalAccess(exception.kind)} onDone={done} onClose={() => setException(null)} />}
  </section>
}
