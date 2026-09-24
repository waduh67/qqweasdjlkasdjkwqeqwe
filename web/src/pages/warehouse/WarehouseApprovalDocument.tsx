import { Link } from 'react-router-dom'
import type { ApprovalDocument } from '@/api/warehouse/approvalReads'
import { useCan } from '@/auth/useCan'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { approvalKindLabels, approvalLineLabel, approvalPersonLabel } from './approvalPresentation'
import { locationLabel } from './receiptChoices'
import { receiptLink } from './receiptFiles'

export function WarehouseApprovalDocument({ document }: { document: ApprovalDocument }) {
  const { can } = useCan()
  const location = (id: string | null) => { const row = document.locations.find(row => row.id === id); return row ? locationLabel(row) : '—' }
  return <section className="card stack" aria-label="Dokumen sumber persetujuan"><h2 style={{ overflowWrap: 'anywhere' }}>{document.code}</h2>
    <p>{approvalKindLabels[document.kind]} · <WarehouseStatus status={document.state} /> · Revisi sumber {document.revision}</p>
    <p>Pembuat: {approvalPersonLabel(document.requester)} · <WarehouseTime value={document.createdAt} /></p>
    {document.reason && <p>{document.reason}</p>}
    <div className="row wrap">
      {document.receiptId && can('inventory.receipt.view') && <Link to={receiptLink(document.receiptId)}>Buka penerimaan sumber</Link>}
      {document.countId && can('inventory.count.view') && <Link to={`/warehouse/counts?countId=${encodeURIComponent(document.countId)}`}>Buka stock opname sumber</Link>}
      {document.transferId && can('inventory.transfer.view') && <Link to={`/warehouse/transfers?transferId=${encodeURIComponent(document.transferId)}`}>Buka transfer sumber</Link>}
      {document.returnId && can('inventory.return.view') && <Link to={`/warehouse/returns?returnId=${encodeURIComponent(document.returnId)}`}>Buka retur sumber</Link>}
    </div>
    <DataTable presentation="warehouse" rows={document.lines} rowKey={line => line.id} columns={[
      { key: 'item', header: 'Barang', cell: approvalLineLabel },
      { key: 'quantity', header: 'Jumlah sumber', cell: line => line.quantityBase === null ? 'Lihat hasil pengajuan' : <WarehouseQuantity value={line.quantityBase} unit={line.baseUnit} /> },
      { key: 'locations', header: 'Asal → Tujuan', cell: line => `${location(line.locationId)} → ${location(line.destinationLocationId)}` },
      { key: 'condition', header: 'Kondisi / Pemilik', cell: line => <span><WarehouseStatus status={line.condition} /> · <WarehouseStatus status={line.legalOwner} /></span> },
    ]} />
    {document.kind === 'COUNT' && (document.comparisons.length ? <><h3>Perbandingan pada pengajuan ini</h3>
      <DataTable presentation="warehouse" rows={document.comparisons} rowKey={row => row.balanceId} columns={[
        { key: 'item', header: 'Posisi barang', cell: row => <span>{document.lines.find(line => line.skuId === row.skuId)?.name}<p className="muted" style={{ overflowWrap: 'anywhere' }}>{row.balanceId}</p></span> },
        { key: 'counter', header: 'Penghitung', cell: row => approvalPersonLabel(row.counter) },
        { key: 'book', header: 'Stok buku saat hitung', cell: row => <WarehouseQuantity value={row.bookQuantityBase} unit={row.baseUnit} /> },
        { key: 'physical', header: 'Hasil fisik', cell: row => <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> },
        { key: 'delta', header: 'Selisih', cell: row => <WarehouseQuantity value={(BigInt(row.quantityBase) - BigInt(row.bookQuantityBase)).toString()} unit={row.baseUnit} /> },
        { key: 'reference', header: 'Lembar hitung', cell: row => row.documentReference },
      ]} /></> : <p>Angka perbandingan tersedia setelah hasil hitung diajukan.</p>)}
  </section>
}
