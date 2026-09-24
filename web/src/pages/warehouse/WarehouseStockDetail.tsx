import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { getLot, getPosition, getSegment, getStockAsset, listSegments, stockHistory, type StockAsset, type StockCost, type StockOrigin, type StockPosition } from '@/api/warehouse/stock'
import type { BaseUnit } from '@/api/warehouse/quantity'
import { useCan } from '@/auth/useCan'
import { EmptyState } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { receiptLink } from './receiptFiles'
import { custodianLabels, segmentLabels, segmentStateLabels, stockEventLabel, stockLink } from './stockPresentation'

export function WarehouseCost({ cost, unit }: { cost: StockCost | null; unit: BaseUnit | null }) {
  const { can } = useCan()
  if (!can('inventory.cost.view') || cost === null) return <p className="muted">Biaya tidak tersedia dalam akses saat ini.</p>
  if (cost.state === 'UNKNOWN') return <p className="muted">Biaya asal belum diketahui.</p>
  return <p>Biaya kelompok asal: {new Intl.NumberFormat('id-ID').format(BigInt(cost.totalMinor))} satuan minor {cost.currency} · Basis {unit ? <WarehouseQuantity value={cost.costBasisQuantityBase} unit={unit} /> : `${cost.costBasisQuantityBase} (satuan belum diketahui)`}</p>
}
export function WarehouseOrigin({ origin }: { origin: StockOrigin | null }) {
  const { can } = useCan()
  if (!origin) return <p className="muted">Dokumen asal tidak tersedia dalam cakupan ini.</p>
  return <div className="stack"><p>Asal: {origin.kind === 'RECEIPT' && can('inventory.receipt.view') ? <Link to={receiptLink(origin.documentId)}>{origin.documentCode}</Link> : origin.documentCode} · {origin.kind}</p>
    {origin.workOrderCodeSnapshot && <p>WO pada dokumen asal: {origin.workOrderCodeSnapshot}</p>}{origin.customerLabelSnapshot && <p>Pelanggan pada dokumen asal: {origin.customerLabelSnapshot}</p>}
  </div>
}

export function WarehouseAssetDetail({ id }: { id: string }) {
  const loader = useCallback(() => getStockAsset(id), [id])
  const result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{asset => <><AssetSummary asset={asset} /><WarehouseStockTimeline resource="assets" id={asset.id} /></>}</WarehouseState>
}
function AssetSummary({ asset }: { asset: StockAsset }) {
  return <section className="card stack" aria-label="Detail perangkat"><h2>{asset.name ?? 'Perangkat belum terverifikasi'}</h2><p>{asset.skuCode} · <strong>{asset.serial}</strong>{asset.mac && ` · MAC ${asset.mac}`}</p>
    <p><WarehouseStatus status={asset.status} /> · <WarehouseStatus status={asset.condition} /> · <WarehouseStatus status={asset.legalOwner} /></p>
    <p>Lokasi: {asset.locationName ?? 'Nama lokasi tidak tersedia'} · Pemegang: {custodianLabels[asset.custodianKind]}</p>
    <p>Jumlah: {asset.quantity ? <WarehouseQuantity value={asset.quantity.quantityBase} unit={asset.quantity.baseUnit} /> : 'Satuan belum diverifikasi'}</p>
    {asset.admission === 'LEGACY_UNRESOLVED' && <p role="alert" className="error">Asal perangkat lama belum diverifikasi. Perangkat ini belum memenuhi syarat untuk pengeluaran gudang.</p>}
    <Link to={stockLink({ tab: 'positions', serial: asset.serial })}>Lihat posisi stok perangkat</Link>
    {asset.installedOnuId && <p>Terikat pada perangkat ONU · <span className="muted">{asset.installedOnuId}</span></p>}
    <WarehouseOrigin origin={asset.origin} /><WarehouseCost cost={asset.cost} unit={asset.quantity?.baseUnit ?? null} />
    <details><summary>Referensi audit perangkat</summary><p style={{ overflowWrap: 'anywhere' }}>{asset.assetId}</p></details>
  </section>
}

