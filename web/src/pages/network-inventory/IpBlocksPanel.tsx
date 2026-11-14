import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { networkInventory } from '@/api/networkInventory'
import type { IpBlock, IpBlockInput, IpProbeJob } from '@/api/networkModels'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Button, TextareaField, TextField } from '@/components/atoms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { DataTable } from '@/components/organisms/DataTable'
import { B2BPagination, errorText, LoadError, useB2BPage } from '../b2b/shared'
import { VendorField, VendorName } from './shared'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

export function IpBlockForm({ onClose, onSaved }: { onClose: () => void; onSaved: (block: IpBlock) => void }) {
  const [input, setInput] = useState<IpBlockInput>({ cidr: '', vendorId: null, gateway: '', notes: '' })
  const [review, setReview] = useState<{ preview: Awaited<ReturnType<typeof networkInventory.previewBlock>>; command: WarehouseCommand<IpBlock> } | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  async function prepare(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('')
    try { const preview = await networkInventory.previewBlock(input); setReview({ preview, command: networkInventory.createBlock({ ...input, cidr: preview.cidr }) }) }
    catch (e) { setError(errorText(e)) } finally { setBusy(false) }
  }
  return <ResourceForm title="Tambah subnet IP public" onClose={onClose} onBack={() => setReview(null)}
    review={review && <WarehouseCommandDialog embedded title="Tinjau blok IP public" confirmLabel="Daftarkan subnet" command={review.command} onClose={() => setReview(null)} onDone={onSaved}
      summary={<><p><strong>{review.preview.cidr}</strong> · Subnet {review.preview.subnet}</p><p>{review.preview.firstAddress} — {review.preview.lastAddress}</p>
        <p>{review.preview.addressCount} alamat · {review.preview.hostCount} host · {review.preview.existingCount} IP lama dipertahankan</p>
        <dl className="resource-review-summary"><dt>Vendor</dt><dd>{input.vendorId ? <VendorName id={input.vendorId} /> : 'Infrastruktur sendiri'}</dd><dt>Gateway</dt><dd>{input.gateway || '—'}</dd><dt>Catatan</dt><dd>{input.notes || '—'}</dd></dl>
        <p>Monitoring dapat diaktifkan dari daftar subnet setelah tersimpan.</p></>} />}
    footer={<><Button onClick={onClose}>Batal</Button><Button form="public-ip-block-form" type="submit" variant="primary" disabled={busy}>Tinjau subnet</Button></>}>
    <form id="public-ip-block-form" className="stack" onSubmit={prepare}>
      <TextField label="Subnet / CIDR IPv4" required value={input.cidr} placeholder="157.15.122.0/23" onChange={(_, d) => setInput({ ...input, cidr: d.value })} />
      <VendorField label="Vendor blok IP" activeOnly value={input.vendorId} onChange={vendorId => setInput({ ...input, vendorId })} />
      <TextField label="Gateway blok" maxLength={15} value={input.gateway} onChange={(_, d) => setInput({ ...input, gateway: d.value })} />
      <TextareaField label="Catatan subnet" maxLength={2000} value={input.notes} onChange={(_, d) => setInput({ ...input, notes: d.value })} />
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}

export function IpBlocksPanel({ selected, onSelect, onJob }: { selected: IpBlock | null; onSelect: (block: IpBlock | null) => void; onJob: (job: IpProbeJob) => void }) {
  const { can } = useCan(), [page, setPage] = useState(0), [error, setError] = useState('')
  const [pending, setPending] = useState<WarehouseCommand<IpBlock> | null>(null)
  const loader = useCallback(() => networkInventory.blocks(page), [page]), result = useB2BPage(loader)
  async function ping(block: IpBlock) { try { onJob(await networkInventory.ping('blocks', block.id).execute()); setError('') } catch (e) { setError(errorText(e)) } }
  return <section className="card stack" aria-label="Subnet IP public"><div className="spread wrap"><h2>Subnet IP public</h2>{selected && <Button onClick={() => onSelect(null)}>Tampilkan semua IP</Button>}</div>
    <LoadError error={result.error || error} reload={result.reload} />
    <DataTable presentation="warehouse" loading={result.loading} rows={result.data?.content ?? []} rowKey={row => row.id} empty="Belum ada subnet. IP tunggal tetap tersedia di daftar."
      columns={[{ key: 'cidr', header: 'Subnet', cell: b => <Button onClick={() => onSelect(b)}>{b.cidr}{selected?.id === b.id ? ' · dipilih' : ''}</Button> },
        { key: 'count', header: 'Alamat / host', cell: b => b.addressCount + ' / ' + b.hostCount }, { key: 'vendor', header: 'Vendor', cell: b => b.vendorName || 'Infrastruktur sendiri' },
        { key: 'monitor', header: 'Monitoring', cell: b => b.monitoringEnabled ? 'Aktif · tiap 5 menit' : 'Nonaktif' }]}
      rowActions={b => [...(can('network.ip.check') ? [{ key: 'ping', label: 'Ping semua host', onClick: () => void ping(b) }] : []),
        ...(can('network.ip.manage') ? [{ key: 'monitor', label: b.monitoringEnabled ? 'Nonaktifkan monitoring' : 'Aktifkan monitoring', onClick: () => setPending(networkInventory.monitorBlock(b, !b.monitoringEnabled)) }] : [])]} />
    <B2BPagination data={result.data} onChange={setPage} />
    {pending && <WarehouseCommandDialog title="Pengaturan monitoring subnet" confirmLabel="Simpan monitoring" command={pending} onClose={() => setPending(null)} onDone={() => { setPending(null); result.reload() }} onReload={result.reload}
      summary={<p>Monitoring memeriksa seluruh host setiap lima menit dari server. Network dan broadcast dikecualikan.</p>} />}
  </section>
}

export function IpProbeProgress({ job, onDone }: { job: IpProbeJob; onDone: () => void }) {
  const loader = useCallback(() => networkInventory.probeJob(job.id), [job.id]), result = useWarehouseQuery(loader)
  const complete = result.state.status === 'ready' && result.state.data.state === 'COMPLETED', notified = useRef(false)
  useEffect(() => { if (complete) { if (!notified.current) { notified.current = true; onDone() }; return }; const timer = window.setInterval(result.reload, 2000); return () => window.clearInterval(timer) }, [complete, onDone, result.reload])
  const current = result.state.status === 'ready' ? result.state.data : job
  return <section className="card stack" aria-label="Progres ping"><p role="status">{complete ? 'Pemeriksaan selesai' : 'Memeriksa IP…'} · {current.completed}/{current.total} host</p>
    <progress value={current.completed} max={Math.max(1, current.total)} /><p>UP {current.up} · DOWN {current.down} · Error {current.errors}</p><LoadError error={result.state.status === 'error' ? errorText(result.state.error) : ''} reload={result.reload} />
  </section>
}
