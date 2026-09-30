import { WarehouseDraftExpired } from '@/components/organisms/warehouse/WarehouseDraftExpired'
import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { listCompensations, listDispositions, type Disposition } from '@/api/warehouse/dispositions'
import type { ReturnDetails } from '@/api/warehouse/returns'
import { useCan } from '@/auth/useCan'
import { Button } from '@/components/atoms'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseDispositionForm } from './WarehouseDispositionForm'

export function WarehouseReturnDispositions({ details, reload }: { details: ReturnDetails; reload: () => void }) {
  const { can } = useCan(), { returnCase: returned } = details
  const [page, setPage] = useState(0), [creating, setCreating] = useState(false)
  const loader = useCallback(() => listDispositions(returned.id, page), [returned.id, page]), result = useWarehouseQuery(loader)
  const manage = ['inventory.custody.manage', 'inventory.return.manage', 'inventory.approval.request', 'inventory.approval.view', 'inventory.location.view'].every(can)
  return <section className="card stack" aria-label="Disposisi dan koreksi retur"><h2>Disposisi dan koreksi retur</h2>
    <p>Kehilangan, scrap, dan koreksi dicatat melalui dokumen dengan pemeriksa independen. Dokumen terdahulu tetap tersimpan.</p>
    {creating ? <WarehouseDispositionForm details={details} onClose={() => setCreating(false)} onDone={() => { setCreating(false); result.reload() }} onReload={reload} />
      : manage && returned.legalOwner === 'ISP' && returned.state === 'RECEIVED_IN_INSPECTION' && !details.references.rmaHandoverId && <Button onClick={() => setCreating(true)}>Ajukan kehilangan / scrap</Button>}
    {returned.legalOwner === 'CUSTOMER' && <p>Barang pelanggan memerlukan penyelesaian kepemilikan sebelum disposisi ISP.</p>}
    <Button onClick={result.reload}>Muat ulang disposisi</Button><WarehouseState {...result}>{data => <>
      {data.items.length ? data.items.map(row => <section className="stack" key={row.id}><h3 style={{ overflowWrap: 'anywhere' }}>{row.code}</h3><p>{row.action === 'LOSS' ? 'Kehilangan' : 'Scrap'} · <WarehouseStatus status={row.state} /> · Revisi {row.revision}</p>
        <p><WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> · Retur sumber revisi {row.sourceRevision} · <WarehouseTime value={row.recordedAt} /></p>
        <WarehouseDraftExpired expiry={row.draftExpiry} /><p>{row.reason}</p><p style={{ overflowWrap: 'anywhere' }}>Bukti: {row.evidenceReference}</p>
        {can('inventory.approval.view') && <Link to={`/warehouse/approvals?sourceDocumentId=${encodeURIComponent(row.id)}`}>Buka persetujuan {row.code}</Link>}
        {row.state === 'POSTED' && <Compensations details={details} original={row} manage={manage} reload={reload} />}
      </section>) : <p>Belum ada disposisi pada halaman ini.</p>}
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
  </section>
}
function Compensations({ details, original, manage, reload }: { details: ReturnDetails; original: Disposition; manage: boolean; reload: () => void }) {
  const { can } = useCan(), [page, setPage] = useState(0), [creating, setCreating] = useState(false)
  const loader = useCallback(() => listCompensations(original.id, page), [original.id, page]), result = useWarehouseQuery(loader)
  return <div className="stack"><h4>Koreksi untuk {original.code}</h4>
    {creating ? <WarehouseDispositionForm details={details} original={original} onClose={() => setCreating(false)} onDone={() => { setCreating(false); result.reload() }} onReload={reload} />
      : manage && details.returnCase.legalOwner === 'ISP' && ['LOST', 'SCRAP'].includes(details.returnCase.state) && <Button onClick={() => setCreating(true)}>Ajukan koreksi ke karantina</Button>}
    <WarehouseState {...result}>{data => <>{data.items.map(row => <div key={row.id}><p>{row.code} · <WarehouseStatus status={row.state} /> · <WarehouseTime value={row.recordedAt} /></p>
      <WarehouseDraftExpired expiry={row.draftExpiry} /><p>{row.reason} · Bukti: {row.evidenceReference}</p><p style={{ overflowWrap: 'anywhere' }}>Pembukuan yang dikoreksi: {row.originalPostingId}</p>
      {can('inventory.approval.view') && <Link to={`/warehouse/approvals?sourceDocumentId=${encodeURIComponent(row.id)}`}>Buka persetujuan {row.code}</Link>}</div>)}
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
  </div>
}
