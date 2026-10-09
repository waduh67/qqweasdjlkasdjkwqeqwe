import { useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import { warehouseError } from '@/api/warehouse/errors'
import type { ReferencePhoto, ReferenceWorkDetail } from '@/api/warehouse/reference'
import { Button, Badge } from '@/components/atoms'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'

export function ReferencePhotoContent({ workId, photo }: { workId: string; photo: { readonly id: string; readonly slot: string } }) {
  const [url, setUrl] = useState<string | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState<unknown>(null)
  const active = useRef(true), pending = useRef(false), blobUrl = useRef<string | null>(null)
  useEffect(() => { active.current = true; return () => { active.current = false; if (blobUrl.current) URL.revokeObjectURL(blobUrl.current) } }, [])
  async function view() {
    if (pending.current) return
    pending.current = true
    setBusy(true); setError(null)
    try {
      const blob = await api.blob('/api/v2/work-orders/' + workId + '/evidence/' + photo.id + '/content')
      if (active.current) { const next = URL.createObjectURL(blob); if (blobUrl.current) URL.revokeObjectURL(blobUrl.current); blobUrl.current = next; setUrl(next) }
    } catch (caught) { if (active.current) setError(caught) }
    finally { pending.current = false; if (active.current) setBusy(false) }
  }
  return <div className="stack">
    {url ? <><img className="reference-work-photo" src={url} alt={'Bukti ' + photo.slot} /><a href={url} download={photo.slot}>Unduh foto {photo.slot}</a></> : <Button disabled={busy} onClick={() => void view()}>{busy ? 'Memuat foto…' : 'Lihat foto ' + photo.slot}</Button>}
    {error !== null && <p role="alert">{warehouseError(error)}</p>}
  </div>
}

export function ReferencePhotos({ detail, photos, enabled, upload }: {
  detail: ReferenceWorkDetail; photos: readonly ReferencePhoto[]; enabled: boolean; upload: (slot: string, file: File) => void
}) {
  const work = detail.workOrder
  return <section className="stack" aria-labelledby="reference-photos-title"><h2 id="reference-photos-title">2. Foto pekerjaan</h2>
    {work.type.photoSlots.length === 0 && <p>Jenis pekerjaan ini tidak memerlukan foto wajib.</p>}
    {work.type.photoSlots.map(slot => {
      const current = photos.find(photo => photo.current && photo.slot === slot && photo.assignmentGeneration === work.assignmentGeneration)
      return <div className="card stack" key={slot}><div className="spread wrap"><strong>{slot}</strong><Badge tone={current ? 'good' : 'warning'}>{current ? 'Sudah diunggah' : 'Belum diunggah'}</Badge></div>
        {current && <><p>{current.uploadedByName} · <WarehouseTime value={current.receivedAt} /></p><ReferencePhotoContent key={current.id} workId={work.id} photo={current} /></>}
        {enabled && <label className="stack"><span>{current ? 'Ganti foto ' : 'Unggah foto '}{slot}</span><input aria-label={(current ? 'Ganti foto ' : 'Unggah foto ') + slot} type="file" accept="image/jpeg,image/png,image/webp" capture="environment" onChange={event => {
          const file = event.currentTarget.files?.[0]; event.currentTarget.value = ''; if (file) upload(slot, file)
        }} /><span className="muted">JPEG, PNG atau WebP, maksimal 5 MB.</span></label>}
      </div>
    })}
    {photos.filter(photo => !photo.current).length > 0 && <details><summary>Foto dari penugasan atau unggahan sebelumnya</summary><div className="stack">{photos.filter(photo => !photo.current).map(photo => <div className="card stack" key={photo.id}><strong>{photo.slot}</strong><p>{photo.uploadedByName} · <WarehouseTime value={photo.receivedAt} /></p><ReferencePhotoContent workId={work.id} photo={photo} /></div>)}</div></details>}
  </section>
}
