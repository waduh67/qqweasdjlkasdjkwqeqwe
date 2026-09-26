import { useCallback, useState } from 'react'
import { attachReceipt, downloadReceiptEvidence, listReceiptEvidence, type WarehouseReceipt, type ReceiptEvidence } from '@/api/warehouse/receipts'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { warehouseError } from '@/api/warehouse/errors'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { saveReceiptFile } from './receiptFiles'

export function WarehouseReceiptEvidence({ receipt, onChanged }: { receipt: WarehouseReceipt; onChanged: () => void }) {
  const { can } = useCan()
  const [page, setPage] = useState(0)
  const loader = useCallback(() => listReceiptEvidence(receipt.id, page), [receipt.id, page])
  const result = useWarehouseQuery(loader)
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [downloading, setDownloading] = useState(false)
  const [operation, setOperation] = useState<WarehouseCommand<unknown> | null>(null)
  function prepare() {
    if (!file || file.size < 1 || file.size > 15728640 || !['application/pdf', 'image/png', 'image/jpeg'].includes(file.type)) { setError('Pilih PNG, JPEG atau PDF berukuran 1 byte sampai 15 MiB.'); return }
    setError(null); setOperation(attachReceipt(receipt.id, receipt.revision, file))
  }
  async function download(row: ReceiptEvidence) {
    if (downloading) return
    setDownloading(true); setError(null)
    try { saveReceiptFile(await downloadReceiptEvidence(receipt.id, row.id), `bukti-${row.id}.${row.contentType === 'application/pdf' ? 'pdf' : row.contentType === 'image/png' ? 'png' : 'jpg'}`) }
    catch (caught) { setError(warehouseError(caught)) }
    finally { setDownloading(false) }
  }
  return <section className="stack" aria-label="Bukti penerimaan"><h2>Bukti penerimaan</h2>
    {can('inventory.receipt.manage') && !['CLOSED', 'EXPIRED'].includes(receipt.state) && <div className="card stack">
      <label className="stack">File bukti (PNG, JPEG, PDF; maksimal 15 MiB)<input type="file" accept="image/png,image/jpeg,application/pdf" onChange={event => { setFile(event.target.files?.[0] ?? null); setError(null) }} style={{ maxWidth: '100%' }} /></label>
      <Button onClick={prepare} disabled={!file}>Tinjau unggahan</Button><p className="muted">Bukti mengikuti isi draft saat diunggah. Pengubahan draft memerlukan bukti baru.</p>
    </div>}
    {error && <p role="alert" className="error">{error}</p>}
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Belum ada bukti" hint="Unggah hasil pemeriksaan untuk mencatat penerimaan atau penolakan barang." />} columns={[
        { key: 'file', header: 'Bukti', cell: row => <span>{row.contentType} · {row.sizeBytes} byte<br /><WarehouseTime value={row.createdAt} /><br /><span className="muted">{row.id.slice(0, 8)}</span></span> },
        { key: 'binding', header: 'Isi dokumen', cell: row => row.matchesCurrentIntake ? 'Sesuai draft saat ini' : 'Bukti draft lama — unggah ulang untuk inspeksi' },
        { key: 'download', header: 'Unduh', cell: row => <Button disabled={downloading} onClick={() => void download(row)}>Unduh bukti</Button> },
      ]} />
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
    {operation && <WarehouseCommandDialog title="Unggah bukti penerimaan" confirmLabel="Unggah bukti" command={operation} onDone={onChanged} onClose={() => setOperation(null)} onReload={onChanged}
      summary={<><p>{receipt.externalReference} · Revisi {receipt.revision}</p><p>{file?.name} · {file?.size} byte</p><p>Unggahan menambah bukti dan revisi dokumen. Belum ada perubahan stok.</p></>} />}
  </section>
}
