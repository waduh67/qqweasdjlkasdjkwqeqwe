import { useCallback, useId, useState, type FormEvent } from 'react'
import type { ReferenceWorkOrder } from '@/api/warehouse/reference'
import { dispatchWorkIntake, type ReferenceWorkIntake } from '@/api/warehouse/referenceWorkIntake'
import { getWorkArea, workAreas, workTypes, type WorkArea } from '@/api/warehouse/referenceWorkManagement'
import { assignmentTechnicians, type TechnicianChoice } from '@/api/warehouse/technicians'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { warehouseError } from '@/api/warehouse/errors'
import { Button, TextField } from '@/components/atoms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { localSchedule, scheduledAt, WorkDraftError } from './referenceWorkDraft'

type Props = { readonly intake: ReferenceWorkIntake; readonly onClose: () => void; readonly onSaved: () => void; readonly onReload: () => void }
export function ReferenceWorkDispatch(props: Props) {
  const load = useCallback(() => props.intake.areaId ? getWorkArea(props.intake.areaId) : Promise.resolve(null), [props.intake.areaId])
  const result = useWarehouseQuery(load)
  if (result.state.status !== 'ready') return <ResourceForm title="Tugaskan teknisi" loading onClose={props.onClose} onBack={props.onClose}
    footer={<Button onClick={props.onClose}>Batal</Button>}><WarehouseState {...result}>{() => null}</WarehouseState></ResourceForm>
  return <DispatchForm {...props} initialArea={result.state.data} />
}
function DispatchForm({ intake, initialArea, onClose, onSaved, onReload }: Props & { readonly initialArea: WorkArea | null }) {
  const formId = useId(), [area, setArea] = useState(initialArea), [type, setType] = useState<ReferenceWorkOrder['type'] | null>(null)
  const [technician, setTechnician] = useState<TechnicianChoice | null>(null), [schedule, setSchedule] = useState(localSchedule(intake.scheduledAt))
  const [error, setError] = useState<string | null>(null), [operation, setOperation] = useState<WarehouseCommand<ReferenceWorkOrder> | null>(null)
  const types = useCallback((search: string, page: number) => workTypes(search, page, intake.type), [intake.type])
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      if (!type || !area || !technician) throw new WorkDraftError('Pilih jenis pekerjaan, area dan satu teknisi NE atau FO.')
      setOperation(dispatchWorkIntake(intake.id, { typeId: type.id, areaId: area.id, technicianId: technician.id, scheduledAt: scheduledAt(schedule, intake.scheduledAt) }))
      setError(null)
    } catch (caught) { setError(caught instanceof WorkDraftError ? caught.message : warehouseError(caught)) }
  }
  return <ResourceForm title="Tugaskan teknisi" onClose={onClose} onBack={() => setOperation(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau penugasan</Button></>}
    review={operation && <WarehouseCommandDialog embedded title="Simpan penugasan" confirmLabel="Tugaskan teknisi" command={operation} onDone={onSaved} onClose={() => setOperation(null)} onReload={onReload}
      summary={<><p><strong>{intake.code} · {intake.title}</strong></p><p>{type?.name} · Teknisi: {technician?.name}</p><p>Area: {area?.name} · Jadwal: {schedule ? new Date(schedule).toLocaleString('id-ID') : 'Belum dijadwalkan'}</p>
        <p>Tugas muncul di Tugas Saya. Hubungan pekerjaan dengan pelanggan dan sumbernya tetap tersimpan.</p></>} />}>
    <form id={formId} className="stack" onSubmit={prepare}>
      <p>{intake.code} · {intake.title}</p>
      <WarehousePicker label="Jenis pekerjaan" load={types} value={type} name={item => item.name} onChange={setType} />
      {type && <p>Foto wajib: {type.photoSlots.join(', ')} · {type.materialRequired ? 'Material wajib dicatat' : 'Material opsional'}.</p>}
      <WarehousePicker label="Teknisi penanggung jawab" load={assignmentTechnicians} value={technician} name={item => item.name + ' · ' + item.email} onChange={setTechnician} />
      <WarehousePicker label="Area pekerjaan" load={workAreas} value={area} name={item => item.name + ' · ' + item.code} onChange={setArea} />
      <TextField label="Jadwal pekerjaan" type="datetime-local" step="0.001" hint="Waktu setempat. Kosongkan jika belum dijadwalkan." value={schedule} onChange={(_, data) => setSchedule(data.value)} />
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