export function WarehousePositionDetail({ id }: { id: string }) {
  const loader = useCallback(() => getPosition(id), [id])
  const result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{row => <><PositionSummary row={row} /><WarehouseStockTimeline resource="stock/positions" id={row.id} /></>}</WarehouseState>
}
function PositionSummary({ row }: { row: StockPosition }) {
  return <section className="card stack" aria-label="Detail posisi"><h2>{row.name}</h2><p>{row.skuCode}{row.serial && ` · ${row.serial}`}</p>
    <p>Lokasi: {row.locationName ?? 'Nama lokasi tidak tersedia'} · Pemegang: {custodianLabels[row.custodianKind]}</p>
    <p><WarehouseStatus status={row.status} /> · <WarehouseStatus status={row.condition} /> · <WarehouseStatus status={row.legalOwner} /></p>
    <p>Tercatat: <WarehouseQuantity value={row.physical.quantityBase} unit={row.physical.baseUnit} /> · Tersedia: <WarehouseQuantity value={row.available.quantityBase} unit={row.available.baseUnit} /></p>
    <p>Reservasi belum dipilih: <WarehouseQuantity value={row.reservedUnpicked.quantityBase} unit={row.reservedUnpicked.baseUnit} /> · Disiapkan: <WarehouseQuantity value={row.reservedPicked.quantityBase} unit={row.reservedPicked.baseUnit} /></p>
    {row.lotId && <Link to={stockLink({ lot: row.lotId })}>Telusuri lot / reel asal</Link>}{row.serial && <Link to={stockLink({ tab: 'assets', serial: row.serial })}>Telusuri perangkat serial</Link>}
    <details><summary>Referensi audit posisi</summary><p style={{ overflowWrap: 'anywhere' }}>Posisi: {row.id}<br />Identitas stok: {row.stockIdentityId}</p></details>
  </section>
}

