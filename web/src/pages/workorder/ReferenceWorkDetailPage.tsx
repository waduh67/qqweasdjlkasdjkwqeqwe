import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { captureCommandSession, type WarehouseCommand } from '@/api/warehouse/transport'
import { completeReferenceWork, getReferenceWork, readWorkflow, referencePhotos, referenceProgress, uploadReferencePhoto, type ReferenceWorkDetail } from '@/api/warehouse/reference'
import { warehouseError } from '@/api/warehouse/errors'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button, TextareaField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { useFieldConnection } from '@/hooks/useFieldConnection'
import { ReferenceWorkStatus } from './ReferenceWorkList'
import { ReferencePhotos } from './ReferencePhotos'
import { ReferenceMaterialsEditor } from './ReferenceMaterialsEditor'
import { ReferenceWorkHistory } from './ReferenceWorkHistory'
import { ReferenceWorkEditor } from './ReferenceWorkEditor'
import { ReferenceWorkAssignment } from './ReferenceWorkAssignment'
import { assertCompletionEvidence, completionMaterials, freshOwnPosition, ReferenceDraftError, type ReferenceMaterialDraft } from './referenceCompletionDraft'

type Review = { readonly title: string; readonly summary: ReactNode; readonly command: WarehouseCommand<unknown>; readonly label: string }
export function ReferenceWorkDetailPage({ field = true }: { field?: boolean }) {
  const { id = '' } = useParams(), { user } = useAuth()
  return <ReferenceWorkDetail key={`${id}:${user?.id}:${user?.tenantId}`} id={id} field={field} />
}
function ReferenceWorkDetail({ id, field }: { id: string; field: boolean }) {
  const { user, readOnly, refreshProfile } = useAuth(), online = useFieldConnection()
  const { can } = useCan(), [editing, setEditing] = useState(false), [assigning, setAssigning] = useState(false)
  const [notes, setNotes] = useState(''), [reason, setReason] = useState(''), [rows, setRows] = useState<readonly ReferenceMaterialDraft[]>([])
  const [review, setReview] = useState<Review | null>(null), [preparing, setPreparing] = useState(false), [error, setError] = useState<unknown>(null)
  const working = useRef(false)
  const load = useCallback(async () => {
    await refreshProfile()
    const [detail, photos] = await Promise.all([getReferenceWork(id), referencePhotos(id)])
    return { detail, photos }
  }, [id, refreshProfile])
  const result = useWarehouseQuery(load, `${user?.id}:${user?.tenantId}`)
  const { reload } = result
  const connected = useRef(online)
  useEffect(() => {
    if (online && !connected.current) reload()
    connected.current = online
  }, [online, reload])
  const base = field ? '/my-work-orders' : '/work-orders'

  async function prepare(detail: ReferenceWorkDetail, build: (fresh: ReferenceWorkDetail) => Promise<Review> | Review) {
    if (working.current || review || !online || readOnly) return
    working.current = true; setPreparing(true); setError(null)
    const check = captureCommandSession()
    try {
      await refreshProfile()
      const [fresh, workflow] = await Promise.all([getReferenceWork(id), readWorkflow()])
      check()
      if (workflow.workflow !== 'REFERENCE' || fresh.workOrder.technicianId !== user?.id || fresh.workOrder.revision !== detail.workOrder.revision || fresh.workOrder.assignmentGeneration !== detail.workOrder.assignmentGeneration) {
        result.reload(); throw new ReferenceDraftError('Tugas atau penugasan sudah berubah. Periksa data terbaru sebelum mengirim.')
      }
      const next = await build(fresh)
      check(); setReview(next)
    } catch (caught) { setError(caught) }
    finally { working.current = false; setPreparing(false) }
  }

  function upload(detail: ReferenceWorkDetail, slot: string, file: File) {
    if (file.size < 1 || file.size > 5 * 1024 * 1024 || !['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) { setError(new ReferenceDraftError('Pilih foto JPEG, PNG atau WebP berukuran maksimal 5 MB.')); return }
    void prepare(detail, fresh => ({ title: 'Unggah foto ' + slot, label: 'Unggah foto', command: uploadReferencePhoto(id, fresh.workOrder.revision, slot, file),
      summary: <><p>{fresh.workOrder.code} · {fresh.workOrder.title}</p><p>{slot} · {file.name} · {Math.ceil(file.size / 1024)} KB</p><p>Foto ini menjadi bukti untuk penugasan Anda saat ini.</p></> }))
  }

  function complete(detail: ReferenceWorkDetail) {
    void prepare(detail, async fresh => {
      const photos = await referencePhotos(id)
      assertCompletionEvidence(fresh, photos)
      const currentRows = await Promise.all(rows.map(async row => ({ ...row, source: row.source ? await freshOwnPosition(row.source) : null })))
      const materials = completionMaterials(currentRows, fresh.workOrder.type.materialRequired)
      if (notes.length > 1000) throw new ReferenceDraftError('Catatan maksimal 1.000 karakter.')
      setRows(currentRows)
      return { title: 'Selesaikan pekerjaan', label: 'Kirim hasil dan selesai', command: completeReferenceWork(id, fresh.workOrder.revision, notes.trim(), materials), summary: <>
        <p><strong>{fresh.workOrder.code} · {fresh.workOrder.title}</strong></p><p>Foto wajib sudah lengkap. Material berikut langsung dicatat sebagai terpakai:</p>
        {currentRows.length === 0 ? <p>Tanpa material terpakai.</p> : <ul>{currentRows.map((row, index) => <li key={row.key}>{row.source?.skuName}{row.source?.serial && ' · ' + row.source.serial} · {row.source && <WarehouseQuantity value={materials[index].quantityBase} unit={row.source.baseUnit} />}</li>)}</ul>}
        <p className="reference-work-notes">{notes.trim() || 'Tanpa catatan tambahan.'}</p><p>Setelah terkirim, hasil pekerjaan tersimpan di riwayat.</p>
      </> }
    })
  }

  function progress(detail: ReferenceWorkDetail, state: 'PENDING' | 'BLOCKED') {
    if (state === 'BLOCKED' && !reason.trim()) { setError(new ReferenceDraftError('Tuliskan kendala yang perlu ditangani.')); return }
    void prepare(detail, fresh => ({ title: state === 'BLOCKED' ? 'Laporkan kendala' : 'Lanjutkan pekerjaan', label: 'Simpan status',
      command: referenceProgress(id, fresh.workOrder.revision, state, reason.trim()), summary: <><p>{fresh.workOrder.code} · {fresh.workOrder.title}</p><p className="reference-work-notes">{reason.trim() || 'Kendala selesai, pekerjaan dilanjutkan.'}</p></> }))
  }

  return <div className="stack"><Link to={base}>Kembali ke {field ? 'Tugas Saya' : 'Work Order'}</Link>
    {!online && <div className="card" role="status">Offline. Data tugas dan stok adalah hasil baca terakhir. Draf di halaman ini belum dikirim. Hubungkan internet untuk memeriksa ulang tugas dan stok; draf hilang bila meninggalkan halaman.</div>}
    {readOnly && <p role="status">Akun sedang baca saja. Hasil pekerjaan belum dapat dikirim.</p>}
    {error !== null && <p role="alert" className="error">{error instanceof ReferenceDraftError ? error.message : warehouseError(error)}</p>}
    {preparing && <p role="status">Memeriksa akses, tugas dan material terbaru…</p>}
    <WarehouseState {...result}>{({ detail, photos }) => {
      const work = detail.workOrder, active = work.state === 'PENDING' || work.state === 'BLOCKED'
      const assigned = work.technicianId === user?.id
      const enabled = assigned && active && online && !readOnly && !preparing && !review
      const operatorEnabled = !field && active && online && !readOnly && !preparing && !review
      const closeEditor = () => { setEditing(false); setAssigning(false); result.reload() }
      return <><PageHeader title={work.title} subtitle={work.code + ' · ' + work.type.name} actions={<>
        <Button disabled={!online || preparing || !!review} onClick={result.reload}>Muat ulang</Button>
        {operatorEnabled && can('workorder.order.update') && <Button onClick={() => setEditing(true)}>Ubah rincian</Button>}
        {operatorEnabled && can('workorder.order.assign') && <Button onClick={() => setAssigning(true)}>Ganti teknisi</Button>}
      </>} />
        <div className="spread wrap"><ReferenceWorkStatus state={work.state} /><span>Teknisi: {work.technicianName}</span>{work.scheduledAt && <span>Jadwal: <WarehouseTime value={work.scheduledAt} /></span>}</div>
        {detail.overdue && active && <p role="status">Tugas melewati batas aktivitas. Perbarui hasil atau laporkan kendala.</p>}
        <section className="card stack"><h2>1. Instruksi pekerjaan</h2><p className="reference-work-notes">{work.description || 'Belum ada instruksi tambahan.'}</p>{work.blockedReason && <p className="reference-work-notes">Kendala: {work.blockedReason}</p>}</section>
        <ReferencePhotos detail={detail} photos={photos} enabled={enabled} upload={(slot, file) => upload(detail, slot, file)} />
        {assigned && active && <><ReferenceMaterialsEditor rows={rows} onChange={setRows} enabled={enabled} required={work.type.materialRequired} />
          <section className="stack"><h2>4. Selesaikan pekerjaan</h2><TextareaField label="Catatan hasil pekerjaan" value={notes} maxLength={1000} disabled={!enabled} onChange={(_, data) => setNotes(data.value)} /><Button variant="primary" disabled={!enabled} onClick={() => complete(detail)}>Periksa dan selesaikan</Button></section>
          <details><summary>Ada kendala atau perlu melanjutkan pekerjaan?</summary><div className="stack"><TextareaField label="Catatan kendala" value={reason} maxLength={1000} disabled={!enabled} onChange={(_, data) => setReason(data.value)} />
            <div className="row wrap"><Button disabled={!enabled} onClick={() => progress(detail, 'BLOCKED')}>Laporkan kendala</Button>{work.state === 'BLOCKED' && <Button disabled={!enabled} onClick={() => progress(detail, 'PENDING')}>Lanjutkan pekerjaan</Button>}</div>
          </div></details>
        </>}
        <ReferenceWorkHistory detail={detail} />
        {editing && operatorEnabled && can('workorder.order.update') && <ReferenceWorkEditor work={work} customerLocked={detail.customerLocked} onClose={() => setEditing(false)} onSaved={closeEditor} onReload={closeEditor} />}
        {assigning && operatorEnabled && can('workorder.order.assign') && <ReferenceWorkAssignment work={work} onClose={() => setAssigning(false)} onSaved={closeEditor} onReload={closeEditor} />}
      </>
    }}</WarehouseState>
    {review && <WarehouseCommandDialog title={review.title} summary={review.summary} command={review.command} confirmLabel={review.label} disabled={!online || readOnly} onDone={() => { setReview(null); result.reload() }} onClose={() => setReview(null)} onReload={() => { setReview(null); result.reload() }} />}
  </div>
}
