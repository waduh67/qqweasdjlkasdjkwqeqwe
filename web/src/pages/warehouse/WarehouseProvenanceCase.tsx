import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Checkbox } from '@fluentui/react-components'
import { Button, SelectField, TextareaField, TextField } from '@/components/atoms'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { useCan } from '@/auth/useCan'
import { getMigrationCase, getMigrationFinalization, listMigrationEvidence, listMigrationResolutions, listMigrationCases,
  uploadMigrationEvidence, downloadMigrationEvidence, resolveMigrationCase, type MigrationResolutionInput } from '@/api/warehouse/provenance'
import type { MigrationCase, MigrationEvidence, MigrationResolution, MigrationSummary } from '@/api/warehouse/provenanceModels'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import type { WarehousePage } from '@/api/warehouse/codec'
import { listSkus } from '@/api/warehouse/masters'
import type { WarehouseSku } from '@/api/warehouse/models'
import { warehouseError } from '@/api/warehouse/errors'
import { saveReceiptFile } from './receiptFiles'
import { baselineCandidate, baselinePreview, migrationCaseLabel, migrationPending, migrationTextInvalid, resolutionLabels } from './provenancePresentation'
import { MigrationStockPreview, MigrationSourceDetails } from './WarehouseProvenanceSource'

export function WarehouseProvenanceCase({ id, summary, onClose, onRefresh }: { id: string; summary: MigrationSummary; onClose: () => void; onRefresh: () => void }) {
  const result = useWarehouseQuery(useCallback(async () => getMigrationCase(id), [id]))
  return <div className="stack"><Button onClick={onClose}>Kembali ke daftar kasus</Button><WarehouseState {...result}>{source => <>
    <section className="card stack"><h2>{migrationCaseLabel(source)}</h2><MigrationSourceDetails source={source} /></section>
    {summary.batch ? <CaseReview key={source.id} source={source} batch={summary.batch.id} epoch={summary.cutover.epoch}
      validating={summary.cutover.state === 'VALIDATING'} onRefresh={onRefresh} /> : <p className="card">Mulai pemeriksaan tenant untuk mengunggah bukti dan menyimpan keputusan pada kasus ini.</p>}
  </>}</WarehouseState></div>
}
function CaseReview({ source, batch, epoch, validating, onRefresh }: { source: MigrationCase; batch: string; epoch: number; validating: boolean; onRefresh: () => void }) {
  const loader = useCallback(async () => {
    const [finalization, history] = await Promise.all([getMigrationFinalization(batch), listMigrationResolutions(batch, source)])
    return { finalization, history }
  }, [batch, source])
  const result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{data => {
    const editable = validating && data.finalization.openingDocumentId === null
    return <div className="stack">
      {!editable && <p className="card" role="status">Pemeriksaan ini sudah dibukukan. Bukti dan keputusan tetap dapat dibaca; perubahan tidak membuka kembali saldo awal.</p>}
      <CaseEvidence source={source} batch={batch} epoch={epoch} editable={editable} latest={data.history.items[0] ?? null}
        onSaved={result.reload} onRefresh={onRefresh} />
      <ResolutionHistory source={source} batch={batch} first={data.history} />
    </div>
  }}</WarehouseState>
}
function CaseEvidence({ source, batch, epoch, editable, latest, onSaved, onRefresh }: {
  source: MigrationCase; batch: string; epoch: number; editable: boolean; latest: MigrationResolution | null; onSaved: () => void; onRefresh: () => void;
}) {
  const [page, setPage] = useState(0), [selected, setSelected] = useState<MigrationEvidence[]>([])
  const files = useWarehouseQuery(useCallback(() => listMigrationEvidence(batch, source, page), [batch, source, page]))
  const [upload, setUpload] = useState(false)
  const [downloading, setDownloading] = useState<string | null>(null), [error, setError] = useState<unknown>(null)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  async function download(file: MigrationEvidence) {
    setDownloading(file.id); setError(null)
    try {
      const blob = await downloadMigrationEvidence(batch, source, file)
      if (mounted.current) saveReceiptFile(blob, 'bukti-migrasi-' + file.id + (blob.type === 'application/pdf' ? '.pdf' : blob.type === 'image/png' ? '.png' : '.jpg'))
    } catch (caught) { if (mounted.current) setError(caught) }
    finally { if (mounted.current) setDownloading(null) }
  }
  return <><section className="card stack" aria-label="Bukti pemeriksaan kasus"><h3>Bukti pemeriksaan</h3>
    <p>Gunakan dokumen atau foto asli yang membuktikan identitas, kuantitas, satuan, dan kepemilikan kasus ini.</p>
    <div className="row wrap"><Button onClick={files.reload}>Muat ulang bukti</Button>{editable && <Button onClick={() => setUpload(!upload)}>{upload ? 'Tutup formulir unggah' : 'Tambah bukti'}</Button>}</div>
    {editable && upload && <EvidenceUpload source={source} batch={batch} epoch={epoch} onSaved={() => { setUpload(false); setPage(0); files.reload() }} onRefresh={onRefresh} />}
    <WarehouseState {...files}>{data => <>
      {data.items.length === 0 && <p>Belum ada bukti pada halaman ini. Unggah bukti asli sebelum menyimpan keputusan.</p>}
      <ul className="stack">{data.items.map(file => <li key={file.id} className="stack">
        {editable ? <Checkbox label={file.label} checked={selected.some(row => row.id === file.id)}
          disabled={selected.length >= 10 && !selected.some(row => row.id === file.id)}
          onChange={(_, change) => setSelected(rows => change.checked ? [...rows.filter(row => row.id !== file.id), file] : rows.filter(row => row.id !== file.id))} />
          : <strong>{file.label}</strong>}
        <p className="muted"><WarehouseTime value={file.createdAt} /> · {file.contentType === 'application/pdf' ? 'PDF' : 'Gambar'} · {Math.ceil(file.sizeBytes / 1024)} KB</p>
        <Button disabled={downloading !== null} onClick={() => void download(file)}>{downloading === file.id ? 'Memeriksa bukti…' : 'Unduh ' + file.label}</Button>
      </li>)}</ul>
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
    {error !== null && <p className="error" role="alert">{warehouseError(error)}</p>}
    {editable && !!selected.length && <div className="stack"><p>{selected.length} dari maksimal 10 bukti dipilih, termasuk pilihan pada halaman lain.</p>
      <ul>{selected.map(file => <li key={file.id}>{file.label} <Button variant="subtle" onClick={() => setSelected(rows => rows.filter(row => row.id !== file.id))}>Lepas pilihan {file.label}</Button></li>)}</ul></div>}
  </section>
    {editable && <ResolutionForm source={source} batch={batch} epoch={epoch} latest={latest} files={selected} onSaved={onSaved} onRefresh={onRefresh} />}
  </>
}
function EvidenceUpload({ source, batch, epoch, onSaved, onRefresh }: { source: MigrationCase; batch: string; epoch: number; onSaved: () => void; onRefresh: () => void }) {
  const [file, setFile] = useState<File | null>(null), [label, setLabel] = useState(''), [error, setError] = useState<unknown>(null)
  const [operation, setOperation] = useState<WarehouseCommand<MigrationEvidence> | null>(null)
  function submit(event: FormEvent) {
    event.preventDefault(); setError(null)
    if (!file || !label.trim() || migrationTextInvalid(label)) return
    try { setOperation(uploadMigrationEvidence(batch, source, epoch, label, file)) } catch (caught) { setError(caught) }
  }
  return <form className="stack" onSubmit={submit}>
    <TextField label="Nama bukti" required maxLength={200} value={label} onChange={(_, data) => setLabel(data.value)} />
    <label className="stack">File bukti (PDF, PNG, JPEG; maksimal 15 MiB)
      <input type="file" accept="application/pdf,image/png,image/jpeg" onChange={event => setFile(event.target.files?.[0] ?? null)} /></label>
    <Button type="submit" disabled={!file || !label.trim() || migrationTextInvalid(label)}>Periksa unggahan</Button>
    {error !== null && <p role="alert" className="error">{warehouseError(error)}</p>}
    {operation && <WarehouseCommandDialog title="Unggah bukti pemeriksaan" command={operation} confirmLabel="Unggah bukti"
      summary={<p>{label} · {file?.name} untuk {migrationCaseLabel(source)}. Bukti yang tersimpan menjadi bagian riwayat pemeriksaan.</p>}
      onClose={() => setOperation(null)} onDone={onSaved} onReload={onRefresh} />}
  </form>
}
function ResolutionForm({ source, batch, epoch, latest, files, onSaved, onRefresh }: { source: MigrationCase; batch: string; epoch: number;
  latest: MigrationResolution | null; files: MigrationEvidence[]; onSaved: () => void; onRefresh: () => void;
}) {
  const { can } = useCan(), pending = migrationPending(source)
  const [kind, setKind] = useState<MigrationResolutionInput['kind']>(pending ? 'CANCEL_PENDING' : 'PROVENANCE_ONLY')
  const [reason, setReason] = useState(''), [sku, setSku] = useState<WarehouseSku | null>(null), [unit, setUnit] = useState(''), [owned, setOwned] = useState(false)
  const [duplicate, setDuplicate] = useState<MigrationCase | null>(null), [operation, setOperation] = useState<WarehouseCommand<MigrationResolution> | null>(null)
  const loadSkus = useCallback((query: string, page: number) => listSkus({ search: query, page, size: 25, state: 'ACTIVE' }), [])
  const loadDuplicates = useCallback((_query: string, page: number) => listMigrationCases(page), [])
  const preview = baselinePreview(source, unit)
  const valid = reason.trim().length > 0 && !migrationTextInvalid(reason) && files.length > 0 && files.length <= 10 &&
    (kind !== 'BASELINE_STOCK' || (sku && owned && preview && preview.baseUnit === sku.baseUnit)) && (kind !== 'DUPLICATE' || duplicate)
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!valid) return
    setOperation(resolveMigrationCase(batch, source, { expectedEpoch: epoch, expectedCaseHash: source.sourceHash, expectedResolutionRevision: latest?.revision ?? 0,
      kind, reason, evidenceIds: files.map(file => file.id), stock: kind === 'BASELINE_STOCK' && sku ? { skuId: sku.id, sourceUnit: unit as 'EA' | 'MM' | 'M', legalOwner: 'ISP' } : null,
      duplicateCaseId: kind === 'DUPLICATE' ? duplicate?.id ?? null : null }))
  }
  return <form className="card stack" onSubmit={submit} aria-label="Keputusan pemeriksaan"><h3>{latest ? 'Tambahkan keputusan pemeriksaan' : 'Catat keputusan pemeriksaan'}</h3>
    <p>Keputusan disimpan sebagai usulan berbukti. Saldo tersedia baru dibukukan setelah persetujuan independen.</p>
    {latest && <p>Keputusan terbaru: {resolutionLabels[latest.kind]} · Revisi {latest.revision}. Keputusan sebelumnya tetap tersimpan.</p>}
    <SelectField label="Hasil pemeriksaan" value={kind} onChange={(_, data) => setKind(data.value as MigrationResolutionInput['kind'])}>
      {pending ? <option value="CANCEL_PENDING">Batalkan efek tertunda</option> : <>
        <option value="PROVENANCE_ONLY">Riwayat saja, bukan stok tersedia</option>
        {baselineCandidate(source) && <option value="BASELINE_STOCK">Calon saldo awal</option>}
        <option value="DUPLICATE">Catatan ganda dari sumber yang sama</option>
      </>}
    </SelectField>
    {kind === 'BASELINE_STOCK' && <>
      {can('inventory.sku.view') ? <WarehousePicker label="SKU saldo awal" load={loadSkus} value={sku} onChange={value => { setSku(value); setUnit('') }}
        name={value => value.name + ' · ' + value.code} eligible={value => (source.sourceTable === 'inventory_serialized_asset' ? value.tracking === 'SERIAL' : value.tracking !== 'SERIAL') &&
          (source.source.warehouseSkuId === null || value.id === source.source.warehouseSkuId)} />
        : <p role="alert">Pemilihan SKU memerlukan izin melihat katalog gudang.</p>}
      <SelectField label="Satuan pada bukti asli" value={unit} required onChange={(_, data) => setUnit(data.value)}>
        <option value="">Pilih satuan yang terbukti…</option>
        {(!sku || sku.baseUnit === 'EA') && <option value="EA">Unit barang (EA)</option>}
        {(!sku || sku.baseUnit === 'MM') && <><option value="MM">Milimeter (MM)</option><option value="M">Meter (M)</option></>}
      </SelectField>
      <MigrationStockPreview source={source} unit={unit} />
      <Checkbox label="Bukti menunjukkan stok ini milik ISP" checked={owned} onChange={(_, data) => setOwned(data.checked === true)} />
    </>}
    {kind === 'DUPLICATE' && <><p>Pilih kasus asli yang telah memiliki keputusan calon saldo awal. Tautan ini mencegah satu barang dihitung dua kali.</p>
      <WarehousePicker label="Kasus asli" load={loadDuplicates} value={duplicate} onChange={setDuplicate} name={migrationCaseLabel} searchable={false}
        eligible={value => value.id !== source.id && ['inventory_serialized_asset', 'inventory_balance_projection'].includes(value.sourceTable)} /></>}
    {kind === 'PROVENANCE_ONLY' && <p>Catatan tetap dipertahankan sebagai riwayat. Jumlah dan identitasnya tidak otomatis menjadi stok yang dapat dikeluarkan.</p>}
    {kind === 'CANCEL_PENDING' && <p>Efek lama ini diajukan untuk pembatalan permanen bersama saldo awal. Riwayat pekerjaan dan bukti asalnya tetap tersimpan.</p>}
    <TextareaField label="Alasan dan rujukan bukti" required maxLength={2000} value={reason} onChange={(_, data) => setReason(data.value)} />
    {migrationTextInvalid(reason) && <p className="error" role="alert">Tulis alasan dalam satu paragraf tanpa baris baru atau karakter kontrol.</p>}
    {!files.length && <p>Pilih minimal satu bukti dari daftar di atas.</p>}
    <Button type="submit" variant="primary" disabled={!valid}>Tinjau keputusan</Button>
    {operation && <WarehouseCommandDialog title="Simpan keputusan pemeriksaan" command={operation} confirmLabel="Simpan keputusan"
      summary={<div className="stack"><p>{resolutionLabels[kind]} untuk {migrationCaseLabel(source)}.</p><p>{reason}</p>
        {kind === 'BASELINE_STOCK' && <MigrationStockPreview source={source} unit={unit} />}
        {kind === 'DUPLICATE' && duplicate && <p>Kasus asli: {migrationCaseLabel(duplicate)}</p>}
        <p>Bukti: {files.map(file => file.label).join(', ')}</p></div>}
      onClose={() => setOperation(null)} onDone={onSaved} onReload={onRefresh} />}
  </form>
}
function ResolutionHistory({ source, batch, first }: { source: MigrationCase; batch: string; first: WarehousePage<MigrationResolution> }) {
  const [page, setPage] = useState(0)
  const result = useWarehouseQuery(useCallback(() => page === 0 ? Promise.resolve(first) : listMigrationResolutions(batch, source, page), [batch, source, first, page]))
  return <section className="card stack" aria-label="Riwayat pemeriksaan"><h3>Riwayat keputusan</h3><WarehouseState {...result}>{data => <>
    {!data.items.length && <p>Belum ada keputusan untuk kasus ini.</p>}
    <ul className="stack">{data.items.map(row => <li key={row.id} className="stack"><strong>{resolutionLabels[row.kind]} · Revisi {row.revision}</strong>
      <p><WarehouseTime value={row.createdAt} /> · {row.reason}</p>
      {row.stock && <p>Usulan: <WarehouseQuantity value={row.stock.quantityBase} unit={row.stock.baseUnit} /> · Satuan bukti: {row.stock.sourceUnit}</p>}
      <details><summary>Referensi keputusan dan bukti</summary><p>ID keputusan: {row.id}</p><p>ID pemeriksa: {row.resolvedBy}</p>
        {row.duplicateCaseId && <p>Kasus asli: {row.duplicateCaseId}</p>}<ul>{row.evidence.map(file => <li key={file.id}>{file.id} · {file.sha256}</li>)}</ul></details>
    </li>)}</ul><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
  </>}</WarehouseState></section>
}
