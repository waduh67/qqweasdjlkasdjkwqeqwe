import { useState } from 'react'
import { Link } from 'react-router-dom'
import { reviewWarehouseActivation, type WarehouseActivationReview } from '@/api/warehouse/referenceWorkflow'
import { useAuth } from '@/auth/useAuth'
import { Badge, Button } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseActivationEditor } from './WarehouseActivationEditor'
import { WarehouseDrainDialog } from './WarehouseDrainDialog'
import { useWarehouseWorkflow } from './WarehouseWorkflowContext'

const blockers: Readonly<Record<string, { readonly title: string; readonly hint: string; readonly links: readonly { readonly to: string; readonly label: string }[] }>> = {
  SAFETY_NOT_ENFORCED: { title: 'Rekonsiliasi data belum selesai', hint: 'Verifikasi asal, satuan dan pemegang stok, lalu selesaikan aktivasi keamanan gudang.', links: [{ to: '/warehouse/provenance', label: 'Rekonsiliasi gudang lama' }] },
  WORKFLOW_NOT_DRAINING: { title: 'Perpindahan belum dimulai', hint: 'Mulai perpindahan untuk menghentikan dokumen baru pada alur lama.', links: [] },
  OPEN_LEGACY_DOCUMENTS: { title: 'Dokumen gudang masih berjalan', hint: 'Selesaikan atau batalkan dokumen lama melalui alur asalnya.', links: [
    { to: '/warehouse/receipts', label: 'Penerimaan' }, { to: '/warehouse/requests', label: 'Permintaan & pengeluaran' },
    { to: '/warehouse/transfers', label: 'Transfer' }, { to: '/warehouse/returns', label: 'Retur' }, { to: '/warehouse/counts', label: 'Stock opname' },
  ] },
  OPEN_RESERVATIONS: { title: 'Material masih dicadangkan', hint: 'Selesaikan pengeluaran atau batalkan reservasi beserta penyiapan barang.', links: [{ to: '/warehouse/requests', label: 'Permintaan & pengeluaran' }] },
  OPEN_LEGACY_WORK_ORDERS: { title: 'Work order lama belum selesai', hint: 'Tutup pekerjaan, persetujuan dan pertanggungjawaban material yang masih berjalan.', links: [{ to: '/work-orders', label: 'Work order' }] },
  UNRESOLVED_FULFILLMENT: { title: 'Pemenuhan pekerjaan perlu diselesaikan', hint: 'Selesaikan atau rekonsiliasi pemenuhan yang belum diterapkan pada pekerjaan asal.', links: [{ to: '/work-orders', label: 'Work order' }] },
  UNRESOLVED_STOCK_OR_IDENTITIES: { title: 'Stok atau identitas belum terverifikasi', hint: 'Periksa asal stok, satuan, kepemilikan dan benturan serial sebelum aktivasi.', links: [{ to: '/warehouse/provenance', label: 'Rekonsiliasi gudang lama' }, { to: '/warehouse/stock', label: 'Stok & perangkat' }] },
  UNRESOLVED_HOLDERS_OR_TRANSIT: { title: 'Pemegang atau barang dalam perjalanan belum jelas', hint: 'Selesaikan transfer dan verifikasi pemegang material teknisi atau kendaraan.', links: [{ to: '/warehouse/transfers', label: 'Transfer' }, { to: '/warehouse/provenance', label: 'Rekonsiliasi pemegang' }] },
}
const workflowLabels = { LEGACY: 'Alur lama', DRAINING: 'Menyelesaikan alur lama', REFERENCE: 'Alur baru aktif' } as const

export function WarehouseTransitionPage() {
  const workflow = useWarehouseWorkflow()
  return <WarehouseState {...workflow}>{data => data.owner ? <OwnerTransition key={data.epoch} /> : <WarehouseDenied />}</WarehouseState>
}

