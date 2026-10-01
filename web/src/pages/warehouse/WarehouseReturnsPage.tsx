import { ClipboardCheck, PackageCheck, RefreshCw, Send } from 'lucide-react'
import { WarehouseListActions } from '@/components/organisms/warehouse/WarehouseListActions'
import { Disclosure } from '@/components/molecules/Disclosure'
import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Tabs } from '@/components/molecules/Tabs'
import { CommandBar, type CommandAction } from '@/components/molecules/CommandBar'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseFacts } from '@/components/organisms/warehouse/WarehouseFacts'
import type { MyMaterialResidual } from '@/api/warehouse/myMaterials'
import './warehouseWorkspaces.css'
import { uuid } from '@/api/warehouse/codec'
import { getReturn, listReturns, returnHistory, type ReturnDetails, type ReturnFilter } from '@/api/warehouse/returns'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehousePendingMaterialReturns } from './WarehousePendingMaterialReturns'
import { WarehouseReturnActions, type ReturnAction } from './WarehouseReturnActions'
import { WarehouseReturnEditor } from './WarehouseReturnEditor'
import { WarehouseReturnFilters } from './WarehouseReturnFilters'
import { WarehouseSupplierReplacements } from './WarehouseSupplierReplacement'
import { WarehouseCustomerRma } from './WarehouseCustomerRma'
import { WarehouseReturnReacquisition } from './WarehouseReturnReacquisition'
import { WarehouseReturnDispositions } from './WarehouseReturnDispositions'
import { completedCustomerRepairInspection, needsPostRepairInspection } from './returnDraft'
import { returnItemLabel, returnLocationLabel, returnOriginLabels } from './returnPresentation'

