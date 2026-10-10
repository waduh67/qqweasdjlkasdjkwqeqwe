import { useCallback, useState } from 'react'
import { b2b, type B2BClient, type B2BInput, type B2BSetting } from '@/api/b2b'
import { useCan } from '@/auth/useCan'
import { Badge, Button, SelectField, TextareaField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { ConfirmDialog } from '@/components/molecules/ConfirmDialog'
import { B2BPagination, errorText, LoadError, useB2BPage } from './shared'

function SettingSummary({ value }: { value: B2BSetting }) {
  return <dl className="resource-review-summary"><dt>Client</dt><dd>{value.name}</dd><dt>Alamat</dt><dd>{value.address}</dd><dt>Kontak / PIC</dt><dd>{value.contact}</dd><dt>Teknisi NE</dt><dd>{value.technicianName}</dd><dt>Target</dt><dd>{value.target} visit / bulan</dd><dt>Status</dt><dd>{value.active ? 'Aktif' : 'Nonaktif'}</dd></dl>
}
function ClientForm({ client, onClose, onDone }: { client: B2BClient | null; onClose: () => void; onDone: () => void }) {
  const { can } = useCan(), writable = can('b2b.client.manage')
  const [input, setInput] = useState<B2BInput>(() => client ? { name: client.next.name, address: client.next.address, contact: client.next.contact, technicianId: client.next.technicianId, target: client.next.target, active: client.next.active, expectedRevision: client.revision } : { name: '', address: '', contact: '', technicianId: '', target: 4, active: true, expectedRevision: 0 })
  const [selectedName, setSelectedName] = useState(client?.next.technicianName ?? '')
  const [q, setQ] = useState(''), [n, setN] = useState(0)
  const load = useCallback(() => b2b.technicians(q, n), [q, n]), technicians = useB2BPage(load)
  const [review, setReview] = useState<WarehouseCommand<B2BClient> | null>(null)
  const [error, setError] = useState('')
  const update = <K extends keyof B2BInput>(key: K, value: B2BInput[K]) => setInput(v => ({ ...v, [key]: value }))
  const toReview = () => {
    if (!writable) return
    if (!input.technicianId) { setError('Pilih Teknisi NE aktif.'); return }
    setError(''); setReview(b2b.save(client?.id, { ...input, name: input.name.trim(), address: input.address.trim(), contact: input.contact.trim() }))
  }
  return <ResourceForm title={client ? 'Client B2B · ' + client.current.setting.name : 'Tambah client B2B'} editing={!!client} readOnly={!writable} onClose={onClose} onBack={() => setReview(null)} onReview={toReview} reviewAction={writable}
    review={review && <WarehouseCommandDialog embedded title="Tinjau client B2B" command={review} disabled={!writable} summary={<><p>{client ? 'Perubahan berlaku bulan berikutnya.' : 'Client ditambahkan pada bulan ini.'}</p><SettingSummary value={{ ...input, technicianName: selectedName }} /></>} onDone={onDone} onClose={() => setReview(null)} />}>
    <div className="stack">
      {client && <details><summary>Data bulan ini · tetap tersimpan</summary><SettingSummary value={client.current.setting} /></details>}
      <p className="muted">{client ? 'Pengaturan di bawah berlaku bulan berikutnya. Target dan penugasan bulan ini tetap.' : 'Tentukan Teknisi NE dan target bulanan. Satu hari menghitung satu visit.'}</p>
      <form className="stack" onSubmit={e => { e.preventDefault(); toReview() }}>
        <TextField label="Nama client" required minLength={2} maxLength={200} value={input.name} disabled={!writable} onChange={(_, d) => update('name', d.value)} />
        <TextareaField label="Alamat" required maxLength={2000} value={input.address} disabled={!writable} onChange={(_, d) => update('address', d.value)} />
        <TextField label="Kontak / PIC" required maxLength={300} value={input.contact} disabled={!writable} onChange={(_, d) => update('contact', d.value)} />
        <TextField label="Target visit per bulan" type="number" required min={1} max={31} value={String(input.target)} disabled={!writable} onChange={(_, d) => update('target', Number(d.value))} />
        <SelectField label="Status" value={input.active ? 'active' : 'inactive'} disabled={!writable} onChange={(_, d) => update('active', d.value === 'active')}><option value="active">Aktif</option><option value="inactive">Nonaktif</option></SelectField>
        <p>Teknisi NE terpilih: <strong>{selectedName || 'Belum dipilih'}</strong></p>
        {writable && <><TextField label="Cari Teknisi NE" value={q} onChange={(_, d) => { setQ(d.value); setN(0) }} />
          <LoadError error={technicians.error} reload={technicians.reload} />
          <DataTable presentation="warehouse" loading={technicians.loading} rows={technicians.data?.content ?? []} rowKey={r => r.id} columns={[{ key: 'name', header: 'Teknisi NE aktif', cell: r => r.name }, { key: 'select', header: 'Penugasan', cell: r => <Button aria-pressed={r.id === input.technicianId} onClick={() => { update('technicianId', r.id); setSelectedName(r.name) }}>{r.id === input.technicianId ? 'Terpilih' : 'Pilih'}</Button> }]} empty={technicians.error ? <></> : 'Tidak ada Teknisi NE aktif yang sesuai.'} />
          <B2BPagination data={technicians.data} onChange={setN} /></>}
        {error && <p role="alert" className="error">{error}</p>}
      </form>
    </div>
  </ResourceForm>
}
export function B2BClientsPage() {
  const { can } = useCan()
  const [q, setQ] = useState(''), [n, setN] = useState(0), [form, setForm] = useState<{ client: B2BClient | null } | null>(null)
  const [deleting, setDeleting] = useState<B2BClient | null>(null), [mutationError, setMutationError] = useState('')
  const [busy, setBusy] = useState(false)
  const load = useCallback(() => b2b.clients(q, n), [q, n]), state = useB2BPage(load)
  async function remove() {
    if (!deleting || busy) return
    setBusy(true)
    try { await b2b.delete(deleting); setDeleting(null); state.reload() } catch (e) { setMutationError(errorText(e)) } finally { setBusy(false) }
  }
  return <div className="stack"><PageHeader title="Client B2B" subtitle="Kelola client, penugasan Teknisi NE, dan target visit bulanan." actions={can('b2b.client.manage') && <Button variant="primary" onClick={() => setForm({ client: null })}>Tambah client B2B</Button>} />
    <TextField label="Cari client B2B" value={q} onChange={(_, d) => { setQ(d.value); setN(0) }} />
    <LoadError error={state.error} reload={state.reload} />{mutationError && <p role="alert" className="error">{mutationError}</p>}
    <DataTable presentation="warehouse" rows={state.data?.content ?? []} rowKey={r => r.id} loading={state.loading} empty={state.error ? <></> : 'Belum ada client B2B. Tambahkan client untuk menjadwalkan visit.'}
      columns={[{ key: 'name', header: 'Client bulan ini', cell: r => r.current.setting.name, description: r => r.current.setting.contact, onCellClick: r => setForm({ client: r }) },
        { key: 'ne', header: 'Teknisi NE', cell: r => r.current.setting.technicianName }, { key: 'target', header: 'Visit / target', cell: r => r.current.counted + ' / ' + r.current.setting.target },
        { key: 'status', header: 'Bulan ini', cell: r => <Badge>{r.current.setting.active ? 'Aktif' : 'Nonaktif'}</Badge> },
        { key: 'next', header: 'Bulan berikutnya', cell: r => r.next.technicianName + ' · ' + r.next.target + ' visit · ' + (r.next.active ? 'Aktif' : 'Nonaktif') }]}
      rowActions={r => [{ key: 'edit', label: 'Buka client', onClick: () => setForm({ client: r }) }, ...(can('b2b.client.manage') ? [{ key: 'delete', label: 'Hapus client', onClick: () => { setMutationError(''); setDeleting(r) } }] : [])]} />
    <B2BPagination data={state.data} onChange={setN} />
    {form && <ClientForm client={form.client} onClose={() => setForm(null)} onDone={() => { setForm(null); state.reload() }} />}
    {deleting && <ConfirmDialog danger busy={busy} title="Hapus client B2B?" message={<><p>{'Hapus ' + deleting.current.setting.name + '? Client dengan riwayat visit harus dinonaktifkan untuk bulan berikutnya.'}</p>{mutationError && <p className="error" role="alert">{mutationError}</p>}</>} confirmLabel="Hapus client" onClose={() => setDeleting(null)} onConfirm={() => void remove()} />}
  </div>
}
