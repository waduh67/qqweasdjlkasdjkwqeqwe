import { useCallback, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { getReceipt, getReceiptHistory, listReceipts, listReceiptEvidence, downloadReceiptEvidence, type WarehouseReceipt, type ReceiptEvidence } from '@/api/warehouse/receipts'
import { warehouseError } from '@/api/warehouse/errors'
import { captureCommandSession } from '@/api/warehouse/transport'
import { Button, EmptyState } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { evidenceLabel, receiptLink, saveReceiptFile } from './receiptFiles'

export function WarehouseArchiveReceipts({ id }: { readonly id: string | null }) {
  return id ? <><Link to="/warehouse/archive?section=receipts">Kembali ke penerimaan lama</Link><ReceiptDetail id={id} /></> : <ReceiptList />
}

function ReceiptList() {
  const [page, setPage] = useState(0), loader = useCallback(() => listReceipts({ page, size: 25 }), [page])
  const result = useWarehouseQuery(loader)
  return <><Button onClick={result.reload}>Muat ulang penerimaan</Button><WarehouseState {...result}>{data => <>
    <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Belum ada penerimaan lama dalam cakupan Anda" />} columns={[
      { key: 'reference', header: 'Referensi', cell: row => <Link to={receiptLink(row.id, true)}>{row.externalReference}</Link> },
      { key: 'supplier', header: 'Pemasok', cell: row => row.supplierName },
      { key: 'status', header: 'Status', cell: row => <WarehouseStatus status={row.state} /> },
      { key: 'time', header: 'Dibuat', cell: row => <WarehouseTime value={row.createdAt} /> },
    ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState></>
}

function ReceiptDetail({ id }: { readonly id: string }) {
  const loader = useCallback(() => getReceipt(id), [id]), result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{receipt => <>
    <section className="card stack" aria-label="Detail penerimaan lama"><h2>{receipt.externalReference}</h2>
      <p>{receipt.supplierName} · <WarehouseStatus status={receipt.state} /> · Revisi {receipt.revision}</p>
      <p>{receipt.sourceLocationName ?? 'Nama sumber tidak tersedia'} → {receipt.inspectionLocationName ?? 'Nama lokasi pemeriksaan tidak tersedia'}</p>
      <p><WarehouseTime value={receipt.createdAt} /></p><Button onClick={result.reload}>Muat ulang dokumen</Button>
    </section>
    <ReceiptLines receipt={receipt} /><ReceiptEvidence id={receipt.id} /><ReceiptHistory id={receipt.id} />
  </>}</WarehouseState>
}

function ReceiptLines({ receipt }: { readonly receipt: WarehouseReceipt }) {
  const [page, setPage] = useState(0)
  return <section className="stack" aria-label="Barang penerimaan lama"><h2>Barang penerimaan</h2><DataTable presentation="warehouse" rows={receipt.lines.slice(page * 25, (page + 1) * 25)} rowKey={line => line.id} columns={[
    { key: 'name', header: 'Barang', cell: line => <span>{line.skuName} · {line.serial ?? line.lotCode ?? line.skuCode}</span> },
    { key: 'quantity', header: 'Jumlah asal', cell: line => <WarehouseQuantity value={line.quantityBase} unit={line.baseUnit} /> },
    { key: 'accepted', header: 'Lolos pemeriksaan', cell: line => <WarehouseQuantity value={line.acceptedBase} unit={line.baseUnit} /> },
    { key: 'rejected', header: 'Ditolak', cell: line => <WarehouseQuantity value={line.rejectedBase} unit={line.baseUnit} /> },
    { key: 'putaway', header: 'Ditempatkan', cell: line => <WarehouseQuantity value={line.putawayBase} unit={line.baseUnit} /> },
  ]} /><WarehousePagination page={page} size={25} total={receipt.lines.length} onChange={setPage} /></section>
}

function ReceiptHistory({ id }: { readonly id: string }) {
  const [page, setPage] = useState(0), loader = useCallback(() => getReceiptHistory(id), [id]), result = useWarehouseQuery(loader)
  const labels: Readonly<Record<string, string>> = { CREATE: 'Draft dibuat', UPDATE: 'Draft diubah', RECEIVE: 'Barang diterima', INSPECT: 'Pemeriksaan dicatat', PUTAWAY: 'Barang ditempatkan', ATTACHMENT: 'Bukti diunggah' }
  return <section className="stack" aria-label="Riwayat penerimaan lama"><h2>Riwayat penerimaan</h2><WarehouseState {...result}>{data => <>
    <DataTable presentation="warehouse" rows={data.slice(page * 25, (page + 1) * 25)} rowKey={row => row.operationId} empty={<EmptyState title="Belum ada riwayat penerimaan" />} columns={[
      { key: 'action', header: 'Peristiwa', cell: row => labels[row.action] ?? row.action },
      { key: 'revision', header: 'Revisi', cell: row => row.revision },
      { key: 'time', header: 'Waktu', cell: row => <WarehouseTime value={row.recordedAt} /> },
    ]} /><WarehousePagination page={page} size={25} total={data.length} onChange={setPage} />
  </>}</WarehouseState></section>
}

function ReceiptEvidence({ id }: { readonly id: string }) {
  const [page, setPage] = useState(0), loader = useCallback(() => listReceiptEvidence(id, page), [id, page]), result = useWarehouseQuery(loader)
  const [downloading, setDownloading] = useState(false), [error, setError] = useState<string | null>(null), busy = useRef(false)
  async function download(row: ReceiptEvidence) {
    if (busy.current) return
    const checkSession = captureCommandSession()
    busy.current = true; setDownloading(true); setError(null)
    try {
      checkSession()
      const blob = await downloadReceiptEvidence(id, row.id)
      checkSession()
      saveReceiptFile(blob, 'bukti-' + row.id + (row.contentType === 'application/pdf' ? '.pdf' : row.contentType === 'image/png' ? '.png' : '.jpg'))
    } catch (caught) { setError(warehouseError(caught)) }
    finally { busy.current = false; setDownloading(false) }
  }
  return <section className="stack" aria-label="Bukti penerimaan lama"><h2>Bukti penerimaan</h2>{error && <p role="alert">{error}</p>}<WarehouseState {...result}>{data => <>
    <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Belum ada bukti tersimpan" />} rowActions={row => [{ key: 'download', label: 'Unduh bukti', disabled: downloading, onClick: () => void download(row) }]} columns={[
      { key: 'file', header: 'Bukti', cell: evidenceLabel },
      { key: 'size', header: 'Ukuran', cell: row => row.sizeBytes + ' byte' },
      { key: 'binding', header: 'Isi dokumen', cell: row => row.matchesCurrentIntake ? 'Sesuai penerimaan tersimpan' : 'Bukti dari revisi penerimaan sebelumnya' },
    ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState></section>
}
