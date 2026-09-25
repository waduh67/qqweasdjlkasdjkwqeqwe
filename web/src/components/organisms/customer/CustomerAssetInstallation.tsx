import { useCallback, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { deployCustomerAsset, getAssetAssignment, getAssetJob, getAssetJobs, getAssetSource, getAssetSources, type AssetHistory, type AssetJob, type AssetObservation, type AssetSource } from '@/api/warehouse/customerAssets'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { sameSerialIdentity } from '@/api/warehouse/serialIdentity'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button, SelectField } from '@/components/atoms'
import { MaterialScanner } from '@/components/organisms/warehouse/MaterialScanner'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { useFieldConnection } from '@/hooks/useFieldConnection'
import { CustomerAssetTopology } from './CustomerAssetTopology'
import { assetTopologyInput, emptyAssetTopology } from './customerAssetDraft'

export function CustomerAssetInstallation(props: { customerId: string; previous?: AssetHistory; observation?: AssetObservation; onDone: () => void; onClose: () => void }) {
  const { user, readOnly } = useAuth(), { can } = useCan()
  if (!user || !can('customer.onu.view') || !can('customer.onu.assign') || !can('workorder.order.field') || (props.observation && !can('monitoring.provisioning.manage'))) return <p role="status">Pemasangan memerlukan teknisi yang ditugaskan dan izin perangkat pelanggan.</p>
  return <Installation key={`${user.tenantId}:${user.id}:${props.customerId}:${props.previous?.asset.id ?? ''}`} {...props} readOnly={readOnly} />
}
function Installation({ customerId, previous, observation, onDone, onClose, readOnly }: {
  customerId: string; previous?: AssetHistory; observation?: AssetObservation; onDone: () => void; onClose: () => void; readOnly: boolean
}) {
  const [job, setJob] = useState<AssetJob | null>(null), [source, setSource] = useState<AssetSource | null>(null), [mode, setMode] = useState<'LOAN' | 'SALE'>('LOAN')
  const [observed, setObserved] = useState(''), [topology, setTopology] = useState(emptyAssetTopology), [review, setReview] = useState<WarehouseCommand<unknown> | null>(null)
  const [error, setError] = useState<string | null>(null), [busy, setBusy] = useState(false), active = useRef(false), online = useFieldConnection()
  const jobs = useCallback((_: string, page: number) => getAssetJobs(customerId, page), [customerId])
  const sources = useCallback((_: string, page: number) => getAssetSources(customerId, job!.id, page), [customerId, job])
  const disabled = busy || !!review || readOnly
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (active.current || !online || disabled) return
    try {
      if (!job || !source || !sameSerialIdentity(observed, source.source.serial) || (observation && !sameSerialIdentity(observation.serial, source.source.serial))) throw new Error('Pilih perangkat yang sudah diterima dan cocokkan serial fisiknya.')
      const inputTopology = assetTopologyInput(topology)
      active.current = true; setBusy(true); setError(null)
      const [currentJob, currentSource, currentPrevious] = await Promise.all([getAssetJob(customerId, job.id), getAssetSource(customerId, job.id, source.id), previous ? getAssetAssignment(customerId, previous.asset.id) : Promise.resolve(undefined)])
      if (JSON.stringify(currentJob) !== JSON.stringify(job) || JSON.stringify(currentSource) !== JSON.stringify(source) || (previous && JSON.stringify(currentPrevious) !== JSON.stringify(previous))) throw new Error('WO, bukti, sumber, atau perangkat lama berubah. Muat ulang sebelum memasang.')
      setReview(deployCustomerAsset(currentJob, currentSource, mode, inputTopology, currentPrevious, observation))
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Pemasangan belum dapat diperiksa.') }
    finally { active.current = false; setBusy(false) }
  }
  return <><form className="card stack" aria-label={previous ? 'Ganti perangkat pelanggan' : 'Pasang perangkat pelanggan'} onSubmit={event => void submit(event)}>
    <h3>{previous ? `Ganti ${previous.asset.serial}` : 'Pasang perangkat dari gudang'}</h3>
    <p>Pilih perangkat yang sudah diakui penerimaannya oleh teknisi pada WO pelanggan ini.</p>
    {!online && <p role="status">Offline: isian masih draf di halaman ini. Pemasangan membutuhkan koneksi dan pemeriksaan ulang.</p>}
    <WarehousePicker label="WO pemasangan" searchable={false} load={jobs} value={job} onChange={value => { setJob(value); setSource(null); setObserved('') }} name={row => `${row.code} · ${row.workType} · ${row.status}`} eligible={row => row.status !== 'DONE' && row.workType === (previous ? 'MIGRATION' : 'PSB')} disabled={disabled || !online} />
    {job && <><Link to={`/my-materials?workOrderId=${job.id}`}>Periksa penerimaan di Material Saya</Link>
      {previous && !job.signature && <p role="status">Lengkapi bukti tanda tangan di WO sebelum mengganti perangkat.</p>}
      <WarehousePicker key={job.id} label="Perangkat yang sudah diterima" searchable={false} load={sources} value={source} onChange={value => { setSource(value); setMode(value?.ownershipModes[0] ?? 'LOAN'); setObserved('') }} name={row => `${row.source.serial} · ${row.source.sku.name} · ${row.source.issueCode}`} eligible={row => !observation || sameSerialIdentity(row.source.serial, observation.serial)} disabled={disabled || !online} />
    </>}
    {source && <><p>Sumber {source.source.issueCode} · {source.source.location.name} · {source.provenance === 'RECEIPT' ? 'Penerimaan gudang' : 'Saldo awal terverifikasi'}.</p>
      <MaterialScanner disabled={disabled} onScan={value => { setObserved(value.trim().toUpperCase()); setError(null) }} />
      {observed && <p role="status">Serial diperiksa: {observed}{!sameSerialIdentity(observed, source.source.serial) ? ' — tidak cocok' : ''}</p>}
      <SelectField label="Kepemilikan perangkat" value={mode} disabled={disabled} onChange={(_, data) => setMode(data.value as 'LOAN' | 'SALE')}>
        {source.ownershipModes.map(value => <option key={value} value={value}>{value === 'LOAN' ? 'Pinjam pakai — milik ISP' : 'Jual — hak milik beralih saat serah-terima diterima'}</option>)}
      </SelectField></>}
    <CustomerAssetTopology value={topology} onChange={setTopology} disabled={disabled || !online} />
    {error && <p role="alert">{error}</p>}<div className="row wrap"><Button onClick={onClose} disabled={busy || !!review}>Batal</Button><Button type="submit" disabled={!online || disabled || !source || (previous && !job?.signature)}>{busy ? 'Memeriksa sumber…' : 'Tinjau pemasangan'}</Button></div>
  </form>{review && <WarehouseCommandDialog title={previous ? 'Konfirmasi penggantian perangkat' : observation ? 'Konfirmasi provisi perangkat terdeteksi' : 'Konfirmasi pemasangan perangkat'} command={review} disabled={!online || readOnly} confirmLabel={previous ? 'Ganti perangkat' : 'Pasang perangkat'} onDone={onDone} onReload={onDone} onClose={() => setReview(null)} summary={<>
    <p>{job?.code} · {source?.source.sku.name} · {source?.source.serial} · {mode === 'LOAN' ? 'Pinjam pakai' : 'Jual'}.</p>
    <p>{topology.odp ? `${topology.odp.code} port ${topology.port}` : 'Belum ditempel ke port ODP.'}</p>
    {previous && <p>Perangkat {previous.asset.serial} dilepas fisik dan masuk pemeriksaan. Pemiliknya tetap {previous.asset.legalOwner === 'CUSTOMER' ? 'pelanggan' : 'ISP'}.</p>}
    <p>Persediaan teknisi berkurang satu setelah pemasangan diterima server. Serah-terima pelanggan dicatat setelahnya dengan bukti tanda tangan.</p>
  </>} />}</>
}
