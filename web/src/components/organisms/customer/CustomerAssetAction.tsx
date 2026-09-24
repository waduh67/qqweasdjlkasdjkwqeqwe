import { useCallback, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { acceptCustomerAsset, getAssetAssignment, getAssetJob, getAssetJobs, relocateCustomerAsset, removeCustomerAsset, type AssetHistory, type AssetJob } from '@/api/warehouse/customerAssets'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { CustomerAssetTopology } from './CustomerAssetTopology'
import { assetTopologyInput, emptyAssetTopology } from './customerAssetDraft'

export type CustomerAssetActionKind = 'handover' | 'remove' | 'relocate'
const label = { handover: 'Terima serah-terima pelanggan', remove: 'Lepas perangkat fisik', relocate: 'Pindah ODP' }
export function CustomerAssetAction({ row, kind, enabled, onDone, onClose }: { row: AssetHistory; kind: CustomerAssetActionKind; enabled: boolean; onDone: () => void; onClose: () => void }) {
  const [job, setJob] = useState<AssetJob | null>(null), [topology, setTopology] = useState(emptyAssetTopology)
  const [review, setReview] = useState<WarehouseCommand<unknown> | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null), active = useRef(false)
  const jobs = useCallback((_: string, page: number) => getAssetJobs(row.asset.customerId, page), [row.asset.customerId])
  const eligible = (job: AssetJob) => kind === 'handover' ? job.id === row.asset.workOrderId : job.status !== 'DONE' && job.workType === (kind === 'remove' ? 'DISMANTLE' : 'MIGRATION')
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!enabled || active.current || review) return
    try {
      if (!job || !eligible(job)) throw new Error('Pilih WO yang sesuai dengan tindakan ini.')
      const input = kind === 'relocate' ? assetTopologyInput(topology, true) : null
      active.current = true; setBusy(true); setError(null)
      const [fresh, current] = await Promise.all([getAssetAssignment(row.asset.customerId, row.asset.id), getAssetJob(row.asset.customerId, job.id)])
      if (JSON.stringify(fresh) !== JSON.stringify(row) || JSON.stringify(current) !== JSON.stringify(job)) throw new Error('Perangkat, penugasan, revisi, atau bukti berubah. Muat ulang untuk meninjaunya kembali.')
      setReview(kind === 'handover' ? acceptCustomerAsset(fresh, current) : kind === 'remove' ? removeCustomerAsset(fresh, current) : relocateCustomerAsset(fresh, current, input!))
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Tindakan belum dapat diperiksa.') }
    finally { active.current = false; setBusy(false) }
  }
  return <><form className="card stack" aria-label={label[kind]} onSubmit={event => void submit(event)}><h3>{label[kind]}</h3><p>{row.asset.sku.name} · {row.asset.serial}</p>
    <WarehousePicker label="WO tindakan perangkat" searchable={false} load={jobs} value={job} onChange={setJob} name={job => `${job.code} · ${job.workType} · ${job.status}`} eligible={eligible} disabled={!enabled || busy || !!review} />
    {job && kind !== 'relocate' && <>{job.signature ? <p>Bukti tanda tangan: {job.signature.signerName} · {new Date(job.signature.recordedAt).toLocaleString('id-ID')}.</p> : <p role="status">Lengkapi bukti tanda tangan pada WO terlebih dahulu.</p>}<Link to={`/work-orders/${job.id}#work-order-evidence`}>Buka bukti pekerjaan</Link></>}
    {kind === 'relocate' && <><p>Pindah ODP hanya mengubah sambungan. Perangkat tetap terpasang pada pelanggan ini.</p><CustomerAssetTopology required value={topology} onChange={setTopology} disabled={!enabled || busy || !!review} /></>}
    {kind === 'remove' && <p>Perangkat masuk karantina untuk diperiksa. Hak milik {row.asset.legalOwner === 'CUSTOMER' ? 'pelanggan tetap berlaku; lanjutkan servis/RMA pelanggan' : 'ISP tetap berlaku'}.</p>}
    {error && <p role="alert">{error}</p>}<div className="row wrap"><Button onClick={onClose} disabled={busy || !!review}>Batal</Button><Button type="submit" disabled={!enabled || busy || !!review || !job || (kind !== 'relocate' && !job.signature)}>Tinjau tindakan</Button></div>
  </form>{review && <WarehouseCommandDialog title={`Konfirmasi: ${label[kind]}`} command={review} disabled={!enabled} confirmLabel={label[kind]} onDone={onDone} onReload={onDone} onClose={() => setReview(null)} summary={<>
    <p>{row.asset.serial} · {job?.code} · {row.asset.ownershipMode === 'SALE' ? 'Perangkat jual' : 'Pinjam pakai'}.</p>
    {kind === 'handover' && <p>Pelanggan {job?.signature?.signerName} menerima perangkat. {row.asset.legalOwner === 'CUSTOMER' ? 'Hak milik pelanggan tetap berlaku.' : row.asset.ownershipMode === 'SALE' ? 'Hak milik beralih ke pelanggan.' : 'Hak milik tetap ISP dengan kewajiban pengembalian.'}</p>}
    {kind === 'remove' && <p>Pelepasan fisik menuju karantina, bukan stok siap pakai. Hak milik tetap {row.asset.legalOwner === 'CUSTOMER' ? 'pelanggan' : 'ISP'}.</p>}
    {kind === 'relocate' && <p>Sambungkan ke {topology.odp?.code} port {topology.port}. Tidak ada pengembalian gudang.</p>}
  </>} />}</>
}
