import { useCallback, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Checkbox } from '@fluentui/react-components'
import { getWorkOrderSignature, getWorkOrderSignatureFile, type WorkOrderSignature } from '@/api/warehouse/evidence'
import { listReacquisitions, requestReacquisition, type ReacquisitionRef, type ReturnDetails } from '@/api/warehouse/returns'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, TextField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { saveReceiptFile } from './receiptFiles'
import { buildReacquisition, readyForReacquisition } from './reacquisitionDraft'
import { returnItemLabel } from './returnPresentation'

export function WarehouseReturnReacquisition({ details, reload }: { details: ReturnDetails; reload: () => void }) {
  const { can } = useCan(), id = details.returnCase.id, [page, setPage] = useState(0), [creating, setCreating] = useState(false)
  const loader = useCallback(() => listReacquisitions(id, page), [id, page]), result = useWarehouseQuery(loader)
  const mayRequest = can('inventory.return.manage') && can('inventory.approval.request') && (can('workorder.evidence.view') || can('workorder.order.field'))
  if (creating) return <ReacquisitionEditor details={details} onClose={() => setCreating(false)} onDone={reload} />
  return <section className="card stack" aria-label="Alih kepemilikan retur"><h2>Alih kepemilikan perangkat pelanggan</h2>
    <p>Perubahan menjadi milik ISP memerlukan bukti pelanggan dan keputusan petugas yang independen. Barang tetap di karantina sampai inspeksi dan reset untuk pelepasan selesai.</p>
    <WarehouseState {...result}>{data => <>
      {data.items.length ? data.items.map(item => <section className="stack" key={item.documentId}>
        <h3 style={{ overflowWrap: 'anywhere' }}>{item.code}</h3><p>Retur sumber revisi {item.sourceReturnRevision} · <WarehouseTime value={item.recordedAt} /></p>
        <p>Alasan: {item.reason}</p><p>Referensi persetujuan pelanggan: {item.titleTransferReference}</p>
        <p>{item.appliedReturnRevision === null ? 'Dokumen permintaan tersimpan. Periksa keputusan pada halaman persetujuan.' : `Alih kepemilikan sudah dicatat pada retur revisi ${item.appliedReturnRevision}. Ikuti kondisi dan lokasi terbaru pada retur.`}</p>
        <Link to={`/warehouse/approvals?sourceDocumentId=${encodeURIComponent(item.documentId)}`}>Buka persetujuan {item.code}</Link>
      </section>) : <p>Belum ada permintaan alih kepemilikan pada halaman ini.</p>}
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
      {readyForReacquisition(details) && <><Button disabled={!mayRequest} onClick={() => setCreating(true)}>Siapkan alih kepemilikan</Button>
        {!mayRequest && <p className="muted">Pengajuan memerlukan izin kelola retur, ajukan persetujuan, dan baca bukti WO asal.</p>}
        {data.totalElements > 0 && <p className="muted">Periksa permintaan tersimpan sebelum membuat yang baru. Permintaan baru tetap memerlukan keputusan independen.</p>}</>}
    </>}</WarehouseState>
  </section>
}
function ReacquisitionEditor({ details, onClose, onDone }: { details: ReturnDetails; onClose: () => void; onDone: () => void }) {
  const workOrderId = details.references.assetOrigin!.workOrderId
  const loader = useCallback(() => getWorkOrderSignature(workOrderId), [workOrderId]), result = useWarehouseQuery(loader)
  return <section className="card stack"><h2>Siapkan alih kepemilikan</h2><p>{details.references.code} · {returnItemLabel(details.references.item)}</p>
    <p>Bukti diambil dari WO pemasangan asal perangkat. Pastikan tanda tangan dan referensi persetujuan pelanggan mendukung perubahan kepemilikan ini.</p>
    <WarehouseState {...result}>{signature => signature ? <ReacquisitionForm details={details} signature={signature} onDone={onDone} />
      : <p role="status">WO pemasangan asal belum memiliki tanda tangan aktif. Lengkapi bukti pada WO tersebut lalu muat ulang.</p>}</WarehouseState>
    <div className="row wrap"><Button onClick={result.reload}>Muat ulang bukti</Button><Button onClick={onClose}>Batal alih kepemilikan</Button></div>
  </section>
}
function ReacquisitionForm({ details, signature, onDone }: { details: ReturnDetails; signature: WorkOrderSignature; onDone: () => void }) {
  const [reason, setReason] = useState(''), [reference, setReference] = useState(''), [confirmed, setConfirmed] = useState(false)
  const [error, setError] = useState(''), [downloading, setDownloading] = useState(false), [operation, setOperation] = useState<WarehouseCommand<ReacquisitionRef> | null>(null)
  async function download() {
    setDownloading(true); setError('')
    try { saveReceiptFile(await getWorkOrderSignatureFile(signature), `tanda-tangan-${signature.revisionId}.${signature.contentType.split('/')[1]}`) }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Bukti tidak dapat dibaca.'); setConfirmed(false) }
    finally { setDownloading(false) }
  }
  function prepare(event: FormEvent) {
    event.preventDefault()
    try { setOperation(requestReacquisition(details.returnCase.id, buildReacquisition(details, signature, reason, reference, confirmed))); setError('') }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa permintaan alih kepemilikan.') }
  }
  return <><form className="stack" aria-label="Permintaan alih kepemilikan" onSubmit={prepare}>
    <p>Penanda tangan: {signature.signerName} · <WarehouseTime value={signature.signedAt} /></p><p>Dicatat oleh: {signature.signedByName ?? 'petugas WO'}</p>
    <Button type="button" disabled={downloading} onClick={() => void download()}>{downloading ? 'Mengunduh bukti…' : 'Unduh tanda tangan asal'}</Button>
    <TextField label="Alasan alih kepemilikan" value={reason} required maxLength={500} onChange={(_, data) => setReason(data.value)} />
    <TextField label="Referensi persetujuan alih kepemilikan" value={reference} required maxLength={500} onChange={(_, data) => setReference(data.value)} />
    <Checkbox label="Saya telah memeriksa bukti dan persetujuan pelanggan untuk alih kepemilikan ini" checked={confirmed} onChange={(_, data) => setConfirmed(data.checked === true)} />
    {error && <p role="alert" className="error">{error}</p>}<Button variant="primary" type="submit" disabled={!confirmed || downloading}>Tinjau alih kepemilikan</Button>
  </form>
    {operation && <WarehouseCommandDialog title="Konfirmasi permintaan alih kepemilikan" confirmLabel="Simpan permintaan alih kepemilikan" command={operation} onDone={onDone} onReload={onDone} onClose={() => setOperation(null)}
      summary={<><p>{details.references.code} · Retur revisi {details.returnCase.revision} · {returnItemLabel(details.references.item)}</p>
        <p>Alasan: {reason}</p><p>Referensi: {reference}</p><p>Penanda tangan: {signature.signerName} · <WarehouseTime value={signature.signedAt} /></p>
        <p>Menyimpan dokumen untuk diajukan kepada petugas independen. Kepemilikan dan stok tersedia belum berubah.</p></>} />}
  </>
}