const detailPath = (id: string) => `/warehouse/returns?returnId=${encodeURIComponent(id)}`
export function WarehouseReturnsPage() {
  const { can } = useCan(), [params] = useSearchParams()
  let id: string | null = null
  const view = params.get('view') ?? 'returns'
  try {
    if ([...params.keys()].some(key => !['returnId', 'view'].includes(key)) || params.getAll('returnId').length > 1 || params.getAll('view').length > 1 || !['returns', 'pending'].includes(view)) throw new Error()
    if (params.has('returnId')) id = uuid(params.get('returnId'))
  } catch { return <div className="card stack" role="alert"><p>Alamat retur tidak dikenal.</p><Link to="/warehouse/returns">Kembali ke daftar retur</Link></div> }
  if (!can('inventory.return.view')) return <WarehouseDenied />
  return <ReturnWorkspace id={id} view={view as 'returns' | 'pending'} />
}
function ReturnWorkspace({ id, view }: { id: string | null; view: 'returns' | 'pending' }) {
  const { can } = useCan(), navigate = useNavigate()
  const [listRevision, setListRevision] = useState(0)
  const [creating, setCreating] = useState(false), [received, setReceived] = useState<MyMaterialResidual | null>(null)
  const [initialSource, setInitialSource] = useState<MyMaterialResidual | null>(null)
  const [visitedPending, setVisitedPending] = useState(view === 'pending'), [visitedList, setVisitedList] = useState(!id)
  useEffect(() => { if (view === 'pending') setVisitedPending(true); if (!id) setVisitedList(true) }, [id, view])
  const pending = view === 'pending' && can('inventory.return.manage')
  return <div className="warehouse-workspace"><PageHeader title="Retur & Servis" />
    <Tabs idPrefix="returns" active={pending ? 'pending' : 'returns'} onChange={tab => navigate(tab === 'pending' ? '/warehouse/returns?view=pending' : '/warehouse/returns')}
      tabs={[{ key: 'returns', label: 'Daftar retur' }, ...(can('inventory.return.manage') ? [{ key: 'pending', label: 'Menunggu penerimaan' }] : [])]} />
    <div role="tabpanel" id="returns-panel-returns" aria-labelledby="returns-tab-returns" hidden={pending} className="warehouse-workspace-panel">
      {(visitedList || !id) && <ReturnList revision={listRevision} onNew={() => { setInitialSource(null); setCreating(true) }} />}
    </div>
    {can('inventory.return.manage') && <div role="tabpanel" id="returns-panel-pending" aria-labelledby="returns-tab-pending" hidden={!pending} className="warehouse-workspace-panel">
      {received && <div className="warehouse-workspace-notice"><p role="status">{received.code} diterima. Catat retur untuk memulai pemeriksaan.</p>
        {can('inventory.location.view') && <Button onClick={() => { setInitialSource(received); setCreating(true) }}>Catat retur</Button>}</div>}
      {(visitedPending || pending) && <WarehousePendingMaterialReturns onReceived={setReceived} />}
    </div>}
    {creating && <WarehouseReturnEditor initialSource={initialSource ? { id: initialSource.id, code: initialSource.code } : undefined}
      onSaved={row => { setListRevision(value => value + 1); setCreating(false); setReceived(null); navigate(detailPath(row.id)) }} onClose={() => setCreating(false)} onReload={() => setCreating(false)} />}
    {id && <ResourceForm readOnly title="Detail retur" onClose={() => navigate('/warehouse/returns')} onBack={() => {}}>
      <ReturnDetail key={id} id={id} onChanged={() => setListRevision(value => value + 1)} />
    </ResourceForm>}
  </div>
}
function ReturnList({ onNew, revision }: { onNew: () => void; revision: number }) {
  const { can } = useCan()
  const [filter, setFilter] = useState<ReturnFilter>({}), [page, setPage] = useState(0)
  const loader = useCallback(() => listReturns({ ...filter, page }), [filter, page, revision]), result = useWarehouseQuery(loader)
  return <><div className="resource-list-controls"><WarehouseListActions onRefresh={result.reload}
    create={can('inventory.return.manage') ? { label: 'Terima retur baru', onClick: onNew, disabled: !can('inventory.location.view') } : undefined} />
    <WarehouseReturnFilters onApply={filter => { setFilter(filter); setPage(0) }} /></div>
    <WarehouseState {...result}>{data => <><DataTable presentation="warehouse" rows={data.items} rowKey={row => row.returnCase.id}
      empty={<EmptyState title="Belum ada retur" hint="Catat retur dari material yang sudah diterima atau perangkat yang dilepas." />} columns={[
        { key: 'code', header: 'Retur', cell: row => <Link to={detailPath(row.returnCase.id)}>{row.references.code}</Link> },
        { key: 'item', header: 'Barang', cell: row => returnItemLabel(row.references.item) },
        { key: 'source', header: 'Asal', cell: row => returnOriginLabels[row.returnCase.origin] },
        { key: 'sourceCode', header: 'Dokumen asal', cell: row => row.references.sourceCode },
        { key: 'amount', header: 'Jumlah retur', cell: row => <WarehouseQuantity value={row.returnCase.quantityBase} unit={row.returnCase.baseUnit} /> },
        { key: 'state', header: 'Status dokumen', cell: row => <span><WarehouseStatus status={row.returnCase.state} />{row.references.rmaHandoverId && <span>{' · '}Serah-terima RMA dibuat</span>}</span> },
        { key: 'owner', header: 'Pemilik', cell: row => <WarehouseStatus status={row.returnCase.legalOwner} /> },
      ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
  </>
}
function ReturnDetail({ id, onChanged }: { id: string; onChanged: () => void }) {
  const loader = useCallback(() => getReturn(id), [id]), result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{details => <ReturnBody details={details} reload={() => { result.reload(); onChanged() }} />}</WarehouseState>
}
function ReturnBody({ details, reload }: { details: ReturnDetails; reload: () => void }) {
  const { can } = useCan(), { returnCase: view, references: refs } = details, [action, setAction] = useState<ReturnAction | null>(null)
  const manage = can('inventory.return.manage'), locations = can('inventory.location.view'), waiting = view.state === 'RECEIVED_IN_INSPECTION' && !refs.rmaHandoverId

  const actions: CommandAction[] = []
  if (manage && waiting && !completedCustomerRepairInspection(details)) actions.push({ key: 'inspect', label: 'Periksa retur', icon: <ClipboardCheck size={16} />, disabled: !locations, onClick: () => setAction('inspect') })
  if (manage && waiting && view.origin === 'ASSET_REMOVAL' && view.inspection && !view.repair) actions.push({ key: 'dispatch', label: 'Kirim ke servis', icon: <Send size={16} />, disabled: !locations || !can('inventory.receipt.view'), onClick: () => setAction('repair-dispatch') })
  if (manage && view.state === 'REPAIR') actions.push({ key: 'receive', label: 'Terima dari servis', icon: <PackageCheck size={16} />, disabled: !locations, onClick: () => setAction('repair-receive') })
  return <div className="warehouse-record">
    {action && <WarehouseReturnActions details={details} action={action} onDone={reload} onClose={() => setAction(null)} />}
    <section className="warehouse-record" aria-label="Detail retur"><h2>{refs.code}</h2>
      <CommandBar primary={actions[0]} actions={[...actions.slice(1), { key: 'refresh', label: 'Muat ulang retur', icon: <RefreshCw size={16} />, onClick: reload }]} />
      <WarehouseFacts items={[
        { label: 'Status', value: <WarehouseStatus status={view.state} /> }, { label: 'Barang', value: returnItemLabel(refs.item) },
        { label: 'Jumlah', value: <WarehouseQuantity value={view.quantityBase} unit={view.baseUnit} /> }, { label: 'Kondisi', value: <WarehouseStatus status={view.condition} /> },
        { label: 'Asal', value: returnOriginLabels[view.origin] }, { label: 'Dokumen asal', value: refs.sourceCode },
        { label: 'Pemilik', value: <WarehouseStatus status={view.legalOwner} /> }, { label: 'Lokasi dokumen', value: returnLocationLabel(details, view.locationId) },
        { label: 'Revisi', value: view.revision }, { label: 'Penerima', value: refs.receivedByName ?? '—' }, { label: 'Dicatat', value: <WarehouseTime value={view.recordedAt} /> },
        ...(view.inspection ? [{ label: 'Bukti inspeksi', value: view.inspection.evidenceReference }, ...(view.inspection.resetConfirmed ? [{ label: 'Bukti reset', value: view.inspection.resetEvidenceReference }] : [])] : []),
        ...(view.repair ? [{ label: 'Penyedia servis', value: refs.vendor?.name ?? refs.vendor?.code }, { label: 'Referensi servis', value: view.repair.vendorReference }] : []),
      ]} />
      {refs.workOrderId && can('inventory.request.view') && can('workorder.order.view') && <Link to={`/warehouse/requests?workOrderId=${encodeURIComponent(refs.workOrderId)}`}>Lihat material {refs.workOrderCode ?? 'work order asal'}</Link>}
      {view.legalOwner === 'CUSTOMER' && <p role="status" className="warehouse-workspace-note">Perangkat tetap milik pelanggan dan tidak menjadi stok tersedia ISP.</p>}
      {needsPostRepairInspection(details) && <p role="status">Perangkat kembali dari servis. Lakukan inspeksi dan reset ulang.</p>}
      {refs.rmaHandoverId && <p role="status">Serah-terima RMA sudah dibuat. Lokasi terkini mengikuti dokumen serah-terima.</p>}
      {!manage && <p className="warehouse-workspace-note">Akses baca saja.</p>}
      {manage && !locations && <p className="warehouse-workspace-note">Izin lihat lokasi diperlukan untuk pemeriksaan dan servis.</p>}
      {manage && waiting && view.origin === 'ASSET_REMOVAL' && view.inspection && !view.repair && !can('inventory.receipt.view') && <p className="warehouse-workspace-note">Pemilihan penyedia servis memerlukan izin lihat penerimaan/pemasok.</p>}
    </section>
    {manage && <WarehouseCustomerRma details={details} reload={reload} />}
    {refs.assetOrigin?.legalOwner === 'CUSTOMER' && can('inventory.approval.view') && <WarehouseReturnReacquisition details={details} reload={reload} />}
    {view.repair && can('inventory.receipt.view') && <WarehouseSupplierReplacements details={details} reload={reload} />}
    {can('inventory.custody.view') && <WarehouseReturnDispositions details={details} reload={reload} />}
    <ReturnHistory details={details} /></div>
}
function ReturnHistory({ details }: { details: ReturnDetails }) {
  const id = details.returnCase.id, [page, setPage] = useState(0), loader = useCallback(() => returnHistory(id, page), [id, page]), result = useWarehouseQuery(loader)
  return <Disclosure title={<>Riwayat retur dan servis</>}><WarehouseState {...result}>{data => <div className="stack">
    <DataTable presentation="warehouse" rows={data.items} rowKey={row => String(row.revision)} columns={[
      { key: 'revision', header: 'Revisi', cell: row => row.revision },
      { key: 'time', header: 'Dicatat', cell: row => <WarehouseTime value={row.recordedAt} /> },
      { key: 'state', header: 'Status', cell: row => <WarehouseStatus status={row.state} /> },
      { key: 'condition', header: 'Kondisi', cell: row => <WarehouseStatus status={row.condition} /> },
      { key: 'location', header: 'Lokasi', cell: row => returnLocationLabel(details, row.locationId) },
      { key: 'inspection', header: 'Bukti inspeksi', cell: row => row.inspection?.evidenceReference ?? '—' },
      { key: 'repair', header: 'Referensi servis', cell: row => row.repair?.vendorReference ?? '—' },
      { key: 'receipt', header: 'Bukti kembali', cell: row => row.repair?.receiptReference ?? '—' },
    ]} />
    <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
  </div>}</WarehouseState></Disclosure>
}
