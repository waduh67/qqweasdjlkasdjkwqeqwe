import { RefreshCw } from 'lucide-react'
import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { listApprovals } from '@/api/warehouse/approvals'
import { listStockShortages } from '@/api/warehouse/overview'
import { listReplenishmentRequests } from '@/api/warehouse/replenishment'
import { listReport } from '@/api/warehouse/reports'
import { listReturns } from '@/api/warehouse/returns'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WAREHOUSE_PAGES, WAREHOUSE_VIEW_PERMISSIONS } from './navigation'
import { replenishmentLink } from './replenishmentPresentation'
import { reportLink } from './reportPresentation'
import { stockLink } from './stockPresentation'
import { WarehouseReportTable } from './WarehouseReportTable'

export function WarehouseOverviewPage() {
  const { can } = useCan()
  if (!WAREHOUSE_VIEW_PERMISSIONS.some(can)) return <WarehouseDenied />
  return <div className="stack warehouse-overview"><PageHeader title="Gudang & Logistik" subtitle="Prioritas pekerjaan dan stok dalam cakupan gudang Anda saat ini." />
    <nav className="warehouse-workflows" aria-label="Pekerjaan gudang">{WAREHOUSE_PAGES.filter(page => ['receipts', 'requests', 'stock', 'catalog'].includes(page.path) && page.permissions.some(can)).map(page => <Link className="card" key={page.path} to={`/warehouse/${page.path}`}><page.icon size={21} strokeWidth={1.75} aria-hidden /><span>{page.label}</span></Link>)}</nav>
    <div className="warehouse-queues">
    {can('inventory.item.view') && <Shortages />}
    {can('inventory.request.view') && <Replenishments />}
    {can('inventory.approval.view') && <Approvals />}
    {can('inventory.return.view') && <><Returns state="RECEIVED_IN_INSPECTION" /><Returns state="REPAIR" /></>}
    {can('inventory.report.view') && <Transit />}
    </div>
  </div>
}
function Shortages() {
  const { can } = useCan(), [page, setPage] = useState(0)
  const loader = useCallback(() => listStockShortages({ page, size: 5 }), [page]), result = useWarehouseQuery(loader)
  return <section className="card stack warehouse-queue" aria-label="Stok di bawah minimum"><h2>Stok di bawah minimum SKU</h2><p className="queue-description">Perbandingan stok tersedia dengan minimum SKU di lokasi yang dapat Anda akses.</p>
    <Button variant="subtle" size="small" aria-label="Perbarui kekurangan stok" title="Perbarui kekurangan stok" icon={<RefreshCw size={15} />} onClick={result.reload} />
    <WarehouseState {...result}>{data => <>{data.totalElements > 0 && <strong>{data.totalElements} barang di bawah minimum</strong>}
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Tidak ada barang di bawah minimum dalam cakupan ini" hint="Barang tanpa minimum tidak termasuk perbandingan ini." />} columns={[
        { key: 'name', header: 'Barang', cell: row => <Link to={stockLink({ tab: 'positions', skuId: row.skuId })}>{row.name} · {row.skuCode}</Link> },
        { key: 'available', header: 'Tersedia / minimum', cell: row => <><WarehouseQuantity value={row.availableBase} unit={row.baseUnit} /> / <WarehouseQuantity value={row.minimumBase} unit={row.baseUnit} /></> },
        { key: 'shortage', header: 'Kurang dari minimum', cell: row => <><WarehouseQuantity value={row.shortageBase} unit={row.baseUnit} />{can('inventory.request.view') && <span>{' · '}<Link to={replenishmentLink({ view: 'rules', skuId: row.skuId })}>Aturan pengisian barang</Link></span>}</> },
      ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
      {can('inventory.receipt.view') && <Link to="/warehouse/receipts">Buka penerimaan barang</Link>}
    </>}</WarehouseState>
  </section>
}
function Replenishments() {
  const [page, setPage] = useState(0), loader = useCallback(() => listReplenishmentRequests({ state: 'PENDING', page, size: 5 }), [page]), result = useWarehouseQuery(loader)
  return <section className="card stack warehouse-queue" aria-label="Pengisian tertunda"><h2>Kebutuhan pengisian tercatat</h2><Button variant="subtle" size="small" aria-label="Perbarui kebutuhan pengisian" title="Perbarui kebutuhan pengisian" icon={<RefreshCw size={15} />} onClick={result.reload} />
    <WarehouseState {...result}>{data => <>{data.totalElements > 0 && <strong>{data.totalElements} kebutuhan belum selesai</strong>}
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.request.id} empty={<EmptyState title="Tidak ada kebutuhan pengisian tertunda" hint="Atur minimum gudang dan hitung ulang saran dari posisi terbaru." />} columns={[
        { key: 'name', header: 'Barang / tujuan', cell: row => <><Link to={replenishmentLink({ requestId: row.request.id })}>{row.sku.name}</Link>{' · '}{row.location.name ?? row.location.code}</> },
        { key: 'quantity', header: 'Jumlah tercatat', cell: row => <WarehouseQuantity value={row.request.quantityBase} unit={row.request.baseUnit} /> },
        { key: 'accepted', header: 'Konfirmasi', cell: row => row.request.acceptedAt ? 'Sudah dikonfirmasi; menunggu pemenuhan' : 'Perlu ditinjau terhadap stok terbaru' },
      ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState><Link to={replenishmentLink({ state: 'PENDING' })}>Buka kebutuhan pengisian</Link>
  </section>
}
function Approvals() {
  const [page, setPage] = useState(0), loader = useCallback(() => listApprovals({ status: 'PENDING', page, size: 5 }), [page]), result = useWarehouseQuery(loader)
  return <section className="card stack warehouse-queue" aria-label="Persetujuan menunggu"><h2>Persetujuan menunggu</h2><Button variant="subtle" size="small" aria-label="Perbarui persetujuan" title="Perbarui persetujuan" icon={<RefreshCw size={15} />} onClick={result.reload} />
    <WarehouseState {...result}>{data => <>{data.totalElements > 0 && <strong>{data.totalElements} persetujuan menunggu</strong>}
      {data.items.length ? <ul>{data.items.map(row => <li key={row.requestId}><Link to={`/warehouse/approvals?approvalId=${row.requestId}`}>{row.code}</Link> · Berlaku sampai <WarehouseTime value={row.expiresAt} /></li>)}</ul> : <p>Tidak ada persetujuan menunggu dalam cakupan ini.</p>}
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState><Link to="/warehouse/approvals">Buka persetujuan gudang</Link>
  </section>
}
function Returns({ state }: { state: 'RECEIVED_IN_INSPECTION' | 'REPAIR' }) {
  const [page, setPage] = useState(0), loader = useCallback(() => listReturns({ state, page, size: 5 }), [state, page]), result = useWarehouseQuery(loader)
  const title = state === 'REPAIR' ? 'Retur dalam servis' : 'Retur menunggu pemeriksaan'
  return <section className="card stack warehouse-queue" aria-label={title}><h2>{title}</h2><Button variant="subtle" size="small" aria-label={`Perbarui ${title.toLowerCase()}`} title={`Perbarui ${title.toLowerCase()}`} icon={<RefreshCw size={15} />} onClick={result.reload} />
    <WarehouseState {...result}>{data => <>{data.totalElements > 0 && <strong>{data.totalElements} retur</strong>}
      {data.items.length ? <ul>{data.items.map(row => <li key={row.returnCase.id}><Link to={`/warehouse/returns?returnId=${row.returnCase.id}`}>{row.references.code} · {row.references.item.name}</Link> · <WarehouseQuantity value={row.returnCase.quantityBase} unit={row.returnCase.baseUnit} /> · <WarehouseTime value={row.returnCase.recordedAt} /></li>)}</ul> : <p>Tidak ada {title.toLowerCase()} dalam cakupan ini.</p>}
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState><Link to="/warehouse/returns">Buka retur dan servis</Link>
  </section>
}
function Transit() {
  const [page, setPage] = useState(0), loader = useCallback(() => listReport('transit-backlog', { page, size: 5, sort: 'createdAt', direction: 'asc' }), [page]), result = useWarehouseQuery(loader)
  return <section className="card stack warehouse-queue" aria-label="Usia barang dalam perjalanan"><h2>Barang dalam perjalanan</h2><Button variant="subtle" size="small" aria-label="Perbarui perjalanan barang" title="Perbarui perjalanan barang" icon={<RefreshCw size={15} />} onClick={result.reload} />
    <WarehouseState {...result}>{data => <>{data.page.totalElements > 0 && <strong>{data.page.totalElements} posisi dalam perjalanan</strong>}<WarehouseReportTable data={data} />
      <WarehousePagination page={data.page.page} size={data.page.size} total={data.page.totalElements} onChange={setPage} />
    </>}</WarehouseState><Link to={reportLink({ kind: 'transit-backlog', sort: 'createdAt', direction: 'asc' })}>Laporan perjalanan barang</Link>
  </section>
}
