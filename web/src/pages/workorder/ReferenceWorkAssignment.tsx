import { useId, useState, type FormEvent } from 'react'
import type { ReferenceWorkOrder } from '@/api/warehouse/reference'
import { assignReferenceWork } from '@/api/warehouse/referenceWorkManagement'
import { assignmentTechnicians, type TechnicianChoice } from '@/api/warehouse/technicians'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button } from '@/components/atoms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'

export function ReferenceWorkAssignment({ work, onClose, onSaved, onReload }: { readonly work: ReferenceWorkOrder; readonly onClose: () => void; readonly onSaved: () => void; readonly onReload: () => void }) {
  const formId = useId(), [technician, setTechnician] = useState<TechnicianChoice | null>(null)
  const [error, setError] = useState<string | null>(null), [operation, setOperation] = useState<WarehouseCommand<ReferenceWorkOrder> | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault()
    if (!technician || technician.id === work.technicianId) { setError('Pilih teknisi pengganti NE atau FO.'); return }
    setOperation(assignReferenceWork(work.id, { technicianId: technician.id, expectedRevision: work.revision })); setError(null)
  }
  return <ResourceForm editing title="Ganti teknisi" onClose={onClose} onBack={() => setOperation(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau pergantian</Button></>}
    review={operation && <WarehouseCommandDialog embedded title="Simpan pergantian teknisi" confirmLabel="Ganti penugasan" command={operation} onDone={onSaved} onClose={() => setOperation(null)} onReload={onReload}
      summary={<><p>{work.code} · {work.title} · Revisi {work.revision}</p><p>{work.technicianName} → <strong>{technician?.name}</strong></p><p>Tugas kembali ke Belum selesai. Foto penugasan sebelumnya tetap di riwayat, dan teknisi pengganti perlu mengirim foto baru.</p></>} />}>
    <form id={formId} className="stack" onSubmit={prepare}><p>Teknisi saat ini: {work.technicianName}.</p>
      <WarehousePicker label="Teknisi pengganti" load={assignmentTechnicians} value={technician} name={item => item.name + ' · ' + item.email} eligible={item => item.id !== work.technicianId} onChange={setTechnician} />
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
