import { useCallback, useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { uuid } from '@/api/warehouse/codec'
import { countHistory, countWorkbench, getCountDetails, getCountReviewDetails, observeCount, recount, startCount, submitCount,
  type CountDetails, type CountFact, type CountFilter, type WarehouseCount } from '@/api/warehouse/counts'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { quantityFromInput } from '@/api/warehouse/quantity'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, TextareaField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseCountEditor } from './WarehouseCountEditor'
import { WarehouseCountEdit } from './WarehouseCountEdit'
import { WarehouseCountFilters } from './WarehouseCountFilters'
import { countCounterLabel, countLineLabel, countPersonLabel } from './countPresentation'
import { locationLabel } from './receiptChoices'

const detailPath = (id: string) => `/warehouse/counts?countId=${encodeURIComponent(id)}`
export function WarehouseCountsPage() {
  const { can } = useCan(), [params] = useSearchParams(), navigate = useNavigate(), [creating, setCreating] = useState(false)
  let id: string | null = null
  try {
    if ([...params.keys()].some(key => key !== 'countId') || params.getAll('countId').length > 1) throw new Error()
    if (params.has('countId')) id = uuid(params.get('countId'))
  } catch { return <div className="card stack" role="alert"><p>Alamat stock opname tidak dikenal.</p><Link to="/warehouse/counts">Kembali ke daftar stock opname</Link></div> }
  if (!can('inventory.count.view')) return <WarehouseDenied />
  return <div className="stack"><PageHeader title="Stock Opname" subtitle="Catat hasil hitung fisik sesuai penugasan, lalu ajukan selisih untuk pemeriksaan independen." />
    {creating ? <WarehouseCountEditor onSaved={count => { setCreating(false); navigate(detailPath(count.id)) }} onClose={() => setCreating(false)} />
      : id ? <><Link to="/warehouse/counts">Kembali ke daftar stock opname</Link><CountDetail key={id} id={id} /></>
      : <>{can('inventory.count.manage') && <Button variant="primary" onClick={() => setCreating(true)}>Buat stock opname</Button>}<CountList /></>}
  </div>
}
function CountList() {
  const [filter, setFilter] = useState<CountFilter>({}), [page, setPage] = useState(0)
  const loader = useCallback(() => countWorkbench({ ...filter, page }), [filter, page]), result = useWarehouseQuery(loader)
  return <><WarehouseCountFilters onApply={filter => { setFilter(filter); setPage(0) }} /><Button onClick={result.reload}>Segarkan stock opname</Button>
    <WarehouseState {...result}>{data => <><DataTable presentation="warehouse" rows={data.items} rowKey={row => row.count.id}
      empty={<EmptyState title="Belum ada stock opname untuk Anda" hint="Dokumen terlihat bagi pembuat dan penghitung yang ditugaskan dalam cakupan lokasi saat ini." />} columns={[
        { key: 'code', header: 'Stock opname', cell: row => <Link to={detailPath(row.count.id)}>{row.references.code}</Link> },
        { key: 'location', header: 'Lokasi', cell: row => locationLabel(row.references.location) },
        { key: 'requester', header: 'Pembuat', cell: row => countPersonLabel(row.references.requester) },
        { key: 'state', header: 'Status', cell: row => <WarehouseStatus status={row.count.state} /> },
        { key: 'date', header: 'Dibuat', cell: row => <WarehouseTime value={row.references.createdAt} /> },
      ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState></>
}
function CountDetail({ id }: { id: string }) {
  // At most 100 positions per round; latest 100 visible facts cover every current observation.
  // Keep historical display independently paged, and never call normal stock reads in this flow.
  const loader = useCallback(async () => ({ details: await getCountDetails(id), recent: await countHistory(id, 0, 100) }), [id])
  const result = useWarehouseQuery(loader)
  return <WarehouseState {...result}>{data => <CountBody details={data.details} recent={data.recent.items.map(row => row.fact)} reload={result.reload} />}</WarehouseState>
}
function CountBody({ details, recent, reload }: { details: CountDetails; recent: CountFact[]; reload: () => void }) {
  const { can } = useCan(), { user } = useAuth(), { count, references } = details
  const [observing, setObserving] = useState<string | null>(null), [reviewing, setReviewing] = useState(false)
  const [editing, setEditing] = useState(false)
  const [operation, setOperation] = useState<{ command: WarehouseCommand<WarehouseCount>; title: string; summary: ReactNode } | null>(null)
  const owner = user?.id === references.requester.id, manage = can('inventory.count.manage')
  const observed = recent.filter(fact => fact.roundRevision === count.roundRevision)
  const complete = count.entries.every(entry => observed.some(fact => fact.balanceId === entry.balanceId))
  if (editing && count.state === 'DRAFT' && owner && manage && can('inventory.location.view'))
    return <WarehouseCountEdit id={count.id} onSaved={() => { setEditing(false); reload() }} onClose={() => setEditing(false)} onReload={() => { setEditing(false); reload() }} />
  if (observing) return <CountObservation details={details} balanceId={observing} onDone={reload} onClose={() => setObserving(null)} />
  return <><section className="card stack" aria-label="Detail stock opname"><h2>{references.code}</h2>
    <p><WarehouseStatus status={count.state} /> · Revisi {count.revision} · <WarehouseTime value={references.createdAt} /></p>
    <p>{locationLabel(references.location)} · Pembuat: {countPersonLabel(references.requester)}</p><p>{references.reason}</p>
    <p>Hanya posisi yang ditugaskan pada dokumen ini yang dihitung. Angka stok buku tidak ditampilkan selama penghitungan.</p>
    <div className="row wrap"><Button onClick={reload}>Muat ulang stock opname</Button>
      {manage && owner && count.state === 'DRAFT' && <Button disabled={!can('inventory.location.view')} onClick={() => setEditing(true)}>Ubah draft stock opname</Button>}
      {manage && owner && count.state === 'DRAFT' && <Button variant="primary" onClick={() => setOperation({ command: startCount(count.id, count.revision), title: 'Mulai penghitungan', summary: <p>Petugas mulai menghitung {count.entries.length} posisi di {locationLabel(references.location)}. Catat hasil fisik setiap posisi sesuai penugasan.</p> })}>Mulai penghitungan</Button>}
      {manage && owner && count.state === 'COUNTING' && <Button variant="primary" disabled={!complete} onClick={() => setOperation({ command: submitCount(count.id, count.revision), title: 'Ajukan hasil hitung', summary: <p>Semua hasil putaran ini akan diperiksa terhadap stok saat penghitungan. Selisih membutuhkan persetujuan independen; perubahan stok selama penghitungan mewajibkan hitung ulang.</p> })}>Ajukan hasil hitung</Button>}
      {manage && owner && count.state === 'RECOUNT_REQUIRED' && <Button variant="primary" onClick={() => setOperation({ command: recount(count.id, count.revision), title: 'Mulai hitung ulang', summary: <p>Buka putaran baru untuk seluruh posisi. Hasil sebelumnya tetap tersimpan di riwayat dan tidak diubah.</p> })}>Mulai hitung ulang</Button>}
    </div>
    {!manage && <p className="muted">Akses baca saja. Pencatatan memerlukan izin kelola stock opname.</p>}
    {manage && owner && count.state === 'DRAFT' && !can('inventory.location.view') && <p className="muted">Perubahan draft memerlukan izin lihat lokasi.</p>}
    {!owner && <p className="muted">Pembuat dokumen memulai, mengajukan, dan membuka hitung ulang. Anda hanya dapat mencatat posisi yang ditugaskan kepada Anda.</p>}
    {owner && count.state === 'COUNTING' && !complete && <p role="status">Tunggu hasil hitung seluruh posisi sebelum mengajukan.</p>}
    {count.state === 'RECOUNT_REQUIRED' && <p role="alert">Dokumen memerlukan hitung ulang. Mulai putaran baru dan catat kembali hasil fisik seluruh posisi.</p>}
    {count.state === 'SUBMITTED' && <><p>Hasil diajukan. Stok belum disesuaikan; pembuat perlu mengajukan persetujuan dan pemeriksa independen mengambil keputusan.</p>
      {can('inventory.approval.view') && <Link to={`/warehouse/approvals?sourceDocumentId=${encodeURIComponent(count.id)}`}>Buka persetujuan stock opname</Link>}</>}
    {count.state === 'POSTED' && <p role="status">Hasil penghitungan sudah dibukukan. Lihat keputusan persetujuan untuk selisih yang memerlukan penyesuaian.</p>}
  </section>
    <DataTable presentation="warehouse" rows={count.entries} rowKey={entry => entry.balanceId} columns={[
      { key: 'item', header: 'Posisi barang', cell: entry => <span>{countLineLabel(details, entry.balanceId)}<p className="muted" style={{ overflowWrap: 'anywhere' }}>Posisi: {entry.balanceId}</p></span> },
      { key: 'person', header: 'Penghitung', cell: entry => countCounterLabel(details, entry.counterId) },
      { key: 'result', header: 'Hasil putaran ini', cell: entry => {
        const fact = observed.find(fact => fact.balanceId === entry.balanceId)
        return fact ? <WarehouseQuantity value={fact.quantityBase} unit={fact.baseUnit} /> : entry.counterId !== user?.id && !owner ? 'Hasil dibatasi sesuai penugasan' : 'Belum dicatat'
      } },
      { key: 'action', header: 'Tindakan', cell: entry => manage && count.state === 'COUNTING' && entry.counterId === user?.id && !observed.some(fact => fact.balanceId === entry.balanceId)
        ? <Button onClick={() => setObserving(entry.balanceId)}>Catat hasil {countLineLabel(details, entry.balanceId)}</Button> : '—' },
    ]} />
    <CountHistory details={details} />
    {can('inventory.approval.view') && ['SUBMITTED', 'APPROVED', 'POSTED'].includes(count.state) && (reviewing ? <WarehouseCountComparison id={count.id} /> : <Button onClick={() => setReviewing(true)}>Lihat perbandingan setelah pengajuan</Button>)}
    {operation && <WarehouseCommandDialog title={operation.title} confirmLabel="Konfirmasi stock opname" command={operation.command} onDone={reload} onReload={reload} onClose={() => setOperation(null)}
      summary={<><p>{references.code} · Revisi {count.revision}</p>{operation.summary}</>} />}
  </>
}
function CountObservation({ details, balanceId, onDone, onClose }: { details: CountDetails; balanceId: string; onDone: () => void; onClose: () => void }) {
  const entry = details.count.entries.find(entry => entry.balanceId === balanceId)!
  const [quantity, setQuantity] = useState(''), [reason, setReason] = useState(''), [reference, setReference] = useState(''), [error, setError] = useState('')
  const [operation, setOperation] = useState<WarehouseCommand<WarehouseCount> | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault()
    try {
      const quantityBase = quantityFromInput(quantity, entry.baseUnit, true)
      if (!reason.trim() || reason.trim().length > 500 || !reference.trim() || reference.trim().length > 500) throw new Error('Isi keterangan penghitungan dan referensi lembar bukti.')
      setOperation(observeCount(details.count.id, { expectedRevision: details.count.revision, balanceId, quantityBase, reason: reason.trim(), documentReference: reference.trim() })); setError('')
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Periksa hasil hitung.') }
  }
  return <><form className="card stack" aria-label="Catat hasil hitung" onSubmit={prepare}><h2>{countLineLabel(details, balanceId)}</h2>
    <p>{locationLabel(details.references.location)} · Penghitung: {countCounterLabel(details, entry.counterId)}</p>
    <p style={{ overflowWrap: 'anywhere' }}>Posisi: {balanceId}</p><p>Masukkan jumlah yang benar-benar dihitung. Nol diperbolehkan. Hasil yang disimpan tidak dapat ditimpa pada putaran yang sama.</p>
    <WarehouseQuantityField label="Hasil hitung fisik" unit={entry.baseUnit} value={quantity} allowZero onChange={setQuantity} />
    <TextareaField label="Keterangan penghitungan" value={reason} required maxLength={500} onChange={(_, data) => setReason(data.value)} />
    <TextField label="Referensi lembar hitung" value={reference} required maxLength={500} onChange={(_, data) => setReference(data.value)} />
    {error && <p className="error" role="alert">{error}</p>}<div className="row wrap"><Button type="button" onClick={onClose}>Batal</Button><Button type="submit" variant="primary">Tinjau hasil hitung</Button></div>
  </form>{operation && <WarehouseCommandDialog title="Simpan hasil hitung" confirmLabel="Simpan hasil fisik" command={operation} onDone={onDone} onReload={onDone} onClose={() => setOperation(null)}
    summary={<><p>{details.references.code} · Revisi {details.count.revision}</p><p>{countLineLabel(details, balanceId)}: {quantity} {entry.baseUnit === 'MM' ? 'm' : 'unit'}</p>
      <p>{reason} · Bukti: {reference}</p><p>Hasil ini menjadi catatan tetap untuk putaran berjalan.</p></>} />}</>
}
function CountHistory({ details }: { details: CountDetails }) {
  const id = details.count.id, [page, setPage] = useState(0), loader = useCallback(() => countHistory(id, page), [id, page]), result = useWarehouseQuery(loader)
  return <details className="card"><summary>Riwayat hasil hitung</summary><WarehouseState {...result}>{data => <div className="stack">
    {!data.items.length && <p>Belum ada hasil hitung yang dapat Anda lihat.</p>}
    {data.items.map(({ fact, recordedAt }) => <section key={fact.id}><h3>{countLineLabel(details, fact.balanceId)}</h3><p>{countCounterLabel(details, fact.counterId)} · Putaran dari revisi {fact.roundRevision} · <WarehouseTime value={recordedAt} /></p>
      <p><WarehouseQuantity value={fact.quantityBase} unit={fact.baseUnit} /> · {fact.reason}</p><p>Bukti: {fact.documentReference}</p></section>)}
    <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
  </div>}</WarehouseState></details>
}
/** Explicit reviewer read: only mounted after submission, never in the blind editor. */
export function WarehouseCountComparison({ id }: { id: string }) {
  const loader = useCallback(() => getCountReviewDetails(id), [id]), result = useWarehouseQuery(loader)
  return <section className="card stack" aria-label="Perbandingan hasil pengajuan"><h2>Perbandingan hasil pengajuan</h2><WarehouseState {...result}>{data => <>
    <p>{data.references.code} · <WarehouseStatus status={data.review.count.state} /> · Revisi {data.review.count.revision}</p>
    <DataTable presentation="warehouse" rows={data.review.observations} rowKey={row => row.balanceId} columns={[
      { key: 'item', header: 'Barang', cell: row => countLineLabel(data, row.balanceId) },
      { key: 'counter', header: 'Penghitung', cell: row => countCounterLabel(data, row.counterId) },
      { key: 'book', header: 'Stok buku saat hitung', cell: row => <WarehouseQuantity value={row.bookQuantityBase} unit={row.baseUnit} /> },
      { key: 'physical', header: 'Hasil fisik', cell: row => <WarehouseQuantity value={row.quantityBase} unit={row.baseUnit} /> },
      { key: 'difference', header: 'Selisih', cell: row => <WarehouseQuantity value={(BigInt(row.quantityBase) - BigInt(row.bookQuantityBase)).toString()} unit={row.baseUnit} /> },
    ]} />
  </>}</WarehouseState></section>
}
