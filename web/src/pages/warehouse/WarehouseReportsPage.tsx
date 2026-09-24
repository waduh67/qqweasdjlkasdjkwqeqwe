import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { exportReport, historyReport, listReport, REPORT_KINDS, reportParams, type ReportFilter, type ReportKind } from '@/api/warehouse/reports'
import { warehouseError } from '@/api/warehouse/errors'
import { getMaterialWorkOrder, listMaterialWorkOrders, type MaterialWorkOrder } from '@/api/warehouse/workOrders'
import { useCan } from '@/auth/useCan'
import { Button, SelectField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseReportTable } from './WarehouseReportTable'
import { reportLabels, reportLink } from './reportPresentation'
import { WarehouseReportPrint } from './WarehouseReportPrint'
import { WarehouseStockFilters } from './WarehouseStockFilters'

export function WarehouseReportsPage() {
  const { can } = useCan(), [params, setParams] = useSearchParams()
  if (!can('inventory.report.view')) return <WarehouseDenied />
  let parsed: ReturnType<typeof reportParams>
  try { parsed = reportParams(params) } catch { return <div role="alert" className="card stack"><p>Filter atau alamat laporan tidak dikenal.</p><Link to="/warehouse/reports">Buka laporan tanpa filter</Link></div> }
  if (parsed.kind === 'work-order-costs' && !can('inventory.cost.view')) return <WarehouseDenied />
  // The server separately checks provenance permission for unresolved stock.
  if (parsed.kind === 'unknown-stock' && !can('inventory.provenance.view')) return <WarehouseDenied />
  function apply(values: Record<string, string>) {
    const next = new URLSearchParams(params); next.delete('page')
    for (const [key, value] of Object.entries(values)) if (value) next.set(key, value); else next.delete(key)
    setParams(next)
  }
  return <div className="stack"><PageHeader title="Laporan Gudang" subtitle="Stok, perjalanan barang dan biaya pemakaian dalam cakupan akses saat ini." />
    <SelectField label="Jenis laporan" value={parsed.kind} onChange={(_, data) => {
      const next = new URLSearchParams(params); next.set('kind', data.value); next.delete('page'); next.delete('sort'); next.delete('documentId'); next.delete('revision')
      if (data.value !== 'work-order-costs') next.delete('workOrderId')
      setParams(next)
    }}>{REPORT_KINDS.filter(kind => (kind !== 'work-order-costs' || can('inventory.cost.view')) && (kind !== 'unknown-stock' || can('inventory.provenance.view'))).map(kind => <option key={kind} value={kind}>{reportLabels[kind]}</option>)}</SelectField>
    {parsed.print ? <WarehouseReportPrint key={`${parsed.print.id}:${parsed.print.revision}`} {...parsed.print} onClose={() => apply({ documentId: '', revision: '' })} /> :
      <ReportList key={params.toString()} kind={parsed.kind} filter={parsed.filter} apply={apply} page={number => { const next = new URLSearchParams(params); next.set('page', String(number)); setParams(next) }} />}
  </div>
}
function ReportList({ kind, filter, apply, page }: { kind: ReportKind; filter: ReportFilter; apply: (values: Record<string, string>) => void; page: (number: number) => void }) {
  const loader = useCallback(() => listReport(kind, filter), [kind, filter]), result = useWarehouseQuery(loader)
  return <>
    <WarehouseStockFilters filter={filter} history={historyReport(kind)} buckets={false} label="Filter laporan" onApply={apply} />
    <ReportPeriod kind={kind} filter={filter} apply={apply} />
    <div className="row wrap"><Button onClick={result.reload}>Muat ulang laporan</Button><Link to={reportLink({ kind })}>Hapus filter laporan</Link></div>
    {kind === 'stock-card' && <p>Saldo awal mencakup pergerakan sebelum rentang tanggal. Setiap saldo dihitung per barang, lokasi, dan satuan.</p>}
    {kind === 'unknown-stock' && <p>Catatan belum terverifikasi tidak dihitung sebagai stok tersedia.</p>}
    <WarehouseState {...result}>{data => <><ReportExport key={`${data.page.totalElements}:${data.page.page}`} kind={kind} filter={filter} total={data.page.totalElements} />
      <WarehouseReportTable data={data} onPrint={row => apply({ documentId: row.documentId, revision: String(row.documentRevision) })} />
      <WarehousePagination page={data.page.page} size={data.page.size} total={data.page.totalElements} onChange={page} />
    </>}</WarehouseState>
  </>
}
function ReportExport({ kind, filter, total }: { kind: ReportKind; filter: ReportFilter; total: number }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null), [url, setUrl] = useState<string | null>(null)
  const mounted = useRef(true), lastUrl = useRef<string | null>(null)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; if (lastUrl.current) URL.revokeObjectURL(lastUrl.current) } }, [])
  async function prepare() {
    setBusy(true); setError(null); setUrl(null)
    if (lastUrl.current) { URL.revokeObjectURL(lastUrl.current); lastUrl.current = null }
    try {
      const blob = await exportReport(kind, filter)
      if (mounted.current) { const next = URL.createObjectURL(blob); lastUrl.current = next; setUrl(next) }
    } catch (caught) { if (mounted.current) setError(warehouseError(caught)) }
    finally { if (mounted.current) setBusy(false) }
  }
  return <section className="card stack" aria-label="Ekspor laporan"><p>{total} baris sesuai filter. CSV mencakup seluruh hasil, maksimal 1.000 baris.</p>
    {total > 1000 && <p>Persempit lokasi, barang, atau rentang tanggal sebelum mengekspor.</p>}
    <div className="row wrap"><Button disabled={busy || total > 1000} onClick={() => void prepare()}>{busy ? 'Menyiapkan CSV…' : 'Siapkan CSV'}</Button>{url && <a href={url} download={`gudang-${kind}.csv`}>Unduh CSV</a>}</div>
    {error && <p role="alert">{error}</p>}
  </section>
}
const workOrders = (query: string, page: number) => listMaterialWorkOrders({ query, page })
function ReportPeriod({ kind, filter, apply }: { kind: ReportKind; filter: ReportFilter; apply: (values: Record<string, string>) => void }) {
  const { can } = useCan()
  const loader = useCallback(() => kind === 'work-order-costs' && filter.workOrderId && can('workorder.view') ? getMaterialWorkOrder(filter.workOrderId) : Promise.resolve(null), [kind, filter.workOrderId, can])
  const result = useWarehouseQuery(loader)
  return <details className="card"><summary>Rentang tanggal{kind === 'work-order-costs' ? ' dan work order' : ''}</summary><WarehouseState {...result}>{workOrder => <PeriodForm kind={kind} filter={filter} initialWorkOrder={workOrder} apply={apply} />}</WarehouseState></details>
}
function PeriodForm({ kind, filter, initialWorkOrder, apply }: { kind: ReportKind; filter: ReportFilter; initialWorkOrder: MaterialWorkOrder | null; apply: (values: Record<string, string>) => void }) {
  const { can } = useCan(), [from, setFrom] = useState(filter.from ?? ''), [until, setUntil] = useState(filter.until ?? '')
  const [workOrder, setWorkOrder] = useState(initialWorkOrder), [error, setError] = useState<string | null>(null)
  function submit(event: FormEvent) {
    event.preventDefault()
    if (Boolean(from) !== Boolean(until) || (from && until && (!Number.isFinite(Date.parse(from)) || !Number.isFinite(Date.parse(until)) || Date.parse(from) >= Date.parse(until) || Date.parse(until) - Date.parse(from) > 366 * 86400000))) { setError('Isi awal dan akhir yang valid, berurutan, maksimal 366 hari.'); return }
    const values = { from: from ? new Date(from).toISOString() : '', until: until ? new Date(until).toISOString() : '' }
    apply({ ...values, ...(kind === 'work-order-costs' ? { workOrderId: workOrder?.id ?? (!can('workorder.view') ? filter.workOrderId ?? '' : '') } : {}) })
  }
  return <form className="stack" onSubmit={submit}><p>Awal termasuk, akhir tidak termasuk. Kosongkan keduanya untuk seluruh waktu. Contoh: 2026-09-01T00:00:00+07:00.</p>
    <TextField label="Awal periode" value={from} onChange={(_, data) => setFrom(data.value)} /><TextField label="Akhir periode" value={until} onChange={(_, data) => setUntil(data.value)} />
    {kind === 'work-order-costs' && can('workorder.view') && <WarehousePicker label="Work order" load={workOrders} value={workOrder} onChange={setWorkOrder} name={row => `${row.code} · ${row.title}`} optional />}
    {error && <p role="alert">{error}</p>}<Button type="submit">Terapkan periode</Button>
  </form>
}
