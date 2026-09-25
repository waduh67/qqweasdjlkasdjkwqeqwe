import { useCallback, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useCan } from '@/auth/useCan'
import { useAuth } from '@/auth/useAuth'
import { Button, EmptyState, SelectField } from '@/components/atoms'
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
  return <div className="stack"><PageHeader title="Rekonsiliasi Gudang Lama" subtitle="Periksa catatan asli, bukti fisik, dan identitas sebelum mengaktifkan stok pada sistem gudang." />
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
  return <WarehouseState {...result}>{summary => <div key={workspaceIdentity(summary)} className="stack">
    <MigrationOverview summary={summary} onRefresh={result.reload} />
    {summary.cutover.state === 'ENFORCED' && !summary.batch ? <section className="card stack"><p>Tenant ini sudah aktif tanpa batch rekonsiliasi lama. Gudang dimulai dengan saldo kosong.</p><Link to="/warehouse">Buka ringkasan gudang</Link></section> : <>
      <nav className="row wrap" aria-label="Tahap rekonsiliasi"><Button variant={view === 'cases' ? 'primary' : 'default'} onClick={() => showCase(null)}>Kasus data lama</Button>
        {summary.batch && <Button variant={view === 'opening' ? 'primary' : 'default'} onClick={() => showOpening(null)}>Saldo awal & aktivasi</Button>}</nav>
      {view === 'opening' && summary.batch ? <WarehouseProvenanceOpening summary={summary} selected={openingId} onSelect={showOpening} onCase={showCase} onRefresh={result.reload} />
        : caseId ? <WarehouseProvenanceCase key={caseId} id={caseId} summary={summary} onClose={() => showCase(null)} onRefresh={result.reload} />
        : <CaseList onSelect={showCase} />}
    </>}
  </div>}</WarehouseState>
}
function MigrationOverview({ summary, onRefresh }: { summary: MigrationSummary; onRefresh: () => void }) {
  const [operation, setOperation] = useState<WarehouseCommand<MigrationSummary> | null>(null)
  const pending = summary.pendingLegacyMovementCount + summary.pendingLegacyFulfillmentCount + summary.pendingLegacyOutboxCount
  return <section className="card stack" aria-label="Ringkasan pemeriksaan"><h2>{cutoverLabels[summary.cutover.state]}</h2>
    <p>{summary.sourceCount} catatan sumber · {summary.conflictGroupCount} kelompok identitas berbenturan · {summary.unitUnverifiedBalanceCount} saldo dengan satuan belum terbukti</p>
    <p>{pending} efek atau antrean tertunda {summary.batch ? 'pada saat pemeriksaan dimulai' : 'pada data saat ini'}. Status pembatalannya diperiksa pada tahap aktivasi.</p>
    <p>Data lama, serial, tautan pelanggan, dan riwayat tetap tersimpan. Catatan yang belum terbukti tidak dianggap stok yang dapat dikeluarkan.</p>
    <details><summary>Lihat jumlah menurut sumber</summary><ul>{Object.entries(summary.sourceCounts).map(([source, count]) =>
      <li key={source}>{migrationSourceLabels[source as keyof typeof migrationSourceLabels]}: {count}</li>)}</ul></details>
    <div className="row wrap"><Button onClick={onRefresh}>Muat ulang laporan gudang lama</Button>
      {!summary.batch && summary.cutover.state !== 'ENFORCED' && <Button variant="primary" onClick={() => setOperation(beginMigration(summary))}>Mulai pemeriksaan gudang</Button>}</div>
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
  return <section className="card stack"><h2>Kasus data lama</h2>
    <SelectField label="Jenis catatan" value={kind} onChange={(_, data) => { setKind(data.value as typeof kind); setPage(0) }}><option value="">Semua sumber</option>
      {MIGRATION_SOURCES.map(source => <option key={source} value={source}>{migrationSourceLabels[source]}</option>)}</SelectField>
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id}
        empty={<EmptyState title="Tidak ada kasus pada halaman ini" hint="Ubah jenis catatan atau lanjutkan pemeriksaan saldo awal jika seluruh sumber memang kosong." />} columns={[
          { key: 'identity', header: 'Catatan asli', cell: row => <span style={{ overflowWrap: 'anywhere' }}>{migrationCaseLabel(row)}<p className="muted">{migrationSourceLabels[row.sourceTable]}</p></span> },
          { key: 'location', header: 'Lokasi', cell: row => row.location?.name || row.location?.code || 'Belum terbukti' },
          { key: 'quantity', header: 'Kuantitas lama', cell: row => row.source.legacyQuantity === null ? 'Lihat catatan asli' : row.source.legacyQuantity + ' · ' + (row.source.baseUnit || 'satuan belum terbukti') },
          { key: 'claims', header: 'Identitas', cell: row => !row.claims.length ? 'Lihat bukti sumber' : [...new Set(row.claims.map(claim => claim.state ? claimLabels[claim.state] : 'Format perlu diperiksa'))].join(' · ') },
          { key: 'action', header: 'Pemeriksaan', cell: row => <Button onClick={() => onSelect(row.id)}>Periksa {migrationCaseLabel(row)}</Button> },
        ]} />
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
  </section>
}
