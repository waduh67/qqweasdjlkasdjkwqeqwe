import { useCallback, useState } from 'react'
import { networkInventory } from '@/api/networkInventory'
import type { NetworkVendor, PublicIp, PublicIpInput } from '@/api/networkModels'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Badge, Button, TextareaField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { ConfirmDialog } from '@/components/molecules/ConfirmDialog'
import { DataTable } from '@/components/organisms/DataTable'
import { FilterBar, FilterPicker, FilterSearch, FilterSelect } from '@/components/organisms/ResourceFilters'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { B2BPagination, errorText, LoadError, useB2BPage } from '../b2b/shared'
import { ClientNetworkDetail, VendorField, VendorName } from './shared'

function IpForm({ ip, writable, onClose, onDone }: { ip: PublicIp | null; writable: boolean; onClose: () => void; onDone: () => void }) {
  const [input, setInput] = useState<PublicIpInput>(ip ? { address: ip.address, subnet: ip.subnet, gateway: ip.gateway, vendorId: ip.vendorId, notes: ip.notes, expectedRevision: ip.revision } : { address: '', subnet: '', gateway: '', vendorId: null, notes: '', expectedRevision: 0 })
  const [review, setReview] = useState<WarehouseCommand<PublicIp> | null>(null)
  const update = <K extends keyof PublicIpInput>(key: K, value: PublicIpInput[K]) => setInput(v => ({ ...v, [key]: value }))
  const prepare = () => { if (writable) setReview(networkInventory.saveIp(ip?.id, input)) }
  return <ResourceForm title={ip ? 'IP public · ' + ip.address : 'Tambah IP public'} editing={!!ip} readOnly={!writable} onClose={onClose} onBack={() => setReview(null)} onReview={prepare} reviewAction={writable}
    review={review && <WarehouseCommandDialog embedded title="Tinjau IP public" command={review} disabled={!writable} summary={<dl className="resource-review-summary"><dt>IP public</dt><dd>{input.address}</dd><dt>Subnet</dt><dd>{input.subnet || '—'}</dd><dt>Gateway</dt><dd>{input.gateway || '—'}</dd><dt>Vendor</dt><dd>{input.vendorId ? <VendorName id={input.vendorId} /> : 'Tanpa vendor'}</dd><dt>Catatan</dt><dd>{input.notes || '—'}</dd></dl>} onDone={onDone} onClose={() => setReview(null)} />}>
    <form className="stack" onSubmit={e => { e.preventDefault(); prepare() }}>
      {ip?.clientId && <p className="muted">Dipakai {ip.clientName}. Lepaskan alokasi melalui Client B2B sebelum mengganti alamat atau vendor.</p>}
      <TextField label="Alamat IP public" required maxLength={15} placeholder="157.15.122.100" value={input.address} disabled={!writable || !!ip?.clientId} onChange={(_, d) => update('address', d.value)} />
      <TextField label="Subnet mask" maxLength={15} placeholder="255.255.255.248" value={input.subnet} disabled={!writable} onChange={(_, d) => update('subnet', d.value)} />
      <TextField label="Gateway" maxLength={15} value={input.gateway} disabled={!writable} onChange={(_, d) => update('gateway', d.value)} />
      <VendorField label="Vendor IP" value={input.vendorId} activeOnly disabled={!writable || !!ip?.clientId} onChange={v => update('vendorId', v)} />
      <TextareaField label="Catatan IP" maxLength={2000} value={input.notes} disabled={!writable} onChange={(_, d) => update('notes', d.value)} />
    </form>
  </ResourceForm>
}
export function PublicIpPage() {
  const { can } = useCan(), writable = can('network.ip.manage')
  const [q, setQ] = useState(''), [page, setPage] = useState(0), [status, setStatus] = useState<'' | 'AVAILABLE' | 'ALLOCATED'>(''), [vendor, setVendor] = useState<NetworkVendor | null>(null)
  const [form, setForm] = useState<{ ip: PublicIp | null } | null>(null), [deleting, setDeleting] = useState<PublicIp | null>(null), [client, setClient] = useState<{ id: string; name: string } | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const vendorId = vendor?.id
  const load = useCallback(() => networkInventory.ips({ q, page, ...(status ? { status } : {}), ...(vendorId ? { vendorId } : {}) }), [q, page, status, vendorId]), state = useB2BPage(load)
  const loadVendors = useCallback(async (q: string, page: number) => {
    const { content, ...pagination } = await networkInventory.vendors({ q, page })
    return { ...pagination, items: content }
  }, [])
  const remove = async () => {
    if (!deleting || busy || !writable) return
    setBusy(true); setError('')
    try { await networkInventory.deleteIp(deleting); setDeleting(null); state.reload() } catch (e) { setError(errorText(e)) } finally { setBusy(false) }
  }
  return <div className="stack">
    <PageHeader title="Manajemen IP" subtitle="Lihat IP yang tersedia, terpakai oleh pelanggan, dan provider asalnya." actions={writable && <Button variant="primary" onClick={() => setForm({ ip: null })}>Tambah IP public</Button>} />
    <FilterBar label="Filter IP public" search={<FilterSearch label="Cari IP / pelanggan" value={q} onChange={q => { setQ(q); setPage(0) }} />}>
      <FilterSelect label="Status IP" value={status} onChange={value => { if (value === '' || value === 'AVAILABLE' || value === 'ALLOCATED') { setStatus(value); setPage(0) } }}><option value="">Semua IP</option><option value="AVAILABLE">Tersedia</option><option value="ALLOCATED">Terpakai</option></FilterSelect>
      <FilterPicker label="Vendor" load={loadVendors} value={vendor} name={vendor => vendor.name + (vendor.active ? '' : ' (nonaktif)')} onChange={vendor => { setVendor(vendor); setPage(0) }} />
    </FilterBar>
    <LoadError error={state.error} reload={state.reload} />
    <DataTable presentation="warehouse" aria-label="Daftar IP public" loading={state.loading} rows={state.data?.content ?? []} rowKey={ip => ip.id} empty={state.error ? <></> : 'Belum ada IP yang sesuai. Daftarkan IP public untuk mengalokasikannya ke client B2B.'} columns={[{ key: 'address', header: 'IP public', cell: ip => ip.address, description: ip => ip.subnet || undefined, onCellClick: ip => setForm({ ip }) }, { key: 'vendor', header: 'Vendor', cell: ip => ip.vendorName || 'Infrastruktur sendiri' }, { key: 'status', header: 'Status', cell: ip => <Badge>{ip.clientId ? 'Terpakai' : 'Tersedia'}</Badge> }, { key: 'client', header: 'Pelanggan', cell: ip => ip.clientId ? <Button onClick={() => { if (ip.clientId) setClient({ id: ip.clientId, name: ip.clientName || 'Client B2B' }) }}>{ip.clientName || 'Client B2B'}</Button> : 'Belum dialokasikan' }, { key: 'gateway', header: 'Gateway', cell: ip => ip.gateway || '—' }]} rowActions={ip => [{ key: 'open', label: 'Buka IP', onClick: () => setForm({ ip }) }, ...(writable && !ip.clientId ? [{ key: 'delete', label: 'Hapus IP', onClick: () => { setError(''); setDeleting(ip) } }] : [])]} />
    <B2BPagination data={state.data} onChange={setPage} />
    {form && <IpForm ip={form.ip} writable={writable} onClose={() => setForm(null)} onDone={() => { setForm(null); state.reload() }} />}
    {client && <ClientNetworkDetail clientId={client.id} name={client.name} onClose={() => setClient(null)} />}
    {deleting && <ConfirmDialog danger busy={busy} title="Hapus IP public?" message={<><p>Hapus {deleting.address} dari daftar IP? IP yang sedang dipakai pelanggan tidak dapat dihapus.</p>{error && <p role="alert" className="error">{error}</p>}</>} confirmLabel="Hapus IP" onClose={() => setDeleting(null)} onConfirm={() => void remove()} />}
  </div>
}
