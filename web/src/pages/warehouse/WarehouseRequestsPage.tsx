import { useCallback, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { uuid } from '@/api/warehouse/codec'
import type { MaterialSummary } from '@/api/warehouse/materialModels'
import { getMaterialHistory, getMaterials, materialRequestAction, submitMaterialRequest } from '@/api/warehouse/materials'
import { listAllocations, type ReservationAllocation } from '@/api/warehouse/reservations'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { getMaterialWorkOrder, listMaterialWorkOrders, WORK_ORDER_STATES, type MaterialWorkOrder } from '@/api/warehouse/workOrders'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, SelectField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { MaterialPlanEditor } from './MaterialPlanEditor'
import { buildReservation, currentAllocations } from './materialActions'
import { WarehouseAllocationEditor } from './WarehouseAllocationEditor'
import { WarehouseReservationEditor } from './WarehouseReservationEditor'
import { WarehouseIssuePanel } from './WarehouseIssuePanel'
import { WarehouseLocationEditor } from './WarehouseLocationEditor'

const requestLink = (id: string) => `/warehouse/requests?workOrderId=${encodeURIComponent(id)}`
export function WarehouseRequestsPage() {
  const { can } = useCan()
  const [params] = useSearchParams()
  let id: string | null = null
  try {
    if ([...params.keys()].some(key => key !== 'workOrderId') || params.getAll('workOrderId').length > 1) throw new Error()
    if (params.has('workOrderId')) id = uuid(params.get('workOrderId'))
  } catch { return <div className="card stack" role="alert"><p>Alamat permintaan tidak dikenal.</p><Link to="/warehouse/requests">Kembali ke daftar work order</Link></div> }
  if (!can('inventory.request.view') || !can('workorder.order.view')) return <div className="card" role="alert"><EmptyState title="Akses permintaan dibatasi" hint="Workbench gudang memerlukan izin lihat permintaan dan lihat work order, beserta cakupan lokasi yang sesuai." /></div>
  return <div className="stack"><PageHeader title="Permintaan & Pengeluaran" subtitle="Rencanakan kebutuhan work order, cadangkan stok, lalu siapkan dan kirim barang." />
    {id ? <RequestDetail key={id} id={id} /> : <WorkOrderList />}
  </div>
}
function WorkOrderList() {
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const loader = useCallback(() => listMaterialWorkOrders({ query: search.trim() || undefined, status: status || undefined, page }), [search, status, page])
  const result = useWarehouseQuery(loader)
  const labels: Record<string, string> = { DRAFT: 'Draft', ASSIGNED: 'Ditugaskan', IN_PROGRESS: 'Dikerjakan', DONE: 'Selesai', CANCELLED: 'Dibatalkan' }
  return <><div className="row wrap"><TextField label="Cari work order" value={search} maxLength={200} onChange={(_, data) => { setSearch(data.value); setPage(0) }} />
    <SelectField label="Status work order" value={status} onChange={(_, data) => { setStatus(data.value); setPage(0) }}><option value="">Semua status</option>{WORK_ORDER_STATES.map(state => <option value={state} key={state}>{labels[state]}</option>)}</SelectField><Button onClick={result.reload}>Segarkan</Button></div>
    <WarehouseState {...result}>{data => <><DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Tidak ada work order dalam cakupan Anda" hint="Pilih atau buat work order pada menu pekerjaan, lalu susun kebutuhan materialnya." />} columns={[
      { key: 'workOrder', header: 'Work order', cell: row => <Link to={requestLink(row.id)}>{row.code} · {row.title}</Link> },
      { key: 'customer', header: 'Pelanggan', cell: row => row.customerId ? row.customerName ?? 'Nama pelanggan tidak tersedia' : 'Tidak terkait pelanggan' },
      { key: 'status', header: 'Status', cell: row => labels[row.status] },
      { key: 'assignees', header: 'Teknisi ditugaskan', cell: row => row.assignees.map(person => person.name ?? 'Nama tidak tersedia').join(', ') || 'Belum ditugaskan' },
    ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
  </>
}
function RequestDetail({ id }: { id: string }) {
  const loader = useCallback(async () => {
    const [summary, workOrder] = await Promise.all([getMaterials(id), getMaterialWorkOrder(id)])
    const allocations = summary.demandDocumentId ? await listAllocations(id) : []
    return { summary, workOrder, allocations }
  }, [id])
  const result = useWarehouseQuery(loader)
  return <><Link to="/warehouse/requests">Kembali ke daftar work order</Link><WarehouseState {...result}>{data => <RequestBody summary={data.summary} workOrder={data.workOrder} allocations={data.allocations} reload={result.reload} />}</WarehouseState></>
}
function RequestBody({ summary, workOrder, allocations, reload }: { summary: MaterialSummary; workOrder: MaterialWorkOrder; allocations: ReservationAllocation[]; reload: () => void }) {
  const { can } = useCan()
  const [editor, setEditor] = useState<'plan' | 'reserve' | 'pick' | 'release' | null>(null)
  const [operation, setOperation] = useState<{ command: WarehouseCommand<unknown>; action: 'submit' | 'reserve' } | null>(null)
  const [transitEditor, setTransitEditor] = useState(false)
  const active = workOrder.status !== 'DONE' && workOrder.status !== 'CANCELLED'
  const manage = can('inventory.request.manage')
  const planManage = manage && (can('workorder.order.update') || can('workorder.order.assign'))
  const substituted = summary.plan?.lines.some(line => line.substitution !== null) ?? false
  const override = !substituted || can('inventory.request.override')
  const pickManage = manage && can('inventory.issue.manage') && can('inventory.issue.view') && can('inventory.sku.view') && override
  const current = currentAllocations(summary, allocations)
  const stale = current.some(row => row.documentRevision !== summary.demandRevision || row.workOrderId !== workOrder.id)
  const canSelect = current.some(row => row.reservedPickedBase === '0' && BigInt(row.reservedUnpickedBase) > 0n)
  const hasObligations = summary.lines.some(line => BigInt(line.reservedUnpickedBase) + BigInt(line.reservedPickedBase) + BigInt(line.issuedBase) > 0n)
  const shortage = summary.lines.some(line => summary.plan?.lines.some(plan => plan.id === line.planLineId) && BigInt(line.backorderBase) > 0n)
  if (editor === 'plan') return <MaterialPlanEditor summary={summary} onSaved={reload} onClose={() => setEditor(null)} onReload={reload} />
  if (editor === 'reserve') return <WarehouseReservationEditor summary={summary} onDone={reload} onClose={() => setEditor(null)} />
  if (editor === 'pick' || editor === 'release') return <WarehouseAllocationEditor summary={summary} allocations={current} action={editor} onDone={reload} onClose={() => setEditor(null)} />
  return <>
    <section className="card stack" aria-label="Permintaan work order"><h2>{workOrder.code} · {workOrder.title}</h2>
      <p>{workOrder.customerId ? workOrder.customerName ?? 'Nama pelanggan tidak tersedia' : 'Pekerjaan tanpa pelanggan'} · Teknisi: {workOrder.assignees.map(person => person.name ?? 'Nama tidak tersedia').join(', ') || 'Belum ditugaskan'}</p>
      <p>Rencana {summary.revisions.planRevision} · WO revisi {summary.revisions.workOrderRevision}{summary.demandRevision !== null && ` · Permintaan revisi ${summary.demandRevision}`} · <WarehouseStatus status={summary.demandState} /></p>
      {!summary.plan ? <p>Rencana material belum disusun.</p> : summary.materialMode === 'NONE' ? <p>Tanpa material: {summary.noMaterialReason ?? summary.plan.reason}</p> : <p>Jumlah diminta, dicadangkan dan dikirim berasal dari catatan permintaan. Konfirmasi diterima ditampilkan per slip di bawah.</p>}
      {shortage && <p role="status">Masih ada kekurangan material. Reservasi atau pengiriman sebagian tetap mencatat sisa yang harus dipenuhi.</p>}
      {stale && <p role="alert">Alokasi berubah saat halaman dimuat. Muat ulang permintaan sebelum memilih barang.</p>}
      <div className="row wrap"><Button onClick={reload}>Muat ulang permintaan</Button>
        {planManage && <Button disabled={!active || hasObligations} onClick={() => setEditor('plan')}>{summary.plan ? 'Revisi rencana' : 'Susun rencana material'}</Button>}
        {planManage && summary.plan && summary.demandState === 'DRAFT' && <Button variant="primary" disabled={!active || !override || (summary.materialMode === 'MATERIAL_REQUIRED' && !can('inventory.sku.view'))} onClick={() => setOperation({ action: 'submit', command: submitMaterialRequest(workOrder.id, { expectedRevision: summary.revisions.planRevision, workOrderRevision: summary.revisions.workOrderRevision }) })}>{summary.materialMode === 'NONE' ? 'Ajukan rencana tanpa material' : 'Ajukan permintaan'}</Button>}
        {manage && summary.demandDocumentId && <><Button disabled={!active || stale || !shortage || !override} onClick={() => setOperation({ action: 'reserve', command: materialRequestAction(summary.demandDocumentId!, 'reserve', buildReservation(summary, [], '', false, true)) })}>Cadangkan otomatis</Button>
          <Button disabled={!active || stale || !shortage || !override} onClick={() => setEditor('reserve')}>Reservasi sebagian / pilih stok</Button><Button disabled={!active || stale || !canSelect || !override} onClick={() => setEditor('release')}>Lepas reservasi</Button></>}
        {can('inventory.issue.manage') && <Button variant="primary" disabled={!active || stale || !pickManage || !canSelect || !workOrder.assignees.length} onClick={() => setEditor('pick')}>Siapkan barang</Button>}
      </div>
      {!manage && <p className="muted">Akses baca saja. Perubahan memerlukan izin kelola permintaan.</p>}
      {manage && !planManage && <p className="muted">Rencana dan pengajuan dikelola petugas dengan izin ubah atau penugasan WO.</p>}
      {hasObligations && planManage && <p className="muted">Rencana terkunci selama ada reservasi, barang disiapkan atau barang dikirim. Batalkan persiapan dan lepas reservasi sebelum merevisi; pengiriman memerlukan alur koreksi.</p>}
      {!active && <p className="muted">WO sudah selesai atau dibatalkan. Transaksi material tidak tersedia.</p>}
      {can('inventory.issue.manage') && !pickManage && <p className="muted">Persiapan memerlukan izin kelola permintaan, lihat slip, lihat barang, dan override untuk substitusi.</p>}
      {!workOrder.assignees.length && <p className="muted">Tugaskan teknisi pada WO sebelum menyiapkan barang. Penerima dicatat pada slip.</p>}
      {substituted && !override && <p className="muted">Permintaan berisi substitusi. Izin override diperlukan untuk memprosesnya.</p>}
    </section>
    {summary.lines.length > 0 && <DataTable presentation="warehouse" rows={summary.lines} rowKey={line => line.planLineId} columns={[
      { key: 'name', header: 'Material', cell: line => { const plan = summary.plan?.lines.find(plan => plan.id === line.planLineId); return plan ? <span>{plan.sku.name} · {plan.sku.code}{plan.continuousCut && <p>Satu potongan utuh</p>}{plan.substitution && <p>Pengganti {plan.originalSku?.name}: {plan.substitution.reason}</p>}</span> : <span>Material revisi sebelumnya <small style={{ overflowWrap: 'anywhere' }}>{line.skuId}</small></span> } },
      { key: 'requested', header: summary.demandState === 'DRAFT' ? 'Rencana belum diajukan' : 'Diminta', cell: line => <WarehouseQuantity value={summary.demandState === 'DRAFT' ? summary.plan?.lines.find(plan => plan.id === line.planLineId)?.quantityBase ?? line.requestedBase : line.requestedBase} unit={line.baseUnit} /> },
      { key: 'reserved', header: 'Dicadangkan', cell: line => <WarehouseQuantity value={line.reservedUnpickedBase} unit={line.baseUnit} /> },
      { key: 'picked', header: 'Disiapkan', cell: line => <WarehouseQuantity value={line.reservedPickedBase} unit={line.baseUnit} /> },
      { key: 'issued', header: 'Dikirim', cell: line => <WarehouseQuantity value={line.issuedBase} unit={line.baseUnit} /> },
      { key: 'shortage', header: 'Kekurangan', cell: line => <WarehouseQuantity value={line.backorderBase} unit={line.baseUnit} /> },
    ]} />}
    <details className="card"><summary>Persiapan pengeluaran</summary><div className="stack"><p>Pengiriman membutuhkan lokasi transit aktif berkode WO_TRANSIT dan cakupan akses dari bin asal sampai transit.</p>
      {can('inventory.location.view') && <Link to="/warehouse/catalog">Kelola lokasi dan cakupan</Link>}
      {can('inventory.location.manage') && can('inventory.location.view') && <Button onClick={() => setTransitEditor(true)}>Siapkan transit WO</Button>}
      {can('inventory.item.view') && <Link to="/warehouse/stock?bucket=TRANSIT">Lihat stok fisik dalam transit</Link>}
    </div></details>
    {can('inventory.issue.view') ? <WarehouseIssuePanel summary={summary} active={active} onChanged={reload} /> : <p className="muted">Izin lihat pengeluaran diperlukan untuk membaca slip dan jumlah yang dikonfirmasi diterima.</p>}
    <MaterialHistory workOrderId={workOrder.id} />
    {transitEditor && <WarehouseLocationEditor row={null} readOnly={false} preset={{ code: 'WO_TRANSIT', name: 'Transit material WO', kind: 'TRANSIT', issueEligible: false }} onClose={() => setTransitEditor(false)} onSaved={reload} onReload={reload} />}
    {operation && <WarehouseCommandDialog title={operation.action === 'submit' ? 'Ajukan permintaan material' : 'Cadangkan stok otomatis'} confirmLabel={operation.action === 'submit' ? 'Konfirmasi pengajuan' : 'Konfirmasi reservasi'} command={operation.command} onClose={() => setOperation(null)} onDone={reload} onReload={reload}
      summary={<><p>{workOrder.code} · Rencana {summary.revisions.planRevision}</p>{summary.materialMode === 'NONE' && <p>Tanpa material: {summary.noMaterialReason ?? summary.plan?.reason}</p>}<ul>{summary.plan?.lines.map(line => {
        const amount = operation.action === 'reserve' ? summary.lines.find(total => total.planLineId === line.id)?.backorderBase ?? '0' : line.quantityBase
        return amount === '0' ? null : <li key={line.id}>{line.sku.name}: <WarehouseQuantity value={amount} unit={line.sku.baseUnit} />{line.substitution && <p>Pengganti {line.originalSku?.name}: {line.substitution.reason}</p>}</li>
      })}</ul><p>{operation.action === 'submit' ? 'Rencana diajukan untuk pekerjaan ini. Stok belum berpindah.' : 'Jumlah di atas adalah sisa kebutuhan yang akan dicoba dicadangkan menurut FIFO. Kekurangan tetap terlihat bila stok belum cukup; stok fisik belum berpindah.'}</p></>} />}
  </>
}
function MaterialHistory({ workOrderId }: { workOrderId: string }) {
  const [page, setPage] = useState(0)
  const loader = useCallback(() => getMaterialHistory(workOrderId, page), [workOrderId, page])
  const result = useWarehouseQuery(loader)
  return <details className="card"><summary>Riwayat rencana material</summary><WarehouseState {...result}>{data => <div className="stack">{data.items.map(row => <section key={row.plan.id}><h3>Rencana {row.plan.planRevision} · <WarehouseStatus status={row.state} /></h3>
    {row.plan.materialMode === 'NONE' ? <p>Tanpa material: {row.plan.reason}</p> : <ul>{row.plan.lines.map(line => <li key={line.id}>{line.sku.name}: <WarehouseQuantity value={line.quantityBase} unit={line.sku.baseUnit} />{line.substitution && <p>Pengganti {line.originalSku?.name}: {line.substitution.reason}</p>}</li>)}</ul>}</section>)}<WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} /></div>}</WarehouseState></details>
}
