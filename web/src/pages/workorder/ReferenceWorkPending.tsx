import { useCallback, useState } from 'react'
import { getWorkArea, getWorkCustomer } from '@/api/warehouse/referenceWorkManagement'
import { WORK_SOURCE_LABELS, type ReferenceWorkIntake } from '@/api/warehouse/referenceWorkIntake'
import { useCan } from '@/auth/useCan'
import { Button, StatusBadge } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { ReferenceWorkDispatch } from './ReferenceWorkDispatch'

type Props = { readonly intake: ReferenceWorkIntake; readonly enabled: boolean; readonly reload: () => void }
export function ReferenceWorkPending({ intake, enabled, reload }: Props) {
  const { can } = useCan(), [dispatching, setDispatching] = useState(false)
  const load = useCallback(async () => {
    const [customer, area] = await Promise.all([getWorkCustomer(intake.customerId), intake.areaId ? getWorkArea(intake.areaId) : null])
    return { customer, area }
  }, [intake.customerId, intake.areaId])
  const details = useWarehouseQuery(load)
  const close = () => { setDispatching(false); reload() }
  return <>
    <PageHeader title={intake.title} subtitle={intake.code + ' · ' + WORK_SOURCE_LABELS[intake.source]} actions={<>
      <Button onClick={reload}>Muat ulang</Button>
      {can('workorder.order.assign') && <Button variant="primary" disabled={!enabled} onClick={() => setDispatching(true)}>Tugaskan teknisi</Button>}
    </>} />
    <StatusBadge status="DRAFT" tone="neutral" label="Belum ditugaskan" />
    <p>Pilih jenis pekerjaan dan satu teknisi penanggung jawab. Setelah disimpan, pekerjaan muncul di Tugas Saya milik teknisi.</p>
    <WarehouseState {...details}>{({ customer, area }) => <div className="spread wrap"><span>Pelanggan: {customer.name} · {customer.code}</span><span>Area: {area?.name ?? 'Pilih saat penugasan'}</span></div>}</WarehouseState>
    <section className="card stack"><h2>Instruksi pekerjaan</h2><p className="reference-work-notes">{intake.description || 'Belum ada instruksi tambahan.'}</p></section>
    <div className="spread wrap"><span>Jadwal: {intake.scheduledAt ? <WarehouseTime value={intake.scheduledAt} /> : 'Belum dijadwalkan'}</span><span>Dibuat: <WarehouseTime value={intake.createdAt} /></span></div>
    {dispatching && enabled && can('workorder.order.assign') && <ReferenceWorkDispatch intake={intake} onClose={() => setDispatching(false)} onSaved={close} onReload={close} />}
  </>
}
