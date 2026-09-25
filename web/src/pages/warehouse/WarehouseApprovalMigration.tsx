import { useCallback, useEffect, useRef, useState } from 'react'
import type { ApprovalDocument } from '@/api/warehouse/approvalReads'
import { WarehouseDataError } from '@/api/warehouse/codec'
import { approvalMigrationCases, approvalMigrationEvidence, type MigrationReviewCase } from '@/api/warehouse/migrationReview'
import { Button } from '@/components/atoms'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { saveReceiptFile } from './receiptFiles'

const sourceLabels: Record<MigrationReviewCase['sourceTable'], string> = {
  inventory_serialized_asset: 'Aset fisik lama', inventory_balance_projection: 'Saldo stok lama', onu: 'Perangkat pelanggan lama',
  inventory_serial_tombstone: 'Identitas perangkat dipensiunkan', inventory_movement: 'Transaksi stok lama', inventory_movement_leg: 'Rincian pergerakan lama',
  inventory_fulfillment_effect: 'Hasil pemenuhan lama', inventory_customer_material_fact: 'Riwayat material pelanggan',
  fulfillment_checkpoint: 'Proses pemenuhan WO', fulfillment_outbox: 'Pesan pemenuhan WO',
}
const resolutions = { BASELINE_STOCK: 'Masuk saldo awal', PROVENANCE_ONLY: 'Disimpan sebagai riwayat', DUPLICATE: 'Duplikat yang sudah ditinjau', CANCEL_PENDING: 'Usulan pembatalan efek tertunda' }

export function WarehouseApprovalMigration({ id, document }: { id: string; document: ApprovalDocument }) {
  const [open, setOpen] = useState(false)
  return <section className="card stack" aria-label="Kasus migrasi pada pengajuan"><h2>Kasus yang ditinjau</h2>
    <p>Catatan dan bukti di bawah berasal dari tinjauan yang disimpan saat pengajuan. Perubahan setelah pengajuan memerlukan pemeriksaan baru.</p>
    {open ? <Cases id={id} document={document} /> : <Button onClick={() => setOpen(true)}>Tampilkan kasus migrasi</Button>}
  </section>
}
function Cases({ id, document }: { id: string; document: ApprovalDocument }) {
  const [page, setPage] = useState(0), loader = useCallback(async () => {
    const result = await approvalMigrationCases(id, page)
    if (result.totalElements !== document.migration?.caseCount || result.items.some(row => row.resolution && row.resolution.batchId !== document.migration?.batchId))
      throw new WarehouseDataError('migration.review.batch')
    return result
  }, [id, page, document.migration]), result = useWarehouseQuery(loader)
  return <><Button onClick={result.reload}>Muat ulang kasus migrasi</Button><WarehouseState {...result}>{data => <>
    {data.items.length === 0 && <p>{document.migration?.caseCount === 0 ? 'Saldo awal kosong tidak menambahkan barang.' : 'Tidak ada kasus sumber pada halaman ini.'}</p>}
    {data.items.map(row => <Case key={row.caseId} id={id} row={row} document={document} />)}
    <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState></>
}
function Case({ id, row, document }: { id: string; row: MigrationReviewCase; document: ApprovalDocument }) {
  const resolution = row.resolution, stock = resolution?.stock
  const name = stock ? document.lines.find(line => line.skuId === stock.skuId)?.name : null
  const location = stock ? document.locations.find(place => place.id === stock.locationId) : null
  const [busy, setBusy] = useState<string | null>(null), [error, setError] = useState<string | null>(null)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  async function download(reference: { id: string; sha256: string }) {
    setBusy(reference.id); setError(null)
    try {
      const blob = await approvalMigrationEvidence(id, reference)
      if (!mounted.current) return
      saveReceiptFile(blob, 'bukti-migrasi-' + reference.id + (blob.type === 'application/pdf' ? '.pdf' : blob.type === 'image/png' ? '.png' : '.jpg'))
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Bukti belum dapat dibaca. Coba lagi.') }
    finally { setBusy(null) }
  }
  return <article className="stack" aria-label={sourceLabels[row.sourceTable]} style={{ overflowWrap: 'anywhere' }}>
    <h3>{name || row.source.model || sourceLabels[row.sourceTable]}</h3>
    <p>{sourceLabels[row.sourceTable]} · {resolution ? resolutions[resolution.kind] : 'Riwayat belum diselesaikan, di luar saldo awal'}</p>
    {row.source.serial !== null && <p>Nomor seri asli: <span style={{ whiteSpace: 'pre-wrap' }}>{row.source.serial || '(kosong)'}</span></p>}
    {row.source.mac !== null && <p>MAC asli: {row.source.mac || '(kosong)'}</p>}
    {row.source.state && <p>Status catatan: <WarehouseStatus status={row.source.state} /></p>}
    {row.source.legacyQuantity !== null && <p>Angka pada catatan lama: {row.source.legacyQuantity}</p>}
    {stock && <p>Saldo awal: <WarehouseQuantity value={stock.quantityBase} unit={stock.baseUnit} /> · {location?.name || location?.code || 'Gudang pada dokumen sumber'}</p>}
    {resolution && <p>{resolution.reason}</p>}
    <div className="row wrap">{resolution?.evidence.map((reference, index) => <Button key={reference.id} disabled={busy !== null} onClick={() => void download(reference)}>
      {busy === reference.id ? 'Membaca bukti…' : 'Unduh bukti kasus ' + (index + 1)}
    </Button>)}</div>
    {error && <p role="alert" className="error">{error}</p>}
    <details><summary>Referensi kasus</summary><p>Kasus: {row.caseId}</p><p>Catatan asli: {row.sourceId}</p>
      {resolution?.duplicateCaseId && <p>Kasus acuan duplikat: {resolution.duplicateCaseId}</p>}<p>Sidik sumber: {row.sourceHash}</p></details>
  </article>
}
