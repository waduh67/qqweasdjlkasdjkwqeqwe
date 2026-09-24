import { useCallback, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
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
import { WarehouseReturnActions, type ReturnAction } from './WarehouseReturnActions'
import { WarehouseReturnEditor } from './WarehouseReturnEditor'
import { WarehouseReturnFilters } from './WarehouseReturnFilters'
import { WarehouseSupplierReplacements } from './WarehouseSupplierReplacement'
import { WarehouseCustomerRma } from './WarehouseCustomerRma'
import { completedCustomerRepairInspection, needsPostRepairInspection } from './returnDraft'
import { returnItemLabel, returnLocationLabel, returnOriginLabels } from './returnPresentation'

const detailPath = (id: string) => `/warehouse/returns?returnId=${encodeURIComponent(id)}`
export function WarehouseReturnsPage() {
  const { can } = useCan(), [params] = useSearchParams(), navigate = useNavigate(), [creating, setCreating] = useState(false)
  let id: string | null = null
  try {
    if ([...params.keys()].some(key => key !== 'returnId') || params.getAll('returnId').length > 1) throw new Error()
    if (params.has('returnId')) id = uuid(params.get('returnId'))
  } catch { return <div className="card stack" role="alert"><p>Alamat retur tidak dikenal.</p><Link to="/warehouse/returns">Kembali ke daftar retur</Link></div> }
  if (!can('inventory.return.view')) return <WarehouseDenied />
  return <div className="stack warehouse-returns"><PageHeader title="Retur & Servis" subtitle="Terima sumber yang sah, periksa kondisi fisik, dan ikuti perangkat selama servis." />
    {creating ? <WarehouseReturnEditor onSaved={row => { setCreating(false); navigate(detailPath(row.id)) }} onClose={() => setCreating(false)} onReload={() => setCreating(false)} />
      : id ? <><Link to="/warehouse/returns">Kembali ke daftar retur</Link><ReturnDetail key={id} id={id} /></>
        : <>{can('inventory.return.manage') && <Button variant="primary" disabled={!can('inventory.location.view')} onClick={() => setCreating(true)}>Terima retur baru</Button>}
          {can('inventory.return.manage') && !can('inventory.location.view') && <p className="muted">Izin lihat lokasi diperlukan untuk memilih karantina penerimaan.</p>}<ReturnList /></>}
  </div>
}
function ReturnList() {
  const [filter, setFilter] = useState<ReturnFilter>({}), [page, setPage] = useState(0)
  const loader = useCallback(() => listReturns({ ...filter, page }), [filter, page]), result = useWarehouseQuery(loader)
  return <><WarehouseReturnFilters onApply={filter => { setFilter(filter); setPage(0) }} /><Button onClick={result.reload}>Segarkan retur</Button>
    <WarehouseState {...result}>{data => <><DataTable presentation="warehouse" rows={data.items} rowKey={row => row.returnCase.id}
      empty={<EmptyState title="Belum ada retur dalam cakupan Anda" hint="Sumber retur berasal dari sisa material yang sudah diterima atau perangkat hasil pelepasan yang sah." />} columns={[
        { key: 'code', header: 'Retur', cell: row => <Link to={detailPath(row.returnCase.id)}>{row.references.code}</Link> },
        { key: 'item', header: 'Barang', cell: row => returnItemLabel(row.references.item) },
        { key: 'source', header: 'Asal', cell: row => <span>{returnOriginLabels[row.returnCase.origin]}<br />{row.references.sourceCode}</span> },
        { key: 'amount', header: 'Jumlah retur', cell: row => <WarehouseQuantity value={row.returnCase.quantityBase} unit={row.returnCase.baseUnit} /> },
        { key: 'state', header: 'Status dokumen', cell: row => <span><WarehouseStatus status={row.returnCase.state} />{row.references.rmaHandoverId && <p>Serah-terima RMA dibuat</p>}</span> },
        { key: 'owner', header: 'Pemilik', cell: row => <WarehouseStatus status={row.returnCase.legalOwner} /> },
      ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
  </>
}
function ReturnDetail({ id }: { id: string }) {
  const loader = useCallback(() => getReturn(id), [id]), result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{details => <ReturnBody details={details} reload={result.reload} />}</WarehouseState>
}
function ReturnBody({ details, reload }: { details: ReturnDetails; reload: () => void }) {
  const { can } = useCan(), { returnCase: view, references: refs } = details, [action, setAction] = useState<ReturnAction | null>(null)
  const manage = can('inventory.return.manage'), locations = can('inventory.location.view'), waiting = view.state === 'RECEIVED_IN_INSPECTION' && !refs.rmaHandoverId
  if (action) return <WarehouseReturnActions details={details} action={action} onDone={reload} onClose={() => setAction(null)} />
  return <><section className="card stack" aria-label="Detail retur"><h2 style={{ overflowWrap: 'anywhere' }}>{refs.code}</h2>
    <p><WarehouseStatus status={view.state} /> · Revisi {view.revision} · <WarehouseTime value={view.recordedAt} /></p>
    <h3>{returnItemLabel(refs.item)}</h3><p><WarehouseQuantity value={view.quantityBase} unit={view.baseUnit} /> · <WarehouseStatus status={view.condition} /> · <WarehouseStatus status={view.legalOwner} /></p>
    <p style={{ overflowWrap: 'anywhere' }}>Sumber: {returnOriginLabels[view.origin]} · {refs.sourceCode}</p>
    <p>Dicatat oleh {refs.receivedByName ?? 'petugas penerimaan'} · Lokasi pada dokumen: {returnLocationLabel(details, view.locationId)}</p>
    {refs.workOrderId && can('inventory.request.view') && can('workorder.order.view') && <Link to={`/warehouse/requests?workOrderId=${encodeURIComponent(refs.workOrderId)}`}>Lihat material {refs.workOrderCode ?? 'work order asal'}</Link>}
    {view.legalOwner === 'CUSTOMER' && <p role="status">Perangkat tetap milik pelanggan. Perbaikan, reset, atau kondisi layak pakai tidak menjadikannya stok tersedia ISP.</p>}
    {needsPostRepairInspection(details) && <p role="status">Perangkat sudah kembali dari servis dan perlu inspeksi serta reset ulang.</p>}
    {view.inspection && <p>Inspeksi terakhir: {view.inspection.evidenceReference}. {view.inspection.resetConfirmed && `Bukti reset: ${view.inspection.resetEvidenceReference}.`}</p>}
    {view.repair && <p>Servis: {refs.vendor?.name ?? refs.vendor?.code} · {view.repair.vendorReference}{view.repair.returnedRevision !== null && ` · Kembali pada revisi ${view.repair.returnedRevision} (${view.repair.result === 'REPAIRED' ? 'diperbaiki' : 'belum diperbaiki'})`}</p>}
    {refs.rmaHandoverId && <p style={{ overflowWrap: 'anywhere' }}>Serah-terima RMA sudah dibuat: {refs.rmaHandoverId}. Lokasi dokumen retur adalah catatan sebelumnya; pergerakan berikutnya mengikuti dokumen serah-terima.</p>}
    <div className="row wrap"><Button onClick={reload}>Muat ulang retur</Button>
      {manage && waiting && !completedCustomerRepairInspection(details) && <Button variant="primary" disabled={!locations} onClick={() => setAction('inspect')}>Periksa retur</Button>}
      {manage && waiting && view.origin === 'ASSET_REMOVAL' && view.inspection && !view.repair && <Button disabled={!locations || !can('inventory.receipt.view')} onClick={() => setAction('repair-dispatch')}>Kirim ke servis</Button>}
      {manage && view.state === 'REPAIR' && <Button variant="primary" disabled={!locations} onClick={() => setAction('repair-receive')}>Terima dari servis</Button>}
    </div>
    {!manage && <p className="muted">Akses baca saja. Tindakan memerlukan izin kelola retur.</p>}
    {manage && !locations && <p className="muted">Izin lihat lokasi diperlukan untuk memilih tujuan pemeriksaan atau servis.</p>}
    {manage && waiting && view.origin === 'ASSET_REMOVAL' && view.inspection && !view.repair && !can('inventory.receipt.view') && <p className="muted">Pemilihan penyedia servis memerlukan izin lihat penerimaan/pemasok.</p>}
  </section>
    {manage && <WarehouseCustomerRma details={details} reload={reload} />}
    {view.repair && can('inventory.receipt.view') && <WarehouseSupplierReplacements details={details} reload={reload} />}
    <ReturnHistory details={details} /></>
}
function ReturnHistory({ details }: { details: ReturnDetails }) {
  const id = details.returnCase.id, [page, setPage] = useState(0), loader = useCallback(() => returnHistory(id, page), [id, page]), result = useWarehouseQuery(loader)
  return <details className="card"><summary>Riwayat retur dan servis</summary><WarehouseState {...result}>{data => <div className="stack">{data.items.map(row => <section className="stack" key={row.revision}>
    <h3>Revisi {row.revision} · <WarehouseStatus status={row.state} /></h3><p><WarehouseTime value={row.recordedAt} /></p>
    <p><WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> · <WarehouseStatus status={row.condition} /> · <WarehouseStatus status={row.legalOwner} /></p>
    <p>{returnLocationLabel(details, row.locationId)}</p>{row.inspection && <p>Bukti inspeksi: {row.inspection.evidenceReference}</p>}
    {row.repair && <p>Referensi servis: {row.repair.vendorReference}{row.repair.receiptReference && ` · Kembali: ${row.repair.receiptReference}`}</p>}
  </section>)}<WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} /></div>}</WarehouseState></details>
}
