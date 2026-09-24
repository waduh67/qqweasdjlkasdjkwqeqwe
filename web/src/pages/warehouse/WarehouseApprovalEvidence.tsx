import { useCallback, useEffect, useRef, useState } from 'react'
import { approvalAttachments, downloadApprovalAttachment, type ApprovalAttachment } from '@/api/warehouse/approvalReads'
import { Button } from '@/components/atoms'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { saveReceiptFile } from './receiptFiles'

export function WarehouseApprovalEvidence({ id }: { id: string }) {
  const [open, setOpen] = useState(false)
  return <section className="card stack" aria-label="Bukti persetujuan"><h2>Bukti pada pengajuan</h2>
    <p>Dokumen dan tanda tangan yang terikat ke pengajuan ini dapat diperiksa di sini. Referensi tertulis tercantum pada dokumen sumber.</p>
    {open ? <EvidenceFiles id={id} /> : <Button onClick={() => setOpen(true)}>Tampilkan bukti pengajuan</Button>}
  </section>
}
function EvidenceFiles({ id }: { id: string }) {
  const [page, setPage] = useState(0), loader = useCallback(() => approvalAttachments(id, page), [id, page]), result = useWarehouseQuery(loader)
  const [busy, setBusy] = useState<string | null>(null), [error, setError] = useState<string | null>(null)
  const [preview, setPreview] = useState<{ url: string; label: string } | null>(null)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview.url) }, [preview])
  async function view(file: ApprovalAttachment) {
    setBusy(file.id); setError(null); setPreview(null)
    try {
      const blob = await downloadApprovalAttachment(id, file)
      if (!mounted.current) return
      if (blob.type === 'application/pdf') saveReceiptFile(blob, `bukti-persetujuan-${file.id}.pdf`)
      else setPreview({ url: URL.createObjectURL(blob), label: file.signerLabel ? `Tanda tangan ${file.signerLabel}` : 'Bukti penerimaan pada pengajuan' })
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Bukti belum dapat dibaca. Muat ulang dan coba lagi.') }
    finally { setBusy(null) }
  }
  return <><Button onClick={result.reload}>Muat ulang bukti</Button><WarehouseState {...result}>{data => <>
    {data.items.length === 0 ? <p>Tidak ada lampiran pada halaman pengajuan ini. Periksa referensi bukti tertulis pada dokumen sumber.</p>
      : <ul className="stack">{data.items.map(file => <li key={file.id} className="stack">
        <p>{file.kind === 'SIGNATURE' ? `Tanda tangan · ${file.signerLabel ?? 'Penerima'}` : 'Bukti penerimaan'} · <WarehouseTime value={file.recordedAt} /></p>
        <Button disabled={busy !== null} onClick={() => void view(file)}>{busy === file.id ? 'Membaca bukti…' : file.contentType === 'application/pdf' ? 'Unduh PDF bukti' : 'Lihat gambar bukti'}</Button>
      </li>)}</ul>}
    <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState>
    {error && <p role="alert" className="error">{error}</p>}
    {preview && <figure className="stack"><img src={preview.url} alt={preview.label} style={{ maxWidth: '100%', maxHeight: 600, objectFit: 'contain' }} /><figcaption>{preview.label}</figcaption>
      <Button onClick={() => setPreview(null)}>Tutup gambar bukti</Button></figure>}
  </>
}
