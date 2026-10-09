import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { getTransfer, listTransfers, transferHistory, type TransferDetails, type WarehouseTransfer } from '@/api/warehouse/transfers'
import { Button, EmptyState } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { transferLineLabel, transferLocationLabel, transferPersonLabel } from './transferPresentation'

export function WarehouseArchiveTransfers({ id }: { readonly id: string | null }) {
  return id ? <><Link to="/warehouse/archive?section=transfers">Kembali ke transfer lama</Link><TransferDetail id={id} /></> : <TransferList />
}

function TransferList() {
  const [page, setPage] = useState(0), loader = useCallback(() => listTransfers({ page, size: 25 }), [page]), result = useWarehouseQuery(loader)
  return <><Button onClick={result.reload}>Muat ulang transfer</Button><WarehouseState {...result}>{data => <>
    <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.transfer.id} empty={<EmptyState title="Belum ada transfer lama dalam cakupan Anda" />} columns={[
      { key: 'code', header: 'Transfer', cell: row => <Link to={'/warehouse/archive?section=transfers&id=' + row.transfer.id}>{row.transfer.code}</Link> },
      { key: 'route', header: 'Rute', cell: row => transferLocationLabel(row, row.transfer.sourceLocationId) + ' → ' + transferLocationLabel(row, row.transfer.destinationLocationId) },
      { key: 'receiver', header: 'Penerima', cell: row => transferPersonLabel(row, row.transfer.receiverId) },
      { key: 'state', header: 'Status', cell: row => <WarehouseStatus status={row.transfer.state} /> },
      { key: 'time', header: 'Dicatat', cell: row => <WarehouseTime value={row.transfer.recordedAt} /> },
    ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState></>
}

function TransferDetail({ id }: { readonly id: string }) {
  const loader = useCallback(() => getTransfer(id), [id]), result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{details => <>
    <section className="card stack" aria-label="Detail transfer lama"><h2>{details.transfer.code}</h2>
      <p><WarehouseStatus status={details.transfer.state} /> · Revisi {details.transfer.revision}</p>
      <p>{transferLocationLabel(details, details.transfer.sourceLocationId)} → {transferLocationLabel(details, details.transfer.transitLocationId)} → {transferLocationLabel(details, details.transfer.destinationLocationId)}</p>
      <p>Pengirim: {transferPersonLabel(details, details.transfer.senderId)} · Penerima: {transferPersonLabel(details, details.transfer.receiverId)}</p>
      <p>Alasan: {details.transfer.reason}</p><p><WarehouseTime value={details.transfer.recordedAt} /></p><Button onClick={result.reload}>Muat ulang dokumen</Button>
    </section><TransferLines details={details} transfer={details.transfer} /><TransferHistory details={details} />
  </>}</WarehouseState>
}

function TransferLines({ details, transfer }: { readonly details: TransferDetails; readonly transfer: WarehouseTransfer }) {
  const [page, setPage] = useState(0)
  return <div className="stack"><DataTable presentation="warehouse" rows={transfer.lines.slice(page * 25, (page + 1) * 25)} rowKey={row => row.id} columns={[
    { key: 'name', header: 'Barang', cell: row => transferLineLabel(details, row.id) },
    { key: 'quantity', header: 'Rencana kirim', cell: row => <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> },
    { key: 'received', header: 'Diterima', cell: row => <WarehouseQuantity value={row.receivedBase} unit={row.baseUnit} /> },
    { key: 'transit', header: 'Dalam perjalanan', cell: row => <WarehouseQuantity value={row.inTransitBase} unit={row.baseUnit} /> },
    { key: 'resolved', header: 'Diselesaikan', cell: row => <WarehouseQuantity value={row.resolvedBase} unit={row.baseUnit} /> },
  ]} /><WarehousePagination page={page} size={25} total={transfer.lines.length} onChange={setPage} /></div>
}

function TransferHistory({ details }: { readonly details: TransferDetails }) {
  const [page, setPage] = useState(0), id = details.transfer.id
  const loader = useCallback(() => transferHistory(id, page), [id, page]), result = useWarehouseQuery(loader)
  return <section className="stack" aria-label="Riwayat transfer lama"><h2>Riwayat transfer</h2><WarehouseState {...result}>{data => <>
    {data.items.map(row => <section className="card stack" key={row.revision}><h3>Revisi {row.revision} · <WarehouseStatus status={row.state} /></h3>
      <p><WarehouseTime value={row.recordedAt} /></p><TransferLines key={row.revision} details={details} transfer={row} />
    </section>)}{!data.items.length && <EmptyState title="Belum ada riwayat transfer" />}
    <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState></section>
}
