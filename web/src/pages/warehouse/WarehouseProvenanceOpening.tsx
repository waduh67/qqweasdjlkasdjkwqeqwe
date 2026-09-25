import { useCallback, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Checkbox } from '@fluentui/react-components'
import { Button, TextareaField, TextField } from '@/components/atoms'
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

export function WarehouseProvenanceOpening({ summary, selected, onSelect, onCase, onRefresh }: {
  summary: MigrationSummary; selected: string | null; onSelect: (id: string | null) => void; onCase: (id: string) => void; onRefresh: () => void;
}) {
  const batch = summary.batch!.id
  const result = useWarehouseQuery(useCallback(() => getMigrationFinalization(batch), [batch]))
  return <div className="stack"><WarehouseState {...result}>{ready => <>
    {ready.finalization ? <FinalizedReceipt value={ready.finalization} /> : <FinalizationForm value={ready} onRefresh={onRefresh} />}
    {selected ? <OpeningDocument batch={batch} id={selected} onClose={() => onSelect(null)} onCase={onCase} /> : <>
      {summary.cutover.state === 'VALIDATING' && ready.openingDocumentId === null && <NewOpening batch={batch} epoch={summary.cutover.epoch} onSelect={onSelect} onCase={onCase} onRefresh={onRefresh} />}
      <OpeningDirectory batch={batch} onSelect={onSelect} />
    </>}
  </>}</WarehouseState></div>
}
function OpeningDirectory({ batch, onSelect }: { batch: string; onSelect: (id: string) => void }) {
  const [page, setPage] = useState(0)
  const result = useWarehouseQuery(useCallback(() => listMigrationOpenings(batch, page), [batch, page]))
  return <section className="card stack"><h2>Usulan saldo awal tersimpan</h2><p>Lanjutkan usulan yang sudah dibuat setelah memuat ulang halaman. Periksa status persetujuannya sebelum membuat usulan pengganti.</p>
    <WarehouseState {...result}>{data => <>
      {!data.items.length ? <p>Belum ada usulan dalam cakupan lokasi Anda. Tinjau hasil pemeriksaan untuk membuat usulan.</p> : <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} columns={[
        { key: 'name', header: 'Usulan', cell: row => <span>{row.code}<p className="muted">{row.migrationReference}</p></span> },
        { key: 'location', header: 'Lokasi pemeriksaan', cell: row => row.reviewLocation.name || row.reviewLocation.code },
        { key: 'state', header: 'Pembukuan', cell: row => row.state === 'POSTED' ? 'Sudah dibukukan' : 'Belum dibukukan' },
        { key: 'time', header: 'Disimpan', cell: row => <WarehouseTime value={row.createdAt} /> },
        { key: 'action', header: 'Tindakan', cell: row => <Button onClick={() => onSelect(row.id)}>Buka {row.code}</Button> },
      ]} />}
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
  </section>
}
function OpeningDocument({ batch, id, onClose, onCase }: { batch: string; id: string; onClose: () => void; onCase: (id: string) => void }) {
  const { can } = useCan()
  const result = useWarehouseQuery(useCallback(async () => getMigrationOpening(batch, id), [batch, id]))
  return <section className="card stack"><Button onClick={onClose}>Kembali ke usulan tersimpan</Button><WarehouseState {...result}>{document => <>
    <h2>{document.code}</h2><p>{document.migrationReference} · <WarehouseTime value={document.createdAt} /></p><p>{document.reason}</p>
    <p>Usulan ini menyimpan hasil pemeriksaan pada saat dibuat. Keputusan kasus yang lebih baru memerlukan tinjauan dan usulan baru.</p>
    <p>Nilai pembelian dan biaya asal tidak diketahui. Usulan tidak membuat transaksi pembelian.</p>
    {can('inventory.approval.view') ? <Link to={'/warehouse/approvals?sourceDocumentId=' + encodeURIComponent(document.id)}>Buka persetujuan saldo awal</Link>
      : <p>Petugas dengan akses persetujuan gudang perlu mengajukan dan memeriksa usulan ini.</p>}
    <ReviewCases cases={document.manifest.cases} issues={[]} onCase={onCase} />
  </>}</WarehouseState></section>
}
function NewOpening({ batch, epoch, onSelect, onCase, onRefresh }: { batch: string; epoch: number; onSelect: (id: string) => void; onCase: (id: string) => void; onRefresh: () => void }) {
  const [open, setOpen] = useState(false)
  return <section className="card stack"><h2>Susun saldo awal</h2>
    <p>Tinjau keputusan seluruh kasus. Stok yang terbukti disiapkan untuk persetujuan independen; riwayat yang belum terbukti tetap dikecualikan dari stok tersedia.</p>
    {open ? <OpeningReview batch={batch} epoch={epoch} onSelect={onSelect} onCase={onCase} onRefresh={onRefresh} />
      : <Button variant="primary" onClick={() => setOpen(true)}>Tinjau hasil pemeriksaan</Button>}
  </section>
}
function OpeningReview({ batch, epoch, onSelect, onCase, onRefresh }: { batch: string; epoch: number; onSelect: (id: string) => void; onCase: (id: string) => void; onRefresh: () => void }) {
  const result = useWarehouseQuery(useCallback(() => getMigrationReview(batch), [batch]))
  return <WarehouseState {...result}>{review => <>
    <Button onClick={result.reload}>Muat ulang hasil pemeriksaan</Button>
    {!!review.issues.length && <p role="alert">{review.issues.length} kasus memerlukan pemeriksaan lanjutan sebelum saldo awal dapat diajukan.</p>}
    <ReviewCases cases={review.manifest.cases} issues={review.issues} onCase={onCase} />
    {!review.issues.length && <OpeningForm review={review} epoch={epoch} onSelect={onSelect} onRefresh={onRefresh} />}
  </>}</WarehouseState>
}
function ReviewCases({ cases, issues, onCase }: { cases: MigrationReviewCase[]; issues: MigrationReview['issues']; onCase: (id: string) => void }) {
  const [page, setPage] = useState(0), stock = cases.filter(row => row.resolution?.stock), totals: Record<string, bigint> = {}
  stock.forEach(row => { const value = row.resolution!.stock!; totals[value.baseUnit] = (totals[value.baseUnit] ?? 0n) + BigInt(value.quantityBase) })
  return <div className="stack"><p>{cases.length} sumber · {stock.length} calon posisi stok · {cases.filter(row => !row.resolution).length} catatan tanpa keputusan</p>
    {stock.length ? <StockTotals totals={Object.fromEntries(Object.entries(totals).map(([unit, amount]) => [unit, amount.toString()]))} />
      : <p>Saldo tersedia nihil: tidak ada baris stok yang diajukan.</p>}
    {!!cases.length && <DataTable presentation="warehouse" rows={cases.slice(page * 25, (page + 1) * 25)} rowKey={row => row.caseId} columns={[
      { key: 'source', header: 'Catatan asli', cell: row => <span>{row.source.serial === '' ? 'Serial kosong' : row.source.serial ?? row.source.model ?? migrationSourceLabels[row.sourceTable]}
        <p className="muted">{migrationSourceLabels[row.sourceTable]}</p></span> },
      { key: 'decision', header: 'Keputusan', cell: row => <span>{row.resolution ? resolutionLabels[row.resolution.kind] : row.resolutionRequired ? 'Keputusan diperlukan' : 'Riwayat belum terbukti'}
        {issues.filter(issue => issue.caseId === row.caseId).map(issue => <p key={issue.code} className="error">{migrationIssueLabel(issue.code)}</p>)}</span> },
      { key: 'quantity', header: 'Calon saldo', cell: row => row.resolution?.stock ? <WarehouseQuantity value={row.resolution.stock.quantityBase} unit={row.resolution.stock.baseUnit} /> : 'Tidak menjadi stok tersedia' },
      { key: 'action', header: 'Pemeriksaan', cell: row => <Button onClick={() => onCase(row.caseId)}>Buka kasus</Button> },
    ]} />}
    <WarehousePagination page={page} size={25} total={cases.length} onChange={setPage} />
  </div>
}
function OpeningForm({ review, epoch, onSelect, onRefresh }: { review: MigrationReview; epoch: number; onSelect: (id: string) => void; onRefresh: () => void }) {
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
  return <form className="stack" onSubmit={submit}>
    <p>Usulan menyimpan pemeriksaan ini tanpa mengaktifkan stok. Seluruh tingkat pemeriksa independen harus menyetujui; pembuat batch dan penyusun buktinya tidak dapat menyetujui sendiri.</p>
    {can('inventory.location.view') ? <WarehousePicker label="Lokasi pemeriksaan saldo awal" load={load} value={place} onChange={setPlace}
      name={value => (value.name || value.code) + ' · ' + value.code} eligible={value => ['WAREHOUSE', 'BIN'].includes(value.kind)} />
      : <p role="alert">Pemilihan lokasi memerlukan izin melihat lokasi gudang.</p>}
    <TextField label="Referensi migrasi" maxLength={500} required value={reference} onChange={(_, data) => setReference(data.value)} />
    <TextareaField label="Alasan pengajuan saldo awal" maxLength={1000} required value={reason} onChange={(_, data) => setReason(data.value)} />
    {migrationTextInvalid(reference + reason) && <p role="alert" className="error">Gunakan satu paragraf tanpa baris baru atau karakter kontrol.</p>}
    {empty && <Checkbox label="Pemeriksaan menyatakan saldo tersedia nol; tidak ada stok fiktif yang dibuat" checked={zero} onChange={(_, data) => setZero(data.checked === true)} />}
    <Button type="submit" variant="primary" disabled={!valid}>Tinjau usulan saldo awal</Button>
    {can('inventory.approval.manage') && <Link to="/warehouse/settings">Periksa tingkat persetujuan saldo awal</Link>}
    {operation && <WarehouseCommandDialog title="Simpan usulan saldo awal" command={operation} confirmLabel="Simpan usulan"
      summary={<div className="stack"><p>{reference} · {place?.name || place?.code}</p><p>{reason}</p>
        {empty && <p>Saldo awal nol, tanpa SKU, lot, atau baris stok tambahan.</p>}<p>Nilai pembelian asal tidak diketahui. Stok tetap menunggu persetujuan independen dan finalisasi.</p></div>}
      onClose={() => setOperation(null)} onDone={result => onSelect(result.id)} onReload={onRefresh} />}
  </form>
}
function StockTotals({ totals }: { totals: Record<string, string> }) {
  return <div className="row wrap">{Object.entries(totals).map(([unit, value]) => <p key={unit}><strong><WarehouseQuantity value={value} unit={unit as 'EA' | 'MM'} /></strong></p>)}</div>
}
function FinalizationForm({ value, onRefresh }: { value: MigrationFinalizationReview; onRefresh: () => void }) {
  const { can } = useCan(), [reason, setReason] = useState(''), [operation, setOperation] = useState<WarehouseCommand<MigrationFinalization> | null>(null)
  const ready = value.cutover.state === 'VALIDATING' && value.issues.length === 0 && value.openingDocumentId && value.reviewHash
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!ready || !value.openingDocumentId || !value.reviewHash || !reason.trim() || migrationTextInvalid(reason)) return
    setOperation(finalizeMigration(value.batchId, { expectedEpoch: value.cutover.epoch, openingDocumentId: value.openingDocumentId, expectedReviewHash: value.reviewHash, reason }))
  }
  return <section className="card stack"><h2>Aktivasi operasi gudang</h2>
    {ready ? <><p>Saldo awal sudah dibukukan melalui persetujuan independen. Periksa ringkasan sebelum mengaktifkan transaksi gudang.</p>
      <StockTotals totals={value.baselineTotals} />{value.baselineCount === 0 && <p>Saldo tersedia nihil, tanpa baris stok.</p>}
      <p>{value.cancellationCount} efek lama dibatalkan · {value.retainedIdentityCount} identitas lama tetap dicadangkan · {value.unresolvedHistoricalCount} catatan historis tanpa keputusan stok.</p>
      <form className="stack" onSubmit={submit}><TextareaField label="Catatan finalisasi" required maxLength={2000} value={reason} onChange={(_, data) => setReason(data.value)} />
        {migrationTextInvalid(reason) && <p role="alert" className="error">Gunakan satu paragraf tanpa baris baru atau karakter kontrol.</p>}
        <Button type="submit" variant="primary" disabled={!reason.trim() || migrationTextInvalid(reason)}>Tinjau aktivasi gudang</Button>
      </form></> : <><p>Aktivasi menunggu saldo awal yang disetujui dan pemeriksaan bukti selesai.</p><ul>{value.issues.map(issue => <li key={issue}>{migrationIssueLabel(issue)}</li>)}</ul></>}
    {value.approvalId && can('inventory.approval.view') && <Link to={'/warehouse/approvals?approvalId=' + encodeURIComponent(value.approvalId)}>Lihat keputusan persetujuan saldo awal</Link>}
    <Button onClick={onRefresh}>Muat ulang status pemeriksaan</Button>
    {operation && <WarehouseCommandDialog title="Aktifkan operasi gudang" command={operation} confirmLabel="Aktifkan gudang"
      summary={<div className="stack"><StockTotals totals={value.baselineTotals} />{value.baselineCount === 0 && <p>Saldo tersedia nol.</p>}<p>{reason}</p>
        <p>Transaksi gudang baru akan diaktifkan. Data lama tetap tersimpan; identitas historis yang belum terbukti tetap dicadangkan.</p></div>}
      onClose={() => setOperation(null)} onDone={onRefresh} onReload={onRefresh} />}
  </section>
}
function FinalizedReceipt({ value }: { value: MigrationFinalization }) {
  const { can } = useCan()
  return <section className="card stack" aria-label="Bukti finalisasi gudang"><h2>Operasi gudang aktif</h2><p role="status">Finalisasi tercatat pada <WarehouseTime value={value.finalizedAt} />.</p><p>{value.reason}</p>
    <p>Saldo awal yang dibukukan:</p>{value.baselineCount ? <StockTotals totals={value.baselineTotals} /> : <p>Saldo tersedia nihil, tanpa baris stok.</p>}
    <p>{value.cancellationCount} efek lama dibatalkan. {value.retainedIdentityCount} identitas lama tetap dicadangkan dan tidak bisa dipakai untuk penerimaan yang bentrok.</p>
    <details><summary>Jumlah sumber dan referensi finalisasi</summary><ul>{Object.entries(value.sourceCounts).map(([kind, count]) => <li key={kind}>{migrationSourceLabels[kind as keyof typeof migrationSourceLabels]}: {count}</li>)}</ul>
      <p>Referensi finalisasi: {value.id}</p><p>ID petugas: {value.finalizedBy}</p><p>Saldo awal: {value.openingDocumentId}</p></details>
    {can('inventory.item.view') && <Link to="/warehouse/stock">Buka stok gudang</Link>}
  </section>
}
