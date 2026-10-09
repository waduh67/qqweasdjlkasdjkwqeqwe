import { useCallback, useId, useState, type FormEvent } from 'react'
import type { ReferenceWorkOrder } from '@/api/warehouse/reference'
import { assignmentTechnicians, type TechnicianChoice } from '@/api/warehouse/technicians'
import { createReferenceWork, getWorkArea, getWorkCustomer, updateReferenceWork, workAreas, workCustomers, workTypes, type WorkArea, type WorkCustomer } from '@/api/warehouse/referenceWorkManagement'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { oneOf } from '@/api/warehouse/codec'
import { warehouseError } from '@/api/warehouse/errors'
import { Button, SelectField, TextareaField, TextField } from '@/components/atoms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { localSchedule, PRIORITY_LABELS, workDetails, WorkDraftError, type WorkDraft, type WorkSeed } from './referenceWorkDraft'

type EditorProps = { readonly work?: ReferenceWorkOrder; readonly seed?: WorkSeed; readonly customerLocked?: boolean; readonly onClose: () => void; readonly onSaved: (work: ReferenceWorkOrder) => void; readonly onReload?: () => void }
export function ReferenceWorkEditor(props: EditorProps) {
  const { work, seed } = props
  const load = useCallback(async () => ({
    area: work ? await getWorkArea(work.areaId) : null,
    customer: work?.customerId || seed?.customerId ? await getWorkCustomer(work?.customerId || seed?.customerId || '') : null,
  }), [work, seed])
  const result = useWarehouseQuery(load)
  if (result.state.status !== 'ready') return <ResourceForm title={work ? 'Ubah rincian pekerjaan' : 'Work order baru'} loading onClose={props.onClose} onBack={props.onClose}
    footer={<Button onClick={props.onClose}>Batal</Button>}><WarehouseState {...result}>{() => null}</WarehouseState></ResourceForm>
  return <WorkEditorForm {...props} {...result.state.data} />
}
function WorkEditorForm({ work, seed, customerLocked = false, area: initialArea, customer: initialCustomer, onClose, onSaved, onReload }: EditorProps & { readonly area: WorkArea | null; readonly customer: WorkCustomer | null }) {
  const formId = useId(), [area, setArea] = useState(initialArea), [customer, setCustomer] = useState(initialCustomer)
  const [type, setType] = useState<ReferenceWorkOrder['type'] | null>(work?.type ?? null), [technician, setTechnician] = useState<TechnicianChoice | null>(null)
  const [draft, setDraft] = useState<WorkDraft>(() => ({ title: work?.title ?? seed?.title ?? '', description: work?.description ?? seed?.description ?? '', priority: work?.priority ?? 'NORMAL', schedule: localSchedule(work?.scheduledAt ?? null) }))
  const [error, setError] = useState<string | null>(null), [operation, setOperation] = useState<WarehouseCommand<ReferenceWorkOrder> | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      const details = workDetails(draft, { areaId: area?.id ?? null, customerId: customer?.id ?? null, previousSchedule: work?.scheduledAt ?? null })
      if (work) setOperation(updateReferenceWork(work.id, { ...details, expectedRevision: work.revision }))
      else {
        if (!type || !technician) throw new WorkDraftError('Pilih jenis pekerjaan dan teknisi NE atau FO.')
        setOperation(createReferenceWork({ ...details, typeId: type.id, technicianId: technician.id }))
      }
      setError(null)
    } catch (caught) { setError(caught instanceof WorkDraftError ? caught.message : warehouseError(caught)) }
  }
  return <ResourceForm editing={!!work} title={work ? 'Ubah rincian pekerjaan' : 'Work order baru'} onClose={onClose} onBack={() => setOperation(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau pekerjaan</Button></>}
    review={operation && <WarehouseCommandDialog embedded title={work ? 'Simpan rincian pekerjaan' : 'Buat penugasan'} confirmLabel={work ? 'Simpan rincian' : 'Buat work order'} command={operation} onDone={onSaved} onClose={() => setOperation(null)} onReload={onReload ?? onClose}
      summary={<><p><strong>{draft.title.trim()}</strong> · {type?.name}</p><p>Teknisi: {work?.technicianName ?? technician?.name}</p><p>Area: {area?.name} · Pelanggan: {customer?.name ?? 'Tanpa pelanggan'}</p><p>Prioritas: {PRIORITY_LABELS[draft.priority]} · Jadwal: {draft.schedule ? new Date(draft.schedule).toLocaleString('id-ID') : 'Belum dijadwalkan'}</p><p className="reference-work-notes">{draft.description || 'Tanpa instruksi tambahan.'}</p><p>{work ? 'Rincian disimpan pada revisi ' + work.revision + '. Jenis dan teknisi tetap pada penugasan saat ini.' : 'Tugas akan muncul di Tugas Saya milik teknisi yang dipilih.'}</p></>} />}>
    <form id={formId} className="stack" onSubmit={prepare}>
      {seed && <p>Draf dari peta: {seed.type === 'PSB' ? 'pasang baru' : 'perbaikan'}. Periksa jenis dan area sebelum membuat penugasan.</p>}
      {!work ? <><WarehousePicker label="Jenis pekerjaan" load={workTypes} value={type} name={item => item.name} onChange={setType} />
        {type && <p>Foto wajib: {type.photoSlots.join(', ')} · {type.materialRequired ? 'Material wajib dicatat' : 'Material opsional'}.</p>}
        <WarehousePicker label="Teknisi penanggung jawab" load={assignmentTechnicians} value={technician} name={item => item.name + ' · ' + item.email} onChange={setTechnician} />
      </> : <p>{work.type.name} · {work.technicianName}. Pergantian teknisi dilakukan melalui tindakan Ganti teknisi.</p>}
      <TextField label="Judul pekerjaan" required maxLength={200} value={draft.title} onChange={(_, data) => setDraft(current => ({ ...current, title: data.value }))} />
      <TextareaField label="Instruksi pekerjaan" maxLength={2000} value={draft.description} onChange={(_, data) => setDraft(current => ({ ...current, description: data.value }))} />
      <WarehousePicker label="Area pekerjaan" load={workAreas} value={area} name={item => item.name + ' · ' + item.code} onChange={setArea} />
      <WarehousePicker optional disabled={customerLocked} label="Pelanggan pekerjaan" load={workCustomers} value={customer} name={item => item.name + ' · ' + item.code} eligible={item => item.status === 'ACTIVE' || item.status === 'PROSPECT' || item.id === work?.customerId} onChange={setCustomer} />
      {customerLocked && <p>Pelanggan terikat langganan dan tetap pada pekerjaan ini.</p>}
      <SelectField label="Prioritas pekerjaan" value={draft.priority} onChange={(_, data) => setDraft(current => ({ ...current, priority: oneOf(data.value, ['LOW', 'NORMAL', 'HIGH', 'URGENT']) }))}>
        {Object.entries(PRIORITY_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </SelectField>
      <TextField label="Jadwal pekerjaan" type="datetime-local" step="0.001" hint="Waktu setempat. Kosongkan jika belum dijadwalkan." value={draft.schedule} onChange={(_, data) => setDraft(current => ({ ...current, schedule: data.value }))} />
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
