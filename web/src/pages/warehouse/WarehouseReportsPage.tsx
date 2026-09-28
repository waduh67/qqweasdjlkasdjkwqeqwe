import { ArrowDownload16Regular } from '@fluentui/react-icons'
import { WarehouseReferenceFilter } from '@/components/organisms/warehouse/WarehouseReferenceFilter'
import { WarehouseListActions } from '@/components/organisms/warehouse/WarehouseListActions'
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useLocation, useNavigationType, useSearchParams } from 'react-router-dom'
import { exportReport, historyReport, listReport, REPORT_KINDS, reportParams, type ReportFilter, type ReportKind } from '@/api/warehouse/reports'
import { warehouseError } from '@/api/warehouse/errors'
import { getMaterialWorkOrder, listMaterialWorkOrders } from '@/api/warehouse/workOrders'
import { useCan } from '@/auth/useCan'
import { Button } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { FilterDateRange, FilterSelect } from '@/components/organisms/ResourceFilters'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseReportTable } from './WarehouseReportTable'
import { reportLabels } from './reportPresentation'
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
  return <div className="stack"><PageHeader title="Laporan Gudang" />

    {parsed.print ? <WarehouseReportPrint key={`${parsed.print.id}:${parsed.print.revision}`} {...parsed.print} onClose={() => apply({ documentId: '', revision: '' })} /> :
      <ReportList key={parsed.kind} selector={<FilterSelect key="kind" label="Jenis laporan" value={parsed.kind} onChange={value => {
      const next = new URLSearchParams(params); next.set('kind', value); next.delete('page'); next.delete('sort'); next.delete('documentId'); next.delete('revision')
      if (value !== 'work-order-costs') next.delete('workOrderId')
      setParams(next)
    }}>{REPORT_KINDS.filter(kind => (kind !== 'work-order-costs' || can('inventory.cost.view')) && (kind !== 'unknown-stock' || can('inventory.provenance.view'))).map(kind => <option key={kind} value={kind}>{reportLabels[kind]}</option>)}</FilterSelect>} kind={parsed.kind} filter={parsed.filter} apply={apply} page={number => { const next = new URLSearchParams(params); next.set('page', String(number)); setParams(next) }} />}
  </div>
}
function ReportList({ kind, filter, apply, page, selector }: { selector: ReactNode; kind: ReportKind; filter: ReportFilter; apply: (values: Record<string, string>) => void; page: (number: number) => void }) {
  const { can } = useCan(), location = useLocation(), navigation = useNavigationType()
  const loader = useCallback(() => listReport(kind, filter), [kind, filter]), result = useWarehouseQuery(loader)
  return <>
    <div className="resource-list-controls"><div className="resource-command-row"><WarehouseListActions onRefresh={result.reload} />
      {result.state.status === 'ready' && <ReportExport key={JSON.stringify(filter)} kind={kind} filter={filter} total={result.state.data.page.totalElements} />}
    </div>
    <WarehouseStockFilters filter={filter} history={historyReport(kind)} buckets={false} label="Filter laporan" onApply={apply}>{[
      selector,
      <FilterDateRange resetKey={navigation === 'POP' ? location.key : undefined} key="period" secondary label="Periode" dateTime fromLabel="Awal periode" untilLabel="Akhir periode" from={filter.from} until={filter.until} onChange={(from, until) => apply({ from: from ?? '', until: until ?? '' })} />,
      kind === 'work-order-costs' && (can('workorder.view') || filter.workOrderId) && <WarehouseReferenceFilter key="work-order" label="Work order" valueId={filter.workOrderId} canLookup={can('workorder.view')} get={getMaterialWorkOrder} load={workOrders} onChange={workOrderId => apply({ workOrderId })} name={row => `${row.code} · ${row.title}`} />,
    ]}</WarehouseStockFilters></div>
    {kind === 'stock-card' && <p>Saldo awal mencakup pergerakan sebelum rentang tanggal. Setiap saldo dihitung per barang, lokasi, dan satuan.</p>}
    {kind === 'unknown-stock' && <p>Catatan belum terverifikasi tidak dihitung sebagai stok tersedia.</p>}
    <WarehouseState {...result}>{data => <>
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
  return <section className="resource-export" aria-label="Ekspor laporan">

    <div className="row wrap"><Button variant="subtle" className="resource-export-action" icon={<ArrowDownload16Regular />} title={total > 1000 ? "Ekspor dibatasi 1.000 baris. Persempit filter." : "Ekspor semua hasil sesuai filter"} disabled={busy || total > 1000} onClick={() => void prepare()}>{busy ? 'Menyiapkan CSV…' : 'Ekspor CSV'}</Button>{url && <a href={url} download={`gudang-${kind}.csv`}>Unduh CSV</a>}</div>
    {error && <p role="alert">{error}</p>}
  </section>
}
const workOrders = (query: string, page: number) => listMaterialWorkOrders({ query, page })
