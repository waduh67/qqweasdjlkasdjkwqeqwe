import { Link, useSearchParams } from 'react-router-dom'
import { useCan } from '@/auth/useCan'
import { PageHeader, Tabs } from '@/components/molecules'
import { WarehouseDenied } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseArchiveReceipts } from './WarehouseArchiveReceipts'
import { WarehouseArchiveTransfers } from './WarehouseArchiveTransfers'
import { WarehouseArchiveStock } from './WarehouseArchiveStock'

const sections = [
  { key: 'receipts', label: 'Penerimaan lama', permission: 'inventory.receipt.view' },
  { key: 'transfers', label: 'Transfer lama', permission: 'inventory.transfer.view' },
  { key: 'stock', label: 'Stok & jejak barang', permission: 'inventory.item.view' },
] as const
type ArchiveSection = typeof sections[number]['key']

function archiveAddress(params: URLSearchParams, section: ArchiveSection): boolean {
  const allowed = section === 'stock' ? ['section', 'position', 'asset', 'lot', 'segment', 'tab', 'serial'] : ['section', 'id']
  if ([...params.keys()].some(key => !allowed.includes(key) || params.getAll(key).length !== 1 || !params.get(key)?.trim())) return false
  const ids = [...params.entries()].filter(([key]) => !['section', 'tab', 'serial'].includes(key))
  if (ids.some(([, value]) => !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value))) return false
  if (params.has('tab') && !['positions', 'assets', 'lots'].includes(params.get('tab') ?? '')) return false
  if ((params.get('serial')?.length ?? 0) > 128) return false
  return ['position', 'asset', 'lot'].filter(key => params.has(key)).length <= 1 && (!params.has('segment') || params.has('lot'))
}

export function WarehouseArchivePage() {
  const { hasPermission } = useCan(), [params, setParams] = useSearchParams()
  const available = sections.filter(row => hasPermission(row.permission))
  const section = sections.find(row => row.key === (params.get('section') ?? available[0]?.key))
  if (!available.length) return <WarehouseDenied />
  return <div className="stack"><PageHeader title="Arsip Gudang" subtitle="Baca dokumen alur lama dan telusuri pergerakan barang sesuai cakupan akses Anda." />
    <p className="muted">Dokumen lama mempertahankan riwayatnya. Posisi stok menunjukkan ledger saat ini, termasuk pergerakan setelah aktivasi alur baru.</p>
    <Tabs tabs={available} active={section?.key ?? ''} onChange={key => setParams({ section: key })} />
    {!section || !archiveAddress(params, section.key) ? <div className="card stack" role="alert"><p>Alamat arsip tidak dikenal.</p><Link to="/warehouse/archive">Buka daftar arsip</Link></div>
      : !hasPermission(section.permission) ? <WarehouseDenied />
        : <ArchiveSectionBody key={section.key + ':' + params.toString()} section={section.key} params={params} />}
  </div>
}

function ArchiveSectionBody({ section, params }: { readonly section: ArchiveSection; readonly params: URLSearchParams }) {
  switch (section) {
    case 'receipts': return <WarehouseArchiveReceipts id={params.get('id')} />
    case 'transfers': return <WarehouseArchiveTransfers id={params.get('id')} />
    case 'stock': return <WarehouseArchiveStock params={params} />
  }
}
