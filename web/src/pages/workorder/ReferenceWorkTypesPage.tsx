import { useId, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Checkbox } from '@fluentui/react-components'
import { oneOf } from '@/api/warehouse/codec'
import { referenceTypes } from '@/api/warehouse/reference'
import { deleteWorkType, saveWorkType, WORK_KINDS, WORK_KIND_LABELS, type WorkType } from '@/api/warehouse/referenceWorkTypes'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useAuth } from '@/auth/useAuth'
import { Button, EmptyState, SelectField, StatusBadge, TextareaField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { useWarehouseWorkflow } from '@/pages/warehouse/WarehouseWorkflowContext'
import { typeDetails } from './referenceTypeDraft'
import { WorkDraftError } from './referenceWorkDraft'

export function ReferenceWorkTypesPage() {
  const workflow = useWarehouseWorkflow()
  return workflow.state.status === 'ready' && workflow.state.data.owner ? <OwnerTypes /> : <WarehouseDenied />
}
function OwnerTypes() {
  const result = useWarehouseQuery(referenceTypes), { readOnly } = useAuth()
  const [search, setSearch] = useState(''), [page, setPage] = useState(0)
  const [editing, setEditing] = useState<{ readonly type: WorkType | null } | null>(null)
  const [removing, setRemoving] = useState<{ readonly type: WorkType; readonly command: WarehouseCommand<WorkType> } | null>(null)
  const saved = () => { setEditing(null); setRemoving(null); setPage(0); result.reload() }
  return <div className="stack"><Link to="/work-orders">Kembali ke Work Order</Link>
    <PageHeader title="Jenis Pekerjaan" subtitle="Tentukan nama pekerjaan, foto wajib, dan kebutuhan material untuk penugasan baru." actions={<><Button onClick={result.reload}>Muat ulang</Button>{!readOnly && <Button variant="primary" onClick={() => setEditing({ type: null })}>Jenis baru</Button>}</>} />
    <p>Perubahan jenis berlaku untuk WO baru. Instruksi foto dan material pada WO yang sudah dibuat tetap tersimpan.</p>
    <TextField label="Cari jenis pekerjaan" value={search} onChange={(_, data) => { setSearch(data.value); setPage(0) }} />
    <WarehouseState {...result}>{types => {
      const rows = types.filter(type => !type.deleted && type.name.toLocaleLowerCase('id').includes(search.toLocaleLowerCase('id')))
      return <><DataTable presentation="warehouse" rows={rows.slice(page * 25, (page + 1) * 25)} rowKey={row => row.id} empty={<EmptyState title="Tidak ada jenis pada pencarian ini" />} columns={[
        { key: 'name', header: 'Jenis', cell: row => row.name, description: row => WORK_KIND_LABELS[row.workType] },
        { key: 'photos', header: 'Foto wajib', cell: row => row.photoSlots.join(', ') },
        { key: 'material', header: 'Material', cell: row => row.materialRequired ? 'Wajib dicatat' : 'Opsional' },
        { key: 'status', header: 'Status', cell: row => <StatusBadge status={row.active ? 'ACTIVE' : 'INACTIVE'} label={row.active ? 'Aktif' : 'Nonaktif'} tone={row.active ? 'good' : 'neutral'} /> },
        { key: 'actions', header: 'Tindakan', cell: row => !readOnly && <div className="row wrap"><Button aria-label={'Ubah ' + row.name} onClick={() => setEditing({ type: row })}>Ubah</Button><Button aria-label={'Hapus ' + row.name} onClick={() => setRemoving({ type: row, command: deleteWorkType(row) })}>Hapus</Button></div> },
      ]} /><WarehousePagination page={page} size={25} total={rows.length} onChange={setPage} /></>
    }}</WarehouseState>
    {editing && !readOnly && <TypeEditor type={editing.type} onClose={() => setEditing(null)} onSaved={saved} />}
    {removing && <WarehouseCommandDialog title="Hapus jenis pekerjaan" command={removing.command} disabled={readOnly} confirmLabel="Hapus jenis" onClose={() => setRemoving(null)} onDone={saved} onReload={saved}
      summary={<><p><strong>{removing.type.name}</strong> · Revisi {removing.type.revision}</p><p>Jenis hanya dapat dihapus bila belum pernah dipakai WO. Nonaktifkan melalui Ubah jika jenis sudah digunakan.</p></>} />}
  </div>
}
function TypeEditor({ type, onClose, onSaved }: { readonly type: WorkType | null; readonly onClose: () => void; readonly onSaved: () => void }) {
  const formId = useId(), [name, setName] = useState(type?.name ?? ''), [kind, setKind] = useState<WorkType['workType']>(type?.workType ?? 'PSB')
  const [slots, setSlots] = useState(type?.photoSlots.join('\n') ?? ''), [material, setMaterial] = useState(type?.materialRequired ?? false), [active, setActive] = useState(type?.active ?? true)
  const [error, setError] = useState<string | null>(null), [operation, setOperation] = useState<WarehouseCommand<WorkType> | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault()
    try { setOperation(saveWorkType(type?.id ?? null, typeDetails({ name, slots, workType: kind, materialRequired: material, active, expectedRevision: type?.revision ?? 0 }))); setError(null) }
    catch (caught) { if (caught instanceof WorkDraftError) setError(caught.message); else throw caught }
  }
  return <ResourceForm editing={!!type} title={type ? 'Ubah jenis pekerjaan' : 'Jenis pekerjaan baru'} onClose={onClose} onBack={() => setOperation(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau jenis</Button></>}
    review={operation && <WarehouseCommandDialog embedded title="Simpan jenis pekerjaan" command={operation} confirmLabel="Simpan jenis" onDone={onSaved} onClose={() => setOperation(null)} onReload={onSaved}
      summary={<><p><strong>{name.trim()}</strong> · {WORK_KIND_LABELS[kind]} · {active ? 'Aktif' : 'Nonaktif'}</p><p>Foto wajib: {slots.split(/\r?\n/).map(slot => slot.trim()).join(', ')}</p><p>Material {material ? 'wajib dicatat' : 'opsional'}. WO yang sudah dibuat mempertahankan ketentuan sebelumnya.</p></>} />}>
    <form id={formId} className="stack" onSubmit={prepare}><TextField label="Nama jenis pekerjaan" required maxLength={200} value={name} onChange={(_, data) => setName(data.value)} />
      <SelectField label="Kategori pekerjaan" value={kind} onChange={(_, data) => setKind(oneOf(data.value, WORK_KINDS))}>{WORK_KINDS.map(value => <option key={value} value={value}>{WORK_KIND_LABELS[value]}</option>)}</SelectField>
      <TextareaField label="Nama foto wajib" required hint="Satu nama per baris. Isi 1–12 foto, misalnya Bukti kedatangan dan Hasil pekerjaan." value={slots} onChange={(_, data) => setSlots(data.value)} />
      <Checkbox label="Material wajib dicatat sebelum selesai" checked={material} onChange={(_, data) => setMaterial(data.checked === true)} />
      <Checkbox label="Jenis aktif untuk penugasan baru" checked={active} onChange={(_, data) => setActive(data.checked === true)} />
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
