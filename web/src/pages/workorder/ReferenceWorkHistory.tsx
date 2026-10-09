import type { ReferenceWorkDetail } from '@/api/warehouse/reference'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { ReferencePhotoContent } from './ReferencePhotos'

const actions: Readonly<Record<string, string>> = { CREATE: 'Tugas dibuat', UPDATE: 'Instruksi diperbarui', ASSIGN: 'Teknisi ditugaskan', DISPATCH: 'Teknisi ditugaskan', REASSIGN: 'Teknisi diganti', PENDING: 'Pekerjaan dilanjutkan', BLOCKED: 'Kendala dicatat', COMPLETE: 'Pekerjaan selesai', CANCEL: 'Tugas dibatalkan', PHOTO: 'Foto diunggah' }
export function ReferenceWorkHistory({ detail }: { detail: ReferenceWorkDetail }) {
  const completed = detail.completion
  return <section className="stack" aria-labelledby="reference-history-title"><h2 id="reference-history-title">Riwayat pekerjaan</h2>
    {completed && <div className="card stack"><h3>Hasil pekerjaan tersimpan</h3><p>Selesai pada <WarehouseTime value={completed.completedAt} /></p><p className="reference-work-notes">{completed.notes || 'Tanpa catatan tambahan.'}</p>
      <DataTable presentation="warehouse" rows={completed.materials} rowKey={row => row.lineId} empty={<p>Tidak ada material terpakai.</p>} columns={[
        { key: 'item', header: 'Material terpakai', cell: row => row.skuName, description: row => [row.serial, row.mac].filter(Boolean).join(' · ') },
        { key: 'amount', header: 'Jumlah', cell: row => <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> },
      ]} />
      {completed.photos.map(photo => <div className="stack" key={photo.id}><strong>{photo.slot}</strong><ReferencePhotoContent workId={detail.workOrder.id} photo={photo} /></div>)}
    </div>}
    <ol className="stack">{detail.timeline.map(event => <li key={event.id}><strong>{actions[event.action] ?? event.action}</strong><p>{event.actorName} · <WarehouseTime value={event.recordedAt} /></p>{event.notes && <p className="reference-work-notes">{event.notes}</p>}</li>)}</ol>
  </section>
}