function OwnerTransition() {
  const workflow = useWarehouseWorkflow(), { readOnly } = useAuth(), [draining, setDraining] = useState(false)
  if (workflow.state.status !== 'ready') return null
  const snapshot = workflow.state.data
  return <div className="stack"><PageHeader title="Perpindahan Gudang" subtitle="Selesaikan pekerjaan lama, periksa data, lalu aktifkan alur gudang dan teknisi yang baru." />
    <section className="card stack" aria-label="Alur gudang saat ini"><h2>{workflowLabels[snapshot.workflow]}</h2>
      <Badge tone={snapshot.workflow === 'REFERENCE' ? 'good' : 'neutral'}>{workflowLabels[snapshot.workflow]}</Badge>
      {snapshot.workflow === 'REFERENCE' ? <><p>Gudang dan teknisi sudah memakai alur baru. Stok terverifikasi tetap memakai ledger yang sama.</p><Link to="/warehouse/archive">Baca arsip gudang</Link></>
        : <><p>{snapshot.workflow === 'LEGACY' ? 'Mulai perpindahan setelah menyiapkan tim untuk menyelesaikan dokumen lama. Aktivasi alur baru dilakukan pada langkah terpisah.' : 'Dokumen baru pada alur lama sudah dihentikan. Selesaikan penghambat di bawah sebelum mengaktifkan alur baru.'}</p>
          <Link to="/warehouse/provenance">Buka rekonsiliasi gudang lama</Link></>}
      {readOnly && <p role="status">Langganan membatasi perubahan. Status dan pemeriksaan tetap dapat dibaca.</p>}
      <div className="row wrap"><Button onClick={workflow.reload}>Muat ulang status</Button>
        {snapshot.workflow === 'LEGACY' && <Button variant="primary" disabled={readOnly} onClick={() => setDraining(true)}>Mulai perpindahan</Button>}
      </div>
    </section>
    {snapshot.workflow !== 'REFERENCE' && <ActivationReadiness tenantId={snapshot.tenantId} epoch={snapshot.epoch} draining={snapshot.workflow === 'DRAINING'} />}
    {draining && <WarehouseDrainDialog snapshot={snapshot} disabled={readOnly} onClose={() => setDraining(false)} onDone={workflow.reload} />}
  </div>
}

function ActivationReadiness({ tenantId, epoch, draining }: { readonly tenantId: string; readonly epoch: number; readonly draining: boolean }) {
  const result = useWarehouseQuery(reviewWarehouseActivation, tenantId + ':' + epoch), workflow = useWarehouseWorkflow()
  const { readOnly } = useAuth(), [review, setReview] = useState<WarehouseActivationReview | null>(null)
  return <section className="stack" aria-label="Kesiapan aktivasi"><div className="spread wrap"><h2>Pemeriksaan kesiapan</h2><Button onClick={result.reload}>Periksa ulang kesiapan</Button></div>
    <WarehouseState {...result}>{data => data.tenantId !== tenantId || data.expectedEpoch !== epoch ? <div className="card stack" role="alert"><p>Status perpindahan sudah berubah. Muat ulang status sebelum melanjutkan.</p><Button onClick={workflow.reload}>Muat ulang status</Button></div> : <>
      <div className="card stack"><p>{data.documents} dokumen · {data.balances} posisi stok · {data.segments} identitas fisik · {data.claims} klaim identitas diperiksa.</p>
        {data.documentStates.length > 0 && <div className="row wrap">{data.documentStates.map(row => <span key={row.state}><WarehouseStatus status={row.state} /> {row.count}</span>)}</div>}
        <p role="status">{data.issues.length ? data.issues.length + ' penghambat harus diselesaikan.' : 'Tidak ada penghambat pada pemeriksaan ini.'}</p>
      </div>
      {data.issues.map(code => { const blocker = blockers[code]; return <section className="card stack" key={code} aria-label={blocker?.title ?? 'Penghambat tambahan'}>
        <h3>{blocker?.title ?? 'Penghambat tambahan'}</h3><p>{blocker?.hint ?? 'Selesaikan penghambat yang dilaporkan server sebelum aktivasi.'}</p>
        <div className="row wrap">{blocker?.links.map(link => <Link key={link.to} to={link.to}>{link.label}</Link>)}</div><p className="muted">Referensi pemeriksaan: {code}</p>
      </section> })}
      {draining && data.issues.length === 0 && <div className="card stack"><p>Data pada pemeriksaan ini siap. Server akan memeriksa ulang saat aktivasi disimpan.</p>
        <Button variant="primary" disabled={readOnly} onClick={() => setReview(data)}>Aktifkan alur gudang baru</Button></div>}
    </>}</WarehouseState>
    {review && <WarehouseActivationEditor review={review} disabled={readOnly} onClose={() => setReview(null)} onDone={() => { setReview(null); result.reload(); workflow.reload() }} />}
  </section>
}
