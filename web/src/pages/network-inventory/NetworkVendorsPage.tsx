import { useCallback, useState } from 'react'
import { networkInventory } from '@/api/networkInventory'
import type { NetworkVendor, VendorInput } from '@/api/networkModels'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useCan } from '@/auth/useCan'
import { Badge, Button, SelectField, TextareaField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { ConfirmDialog } from '@/components/molecules/ConfirmDialog'
import { DataTable } from '@/components/organisms/DataTable'
import { FilterBar, FilterSearch, FilterSelect } from '@/components/organisms/ResourceFilters'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { B2BPagination, errorText, LoadError, useB2BPage } from '../b2b/shared'

function VendorForm({ vendor, writable, onClose, onDone }: { vendor: NetworkVendor | null; writable: boolean; onClose: () => void; onDone: () => void }) {
  const [input, setInput] = useState<VendorInput>(vendor ? { ...vendor, expectedRevision: vendor.revision } : { name: '', contact: '', notes: '', active: true, expectedRevision: 0 })
  const [review, setReview] = useState<WarehouseCommand<NetworkVendor> | null>(null)
  const update = <K extends keyof VendorInput>(key: K, value: VendorInput[K]) => setInput(v => ({ ...v, [key]: value }))
  const prepare = () => { if (writable) setReview(networkInventory.saveVendor(vendor?.id, input)) }
  return <ResourceForm title={vendor ? 'Vendor · ' + vendor.name : 'Tambah vendor'} editing={!!vendor} readOnly={!writable} onClose={onClose} onBack={() => setReview(null)} onReview={prepare} reviewAction={writable}
    review={review && <WarehouseCommandDialog embedded title="Tinjau vendor" command={review} disabled={!writable} summary={<dl className="resource-review-summary"><dt>Vendor</dt><dd>{input.name}</dd><dt>Kontak</dt><dd>{input.contact || '—'}</dd><dt>Status</dt><dd>{input.active ? 'Aktif' : 'Nonaktif'}</dd><dt>Catatan</dt><dd>{input.notes || '—'}</dd></dl>} onDone={onDone} onClose={() => setReview(null)} />}>
    <form className="stack" onSubmit={e => { e.preventDefault(); prepare() }}>
      <TextField label="Nama vendor" required minLength={2} maxLength={200} value={input.name} disabled={!writable} onChange={(_, d) => update('name', d.value)} />
      <TextField label="Kontak vendor" maxLength={300} value={input.contact} disabled={!writable} onChange={(_, d) => update('contact', d.value)} />
      <SelectField label="Status vendor" value={input.active ? 'active' : 'inactive'} disabled={!writable} onChange={(_, d) => update('active', d.value === 'active')}><option value="active">Aktif</option><option value="inactive">Nonaktif</option></SelectField>
      <TextareaField label="Catatan vendor" maxLength={2000} value={input.notes} disabled={!writable} onChange={(_, d) => update('notes', d.value)} />
    </form>
  </ResourceForm>
}
export function NetworkVendorsPage() {
  const { can } = useCan(), writable = can('network.ip.manage')
  const [q, setQ] = useState(''), [page, setPage] = useState(0), [active, setActive] = useState('all')
  const [form, setForm] = useState<{ vendor: NetworkVendor | null } | null>(null), [deleting, setDeleting] = useState<NetworkVendor | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const load = useCallback(() => networkInventory.vendors({ q, page, ...(active === 'all' ? {} : { active: active === 'active' }) }), [q, page, active]), state = useB2BPage(load)
  const remove = async () => {
    if (!deleting || busy || !writable) return
    setBusy(true); setError('')
    try { await networkInventory.deleteVendor(deleting); setDeleting(null); state.reload() } catch (e) { setError(errorText(e)) } finally { setBusy(false) }
  }
  return <div className="stack">
    <PageHeader title="Vendor Jaringan" subtitle="Master provider mainlink, backup, dan IP public pelanggan." actions={writable && <Button variant="primary" onClick={() => setForm({ vendor: null })}>Tambah vendor</Button>} />
    <FilterBar label="Filter vendor jaringan" search={<FilterSearch label="Cari vendor" value={q} onChange={q => { setQ(q); setPage(0) }} />}>
      <FilterSelect label="Status vendor" value={active} defaultValue="all" onChange={value => { setActive(value); setPage(0) }}><option value="all">Semua status</option><option value="active">Aktif</option><option value="inactive">Nonaktif</option></FilterSelect>
    </FilterBar>
    <LoadError error={state.error} reload={state.reload} />
    <DataTable presentation="warehouse" aria-label="Vendor jaringan" loading={state.loading} rows={state.data?.content ?? []} rowKey={v => v.id} empty={state.error ? <></> : 'Belum ada vendor yang sesuai.'} columns={[{ key: 'name', header: 'Vendor', cell: v => v.name, description: v => v.contact, onCellClick: v => setForm({ vendor: v }) }, { key: 'status', header: 'Status', cell: v => <Badge>{v.active ? 'Aktif' : 'Nonaktif'}</Badge> }, { key: 'notes', header: 'Catatan', cell: v => v.notes || '—' }]} rowActions={v => [{ key: 'open', label: 'Buka vendor', onClick: () => setForm({ vendor: v }) }, ...(writable ? [{ key: 'delete', label: 'Hapus vendor', onClick: () => { setError(''); setDeleting(v) } }] : [])]} />
    <B2BPagination data={state.data} onChange={setPage} />
    {form && <VendorForm vendor={form.vendor} writable={writable} onClose={() => setForm(null)} onDone={() => { setForm(null); state.reload() }} />}
    {deleting && <ConfirmDialog danger busy={busy} title="Hapus vendor?" message={<><p>{deleting.name} hanya bisa dihapus jika belum dipakai IP atau koneksi pelanggan. Vendor yang masih dipakai dapat dinonaktifkan.</p>{error && <p role="alert" className="error">{error}</p>}</>} confirmLabel="Hapus vendor" onClose={() => setDeleting(null)} onConfirm={() => void remove()} />}
  </div>
}
