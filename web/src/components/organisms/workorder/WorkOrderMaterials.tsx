import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import type { WorkOrderView } from '@/api/workorder'
import { closeMaterialSettlement, getMaterialFieldContext, getMaterialSettlement, type MaterialFieldContext, type MaterialSettlement } from '@/api/warehouse/materialExecution'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button, TextareaField } from '@/components/atoms'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WorkOrderMaterialPlanning } from './WorkOrderMaterialPlanning'
import { WorkOrderMaterialUse } from './WorkOrderMaterialUse'
import { WorkOrderMaterialHistory } from './WorkOrderMaterialHistory'
import { WorkOrderMaterialObligations, WorkOrderMaterialReview } from './WorkOrderMaterialReview'
import { WorkOrderMaterialRework } from './WorkOrderMaterialRework'
import { WorkOrderMaterialHandover } from './WorkOrderMaterialHandover'

const materialLabels = { OPEN: 'Belum ditutup', SETTLING: 'Penyelesaian sisa barang', CLOSED: 'Material ditutup', OVERDUE: 'Kewajiban melewati batas waktu' }
const technicalLabels: Record<string, string> = { DRAFT: 'Draft', ASSIGNED: 'Ditugaskan', IN_PROGRESS: 'Dikerjakan', DONE: 'Teknis selesai', CANCELLED: 'Dibatalkan' }
const qaLabels: Record<string, string> = { PENDING: 'Menunggu pemeriksaan', APPROVED: 'Disetujui QA', REJECTED: 'Ditolak, perlu pengerjaan ulang' }
const provisionLabels: Record<string, string> = { NOT_APPLICABLE: 'Tidak diperlukan', PENDING: 'Menunggu', OWNER_APPLIED: 'Diterapkan pemilik layanan', COMPLETED: 'Selesai', FAILED: 'Gagal', RECONCILIATION_REQUIRED: 'Perlu rekonsiliasi', PROCESSING: 'Diproses' }

