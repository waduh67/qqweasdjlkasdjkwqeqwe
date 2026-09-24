import { useCallback, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { uuid } from '@/api/warehouse/codec'
import { getMyMaterialContext, getMyMaterialCustody, getMyMaterialIssues, getMyMaterialJobs, getMyMaterialResiduals, getMyMaterialSource, type MyMaterialContext, type MyMaterialIssue } from '@/api/warehouse/myMaterials'
import type { MaterialCustody } from '@/api/warehouse/materialExecution'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WorkOrderMaterialUse } from '@/components/organisms/workorder/WorkOrderMaterialUse'
import type { MaterialUseDraft } from '@/components/organisms/workorder/materialUseDraft'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { useFieldConnection } from '@/hooks/useFieldConnection'
import { MyMaterialReceipt } from './MyMaterialReceipt'
import { MyMaterialReturn } from './MyMaterialReturn'
import { MyMaterialResidualAcknowledgement } from './MyMaterialResidualAcknowledgement'

const path = (id: string) => `/my-materials?workOrderId=${encodeURIComponent(id)}`
export function MyMaterialsPage() {
  const { user, readOnly } = useAuth(), { can } = useCan(), [params] = useSearchParams()
  let id: string | null = null
  try { if ([...params.keys()].some(key => key !== 'workOrderId') || params.getAll('workOrderId').length > 1) throw new Error(); if (params.has('workOrderId')) id = uuid(params.get('workOrderId')) }
  catch { return <div role="alert"><p>Alamat material tidak dikenal.</p><Link to="/my-materials">Kembali ke Material Saya</Link></div> }
  if (!user || !can('workorder.order.field')) return <WarehouseDenied />
  return <MyMaterialsWorkspace key={`${user.tenantId}:${user.id}`} actor={user.id} id={id} readOnly={readOnly} />
}
function MyMaterialsWorkspace({ actor, id, readOnly }: { actor: string; id: string | null; readOnly: boolean }) {
  const online = useFieldConnection()
  return <div className="stack"><PageHeader title="Material Saya" subtitle="Penerimaan, barang di tangan Anda, dan penyelesaian sisa material." />
    {!online && <div className="card" role="status"><p>Offline — perubahan hanya draf di tab ini dan belum dikirim. Jumlah yang terlihat adalah catatan terakhir dari server.</p><p>Sambungkan internet untuk memeriksa ulang akses, penugasan, dan sumber sebelum mengirim. Draf hilang saat keluar dari halaman atau berganti akun.</p></div>}
    {readOnly && <p role="status">Akun sedang baca saja. Transaksi material belum dapat dikirim.</p>}
    <div className="row wrap"><Link to="/my-work-orders">Tugas Saya</Link>{id && <Link to="/my-materials">Semua material saya</Link>}</div>
    {id ? <MaterialJob key={id} id={id} actor={actor} online={online} readOnly={readOnly} /> : <MaterialJobs online={online} />}
  </div>
}
function MaterialJobs({ online }: { online: boolean }) {
  const [page, setPage] = useState(0), loader = useCallback(() => getMyMaterialJobs(page), [page]), result = useWarehouseQuery(loader)
  return <><Button disabled={!online} onClick={result.reload}>Segarkan material saya</Button><WarehouseState {...result}>{data => <>
    <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Belum ada material untuk Anda" hint="Pengiriman yang ditujukan kepada Anda akan muncul di sini. Rencana WO dapat dilihat di Tugas Saya." />} columns={[
      { key: 'job', header: 'Work order', cell: row => <Link to={path(row.id)}>{row.code}</Link> },
      { key: 'date', header: 'Dokumen terakhir', cell: row => <WarehouseTime value={row.updatedAt} /> },
    ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState></>
}
function MaterialJob({ id, actor, online, readOnly }: { id: string; actor: string; online: boolean; readOnly: boolean }) {
  const loader = useCallback(() => getMyMaterialContext(id), [id]), result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{context => <MaterialJobBody context={context} actor={actor} online={online} readOnly={readOnly} reload={result.reload} />}</WarehouseState>
}
type Action = { kind: 'receipt'; issue: MyMaterialIssue } | { kind: 'return'; source: MaterialCustody } | { kind: 'use' }
function MaterialJobBody({ context, actor, online, readOnly, reload }: { context: MyMaterialContext; actor: string; online: boolean; readOnly: boolean; reload: () => void }) {
  const [action, setAction] = useState<Action | null>(null), { can } = useCan()
  const enabled = online && !readOnly
  async function prepareUse(rows: MaterialUseDraft[]) {
    const [fresh, ...sources] = await Promise.all([getMyMaterialContext(context.id), ...rows.flatMap(row => row.source ? [getMyMaterialSource(context.id, row.source.id)] : [])])
    if (!fresh.currentAssignee || !fresh.active || !fresh.field || JSON.stringify(fresh.field) !== JSON.stringify(context.field)) throw new Error('Penugasan atau rencana berubah. Muat ulang material sebelum mencatat pemakaian.')
    for (const source of sources) {
      const original = rows.find(row => row.source?.id === source.id)?.source
      if (!original || original.stockRevision !== source.stockRevision || original.quantityBase !== source.quantityBase || original.sourceUsageId !== source.sourceUsageId) throw new Error('Sisa material berubah. Muat ulang sumber sebelum mencatat pemakaian.')
    }
  }
  return <><section className="card stack"><h2>{context.code}</h2><p>Status teknis: <WarehouseStatus status={context.technicalState} /> · QA: {context.qaState ?? 'Belum dinilai'} · WO revisi {context.workOrderRevision}</p>
    {!context.currentAssignee && <p role="status">Anda tidak lagi ditugaskan pada WO ini. Sisa milik Anda tetap dapat dikembalikan; pemakaian baru tidak diizinkan.</p>}
    {context.field?.plan && <p>Rencana material revisi {context.field.plan.planRevision} · {context.field.plan.materialMode === 'NONE' ? 'Tanpa material' : 'Memerlukan material'} · Pemakaian revisi {context.field.useRevision}</p>}
    <div className="row wrap"><Button disabled={!online || !!action} onClick={reload}>Segarkan material WO</Button>
      {context.currentAssignee && can('workorder.order.view') && <Link to={`/my-work-orders/${context.id}`}>Detail tugas dan bukti</Link>}
      {!readOnly && context.currentAssignee && context.active && context.field?.planState === 'SUBMITTED' && (context.field.plan?.materialMode !== 'NONE' || context.field.useRevision === 0) && <Button disabled={!online || !!action} onClick={() => setAction({ kind: 'use' })}>Catat pemakaian</Button>}</div>
  </section>
    {action?.kind === 'receipt' ? <MyMaterialReceipt context={context} issue={action.issue} actor={actor} online={enabled} onDone={reload} onClose={() => setAction(null)} />
      : action?.kind === 'return' ? <MyMaterialReturn context={context} source={action.source} online={enabled} onDone={reload} onClose={() => setAction(null)} />
        : action?.kind === 'use' && context.field ? <WorkOrderMaterialUse context={context.field} online={enabled} prepare={prepareUse} evidenceHref={can('workorder.order.view') ? `/my-work-orders/${context.id}#work-order-evidence` : null} onDone={reload} onClose={() => setAction(null)} />
          : <><MaterialIssues context={context} enabled={enabled} select={issue => setAction({ kind: 'receipt', issue })} />
            <MaterialCustodyList context={context} enabled={enabled} select={source => setAction({ kind: 'return', source })} />
            <MaterialResiduals context={context} actor={actor} enabled={enabled} reload={reload} /></>}
  </>
}
function MaterialIssues({ context, enabled, select }: { context: MyMaterialContext; enabled: boolean; select: (row: MyMaterialIssue) => void }) {
  const [page, setPage] = useState(0), load = useCallback(() => getMyMaterialIssues(context.id, page), [context.id, page]), result = useWarehouseQuery(load)
  return <section className="stack" aria-label="Pengiriman untuk saya"><h3>Pengiriman untuk saya</h3><WarehouseState {...result}>{data => <>
    {data.items.length === 0 && <p>Belum ada pengiriman dalam cakupan Anda.</p>}{data.items.map(issue => <article className="card stack" key={issue.id}>
      <h4 style={{ overflowWrap: 'anywhere' }}>{issue.code}</h4><p>{issue.sender.name} → {issue.receiver.name} · <WarehouseStatus status={issue.state} /> · Revisi {issue.revision}</p>
      <ul>{issue.lines.map(line => <li key={line.id}>{line.sku.name} · {line.serial ?? line.lotCode} · Diterima <WarehouseQuantity value={line.acceptedBase} unit={line.baseUnit} /> · Menunggu <WarehouseQuantity value={line.remainingBase} unit={line.baseUnit} /></li>)}</ul>
      {issue.state !== 'RECEIVED' && <>{issue.workOrderRevision !== context.workOrderRevision && <p role="status">Revisi WO berubah sejak pengiriman. Petugas perlu memeriksa pengiriman ini.</p>}
        <Button disabled={!enabled || !context.currentAssignee || !context.active || issue.workOrderRevision !== context.workOrderRevision} onClick={() => select(issue)}>Terima barang</Button></>}
    </article>)}<WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState></section>
}
function MaterialCustodyList({ context, enabled, select }: { context: MyMaterialContext; enabled: boolean; select: (row: MaterialCustody) => void }) {
  const [page, setPage] = useState(0), load = useCallback(() => getMyMaterialCustody(context.id, page), [context.id, page]), result = useWarehouseQuery(load)
  return <section className="stack" aria-label="Barang di tangan saya"><h3>Barang di tangan saya</h3><WarehouseState {...result}>{data => <>
    <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Tidak ada sisa di tangan Anda" hint="Barang dalam perjalanan baru tercatat di sini setelah penerimaan." />} columns={[
      { key: 'sku', header: 'Barang', cell: row => <span>{row.sku.name}<br />{row.serial ?? row.lotCode}</span> },
      { key: 'amount', header: 'Jumlah di tangan', cell: row => <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> },
      { key: 'location', header: 'Lokasi', cell: row => row.location.name ?? row.location.code },
      { key: 'source', header: 'Pengiriman asal', cell: row => row.issueCode },
      { key: 'action', header: 'Tindakan', cell: row => row.sku.tracking === 'SERIAL' ? <span>Gunakan alur aset pelanggan pada detail WO.</span> : <Button disabled={!enabled} onClick={() => select(row)}>Kembalikan sisa</Button> },
    ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState></section>
}
function MaterialResiduals({ context, actor, enabled, reload }: { context: MyMaterialContext; actor: string; enabled: boolean; reload: () => void }) {
  const [page, setPage] = useState(0), load = useCallback(() => getMyMaterialResiduals(context.id, page), [context.id, page]), result = useWarehouseQuery(load)
  return <section className="stack" aria-label="Pengembalian dan serah-terima saya"><h3>Pengembalian dan serah-terima</h3><WarehouseState {...result}>{data => <>
    {data.items.length === 0 && <p>Belum ada pengembalian atau serah-terima dalam cakupan Anda.</p>}{data.items.map(row => <article className="card stack" key={row.id}>
      <h4>{row.sku.name} · {row.serial ?? row.lotCode}</h4><p><WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> · <WarehouseStatus status={row.state} /> · Revisi {row.revision}</p>
      <p>{row.sender?.name ?? 'Pengirim'} → {row.receiver?.name ?? row.location.name} · {row.purpose === 'RETURN' ? 'Pengembalian ke gudang' : 'Serah-terima teknisi'}</p>
      {row.purpose === 'RETURN' && row.state === 'RECEIVED_IN_INSPECTION' && <p>Sudah diterima petugas; masih perlu pemeriksaan. Penerimaan belum berarti stok tersedia.</p>}
      {row.purpose === 'HANDOVER' && row.receiver?.id === actor && context.currentAssignee && context.active && row.state === 'DISPATCHED' && <MyMaterialResidualAcknowledgement row={row} enabled={enabled} onDone={reload} />}
    </article>)}<WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState></section>
}
