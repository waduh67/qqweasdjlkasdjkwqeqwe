import { ClipboardList, FileCheck, Power, RefreshCw } from 'lucide-react'
import { CommandBar } from '@/components/molecules/CommandBar'
import { WarehouseFacts } from '@/components/organisms/warehouse/WarehouseFacts'
import { WarehouseProvenanceCase } from './WarehouseProvenanceCase'
import { useId } from 'react'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { Disclosure } from '@/components/molecules/Disclosure'
import { WarehouseDraftExpired } from '@/components/organisms/warehouse/WarehouseDraftExpired'
import { useCallback, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Checkbox } from '@fluentui/react-components'
import { Button, EmptyState, TextareaField, TextField } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { useCan } from '@/auth/useCan'
import { getMigrationFinalization, getMigrationOpening, getMigrationReview, listMigrationOpenings, requestMigrationOpening, finalizeMigration } from '@/api/warehouse/provenance'
import type { MigrationFinalization, MigrationFinalizationReview, MigrationOpening, MigrationReview, MigrationSummary } from '@/api/warehouse/provenanceModels'
import type { MigrationReviewCase } from '@/api/warehouse/migrationReview'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import type { WarehouseLocation } from '@/api/warehouse/models'
import { listLocations } from '@/api/warehouse/masters'
import { migrationIssueLabel, migrationSourceLabels, migrationTextInvalid, resolutionLabels } from './provenancePresentation'

