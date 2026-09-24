import { useCallback, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { listStock } from '@/api/warehouse/masters'
import { listAssets, listLots, listPositions, listUnknownStock, STOCK_BUCKETS, type PositionFilter, type StockFilter } from '@/api/warehouse/stock'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState } from '@/components/atoms'
import { PageHeader, Tabs } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseSerialLookup } from '@/components/organisms/warehouse/WarehouseSerialLookup'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseAssetDetail, WarehouseLotDetail, WarehousePositionDetail } from './WarehouseStockDetail'
import { WarehouseStockFilters } from './WarehouseStockFilters'
import { custodianLabels, stockLink } from './stockPresentation'

const tabs = [{ key: 'summary', label: 'Ringkasan' }, { key: 'positions', label: 'Posisi' }, { key: 'assets', label: 'Perangkat serial' }, { key: 'lots', label: 'Lot / reel' }, { key: 'unknown', label: 'Belum terverifikasi' }] as const
type StockTab = typeof tabs[number]['key']
const filterKeys = ['page', 'skuId', 'locationId', 'serial', 'status', 'condition', 'owner', 'sort', 'direction', 'bucket'] as const
function validParams(params: URLSearchParams) {
  const allowed = new Set<string>([...filterKeys, 'tab', 'asset', 'lot', 'position', 'segment'])
  if ([...params.keys()].some(key => !allowed.has(key) || params.getAll(key).length !== 1 || !params.get(key)?.trim())) return false
  if (!tabs.some(tab => tab.key === (params.get('tab') ?? 'summary'))) return false
  if (params.has('page') && (!/^(0|[1-9][0-9]*)$/.test(params.get('page')!) || !Number.isSafeInteger(Number(params.get('page'))))) return false
  if (params.has('bucket') && (!STOCK_BUCKETS.includes(params.get('bucket') as typeof STOCK_BUCKETS[number]) || !['summary', 'positions'].includes(params.get('tab') ?? 'summary'))) return false
  if (['asset', 'lot', 'position'].filter(key => params.has(key)).length > 1 || (params.has('segment') && !params.has('lot'))) return false
  return true
}
export function WarehouseStockPage() {
  const [params, setParams] = useSearchParams()
  const { can } = useCan()
  const tab = (params.get('tab') ?? 'summary') as StockTab
  return <div className="stack"><PageHeader title="Stok & Perangkat" subtitle="Telusuri barang, lokasi, reservasi dan asalnya dalam cakupan akses Anda." />
    {!validParams(params) ? <div className="card stack" role="alert"><p>Filter atau alamat stok tidak dikenal.</p><Link to="/warehouse/stock">Buka stok tanpa filter</Link></div>
      : params.has('asset') ? <><Link to={stockLink({ tab: 'assets' })}>Kembali ke perangkat</Link><WarehouseAssetDetail key={params.get('asset')} id={params.get('asset')!} /></>
        : params.has('lot') ? <><Link to={stockLink({ tab: 'lots' })}>Kembali ke lot / reel</Link><WarehouseLotDetail key={params.get('lot')} id={params.get('lot')!} segmentId={params.get('segment') ?? undefined} /></>
          : params.has('position') ? <><Link to={stockLink({ tab: 'positions' })}>Kembali ke posisi stok</Link><WarehousePositionDetail key={params.get('position')} id={params.get('position')!} /></>
            : <><Tabs tabs={tabs.filter(row => row.key !== 'unknown' || can('inventory.provenance.view'))} active={tab} onChange={next => {
              const values = new URLSearchParams(params); values.set('tab', next); values.delete('page'); if (!['summary', 'positions'].includes(next)) values.delete('bucket'); setParams(values)
            }} />{tab === 'unknown' && !can('inventory.provenance.view') ? <WarehouseDenied /> : <StockList key={params.toString()} tab={tab} params={params} setParams={setParams} />}</>}
  </div>
}
function StockEmpty() {
  const { can } = useCan()
  return <div className="stack"><EmptyState title="Tidak ada stok yang cocok dalam cakupan Anda" hint="Ubah filter atau catat penerimaan untuk menambah stok terverifikasi." />
    {can('inventory.receipt.view') && <Link to="/warehouse/receipts">Buka penerimaan barang</Link>}{can('inventory.sku.view') && <Link to="/warehouse/catalog">Siapkan barang dan lokasi</Link>}
  </div>
}
function StockList({ tab, params, setParams }: { tab: StockTab; params: URLSearchParams; setParams: (value: URLSearchParams) => void }) {
  const { can } = useCan()
  const filter = useMemo(() => Object.fromEntries(filterKeys.flatMap(key => params.has(key) && params.get(key) !== '' ? [[key, key === 'page' ? Number(params.get(key)) : params.get(key)!]] : [])) as PositionFilter, [params])
  const loader = useCallback(async () => {
    switch (tab) {
      case 'summary': return { tab, page: await listStock(filter) } as const
      case 'positions': return { tab, page: await listPositions(filter) } as const
      case 'assets': return { tab, page: await listAssets(filter as StockFilter) } as const
      case 'lots': return { tab, page: await listLots(filter as StockFilter) } as const
      case 'unknown': return { tab, page: await listUnknownStock(filter as StockFilter) } as const
    }
  }, [tab, filter])
  const result = useWarehouseQuery(loader)
  const [found, setFound] = useState<{ assetId: string; serial: string } | null>(null)
  function apply(values: Record<string, string>) {
    const next = new URLSearchParams(params); next.delete('page')
    for (const [key, value] of Object.entries(values)) { if (value) next.set(key, value); else next.delete(key) }
    setParams(next)
  }
  function positionsLink(skuId: string) {
    const next = new URLSearchParams(params)
    next.set('tab', 'positions'); next.set('skuId', skuId); next.delete('page')
    return `/warehouse/stock?${next}`
  }
  return <>
    <WarehouseStockFilters filter={filter} buckets={tab === 'summary' || tab === 'positions'} onApply={apply} />
    <div className="row wrap"><Button onClick={result.reload}>Segarkan stok</Button><Link to={stockLink({ tab })}>Hapus filter</Link></div>
    {tab === 'assets' && <details className="card"><summary>Cari atau pindai perangkat</summary><WarehouseSerialLookup onSelect={setFound} candidate={found && <Link to={stockLink({ asset: found.assetId })}>Buka perangkat {found.serial}</Link>} /></details>}
    {tab === 'summary' && <p className="muted">Jumlah tercatat juga mencakup material terpakai. Tersedia sudah dikurangi reservasi; ambang minimum dibandingkan dengan hasil filter dan cakupan saat ini.</p>}
    {tab === 'unknown' && <p className="muted">Data ini belum memenuhi verifikasi asal, satuan atau kepemilikan. Nilainya tidak dihitung sebagai stok tersedia.</p>}
    <WarehouseState {...result}>{data => <>
      {data.tab === 'summary' && <DataTable presentation="warehouse" rows={data.page.items} rowKey={row => row.id} empty={<StockEmpty />} columns={[
        { key: 'name', header: 'Barang', cell: row => <span><Link to={positionsLink(row.skuId)}>{row.name}</Link><br /><span className="muted">{row.skuCode}</span></span> },
        { key: 'physical', header: 'Tercatat', align: 'right', cell: row => <WarehouseQuantity value={row.physical.quantityBase} unit={row.physical.baseUnit} /> },
        { key: 'reserved', header: 'Dipesan', align: 'right', cell: row => <WarehouseQuantity value={row.reservedUnpicked.quantityBase} unit={row.reservedUnpicked.baseUnit} /> },
        { key: 'picked', header: 'Disiapkan', align: 'right', cell: row => <WarehouseQuantity value={row.reservedPicked.quantityBase} unit={row.reservedPicked.baseUnit} /> },
        { key: 'available', header: 'Tersedia', align: 'right', cell: row => <WarehouseQuantity value={row.available.quantityBase} unit={row.available.baseUnit} /> },
        { key: 'minimum', header: 'Minimum SKU', cell: row => row.minimumQuantityBase === null ? 'Ambang tidak tersedia' : <span><WarehouseQuantity value={row.minimumQuantityBase} unit={row.physical.baseUnit} />{BigInt(row.available.quantityBase) < BigInt(row.minimumQuantityBase) && <><br /><strong>Di bawah minimum</strong></>}</span> },
      ]} />}
      {data.tab === 'positions' && <DataTable presentation="warehouse" rows={data.page.items} rowKey={row => row.id} empty={<StockEmpty />} columns={[
        { key: 'name', header: 'Barang', cell: row => <span><Link to={stockLink({ position: row.id })}>{row.name}</Link><br />{row.serial ?? row.skuCode}</span> },
        { key: 'location', header: 'Lokasi / pemegang', cell: row => <span>{row.locationName ?? 'Nama lokasi tidak tersedia'}<br /><span className="muted">{custodianLabels[row.custodianKind]}</span></span> },
        { key: 'status', header: 'Status / kondisi', cell: row => <span><WarehouseStatus status={row.status} /><br /><WarehouseStatus status={row.condition} /></span> },
        { key: 'owner', header: 'Pemilik', cell: row => <WarehouseStatus status={row.legalOwner} /> },
        { key: 'quantity', header: 'Tercatat', cell: row => <WarehouseQuantity value={row.physical.quantityBase} unit={row.physical.baseUnit} /> },
        { key: 'available', header: 'Tersedia', cell: row => <WarehouseQuantity value={row.available.quantityBase} unit={row.available.baseUnit} /> },
      ]} />}
      {data.tab === 'assets' && <DataTable presentation="warehouse" rows={data.page.items} rowKey={row => row.id} empty={<StockEmpty />} columns={[
        { key: 'serial', header: 'Serial', cell: row => <Link to={stockLink({ asset: row.id })}>{row.serial}</Link> },
        { key: 'name', header: 'Barang', cell: row => <span>{row.name ?? 'Perangkat belum terverifikasi'}<br />{row.skuCode}</span> },
        { key: 'location', header: 'Lokasi', cell: row => row.locationName ?? 'Nama lokasi tidak tersedia' },
        { key: 'status', header: 'Status', cell: row => <WarehouseStatus status={row.status} /> },
        { key: 'owner', header: 'Pemilik', cell: row => <WarehouseStatus status={row.legalOwner} /> },
        { key: 'cost', header: 'Biaya asal', cell: row => !can('inventory.cost.view') || row.cost === null ? 'Tidak tersedia dalam akses ini' : row.cost.state === 'UNKNOWN' ? 'Belum diketahui' : 'Tercatat pada detail' },
      ]} />}
      {data.tab === 'lots' && <DataTable presentation="warehouse" rows={data.page.items} rowKey={row => row.id} empty={<StockEmpty />} columns={[
        { key: 'lot', header: 'Lot / reel', cell: row => <Link to={stockLink({ lot: row.id })}>{row.code}</Link> },
        { key: 'name', header: 'Barang', cell: row => row.name },
        { key: 'quantity', header: 'Penerimaan asal', cell: row => <WarehouseQuantity value={row.received.quantityBase} unit={row.received.baseUnit} /> },
        { key: 'cost', header: 'Biaya asal', cell: row => !can('inventory.cost.view') || row.cost === null ? 'Tidak tersedia dalam akses ini' : row.cost.state === 'UNKNOWN' ? 'Belum diketahui' : 'Tercatat pada detail' },
      ]} />}
      {data.tab === 'unknown' && <DataTable presentation="warehouse" rows={data.page.items} rowKey={row => row.id} empty={<EmptyState title="Tidak ada stok belum terverifikasi dalam cakupan ini" />} columns={[
        { key: 'name', header: 'Barang / serial', cell: row => <span>{row.name ?? 'Data barang lama'}<br />{row.source === 'ASSET' && row.serial ? <Link to={stockLink({ asset: row.id })}>{row.serial}</Link> : row.serial}</span> },
        { key: 'quantity', header: 'Nilai tercatat', cell: row => row.quantityBase !== null && row.baseUnit !== null ? <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> : `${row.rawQuantity ?? row.quantityBase ?? 'Belum diketahui'} · satuan belum diverifikasi` },
        { key: 'owner', header: 'Pemilik', cell: row => <WarehouseStatus status={row.legalOwner} /> },
        { key: 'state', header: 'Verifikasi', cell: row => <WarehouseStatus status={row.admission} /> },
      ]} />}
      <WarehousePagination page={data.page.page} size={data.page.size} total={data.page.totalElements} onChange={page => { const next = new URLSearchParams(params); next.set('page', String(page)); setParams(next) }} />
    </>}</WarehouseState>
  </>
}
