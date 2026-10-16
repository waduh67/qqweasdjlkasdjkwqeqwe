import { ApiError } from '@/api/client'
import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { getAssetHistory, getAssetWorkspace, type AssetHistory } from '@/api/warehouse/customerAssets'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button } from '@/components/atoms'
import { DataTable, type Column, type RowAction } from '@/components/organisms/DataTable'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { CreationSummary } from '@/components/organisms/CreationReview'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useFieldConnection } from '@/hooks/useFieldConnection'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { CustomerAssetAction, type CustomerAssetActionKind } from './CustomerAssetAction'
import { CustomerAssetInstallation } from './CustomerAssetInstallation'
import { CustomerAssetException, assetExceptionLabel } from './CustomerAssetException'
import type { AssetExceptionKind } from '@/api/warehouse/customerAssetExceptions'

export function CustomerAssetPanel({ customerId, areaId, onChanged }: { customerId: string; areaId?: string | null; onChanged: () => void }) {
  const { user, readOnly } = useAuth(), { can } = useCan()
  if (!user || !can('customer.onu.view')) return null
  return <Assets key={`${user.tenantId}:${user.id}:${customerId}`} customerId={customerId} areaId={areaId} onChanged={onChanged} readOnly={readOnly} />
}
function Assets({ customerId, areaId, onChanged, readOnly }: { customerId: string; areaId?: string | null; onChanged: () => void; readOnly: boolean }) {
  const [detail, setDetail] = useState<AssetHistory | null>(null)
  const [page, setPage] = useState(0), [install, setInstall] = useState(false)
  const [action, setAction] = useState<{ row: AssetHistory; kind: CustomerAssetActionKind | 'replace' } | null>(null)
  const [exception, setException] = useState<{ row: AssetHistory; kind: AssetExceptionKind } | null>(null)
  const load = useCallback(async () => { const [context, history] = await Promise.all([getAssetWorkspace(customerId), getAssetHistory(customerId, page)]); return { context, history } }, [customerId, page])
  const result = useWarehouseQuery(load), { can } = useCan(), online = useFieldConnection()
  const manage = can('customer.onu.assign') && can('workorder.order.field'), enabled = manage && online && !readOnly
  const idle = !install && !action && !exception
  const proposalAccess = (kind: AssetExceptionKind) => can('inventory.approval.request') && can('inventory.approval.view') && (kind === 'title' || can('inventory.custody.manage'))
  const done = () => { setInstall(false); setAction(null); setException(null); result.reload(); onChanged() }
  const columns: Column<AssetHistory>[] = [
    { key: 'serial', header: 'Nomor seri', cell: row => row.asset.serial, onCellClick: setDetail },
    { key: 'sku', header: 'Barang', cell: row => row.asset.sku.name },
    { key: 'status', header: 'Status', cell: row => row.asset.endedAt ? 'Sudah dilepas' : 'Terpasang' },
    { key: 'ownership', header: 'Kepemilikan', cell: row => row.asset.ownershipMode === 'LOAN' ? 'Pinjam pakai' : 'Jual' },
    { key: 'owner', header: 'Pemilik', cell: row => row.asset.legalOwner === 'CUSTOMER' ? 'Pelanggan' : 'ISP' },
    { key: 'origin', header: 'Asal', cell: row => row.asset.origin?.code ?? 'Belum terverifikasi' },
    { key: 'handover', header: 'Serah terima', cell: row => row.asset.handoverState === 'ACCEPTED' ? 'Diterima' : 'Menunggu penerimaan' },
    { key: 'date', header: 'Dipasang', cell: row => new Date(row.asset.startedAt).toLocaleString('id-ID') },
  ]
  const actions = (row: AssetHistory): RowAction[] => {
    const items: RowAction[] = [{ key: 'detail', label: 'Lihat detail', onClick: () => setDetail(row) }]
    if (manage && !row.asset.endedAt && idle) {
      if (row.asset.handoverState === 'PENDING') items.push({ key: 'handover', label: 'Terima serah-terima pelanggan', disabled: !enabled, onClick: () => setAction({ row, kind: 'handover' }) })
      items.push({ key: 'replace', label: 'Ganti perangkat', disabled: !enabled, onClick: () => setAction({ row, kind: 'replace' }) },
        { key: 'remove', label: 'Lepas perangkat fisik', disabled: !enabled, onClick: () => setAction({ row, kind: 'remove' }) })
      if (row.episode) items.push({ key: 'relocate', label: 'Pindah ODP', disabled: !enabled, onClick: () => setAction({ row, kind: 'relocate' }) })
    }
    if (idle && !row.asset.endedAt && row.asset.handoverState === 'ACCEPTED' && row.asset.provenance !== 'UNKNOWN' && row.asset.positionStatus === 'CUSTOMER_INSTALLED') {
      if (proposalAccess('title')) items.push({ key: 'title', label: assetExceptionLabel.title, disabled: !online || readOnly, onClick: () => setException({ row, kind: 'title' }) })
      if (can('inventory.approval.request') && can('inventory.custody.manage') && row.asset.ownershipMode === 'LOAN' && row.asset.legalOwner === 'ISP') items.push({ key: 'loss', label: assetExceptionLabel.loss, disabled: !online || readOnly || !proposalAccess('loss'), onClick: () => setException({ row, kind: 'loss' }) })
    }
    return items
  }
  return <section className="customer-section" id="customer-assets" aria-label="Aset perangkat pelanggan"><h2>Aset perangkat pelanggan</h2>
    {!online && <p role="status">Koneksi terputus. Tindakan perangkat tersedia setelah terhubung kembali.</p>}
    {areaId === null && result.state.status === 'error' && result.state.error instanceof ApiError && result.state.error.status === 404 && <p className="form-note">Tentukan area pelanggan untuk melihat riwayat perangkat.</p>}
    <WarehouseState {...result}>{({ context, history }) => <>
      {context.unresolvedDevices > 0 && <div role="status"><p>{context.unresolvedDevices} perangkat menunggu rekonsiliasi asal gudang.</p>
        {can('inventory.provenance.manage') && <Link to="/warehouse/provenance">Rekonsiliasi perangkat lama</Link>}</div>}
      {manage && idle && <div className="customer-actions"><Button variant="subtle" disabled={!enabled} onClick={() => setInstall(true)}>Pasang perangkat dari gudang</Button></div>}
      {can('inventory.approval.request') && can('inventory.custody.manage') && !can('inventory.approval.view') && <p role="note">Pengajuan kehilangan memerlukan akses lihat persetujuan.</p>}
      <DataTable columns={columns} rows={history.items} rowKey={row => row.asset.id} rowActions={actions} empty={<p>Belum ada riwayat aset gudang.</p>} />
      <WarehousePagination page={page} size={history.size} total={history.totalElements} onChange={setPage} />
    </>}</WarehouseState>
    {detail && <ResourceForm title={detail.asset.serial} readOnly onClose={() => setDetail(null)} onBack={() => {}}>
      <CreationSummary rows={[
        ['Barang', detail.asset.sku.name], ['Status', detail.asset.endedAt ? 'Sudah dilepas' : 'Terpasang'],
        ['Asal', detail.asset.origin?.code ?? 'Belum terverifikasi'], ['Dokumen pengeluaran', detail.asset.issueCode],
        ['Sumber', detail.asset.provenance === 'RECEIPT' ? 'Penerimaan gudang' : detail.asset.provenance === 'OPENING_BALANCE' ? 'Saldo awal terverifikasi' : 'Belum diketahui'],
        ['Dipasang', new Date(detail.asset.startedAt).toLocaleString('id-ID')],
        ['Dilepas', detail.asset.endedAt ? new Date(detail.asset.endedAt).toLocaleString('id-ID') : '—'],
        ['Pemilik', detail.asset.legalOwner === 'CUSTOMER' ? 'Pelanggan' : 'ISP'],
        ['Serah terima', detail.asset.handoverState === 'ACCEPTED' ? 'Diterima pelanggan' : 'Menunggu penerimaan'],
        ['Wajib dikembalikan', detail.asset.recoveryRequired ? 'Ya' : 'Tidak'],
      ]} />
      {can('workorder.order.view') && <Link to={`/work-orders/${detail.asset.workOrderId}#work-order-materials`}>Buka work order pemasangan</Link>}
    </ResourceForm>}
    {install && <CustomerAssetInstallation customerId={customerId} onDone={done} onClose={() => setInstall(false)} />}
    {action?.kind === 'replace' && <CustomerAssetInstallation customerId={customerId} previous={action.row} onDone={done} onClose={() => setAction(null)} />}
    {action && action.kind !== 'replace' && <CustomerAssetAction row={action.row} kind={action.kind} enabled={enabled} onDone={done} onClose={() => setAction(null)} />}
    {exception && <CustomerAssetException row={exception.row} kind={exception.kind} enabled={online && !readOnly && proposalAccess(exception.kind)} onDone={done} onClose={() => setException(null)} />}
  </section>
}
