import { ClipboardCheck, List, RefreshCw } from 'lucide-react'
import { Tabs } from '@/components/molecules/Tabs'
import { CommandBar } from '@/components/molecules/CommandBar'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { FilterBar, FilterSelect } from '@/components/organisms/ResourceFilters'
import './warehouseWorkspaces.css'
import { useCallback, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useCan } from '@/auth/useCan'
import { useAuth } from '@/auth/useAuth'
import { EmptyState } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseState, WarehouseDenied } from '@/components/organisms/warehouse/WarehouseState'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { uuid } from '@/api/warehouse/codec'
import { MIGRATION_SOURCES } from '@/api/warehouse/migrationReview'
import { getMigrationSummary, beginMigration, listMigrationCases } from '@/api/warehouse/provenance'
import type { MigrationSummary } from '@/api/warehouse/provenanceModels'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { WarehouseProvenanceCase } from './WarehouseProvenanceCase'
import { WarehouseProvenanceOpening } from './WarehouseProvenanceOpening'
import { claimLabels, cutoverLabels, migrationCaseLabel, migrationSourceLabels } from './provenancePresentation'

export function WarehouseProvenancePage() {
  const { can } = useCan(), { user } = useAuth(), [params, setParams] = useSearchParams()
  let caseId: string | null = null, openingId: string | null = null, view: 'cases' | 'opening' = 'cases'
  try {
    if ([...params.keys()].some(key => !['caseId', 'openingId', 'view'].includes(key)) ||
      ['caseId', 'openingId', 'view'].some(key => params.getAll(key).length > 1)) throw new Error()
    if (params.has('caseId')) caseId = uuid(params.get('caseId'))
    if (params.has('openingId')) openingId = uuid(params.get('openingId'))
    const selectedView = params.get('view')
    if (selectedView !== null && !['cases', 'opening'].includes(selectedView)) throw new Error()
    if (caseId && openingId) throw new Error()
    view = openingId || selectedView === 'opening' ? 'opening' : 'cases'
  } catch { return <div className="card stack" role="alert"><p>Alamat pemeriksaan gudang tidak dikenal.</p><Link to="/warehouse/provenance">Kembali ke pemeriksaan gudang</Link></div> }
  if (!can('inventory.provenance.manage')) return <WarehouseDenied />
  function showCase(id: string | null) { setParams(id ? { caseId: id } : {}) }
  function showOpening(id: string | null) { setParams(id ? { view: 'opening', openingId: id } : { view: 'opening' }) }
  return <div className="warehouse-workspace"><PageHeader title="Rekonsiliasi Gudang Lama" />
    <ProvenanceWorkspace key={user?.id} caseId={caseId} openingId={openingId} view={view} showCase={showCase} showOpening={showOpening} />
  </div>
}
function workspaceIdentity(summary: MigrationSummary) { return summary.cutover.tenantId + ':' + summary.cutover.epoch + ':' + (summary.batch?.id ?? 'preview') }
function ProvenanceWorkspace({ caseId, openingId, view, showCase, showOpening }: {
  caseId: string | null; openingId: string | null; view: 'cases' | 'opening'; showCase: (id: string | null) => void; showOpening: (id: string | null) => void;
}) {
  const { user } = useAuth()
  const result = useWarehouseQuery(useCallback(async () => {
    if (!user) throw new Error('Sesi berakhir. Masuk kembali untuk membaca laporan.')
    return getMigrationSummary()
  }, [user]))
  return <WarehouseState {...result}>{summary => <WorkspaceContent key={workspaceIdentity(summary)} summary={summary} caseId={caseId} openingId={openingId} view={view} showCase={showCase} showOpening={showOpening} onRefresh={result.reload} />}</WarehouseState>
}
function WorkspaceContent({ summary, caseId, openingId, view, showCase, showOpening, onRefresh }: {
  summary: MigrationSummary; caseId: string | null; openingId: string | null; view: 'cases' | 'opening';
  showCase: (id: string | null) => void; showOpening: (id: string | null) => void; onRefresh: () => void;
}) {
  const [visitedCases, setVisitedCases] = useState(!caseId && view === 'cases'), [visitedOpening, setVisitedOpening] = useState(view === 'opening')
  useEffect(() => { if (!caseId && view === 'cases') setVisitedCases(true); if (view === 'opening') setVisitedOpening(true) }, [caseId, view])
  const opening = view === 'opening' && !!summary.batch
  return <div className="warehouse-workspace-panel">
    <MigrationOverview summary={summary} onRefresh={onRefresh} />
    {summary.cutover.state === 'ENFORCED' && !summary.batch ? <div className="warehouse-workspace-notice"><p>Gudang sudah aktif dengan saldo awal kosong.</p><Link to="/warehouse">Buka gudang</Link></div> : <>
      <Tabs idPrefix="provenance" active={opening ? 'opening' : 'cases'} onChange={tab => tab === 'opening' ? showOpening(null) : showCase(null)}
        tabs={[{ key: 'cases', label: 'Catatan lama' }, ...(summary.batch ? [{ key: 'opening', label: 'Saldo awal & aktivasi' }] : [])]} />
      <div className="warehouse-workspace-panel" role="tabpanel" id="provenance-panel-cases" aria-labelledby="provenance-tab-cases" hidden={opening}>
        {(visitedCases || (!caseId && !opening)) && <CaseList onSelect={showCase} />}
      </div>
      {summary.batch && <div className="warehouse-workspace-panel" role="tabpanel" id="provenance-panel-opening" aria-labelledby="provenance-tab-opening" hidden={!opening}>
        {(visitedOpening || opening) && <WarehouseProvenanceOpening summary={summary} selected={openingId} onSelect={showOpening} onRefresh={onRefresh} />}
      </div>}
      {caseId && <WarehouseProvenanceCase key={caseId} id={caseId} summary={summary} onClose={() => showCase(null)} onRefresh={onRefresh} />}
    </>}
  </div>
}
function MigrationOverview({ summary, onRefresh }: { summary: MigrationSummary; onRefresh: () => void }) {
  const [operation, setOperation] = useState<WarehouseCommand<MigrationSummary> | null>(null), [sources, setSources] = useState(false)
  const pending = summary.pendingLegacyMovementCount + summary.pendingLegacyFulfillmentCount + summary.pendingLegacyOutboxCount
  return <section className="warehouse-workspace-panel" aria-label="Ringkasan pemeriksaan">
    <CommandBar primary={!summary.batch && summary.cutover.state !== 'ENFORCED' ? { key: 'begin', label: 'Mulai pemeriksaan gudang', icon: <ClipboardCheck size={16} />, onClick: () => setOperation(beginMigration(summary)) } : undefined}
      actions={[{ key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, onClick: onRefresh }, { key: 'sources', label: 'Rincian sumber', icon: <List size={16} />, onClick: () => setSources(true) }]} />
    <div className="warehouse-workspace-stage"><strong>{cutoverLabels[summary.cutover.state]}</strong><p className="warehouse-workspace-note">{summary.cutover.state === 'VALIDATING' ? 'Periksa bukti, ajukan saldo awal, lalu aktifkan gudang.' : summary.cutover.state === 'ENFORCED' ? 'Saldo awal telah ditetapkan. Riwayat pemeriksaan tetap tersedia.' : 'Periksa catatan lama sebelum menetapkan saldo awal gudang.'}</p></div>
    <dl className="warehouse-workspace-metrics">
      {[['Catatan sumber', summary.sourceCount], ['Konflik identitas', summary.conflictGroupCount], ['Satuan belum terverifikasi', summary.unitUnverifiedBalanceCount], ['Transaksi tertunda', pending]].map(([label, count]) => <div key={label}><dt>{label}</dt><dd>{count}</dd></div>)}
    </dl>
    {summary.batch && <p className="warehouse-workspace-note">Jumlah sumber dicatat saat pemeriksaan dimulai.</p>}
    {sources && <ResourceForm readOnly title="Rincian sumber" onClose={() => setSources(false)} onBack={() => {}}><DataTable presentation="warehouse" rows={Object.entries(summary.sourceCounts).map(([source, count]) => ({ source, count }))} rowKey={row => row.source} columns={[
      { key: 'source', header: 'Jenis catatan', cell: row => migrationSourceLabels[row.source as keyof typeof migrationSourceLabels] },
      { key: 'count', header: 'Jumlah', cell: row => row.count, align: 'right' },
    ]} /></ResourceForm>}
    {operation && <WarehouseCommandDialog title="Mulai pemeriksaan gudang lama" command={operation} confirmLabel="Mulai pemeriksaan"
      summary={<div className="stack"><p>{summary.sourceCount} catatan sumber akan menjadi dasar pemeriksaan ini.</p>
        {summary.sourceCount === 0 && <p>Tidak ada catatan stok lama pada laporan. Saldo awal nol tetap memerlukan pemeriksaan dan persetujuan independen.</p>}
        <p>Transaksi stok baru ditahan selama pemeriksaan. Data lama dan pemantauan perangkat tetap dapat dibaca. Hasil pemeriksaan harus disetujui sebelum gudang diaktifkan.</p></div>}
      onClose={() => setOperation(null)} onDone={onRefresh} onReload={onRefresh} />}
  </section>
}
function CaseList({ onSelect }: { onSelect: (id: string) => void }) {
  const [kind, setKind] = useState<typeof MIGRATION_SOURCES[number] | ''>(''), [page, setPage] = useState(0)
  const result = useWarehouseQuery(useCallback(() => listMigrationCases(page, kind || undefined), [page, kind]))
  return <section className="warehouse-workspace-panel" aria-label="Catatan lama">
    <FilterBar><FilterSelect label="Jenis catatan" value={kind} onChange={value => { setKind(value as typeof kind); setPage(0) }}><option value="">Semua sumber</option>
      {MIGRATION_SOURCES.map(source => <option key={source} value={source}>{migrationSourceLabels[source]}</option>)}</FilterSelect></FilterBar>
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id}
        empty={<EmptyState title="Tidak ada catatan" hint="Pilih jenis catatan lain atau lanjutkan pemeriksaan saldo awal." />} columns={[
          { key: 'identity', header: 'Catatan asli', cell: migrationCaseLabel, onCellClick: row => onSelect(row.id) },
          { key: 'source', header: 'Jenis catatan', cell: row => migrationSourceLabels[row.sourceTable] },
          { key: 'location', header: 'Lokasi', cell: row => row.location?.name || row.location?.code || 'Belum terbukti' },
          { key: 'quantity', header: 'Kuantitas lama', cell: row => row.source.legacyQuantity === null ? 'Lihat catatan asli' : row.source.legacyQuantity + ' · ' + (row.source.baseUnit || 'satuan belum terbukti') },
          { key: 'claims', header: 'Identitas', cell: row => !row.claims.length ? 'Lihat bukti sumber' : [...new Set(row.claims.map(claim => claim.state ? claimLabels[claim.state] : 'Format perlu diperiksa'))].join(' · ') },

        ]} />
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
  </section>
}
