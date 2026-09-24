import { Link } from 'react-router-dom'
import type { WarehouseReport } from '@/api/warehouse/reports'
import type { ReportMovement } from '@/api/warehouse/reportModels'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { custodianLabels, stockLink } from './stockPresentation'

import { reportLink } from './reportPresentation'

const money = (value: string, currency: string) => `${new Intl.NumberFormat('id-ID').format(BigInt(value))} ${currency} (satuan minor)`
const assignmentLabels = { APPROVED_LOSS: 'Kehilangan disetujui', RECOVERED: 'Sudah diambil kembali', CLOSED: 'Selesai', PENDING_HANDOVER: 'Menunggu serah terima', ACTIVE: 'Aktif' }
export function WarehouseReportTable({ data, onPrint }: { data: WarehouseReport; onPrint: (row: ReportMovement) => void }) {
  const { can } = useCan()
  const empty = <EmptyState title="Tidak ada data laporan dalam cakupan ini" hint="Ubah rentang tanggal atau filter untuk melihat catatan lain." />
  const item = (row: { name: string; skuId: string }) => can('inventory.item.view') ? <Link to={stockLink({ tab: 'positions', skuId: row.skuId })}>{row.name}</Link> : row.name
  const document = (row: ReportMovement) => <><span>{row.documentCode} · Revisi {row.documentRevision}</span>{['RECEIVE', 'ISSUE', 'RETURN'].includes(row.movementKind) && <Button onClick={() => onPrint(row)}>Pratinjau dokumen</Button>}</>
  if (data.kind === 'stock') return <DataTable presentation="warehouse" rows={data.page.items} rowKey={row => row.id} empty={empty} columns={[
    { key: 'name', header: 'Barang', cell: item },
    { key: 'physical', header: 'Tercatat', cell: row => <WarehouseQuantity value={row.physical.quantityBase} unit={row.physical.baseUnit} /> },
    { key: 'reserved', header: 'Dipesan / disiapkan', cell: row => <><WarehouseQuantity value={row.reservedUnpicked.quantityBase} unit={row.physical.baseUnit} /> / <WarehouseQuantity value={row.reservedPicked.quantityBase} unit={row.physical.baseUnit} /></> },
    { key: 'available', header: 'Tersedia', cell: row => <WarehouseQuantity value={row.available.quantityBase} unit={row.physical.baseUnit} /> },
  ]} />
  if (data.kind === 'unknown-stock') return <DataTable presentation="warehouse" rows={data.page.items} rowKey={row => row.id} empty={empty} columns={[
    { key: 'name', header: 'Barang', cell: row => row.name ?? row.serial ?? 'Nama belum terverifikasi' },
    { key: 'quantity', header: 'Jumlah asal', cell: row => row.baseUnit && row.quantityBase !== null ? <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> : `${row.rawQuantity ?? 'Tidak tercatat'} · satuan belum diverifikasi` },
    { key: 'state', header: 'Verifikasi', cell: () => 'Asal, satuan, atau kepemilikan belum terverifikasi; tidak tersedia untuk dikeluarkan.' },
  ]} />
  if (data.kind === 'movements' || data.kind === 'stock-card') return <DataTable presentation="warehouse" rows={data.page.items} rowKey={row => row.id} empty={empty} columns={[
    { key: 'time', header: 'Tanggal / dokumen', cell: row => <><WarehouseTime value={row.recordedAt} /><br />{document(row)}</> },
    { key: 'name', header: 'Barang / serial', cell: row => <>{item(row)}<br />{row.serial}</> },
    { key: 'location', header: 'Lokasi / keadaan', cell: row => <>{row.locationName ?? 'Nama lokasi tidak tersedia'}<br /><WarehouseStatus status={row.status} /> · <WarehouseStatus status={row.legalOwner} /></> },
    { key: 'quantity', header: 'Pergerakan', cell: row => <>{row.direction === 'IN' ? 'Masuk' : 'Keluar'} · <WarehouseQuantity value={row.quantity.quantityBase} unit={row.quantity.baseUnit} />{row.compensatesPostingId && <p>Pembalikan: {row.compensatesPostingId}</p>}</> },
    ...(data.kind === 'stock-card' ? [{ key: 'balance', header: 'Awal → akhir', cell: (row: ReportMovement) => 'openingQuantityBase' in row && 'closingQuantityBase' in row ? <><WarehouseQuantity value={String(row.openingQuantityBase)} unit={row.quantity.baseUnit} /> → <WarehouseQuantity value={String(row.closingQuantityBase)} unit={row.quantity.baseUnit} /></> : null }] : []),
  ]} />
  if (data.kind === 'custody-aging' || data.kind === 'transit-backlog') return <DataTable presentation="warehouse" rows={data.page.items} rowKey={row => row.id} empty={empty} columns={[
    { key: 'name', header: 'Barang / serial', cell: row => <>{item(row)}<br />{row.serial}</> },
    { key: 'location', header: 'Lokasi / pemegang', cell: row => <>{row.locationName ?? 'Nama lokasi tidak tersedia'}<br />{custodianLabels[row.custodianKind]}</> },
    { key: 'quantity', header: 'Jumlah', cell: row => <WarehouseQuantity value={row.quantity.quantityBase} unit={row.quantity.baseUnit} /> },
    { key: 'age', header: 'Sejak / usia', cell: row => row.enteredAt && row.ageSeconds !== null ? <><WarehouseTime value={row.enteredAt} /><br />{(BigInt(row.ageSeconds) / 86400n).toString()} hari</> : 'Waktu masuk belum diketahui' },
  ]} />
  if (data.kind === 'loan-assets' || data.kind === 'sold-assets') return <DataTable presentation="warehouse" rows={data.page.items} rowKey={row => row.id} empty={empty} columns={[
    { key: 'serial', header: 'Perangkat', cell: row => <>{can('inventory.item.view') ? <Link to={stockLink({ asset: row.assetId })}>{row.serial}</Link> : row.serial}<br />{row.name}</> },
    { key: 'owner', header: 'Pemilik / penugasan', cell: row => <><WarehouseStatus status={row.legalOwner} /><br />{assignmentLabels[row.assignmentState]}</> },
    { key: 'period', header: 'Masa penugasan', cell: row => <><WarehouseTime value={row.startedAt} />{row.endedAt && <> → <WarehouseTime value={row.endedAt} /></>}</> },
    { key: 'wo', header: 'Work order', cell: row => can('inventory.cost.view') ? <Link to={reportLink({ kind: 'work-order-costs', workOrderId: row.workOrderId })}>Biaya WO terkait</Link> : 'Tercatat pada penugasan' },
  ]} />
  if (data.kind !== 'work-order-costs' || !can('inventory.cost.view')) return null
  return <>
    <section className="card stack" aria-label="Total biaya pemakaian"><p>Total seluruh hasil filter dalam lokasi yang dapat Anda lihat. Biaya pemakaian operasional, termasuk pembalikan; bukan nilai aset.</p>
      {data.page.currencyTotals.map(total => <strong key={total.currency}>{money(total.totalMinor, total.currency)}</strong>)}
      {data.page.unknownQuantities.map(total => <p key={total.baseUnit}>Biaya belum diketahui untuk <WarehouseQuantity value={total.quantityBase} unit={total.baseUnit} />. Tidak dihitung sebagai biaya nol.</p>)}
    </section>
    <DataTable presentation="warehouse" rows={data.page.items} rowKey={row => row.id} empty={empty} columns={[
      { key: 'wo', header: 'Work order / tanggal', cell: row => <><Link to={reportLink({ kind: 'work-order-costs', workOrderId: row.workOrderId })}>{row.workOrderCode ?? 'WO terkait'}</Link><br /><WarehouseTime value={row.recordedAt} /></> },
      { key: 'item', header: 'Barang / pemakaian', cell: row => <>{item(row)}<br /><WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} />{row.compensatesPostingId && <p>Pembalikan pemakaian</p>}</> },
      { key: 'cost', header: 'Biaya operasional', cell: row => row.costState === 'UNKNOWN' ? 'Biaya belum diketahui' : <>{money(row.lineTotalMinor!, row.currency!)}<p className="muted">Asal {money(row.sourceTotalMinor!, row.currency!)} per <WarehouseQuantity value={row.sourceBasisQuantityBase!} unit={row.baseUnit} />; dibulatkan ke satuan minor terdekat, setengah ke atas.</p></> },
    ]} />
  </>
}