export function WarehouseLotDetail({ id, segmentId }: { id: string; segmentId?: string }) {
  const loader = useCallback(() => getLot(id), [id])
  const result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{lot => <>
    <section className="card stack" aria-label="Detail lot"><h2>{lot.name} · {lot.code}</h2><p>Diterima: <WarehouseQuantity value={lot.received.quantityBase} unit={lot.received.baseUnit} /> · <WarehouseTime value={lot.receivedAt} /></p>
      <p role={lot.conservation.consistent ? 'status' : 'alert'} className={lot.conservation.consistent ? '' : 'error'}>{lot.conservation.consistent ? 'Jumlah reel dan seluruh bagiannya konsisten.' : 'Jumlah reel dan bagiannya tidak konsisten. Periksa riwayat sebelum memproses barang.'}</p>
      <p>Bagian aktif: <WarehouseQuantity value={lot.conservation.activeQuantityBase} unit={lot.received.baseUnit} /> · Bagian selesai: <WarehouseQuantity value={lot.conservation.terminalQuantityBase} unit={lot.received.baseUnit} /></p>
      <p className="muted">{lot.conservation.rootCount} bagian asal · {lot.conservation.splitCount} bagian telah dipecah. Induk yang dipecah tidak dihitung kembali sebagai stok aktif.</p>
      <WarehouseOrigin origin={lot.origin} /><WarehouseCost cost={lot.cost} unit={lot.received.baseUnit} />
    </section>
    <WarehouseSegments lotId={lot.id} />{segmentId && <WarehouseSegmentDetail lotId={lot.id} id={segmentId} />}
    <WarehouseStockTimeline resource="lots" id={lot.id} />
  </>}</WarehouseState>
}
function WarehouseSegments({ lotId }: { lotId: string }) {
  const [page, setPage] = useState(0)
  const loader = useCallback(() => listSegments(lotId, { page, sort: 'createdAt' }), [lotId, page])
  const result = useWarehouseQuery(loader)
  return <section className="stack" aria-label="Bagian reel"><h2>Bagian lot / reel</h2><WarehouseState {...result}>{data => <>
    <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} columns={[
      { key: 'part', header: 'Bagian', cell: row => <Link to={stockLink({ lot: lotId, segment: row.id })}>{segmentLabels[row.kind]} · {row.id.slice(0, 8)}</Link> },
      { key: 'quantity', header: 'Jumlah', cell: row => <WarehouseQuantity value={row.quantity.quantityBase} unit={row.quantity.baseUnit} /> },
      { key: 'state', header: 'Status', cell: row => segmentStateLabels[row.state] },
      { key: 'parent', header: 'Induk', cell: row => row.parentSegmentId ? <Link to={stockLink({ lot: lotId, segment: row.parentSegmentId })}>Lihat induk</Link> : 'Bagian asal' },
      { key: 'children', header: 'Hasil pecahan', cell: row => `${row.childCount} bagian${row.conserved ? '' : ' · jumlah tidak konsisten'}` },
    ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState></section>
}
function WarehouseSegmentDetail({ lotId, id }: { lotId: string; id: string }) {
  const loader = useCallback(() => getSegment(lotId, id), [lotId, id])
  const result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{segment => <section className="card stack" aria-label="Detail bagian reel"><h2>{segmentLabels[segment.kind]} · {segment.id.slice(0, 8)}</h2>
    <p><WarehouseQuantity value={segment.quantity.quantityBase} unit={segment.quantity.baseUnit} /> · {segmentStateLabels[segment.state]}</p>
    {!segment.conserved && <p className="error" role="alert">Jumlah pecahan tidak sama dengan induknya.</p>}
    {segment.parentSegmentId && <Link to={stockLink({ lot: lotId, segment: segment.parentSegmentId })}>Buka bagian induk</Link>}
    {segment.children.length > 0 && <><p>Hasil pemecahan:</p><ul>{segment.children.map(child => <li key={child}><Link to={stockLink({ lot: lotId, segment: child })}>Buka bagian {child.slice(0, 8)}</Link></li>)}</ul></>}
    {segment.childCount > segment.children.length && <p>Ditampilkan {segment.children.length} dari {segment.childCount} bagian. Daftar bagian lot di atas memuat seluruh bagian melalui halaman berikutnya.</p>}
    <p className="muted"><WarehouseTime value={segment.createdAt} /></p>
  </section>}</WarehouseState>
}

export function WarehouseStockTimeline({ resource, id }: { resource: 'assets' | 'lots' | 'stock/positions'; id: string }) {
  const [page, setPage] = useState(0)
  const loader = useCallback(() => stockHistory(resource, id, { page, sort: 'createdAt', direction: 'desc' }), [resource, id, page])
  const result = useWarehouseQuery(loader)
  return <section className="stack" aria-label="Jejak barang"><h2>Jejak barang</h2><p className="muted">Peristiwa terbaru ditampilkan terlebih dahulu, sesuai lokasi yang dapat Anda akses.</p>
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Belum ada jejak yang dapat ditampilkan" />} columns={[
        { key: 'time', header: 'Waktu', cell: row => <WarehouseTime value={row.recordedAt} /> },
        { key: 'event', header: 'Peristiwa', cell: stockEventLabel },
        { key: 'quantity', header: 'Jumlah', cell: row => row.kind === 'RESERVATION' ? <span>Belum dipilih: <WarehouseQuantity value={row.reservedUnpickedBase} unit={row.baseUnit} /><br />Disiapkan: <WarehouseQuantity value={row.reservedPickedBase} unit={row.baseUnit} /></span> : <WarehouseQuantity value={row.quantity.quantityBase} unit={row.quantity.baseUnit} /> },
        { key: 'details', header: 'Rincian', cell: row => row.kind === 'MOVEMENT_LEG' ? <span>{row.documentCode} · Revisi {row.documentRevision}<br /><WarehouseStatus status={row.status} />{row.currentLocationName && <><br />Lokasi (nama saat ini): {row.currentLocationName}</>}</span>
          : row.kind === 'INSPECTION' ? <WarehouseStatus status={row.disposition} /> : row.kind === 'RESERVATION' ? <span>{row.state} · Revisi {row.documentRevision}</span> : <span>Revisi pemakaian {row.useRevision}{row.compensationId && ' · Kompensasi tercatat'}</span> },
      ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
  </section>
}