export function WorkOrderMaterials({ workOrder }: { workOrder: WorkOrderView }) {
  const { can } = useCan()
  if (!can('workorder.order.view') && !can('workorder.order.field')) return null
  return <MaterialLoader key={`${workOrder.id}:${workOrder.status}:${workOrder.approvalStatus}:${workOrder.assignees.map(row => row.id).join(',')}`} workOrder={workOrder} />
}
function MaterialLoader({ workOrder }: { workOrder: WorkOrderView }) {
  const loader = useCallback(async () => {
    const [context, settlement] = await Promise.all([getMaterialFieldContext(workOrder.id), getMaterialSettlement(workOrder.id)])
    if (context.workOrderId !== workOrder.id || settlement.workOrderId !== workOrder.id) throw new Error('Konteks material tidak sesuai work order.')
    return { context, settlement }
  }, [workOrder.id]), result = useWarehouseQuery(loader)
  return <section className="card stack" aria-label="Material pekerjaan" id="work-order-materials"><h2>Material pekerjaan</h2><WarehouseState {...result}>{data => <Materials workOrder={workOrder} {...data} reload={result.reload} />}</WarehouseState></section>
}
function Materials({ workOrder, context, settlement, reload }: { workOrder: WorkOrderView; context: MaterialFieldContext; settlement: MaterialSettlement; reload: () => void }) {
  const { can } = useCan(), { user } = useAuth()
  const [using, setUsing] = useState(false), [history, setHistory] = useState(false), [closing, setClosing] = useState(false), [reason, setReason] = useState('')
  const [close, setClose] = useState<WarehouseCommand<unknown> | null>(null)
  const [reworking, setReworking] = useState(false)
  const [handover, setHandover] = useState(false)
  const active = settlement.technicalState !== 'DONE' && settlement.technicalState !== 'CANCELLED'
  const mine = !!user && workOrder.assignees.some(row => row.id === user.id)
  const report = can('workorder.order.field') && mine && active && context.planState === 'SUBMITTED' && !!context.plan
    && (context.useRevision === 0 || context.plan.materialMode === 'MATERIAL_REQUIRED')
  const clear = settlement.outstandingBase === '0' && settlement.obligations.reservedUnpickedBase === '0' && settlement.obligations.pickedBase === '0'
  const canRework = settlement.technicalState === 'IN_PROGRESS' && settlement.qaState === 'REJECTED' && context.useRevision > 0 && context.plan?.materialMode === 'MATERIAL_REQUIRED'
    && can('inventory.request.view') && can('inventory.request.manage') && can('inventory.sku.view') && (can('workorder.order.update') || can('workorder.order.approve') || (can('workorder.order.field') && mine))
  return <>
    <dl className="wo-grid"><div><dt>Pekerjaan teknis</dt><dd>{technicalLabels[settlement.technicalState] ?? settlement.technicalState}</dd></div>
      <div><dt>Pemeriksaan QA</dt><dd>{settlement.qaState ? qaLabels[settlement.qaState] ?? settlement.qaState : 'Belum diajukan'}</dd></div>
      <div><dt>Provisioning</dt><dd>{provisionLabels[settlement.provisioningState] ?? settlement.provisioningState}</dd></div>
      <div><dt>Penyelesaian material</dt><dd>{materialLabels[settlement.materialState]}</dd></div></dl>
    <p>Selesai teknis, persetujuan QA, aktivasi layanan, dan penutupan material dicatat terpisah. Sisa barang tetap harus diselesaikan setelah WO dibatalkan atau teknisi diganti.</p>
    {context.plan ? <><p>Rencana {context.plan.planRevision} · Pemakaian revisi {context.useRevision}</p>{context.plan.materialMode === 'NONE' ? <p>Rencana tanpa material: {context.plan.reason}</p> : <ul>{context.plan.lines.map(line => <li key={line.id}>{line.sku.name}: <WarehouseQuantity value={line.quantityBase} unit={line.sku.baseUnit} />{line.continuousCut && ' · satu potongan utuh'}</li>)}</ul>}</> : <p>Belum ada rencana material. Susun kebutuhan atau nyatakan alasan tanpa material sebelum mengajukan pekerjaan.</p>}
    {can('inventory.request.view') && <WorkOrderMaterialPlanning id={workOrder.id} active={active} onChanged={reload} />}
    <div className="row wrap"><Button onClick={reload}>Muat ulang material WO</Button>{report && <Button variant="primary" onClick={() => setUsing(true)}>{context.plan?.materialMode === 'NONE' ? 'Nyatakan tanpa pemakaian material' : context.latestUsageId ? 'Catat tambahan pemakaian' : 'Catat pemakaian material'}</Button>}
      <Button onClick={() => setHistory(value => !value)}>{history ? 'Tutup riwayat pemakaian' : 'Lihat riwayat pemakaian'}</Button>
      {can('inventory.report.view') && can('inventory.cost.view') && <Link to={`/warehouse/reports?kind=work-order-costs&workOrderId=${workOrder.id}`}>Lihat biaya material WO</Link>}
    </div>
    {can('workorder.order.field') && !mine && <p>Anda bukan teknisi yang ditugaskan saat ini. Pemakaian baru harus dilaporkan oleh teknisi aktif.</p>}
    {!active && <p>Pelaporan pemakaian baru ditutup pada status WO ini.</p>}
    {using && report && <WorkOrderMaterialUse context={context} onDone={reload} onClose={() => setUsing(false)} />}
    {history && <WorkOrderMaterialHistory id={workOrder.id} />}
    <WorkOrderMaterialReview id={workOrder.id} />
    {canRework && <Button onClick={() => setReworking(true)}>Tambah kebutuhan pengerjaan ulang</Button>}
    {canRework && reworking && <WorkOrderMaterialRework context={context} onDone={reload} onClose={() => setReworking(false)} />}
    {active && can('workorder.order.assign') && can('inventory.issue.manage') && <><Button onClick={() => setHandover(true)}>Atur serah-terima teknisi</Button>
      {handover && <WorkOrderMaterialHandover context={context} onDone={reload} onClose={() => setHandover(false)} />}</>}
    <section className="stack" aria-label="Kewajiban material"><h3>Sisa kewajiban material</h3>{settlement.obligations.dueAt && <p>Batas penyelesaian: <WarehouseTime value={settlement.obligations.dueAt} /></p>}
      {clear ? <p>Tidak ada barang atau reservasi yang menunggu penyelesaian pada ringkasan ini.</p> : <><p>Masih ada barang di tangan pemegang, dalam perjalanan, menunggu pemeriksaan retur, atau terikat reservasi.</p>
        <WorkOrderMaterialObligations id={workOrder.id} /></>}
      {can('workorder.order.field') && <Link to={`/my-materials?workOrderId=${workOrder.id}`}>Terima atau kembalikan melalui Material Saya</Link>}
      {workOrder.customerId && can('customer.onu.view') && can('customer.customer.view') && <Link to="/customers" state={{ openCustomerId: workOrder.customerId }}>Pasang perangkat dan periksa aset pelanggan</Link>}
      {can('inventory.return.view') && <Link to="/warehouse/returns">Lihat retur dan pemeriksaan gudang</Link>}
      {can('workorder.order.close') && settlement.materialState !== 'CLOSED' && <Button disabled={!clear} onClick={() => setClosing(true)}>Tutup kewajiban material</Button>}
      {closing && <form className="stack" onSubmit={event => { event.preventDefault(); if (reason.trim()) setClose(closeMaterialSettlement(workOrder.id, { expectedRevision: settlement.revision, workOrderRevision: context.workOrderRevision, reason: reason.trim() })) }}>
        <TextareaField label="Alasan penutupan material" required maxLength={1000} value={reason} onChange={(_, value) => setReason(value.value)} /><div className="row wrap"><Button onClick={() => setClosing(false)}>Batal</Button><Button type="submit">Tinjau penutupan material</Button></div>
      </form>}
    </section>
    {close && <WarehouseCommandDialog title="Konfirmasi penutupan material" command={close} confirmLabel="Tutup material" onDone={reload} onReload={reload} onClose={() => setClose(null)} summary={<><p>{workOrder.code} · WO revisi {context.workOrderRevision} · Penyelesaian revisi {settlement.revision}</p><p>{reason}</p><p>Server memeriksa kembali seluruh sisa barang dan reservasi. Penutupan material tidak mengubah persetujuan QA atau status layanan.</p></>} />}
  </>
}