export function WarehouseProvenanceOpening({ summary, selected, onSelect, onRefresh }: {
  summary: MigrationSummary; selected: string | null; onSelect: (id: string | null) => void; onRefresh: () => void;
}) {
  const batch = summary.batch!.id, [directoryRevision, setDirectoryRevision] = useState(0)
  const result = useWarehouseQuery(useCallback(() => getMigrationFinalization(batch), [batch]))
  return <div className="warehouse-workspace-panel"><WarehouseState {...result}>{ready => <>
    {ready.finalization ? <FinalizedReceipt value={ready.finalization} /> : <ol className="warehouse-setup-steps">
      <li><ClipboardList size={24} aria-hidden /><div><h3>1. Tinjau hasil pemeriksaan</h3><p>Pastikan keputusan dan bukti lengkap sebelum menyusun saldo awal.</p>
        {summary.cutover.state === 'VALIDATING' && ready.openingDocumentId === null ? <NewOpening summary={summary} onSelect={id => { setDirectoryRevision(value => value + 1); onSelect(id) }} onRefresh={onRefresh} />
          : <p className="warehouse-workspace-note">Saldo awal sudah dibukukan. Buka usulan tersimpan untuk melihat hasilnya.</p>}
      </div></li>
      <li><FileCheck size={24} aria-hidden /><div><h3>2. Persetujuan saldo awal</h3><p>Usulan diperiksa oleh petugas independen. Buka usulan di bawah untuk melihat persetujuannya.</p></div></li>
      <li><Power size={24} aria-hidden /><div><FinalizationForm value={ready} onRefresh={onRefresh} /></div></li>
    </ol>}
    <OpeningDirectory key={directoryRevision} batch={batch} onSelect={onSelect} />
    {selected && <OpeningDocument summary={summary} id={selected} onClose={() => onSelect(null)} onRefresh={onRefresh} />}
  </>}</WarehouseState></div>
}
function OpeningDirectory({ batch, onSelect }: { batch: string; onSelect: (id: string) => void }) {
  const [page, setPage] = useState(0)
  const result = useWarehouseQuery(useCallback(() => listMigrationOpenings(batch, page), [batch, page]))
  return <section className="warehouse-record-section" aria-label="Usulan saldo awal tersimpan"><h3>Usulan saldo awal</h3>
    <CommandBar actions={[{ key: 'refresh', label: 'Segarkan usulan', icon: <RefreshCw size={16} />, onClick: result.reload }]} />
    <WarehouseState {...result}>{data => <>
      <DataTable empty={<EmptyState title="Belum ada usulan saldo awal" hint="Tinjau hasil pemeriksaan untuk menyusun usulan." />} presentation="warehouse" rows={data.items} rowKey={row => row.id} columns={[
        { key: 'name', header: 'Usulan', cell: row => row.code, onCellClick: row => onSelect(row.id) },
        { key: 'reference', header: 'Referensi', cell: row => row.migrationReference },
        { key: 'location', header: 'Lokasi pemeriksaan', cell: row => row.reviewLocation.name || row.reviewLocation.code },
        { key: 'state', header: 'Pembukuan', cell: row => row.state === 'EXPIRED' ? 'Kedaluwarsa' : row.state === 'POSTED' ? 'Sudah dibukukan' : 'Belum dibukukan' },
        { key: 'time', header: 'Disimpan', cell: row => <WarehouseTime value={row.createdAt} /> },

      ]} />
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
  </section>
}
function OpeningDocument({ summary, id, onClose, onRefresh }: { summary: MigrationSummary; id: string; onClose: () => void; onRefresh: () => void }) {
  const { can } = useCan(), batch = summary.batch!.id, [caseId, setCaseId] = useState<string | null>(null)
  const result = useWarehouseQuery(useCallback(async () => getMigrationOpening(batch, id), [batch, id]))
  return <ResourceForm readOnly title="Usulan saldo awal" onClose={onClose} onBack={() => {}}><WarehouseState {...result}>{document => <div className="warehouse-record">
    <h2>{document.code}</h2><WarehouseFacts items={[{ label: 'Referensi', value: document.migrationReference }, { label: 'Disimpan', value: <WarehouseTime value={document.createdAt} /> }, { label: 'Alasan', value: document.reason }]} />
    <WarehouseDraftExpired expiry={document.draftExpiry} />
    <p>Usulan menyimpan hasil pemeriksaan saat dibuat. Perubahan keputusan memerlukan usulan baru.</p>
    <p className="warehouse-workspace-note">Nilai pembelian dan biaya asal tidak diketahui. Usulan tidak membuat transaksi pembelian.</p>
    {can('inventory.approval.view') ? <Link to={'/warehouse/approvals?sourceDocumentId=' + encodeURIComponent(document.id)}>Buka persetujuan saldo awal</Link>
      : <p>Hubungi petugas persetujuan gudang untuk melanjutkan usulan ini.</p>}
    <ReviewCases cases={document.manifest.cases} issues={[]} onCase={setCaseId} />
  </div>}</WarehouseState>
    {caseId && <WarehouseProvenanceCase id={caseId} summary={summary} onClose={() => setCaseId(null)} onRefresh={onRefresh} />}
  </ResourceForm>
}
function NewOpening({ summary, onSelect, onRefresh }: { summary: MigrationSummary; onSelect: (id: string) => void; onRefresh: () => void }) {
  const [open, setOpen] = useState(false)
  return <><Button onClick={() => setOpen(true)}>Tinjau hasil pemeriksaan</Button>
    {open && <OpeningReview onClose={() => setOpen(false)} summary={summary} onSelect={id => { setOpen(false); onSelect(id) }} onRefresh={onRefresh} />}
  </>
}
function OpeningReview({ onClose, summary, onSelect, onRefresh }: { onClose: () => void; summary: MigrationSummary; onSelect: (id: string) => void; onRefresh: () => void }) {
  const batch = summary.batch!.id, [creating, setCreating] = useState(false), [caseId, setCaseId] = useState<string | null>(null)
  const result = useWarehouseQuery(useCallback(() => getMigrationReview(batch), [batch]))
  return <ResourceForm readOnly title="Hasil pemeriksaan" onClose={onClose} onBack={() => {}}><WarehouseState {...result}>{review => <div className="warehouse-record">
    <CommandBar primary={{ key: 'create', label: 'Susun saldo awal', icon: <FileCheck size={16} />, disabled: !!review.issues.length, onClick: () => setCreating(true) }}
      actions={[{ key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, onClick: result.reload }]} />
    {!!review.issues.length && <p role="alert">{review.issues.length} temuan perlu diselesaikan sebelum saldo awal diajukan.</p>}
    <ReviewCases cases={review.manifest.cases} issues={review.issues} onCase={setCaseId} />
    {creating && !review.issues.length && <OpeningForm onClose={() => setCreating(false)} review={review} epoch={summary.cutover.epoch} onSelect={onSelect} onRefresh={onRefresh} />}
  </div>}</WarehouseState>
    {caseId && <WarehouseProvenanceCase id={caseId} summary={summary} onClose={() => { setCaseId(null); result.reload() }} onRefresh={onRefresh} />}
  </ResourceForm>
}
function ReviewCases({ cases, issues, onCase }: { cases: MigrationReviewCase[]; issues: MigrationReview['issues']; onCase: (id: string) => void }) {
  const [page, setPage] = useState(0), stock = cases.filter(row => row.resolution?.stock), totals: Record<string, bigint> = {}
  stock.forEach(row => { const value = row.resolution!.stock!; totals[value.baseUnit] = (totals[value.baseUnit] ?? 0n) + BigInt(value.quantityBase) })
  return <div className="stack"><p>{cases.length} sumber · {stock.length} calon posisi stok · {cases.filter(row => !row.resolution).length} catatan tanpa keputusan</p>
    {stock.length ? <StockTotals totals={Object.fromEntries(Object.entries(totals).map(([unit, amount]) => [unit, amount.toString()]))} />
      : <p>Saldo tersedia nihil: tidak ada baris stok yang diajukan.</p>}
    {!!cases.length && <DataTable presentation="warehouse" rows={cases.slice(page * 25, (page + 1) * 25)} rowKey={row => row.caseId} columns={[
      { key: 'source', header: 'Catatan asli', cell: row => row.source.serial === '' ? 'Serial kosong' : row.source.serial ?? row.source.model ?? migrationSourceLabels[row.sourceTable], onCellClick: row => onCase(row.caseId) },
      { key: 'type', header: 'Jenis catatan', cell: row => migrationSourceLabels[row.sourceTable] },
      { key: 'decision', header: 'Keputusan', cell: row => row.resolution ? resolutionLabels[row.resolution.kind] : row.resolutionRequired ? 'Keputusan diperlukan' : 'Riwayat belum terbukti' },
      { key: 'issues', header: 'Pemeriksaan', cell: row => issues.some(issue => issue.caseId === row.caseId) ? 'Perlu ditinjau' : 'Selesai' },
      { key: 'quantity', header: 'Calon saldo', cell: row => row.resolution?.stock ? <WarehouseQuantity value={row.resolution.stock.quantityBase} unit={row.resolution.stock.baseUnit} /> : 'Tidak menjadi stok tersedia' },

    ]} />}
    <WarehousePagination page={page} size={25} total={cases.length} onChange={setPage} />
    {!!issues.length && <ul className="warehouse-review-issues">{issues.map((issue, index) => <li key={index}><Button variant="subtle" onClick={() => onCase(issue.caseId)}>{migrationIssueLabel(issue.code)}</Button></li>)}</ul>}
  </div>
}
function OpeningForm({ onClose, review, epoch, onSelect, onRefresh }: { onClose: () => void; review: MigrationReview; epoch: number; onSelect: (id: string) => void; onRefresh: () => void }) {
  const formId = useId()
  const { can } = useCan(), [place, setPlace] = useState<WarehouseLocation | null>(null), [reference, setReference] = useState(''), [reason, setReason] = useState('')
  const [zero, setZero] = useState(false), [operation, setOperation] = useState<WarehouseCommand<MigrationOpening> | null>(null)
  const empty = !review.manifest.cases.some(row => row.resolution?.kind === 'BASELINE_STOCK')
  const load = useCallback((search: string, page: number) => listLocations({ search, page, size: 25, state: 'ACTIVE' }), [])
  const valid = place && reference.trim() && reason.trim() && !migrationTextInvalid(reference + reason) && (!empty || zero)
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!valid || !place) return
    setOperation(requestMigrationOpening(review.manifest.batchId, { expectedEpoch: epoch, expectedReviewHash: review.reviewHash,
      reviewLocationId: place.id, expectedReviewLocationRevision: place.revision, migrationReference: reference, reason }))
  }
  return <ResourceForm title="Simpan usulan saldo awal" onClose={onClose} onBack={() => setOperation(null)} review={operation && <WarehouseCommandDialog embedded title="Simpan usulan saldo awal" command={operation} confirmLabel="Simpan usulan"
      summary={<div className="stack"><p>{reference} · {place?.name || place?.code}</p><p>{reason}</p>
        {empty && <p>Saldo awal nol. Tidak ada stok yang ditambahkan.</p>}<p>Nilai pembelian asal tidak diketahui. Stok tetap menunggu persetujuan independen dan finalisasi.</p></div>}
      onClose={() => setOperation(null)} onDone={result => onSelect(result.id)} onReload={onRefresh} />} footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary" disabled={!valid}>Tinjau usulan saldo awal</Button></>}><form id={formId} className="stack" onSubmit={submit}>
    <p>Saldo awal tersedia setelah disetujui petugas independen dan gudang diaktifkan. Pembuat pemeriksaan dan penyusun bukti tidak dapat menyetujui usulannya sendiri.</p>
    {can('inventory.location.view') ? <WarehousePicker label="Lokasi pemeriksaan saldo awal" load={load} value={place} onChange={setPlace}
      name={value => (value.name || value.code) + ' · ' + value.code} eligible={value => ['WAREHOUSE', 'BIN'].includes(value.kind)} />
      : <p role="alert">Pemilihan lokasi memerlukan izin melihat lokasi gudang.</p>}
    <TextField label="Referensi migrasi" maxLength={500} required value={reference} onChange={(_, data) => setReference(data.value)} />
    <TextareaField label="Alasan pengajuan saldo awal" maxLength={1000} required value={reason} onChange={(_, data) => setReason(data.value)} />
    {migrationTextInvalid(reference + reason) && <p role="alert" className="error">Gunakan satu paragraf tanpa baris baru atau karakter kontrol.</p>}
    {empty && <Checkbox label="Pemeriksaan menyatakan saldo tersedia nol" checked={zero} onChange={(_, data) => setZero(data.checked === true)} />}

    {can('inventory.approval.manage') && <Link to="/warehouse/settings">Periksa tingkat persetujuan saldo awal</Link>}

  </form></ResourceForm>
}
function StockTotals({ totals }: { totals: Record<string, string> }) {
  return <div className="row wrap">{Object.entries(totals).map(([unit, value]) => <p key={unit}><strong><WarehouseQuantity value={value} unit={unit as 'EA' | 'MM'} /></strong></p>)}</div>
}
function FinalizationForm({ value, onRefresh }: { value: MigrationFinalizationReview; onRefresh: () => void }) {
  const { can } = useCan(), [reason, setReason] = useState(''), [operation, setOperation] = useState<WarehouseCommand<MigrationFinalization> | null>(null)
  const formId = useId(), [open, setOpen] = useState(false)
  const close = () => { setOpen(false); setReason(''); setOperation(null) }
  const ready = value.cutover.state === 'VALIDATING' && value.issues.length === 0 && value.openingDocumentId && value.reviewHash
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!ready || !value.openingDocumentId || !value.reviewHash || !reason.trim() || migrationTextInvalid(reason)) return
    setOperation(finalizeMigration(value.batchId, { expectedEpoch: value.cutover.epoch, openingDocumentId: value.openingDocumentId, expectedReviewHash: value.reviewHash, reason }))
  }
  return <section className="stack"><h3>3. Aktifkan operasi gudang</h3>
    {ready ? <Button onClick={() => setOpen(true)}>Aktifkan operasi gudang</Button> : <><p>Aktivasi menunggu persetujuan saldo awal dan pemeriksaan bukti.</p><ul>{value.issues.map(issue => <li key={issue}>{migrationIssueLabel(issue)}</li>)}</ul></>}
    {value.approvalId && can('inventory.approval.view') && <Link to={'/warehouse/approvals?approvalId=' + encodeURIComponent(value.approvalId)}>Lihat keputusan persetujuan saldo awal</Link>}

    {open && ready && <ResourceForm title="Aktifkan operasi gudang" editing onClose={close} onBack={() => setOperation(null)}
      footer={<><Button onClick={close}>Batal</Button><Button form={formId} type="submit" variant="primary" disabled={!reason.trim() || migrationTextInvalid(reason)}>Tinjau aktivasi gudang</Button></>}
      review={operation && <WarehouseCommandDialog embedded title="Aktifkan operasi gudang" command={operation} confirmLabel="Aktifkan gudang"
        summary={<div className="stack"><StockTotals totals={value.baselineTotals} />{value.baselineCount === 0 && <p>Saldo tersedia nol.</p>}<p>{reason}</p>
          <p>Transaksi gudang akan diaktifkan. Riwayat lama tetap tersimpan.</p></div>}
        onClose={() => setOperation(null)} onDone={onRefresh} onReload={onRefresh} />}>
      <form id={formId} className="stack" onSubmit={submit}>
        <StockTotals totals={value.baselineTotals} />{value.baselineCount === 0 && <p>Saldo tersedia nol.</p>}
        <p>{value.cancellationCount} efek lama dibatalkan · {value.retainedIdentityCount} identitas dicadangkan · {value.unresolvedHistoricalCount} catatan historis belum terverifikasi.</p>
        <TextareaField label="Catatan finalisasi" required maxLength={2000} value={reason} onChange={(_, data) => setReason(data.value)} />
        {migrationTextInvalid(reason) && <p role="alert" className="error">Gunakan satu paragraf tanpa baris baru.</p>}
      </form>
    </ResourceForm>}
  </section>
}

