import { useCallback, useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { uuid } from '@/api/warehouse/codec'
import { approvalHistory, approvalWorkbench, evaluateApprovalSource, getApprovalDetails, getApprovalSource, type ApprovalDetails, type ApprovalDocument, type ApprovalFilter } from '@/api/warehouse/approvalReads'
import { decideApproval, requestApproval, reworkApproval, type WarehouseApproval } from '@/api/warehouse/approvals'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, TextareaField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseApprovalDocument } from './WarehouseApprovalDocument'
import { WarehouseApprovalEvidence } from './WarehouseApprovalEvidence'
import { WarehouseApprovalFilters } from './WarehouseApprovalFilters'
import { approvalBlockLabels, approvalCostLabel, approvalImpact, approvalOperationLabels, approvalPersonLabel } from './approvalPresentation'

const approvalPath = (id: string) => `/warehouse/approvals?approvalId=${encodeURIComponent(id)}`
const sourcePath = (id: string) => `/warehouse/approvals?sourceDocumentId=${encodeURIComponent(id)}`
export function WarehouseApprovalsPage() {
  const { can } = useCan(), [params] = useSearchParams()
  let id: string | null = null, sourceId: string | null = null
  try {
    if ([...params.keys()].some(key => !['approvalId', 'sourceDocumentId'].includes(key)) || params.getAll('approvalId').length > 1 || params.getAll('sourceDocumentId').length > 1 || (params.has('approvalId') && params.has('sourceDocumentId'))) throw new Error()
    if (params.has('approvalId')) id = uuid(params.get('approvalId'))
    if (params.has('sourceDocumentId')) sourceId = uuid(params.get('sourceDocumentId'))
  } catch { return <div className="card stack" role="alert"><p>Alamat persetujuan tidak dikenal.</p><Link to="/warehouse/approvals">Kembali ke daftar persetujuan</Link></div> }
  if (!can('inventory.approval.view')) return <WarehouseDenied />
  return <div className="stack"><PageHeader title="Persetujuan Gudang" subtitle="Periksa dokumen sumber dan ambil keputusan sesuai tahap serta kewenangan saat ini." />
    {id ? <><Link to="/warehouse/approvals">Kembali ke daftar persetujuan</Link><ApprovalDetail key={id} id={id} /></>
      : sourceId ? <><Link to="/warehouse/approvals">Kembali ke daftar persetujuan</Link><ApprovalSource key={sourceId} id={sourceId} /></> : <ApprovalList />}
  </div>
}
function ApprovalList({ sourceId }: { sourceId?: string }) {
  const [filter, setFilter] = useState<ApprovalFilter>({}), [page, setPage] = useState(0)
  const loader = useCallback(() => approvalWorkbench({ ...filter, sourceDocumentId: sourceId, page }), [filter, sourceId, page]), result = useWarehouseQuery(loader)
  return <section className="stack" aria-label="Daftar persetujuan">
    {sourceId ? <h2>Permintaan tersimpan untuk dokumen ini</h2> : <WarehouseApprovalFilters onApply={filter => { setFilter(filter); setPage(0) }} />}
    <Button onClick={result.reload}>Segarkan persetujuan</Button><WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.approval.requestId}
        empty={<EmptyState title="Belum ada permintaan persetujuan dalam cakupan Anda" hint="Ajukan dari dokumen sumber yang siap diperiksa. Permintaan lama tetap dapat dibuka untuk melihat keputusannya." />} columns={[
          { key: 'document', header: 'Dokumen', cell: row => <Link to={approvalPath(row.approval.requestId)}>{row.documentCode} · Revisi {row.approval.sourceRevision}</Link> },
          { key: 'operation', header: 'Jenis', cell: row => approvalOperationLabels[row.operation] },
          { key: 'requester', header: 'Pembuat', cell: row => approvalPersonLabel(row.requester) },
          { key: 'state', header: 'Status', cell: row => <WarehouseStatus status={row.approval.status} /> },
          { key: 'date', header: 'Diajukan', cell: row => <WarehouseTime value={row.requestedAt} /> },
          { key: 'expiry', header: 'Berlaku sampai', cell: row => <WarehouseTime value={row.approval.expiresAt} /> },
        ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState></section>
}
function ApprovalSource({ id }: { id: string }) {
  const loader = useCallback(() => getApprovalSource(id), [id]), result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{source => <><WarehouseApprovalDocument document={source.document} />
    <SourceRequest document={source.document} allowed={source.canRequest} block={source.requestBlock} reload={result.reload} /><ApprovalList sourceId={id} />
  </>}</WarehouseState>
}
function SourceRequest({ document, allowed, block, reload }: { document: ApprovalDocument; allowed: boolean; block: string | null; reload: () => void }) {
  const { can } = useCan(), { user } = useAuth(), [checking, setChecking] = useState(false)
  const requester = user?.id === document.requester.id && can('inventory.approval.request')
  return <section className="card stack" aria-label="Pengajuan persetujuan"><p>{approvalImpact(document.kind)}</p>
    <Button onClick={reload}>Muat ulang sumber persetujuan</Button>
    {allowed && requester ? checking ? <RequestRequirements document={document} reload={reload} /> : <Button variant="primary" onClick={() => setChecking(true)}>Periksa persyaratan persetujuan</Button>
      : <p role="status">{block ? approvalBlockLabels[block] ?? 'Dokumen belum dapat diajukan oleh akun ini.' : 'Pengajuan memerlukan pembuat dokumen dengan izin ajukan persetujuan.'}</p>}
    {document.kind === 'COUNT' && document.state === 'RECOUNT_REQUIRED' && <p>Mulai hitung ulang dari dokumen opname, lalu ajukan hasil putaran baru.</p>}
    <p className="muted">Pembuat dan pihak yang terlibat dalam transaksi tidak dapat menjadi pemeriksa independen untuk permintaan ini.</p>
  </section>
}
function RequestRequirements({ document, reload }: { document: ApprovalDocument; reload: () => void }) {
  const navigate = useNavigate(), { can } = useCan()
  const loader = useCallback(() => evaluateApprovalSource(document.id, document.revision), [document.id, document.revision]), result = useWarehouseQuery(loader)
  const [operation, setOperation] = useState<WarehouseCommand<WarehouseApproval> | null>(null)
  return <><WarehouseState {...result}>{evaluation => evaluation.code === 'APPROVAL_REQUIRED' && evaluation.requiredAction === 'REQUEST_APPROVAL'
    ? <><p>Dokumen memerlukan persetujuan independen berdasarkan kebijakan saat ini.</p><Button variant="primary" onClick={() => setOperation(requestApproval({ sourceDocumentId: document.id, sourceRevision: document.revision }))}>Ajukan persetujuan</Button></>
    : <p role="status">Persetujuan tidak diperlukan menurut kebijakan saat ini. Lanjutkan tindakan pada dokumen sumber.</p>}</WarehouseState>
    {can('inventory.approval.manage') && <Link to="/warehouse/settings">Periksa setelan persetujuan</Link>}
    {operation && <WarehouseCommandDialog title="Ajukan dokumen untuk persetujuan" confirmLabel="Kirim permintaan persetujuan" command={operation}
      onDone={approval => navigate(approvalPath(approval.requestId))} onClose={() => setOperation(null)} onReload={reload}
      summary={<><p>{document.code} · Revisi sumber {document.revision}</p><p>{approvalImpact(document.kind)}</p><p>Pengajuan menyimpan dokumen dan persyaratan pemeriksa. Perubahan stok hanya dibukukan setelah seluruh keputusan yang diperlukan terpenuhi.</p></>} />}
  </>
}
function ApprovalDetail({ id }: { id: string }) {
  const loader = useCallback(() => getApprovalDetails(id), [id]), result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{details => <ApprovalBody details={details} reload={result.reload} />}</WarehouseState>
}
function ApprovalBody({ details, reload }: { details: ApprovalDetails; reload: () => void }) {
  const { can } = useCan(), { user } = useAuth(), { approval, document, actions } = details
  const [decision, setDecision] = useState<'APPROVE' | 'REJECT' | null>(null), [reworking, setReworking] = useState(false)
  const own = user?.id === document.requester.id
  return <><section className="card stack" aria-label="Status persetujuan"><h2><WarehouseStatus status={approval.status} /></h2>
    <p>Revisi permintaan {approval.revision} · Diajukan <WarehouseTime value={details.requestedAt} /></p>
    <p>Berlaku sampai <WarehouseTime value={approval.expiresAt} /></p>
    <p>Dokumen yang diperiksa: revisi {document.revision}. Sumber saat ini: revisi {details.currentSourceRevision}, <WarehouseStatus status={details.currentSourceState} />.</p>
    <p>{approvalImpact(document.kind)}</p><div className="row wrap"><Button onClick={reload}>Muat ulang persetujuan</Button><Link to={sourcePath(document.id)}>Buka sumber dan pengajuan lainnya</Link></div>
    {details.cost && can('inventory.cost.view') && <p>Nilai persetujuan: <strong>{approvalCostLabel(details.cost)}</strong></p>}
    {details.effect && <section className="stack" aria-label="Hasil persetujuan dibukukan"><h3>Keputusan akhir sudah dibukukan</h3>
      <p><WarehouseTime value={details.effect.recordedAt} /></p><p>{approvalImpact(document.kind)}</p>
      <p style={{ overflowWrap: 'anywhere' }}>Referensi pembukuan: {details.effect.operationId}</p>
      <p>{details.effect.movementIds.length} catatan pergerakan tersimpan. Buka dokumen sumber untuk kondisi terbaru.</p>
    </section>}
    {approval.status === 'PENDING' && <p>Tahap pemeriksaan saat ini: {actions.currentTier ?? 'Belum tersedia'}. Keputusan pada tahap berikutnya memerlukan pemeriksa yang berbeda.</p>}
    {actions.canDecide && can('inventory.approval.decide') && !own ? <div className="row wrap"><Button variant="primary" onClick={() => setDecision('APPROVE')}>Setujui permintaan</Button><Button onClick={() => setDecision('REJECT')}>Kembalikan untuk perbaikan</Button></div>
      : approval.status === 'PENDING' && <p role="status">{own ? approvalBlockLabels.INDEPENDENT_APPROVER_REQUIRED : approvalBlockLabels[actions.decisionBlock ?? 'NOT_CURRENT_APPROVER'] ?? approvalBlockLabels.NOT_CURRENT_APPROVER}</p>}
    {actions.canRework && own && can('inventory.approval.request') && <Button onClick={() => setReworking(true)}>Buka perbaikan dokumen</Button>}
    {['EXPIRED', 'STALE', 'REWORK_REQUIRED'].includes(approval.status) && <p>Periksa catatan keputusan dan dokumen sumber. Penghitungan perlu putaran baru; permintaan berbasis bukti baru dibuat dari alur sumbernya.</p>}
  </section><WarehouseApprovalDocument document={document} /><WarehouseApprovalEvidence key={approval.requestId} id={approval.requestId} />
    <details className="card"><summary>Persyaratan pemeriksa tersimpan</summary><p>Versi kebijakan {details.policy.revision}. Kelayakan tindakan tetap diperiksa terhadap izin dan cakupan saat ini.</p>
      <ol>{details.policy.tiers.map(tier => <li key={tier.number}>Tahap {tier.number}: {tier.approvers.map(approvalPersonLabel).join(', ')}</li>)}</ol></details>
    <ApprovalHistory id={approval.requestId} />
    {decision && <ApprovalDecision details={details} decision={decision} onClose={() => setDecision(null)} reload={reload} />}
    {reworking && <ApprovalRework details={details} onClose={() => setReworking(false)} reload={reload} />}
  </>
}
function ApprovalDecision({ details, decision, onClose, reload }: { details: ApprovalDetails; decision: 'APPROVE' | 'REJECT'; onClose: () => void; reload: () => void }) {
  const [reason, setReason] = useState(''), [error, setError] = useState(''), [operation, setOperation] = useState<WarehouseCommand<WarehouseApproval> | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim() || reason.trim().length > 500) { setError('Isi alasan keputusan atau referensi pemeriksaan, maksimal 500 karakter.'); return }
    setOperation(decideApproval({ requestId: details.approval.requestId, expectedRevision: details.approval.revision, decision, reason: reason.trim() })); setError('')
  }
  return <><form className="card stack" aria-label="Keputusan persetujuan" onSubmit={prepare}><h2>{decision === 'APPROVE' ? 'Setujui permintaan' : 'Kembalikan untuk perbaikan'}</h2>
    <p>{details.document.code} · Revisi permintaan {details.approval.revision} · Tahap {details.actions.currentTier}</p>
    <TextareaField label="Alasan keputusan / referensi pemeriksaan" required maxLength={500} value={reason} onChange={(_, data) => setReason(data.value)} />
    {error && <p className="error" role="alert">{error}</p>}
    <div className="row wrap"><Button type="button" onClick={onClose}>Batal</Button><Button type="submit" variant="primary">Tinjau keputusan</Button></div>
  </form>{operation && <WarehouseCommandDialog title="Konfirmasi keputusan persetujuan" confirmLabel="Simpan keputusan" command={operation} onDone={reload} onClose={() => setOperation(null)} onReload={reload}
    summary={<><p>{details.document.code} · Revisi permintaan {details.approval.revision}</p><p>{decision === 'APPROVE' ? 'Menyetujui tahap pemeriksaan saat ini.' : 'Mengembalikan permintaan untuk diperbaiki. Tidak ada penyesuaian stok dari penolakan ini.'}</p>
      <p>{reason}</p>{decision === 'APPROVE' && <p>{approvalImpact(details.document.kind)}</p>}</>} />}</>
}
function ApprovalRework({ details, onClose, reload }: { details: ApprovalDetails; onClose: () => void; reload: () => void }) {
  const navigate = useNavigate()
  const [operation] = useState(() => reworkApproval(details.approval.requestId, details.actions.reworkSourceRevision))
  return <WarehouseCommandDialog title="Buka perbaikan dokumen sumber" confirmLabel="Buka perbaikan" command={operation} onClose={onClose} onReload={reload}
    onDone={result => navigate(sourcePath(result.sourceDocumentId))} summary={<><p>{details.document.code} · Revisi sumber saat ini {details.actions.reworkSourceRevision}</p>
      <p>Dokumen kembali menjadi draft dengan revisi baru. Perbaiki isinya melalui alur sumber, lalu periksa persyaratan dan ajukan kembali. Keputusan lama tetap tersimpan.</p></>} />
}
function ApprovalHistory({ id }: { id: string }) {
  const [page, setPage] = useState(0), loader = useCallback(() => approvalHistory(id, page), [id, page]), result = useWarehouseQuery(loader)
  return <details className="card"><summary>Riwayat keputusan</summary><WarehouseState {...result}>{data => <div className="stack">
    {!data.items.length && <p>Belum ada keputusan pemeriksa.</p>}
    {data.items.map(row => <section key={row.id}><h3>Tahap {row.tier} · {row.decision === 'APPROVE' ? 'Disetujui' : 'Dikembalikan untuk perbaikan'}</h3>
      <p>{approvalPersonLabel(row.approver)}{row.delegatedFrom && ` atas delegasi ${approvalPersonLabel(row.delegatedFrom)}`} · <WarehouseTime value={row.decidedAt} /> · Revisi {row.revision}</p>
      {row.reason && <p>{row.reason}</p>}{row.evidenceReference && <p style={{ overflowWrap: 'anywhere' }}>Bukti: {row.evidenceReference}</p>}
    </section>)}<WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
  </div>}</WarehouseState></details>
}
