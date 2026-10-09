import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { listAssets, listLots, listPositions } from '@/api/warehouse/stock'
import { Button, EmptyState } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseAssetDetail, WarehouseLotDetail, WarehousePositionDetail } from './WarehouseStockDetail'
import { custodianLabels, stockLink } from './stockPresentation'

export function WarehouseArchiveStock({ params }: { readonly params: URLSearchParams }) {
  const position = params.get('position'), asset = params.get('asset'), lot = params.get('lot')
  return position || asset || lot ? <><Link to="/warehouse/archive?section=stock">Kembali ke posisi stok</Link>
    {position && <WarehousePositionDetail id={position} archive />}{asset && <WarehouseAssetDetail id={asset} archive />}
    {lot && <WarehouseLotDetail id={lot} segmentId={params.get('segment') ?? undefined} archive />}
  </> : <StockList tab={params.get('tab') ?? 'positions'} serial={params.get('serial') ?? undefined} />
}

function StockList({ tab, serial }: { readonly tab: string; readonly serial: string | undefined }) {
  const [page, setPage] = useState(0)
  const loader = useCallback(async () => {
    const filter = { page, size: 25, sort: 'name', serial } as const
    if (tab === 'assets') return { kind: 'assets', data: await listAssets(filter) } as const
    if (tab === 'lots') return { kind: 'lots', data: await listLots(filter) } as const
    return { kind: 'positions', data: await listPositions(filter) } as const
  }, [page, tab, serial]), result = useWarehouseQuery(loader)
  return <><nav className="row wrap" aria-label="Penelusuran stok arsip"><Link to={stockLink({ tab: 'positions' }, true)}>Posisi stok</Link><Link to={stockLink({ tab: 'assets' }, true)}>Perangkat serial</Link><Link to={stockLink({ tab: 'lots' }, true)}>Lot / reel</Link></nav>
    {serial && <p>Serial: {serial}</p>}<Button onClick={result.reload}>Muat ulang stok</Button><WarehouseState {...result}>{result => <>
    <StockTable result={result} /><WarehousePagination page={result.data.page} size={result.data.size} total={result.data.totalElements} onChange={setPage} />
  </>}</WarehouseState></>
}

type StockListResult =
  | { readonly kind: 'assets'; readonly data: Awaited<ReturnType<typeof listAssets>> }
  | { readonly kind: 'lots'; readonly data: Awaited<ReturnType<typeof listLots>> }
  | { readonly kind: 'positions'; readonly data: Awaited<ReturnType<typeof listPositions>> }

function StockTable({ result }: { readonly result: StockListResult }) {
  switch (result.kind) {
    case 'assets': return <DataTable presentation="warehouse" rows={result.data.items} rowKey={row => row.id} empty={<EmptyState title="Belum ada perangkat dalam cakupan Anda" />} columns={[
      { key: 'serial', header: 'Serial', cell: row => <Link to={stockLink({ asset: row.id }, true)}>{row.serial}</Link> },
      { key: 'name', header: 'Barang', cell: row => row.name ?? 'Perangkat belum terverifikasi' },
      { key: 'location', header: 'Lokasi', cell: row => row.locationName ?? 'Nama lokasi tidak tersedia' },
      { key: 'status', header: 'Status', cell: row => <WarehouseStatus status={row.status} /> },
    ]} />
    case 'lots': return <DataTable presentation="warehouse" rows={result.data.items} rowKey={row => row.id} empty={<EmptyState title="Belum ada lot / reel dalam cakupan Anda" />} columns={[
      { key: 'code', header: 'Lot / reel', cell: row => <Link to={stockLink({ lot: row.id }, true)}>{row.code}</Link> },
      { key: 'name', header: 'Barang', cell: row => row.name },
      { key: 'quantity', header: 'Penerimaan asal', cell: row => <WarehouseQuantity value={row.received.quantityBase} unit={row.received.baseUnit} /> },
    ]} />
    case 'positions': return <DataTable presentation="warehouse" rows={result.data.items} rowKey={row => row.id} empty={<EmptyState title="Belum ada posisi stok dalam cakupan Anda" />} columns={[
      { key: 'name', header: 'Barang', cell: row => <Link to={stockLink({ position: row.id }, true)}>{row.name} · {row.serial ?? row.skuCode}</Link> },
      { key: 'location', header: 'Lokasi / pemegang', cell: row => (row.locationName ?? 'Nama lokasi tidak tersedia') + ' · ' + custodianLabels[row.custodianKind] },
      { key: 'status', header: 'Status / kondisi', cell: row => <span><WarehouseStatus status={row.status} /> · <WarehouseStatus status={row.condition} /></span> },
      { key: 'physical', header: 'Tercatat saat ini', cell: row => <WarehouseQuantity value={row.physical.quantityBase} unit={row.physical.baseUnit} /> },
      { key: 'available', header: 'Tersedia saat ini', cell: row => <WarehouseQuantity value={row.available.quantityBase} unit={row.available.baseUnit} /> },
    ]} />
  }
}