function FinalizedReceipt({ value }: { value: MigrationFinalization }) {
  const { can } = useCan()
  return <section className="card stack" aria-label="Bukti finalisasi gudang"><h2>Operasi gudang aktif</h2><p role="status">Finalisasi tercatat pada <WarehouseTime value={value.finalizedAt} />.</p><p>{value.reason}</p>
    <p>Saldo awal yang dibukukan:</p>{value.baselineCount ? <StockTotals totals={value.baselineTotals} /> : <p>Saldo tersedia nihil, tanpa baris stok.</p>}
    <p>{value.cancellationCount} efek lama dibatalkan. {value.retainedIdentityCount} identitas lama tetap dicadangkan dan tidak bisa dipakai untuk penerimaan yang bentrok.</p>
    <Disclosure title={<>Jumlah sumber dan referensi finalisasi</>}><ul>{Object.entries(value.sourceCounts).map(([kind, count]) => <li key={kind}>{migrationSourceLabels[kind as keyof typeof migrationSourceLabels]}: {count}</li>)}</ul>
      <p>Referensi finalisasi: {value.id}</p><p>ID petugas: {value.finalizedBy}</p><p>Saldo awal: {value.openingDocumentId}</p></Disclosure>
    {can('inventory.item.view') && <Link to="/warehouse/stock">Buka stok gudang</Link>}
  </section>
}
