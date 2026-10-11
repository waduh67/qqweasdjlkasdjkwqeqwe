import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { RefreshCw } from 'lucide-react'
import { getTicketSummary, listTickets, TICKET_CATEGORY_LABEL, TICKET_PRIORITY_LABEL, TICKET_STATUS_LABEL,
  type TicketCategory, type TicketFilter, type TicketStatus, type TicketSummaryView, type TicketView } from '@/api/helpdesk'
import { useAuth } from '@/auth/useAuth'
import { Button, EmptyState } from '@/components/atoms'
import { PageHeader, Tabs } from '@/components/molecules'
import { CommandBar } from '@/components/molecules/CommandBar'
import { Pagination } from '@/components/molecules/Pagination'
import { DataTable, type Column } from '@/components/organisms'
import { FilterBar, FilterSearch, FilterSelect } from '@/components/organisms/ResourceFilters'
import { timeAgo } from '@/utils/timeAgo'
import { TicketBlade } from './helpdesk/TicketBlade'
import { ticketSla } from './helpdesk/presentation'
import './helpdesk/helpdesk.css'

type Queue = '' | 'mine' | 'unassigned' | 'overdue'
type Filters = { query: string; status: TicketStatus | ''; category: TicketCategory | ''; queue: Queue; page: number }
const EMPTY: Filters = { query: '', status: '', category: '', queue: '', page: 0 }
const SIZE = 25

export function HelpdeskPage() {
  const { user } = useAuth()
  const [tab, setTab] = useState<'tickets' | 'overview'>('tickets')
  const [filters, setFilters] = useState<Filters>(EMPTY)
  const [revision, setRevision] = useState(0)
  const [tickets, setTickets] = useState<TicketView[]>([]), [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true), [error, setError] = useState('')
  const [summary, setSummary] = useState<TicketSummaryView | null>(null), [summaryError, setSummaryError] = useState(false)
  const [searchParams, setSearchParams] = useSearchParams()
  const ticketId = searchParams.get('ticket')
  const query = useMemo<TicketFilter>(() => ({ query: filters.query, status: filters.status, category: filters.category,
    assigneeId: filters.queue === 'mine' ? user?.id : undefined,
    unassigned: filters.queue === 'unassigned' || undefined, overdue: filters.queue === 'overdue' || undefined,
  }), [filters.query, filters.status, filters.category, filters.queue, user?.id])
  useEffect(() => {
    let current = true
    setLoading(true); setError('')
    void listTickets(query, SIZE, filters.page).then(result => {
      if (!current) return
      const lastPage = Math.max(0, result.totalPages - 1)
      if (filters.page > lastPage) { setFilters(value => ({ ...value, page: lastPage })); return }
      setTickets(result.content); setTotal(result.totalElements)
    }).catch(() => { if (current) setError('Daftar tiket belum berhasil dimuat.') })
      .finally(() => { if (current) setLoading(false) })
    return () => { current = false }
  }, [query, filters.page, revision])
  useEffect(() => {
    let current = true
    setSummaryError(false)
    void getTicketSummary().then(value => { if (current) setSummary(value) }).catch(() => { if (current) setSummaryError(true) })
    return () => { current = false }
  }, [revision])
  const refresh = () => setRevision(value => value + 1)
  const filter = (patch: Partial<Filters>) => setFilters(value => ({ ...value, ...patch, page: 0 }))
  const openDetail = (id: string) => setSearchParams(current => { const next = new URLSearchParams(current); next.set('ticket', id); return next })
  const closeDetail = () => setSearchParams(current => { const next = new URLSearchParams(current); next.delete('ticket'); return next }, { replace: true })
  const columns: Column<TicketView>[] = [
    { key: 'code', header: 'Tiket', minWidth: 140, cell: t => t.code, onCellClick: t => openDetail(t.id) },
    { key: 'subject', header: 'Subjek', minWidth: 250, cell: t => t.subject },
    { key: 'status', header: 'Status', minWidth: 170, cell: t => TICKET_STATUS_LABEL[t.status] },
    { key: 'priority', header: 'Prioritas', minWidth: 95, cell: t => TICKET_PRIORITY_LABEL[t.priority] },
    { key: 'customer', header: 'Pelanggan', minWidth: 180, cell: t => <Link to={`/customers/${t.customerId}`}>{t.customerName}</Link> },
    { key: 'category', header: 'Kategori', minWidth: 155, cell: t => TICKET_CATEGORY_LABEL[t.category] },
    { key: 'assignee', header: 'Penanggung jawab', minWidth: 180, cell: t => t.assigneeName ?? 'Belum ditugaskan' },
    { key: 'sla', header: 'SLA', minWidth: 250, cell: t => { const sla = ticketSla(t); return <span className={sla.overdue ? 'helpdesk-overdue' : undefined}>{sla.label}</span> } },
    { key: 'activity', header: 'Aktivitas terakhir', minWidth: 160, cell: t => timeAgo(t.lastActivityAt) },
    { key: 'workOrder', header: 'Work order', minWidth: 145, cell: t => t.workOrderId ? <Link to={`/work-orders/${t.workOrderId}`}>{t.workOrderCode}</Link> : '—' },
  ]
  const filtered = !!(filters.query || filters.status || filters.category || filters.queue)
  return <div className="helpdesk-page">
    <PageHeader title="Helpdesk" />
    <Tabs idPrefix="helpdesk" active={tab} onChange={setTab} tabs={[{ key: 'tickets', label: 'Tiket' }, { key: 'overview', label: 'Ringkasan' }]} />
    <section className="helpdesk-panel" role="tabpanel" id={`helpdesk-panel-${tab}`} aria-labelledby={`helpdesk-tab-${tab}`}>
      {tab === 'tickets' ? <>
        <div className="resource-list-controls">
          <CommandBar primary={{ key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, disabled: loading, onClick: refresh }} />
          <FilterBar label="Filter tiket" search={<FilterSearch label="Cari tiket" value={filters.query} onChange={query => filter({ query })} />}>
            <FilterSelect label="Antrean" value={filters.queue} onChange={queue => filter({ queue: queue as Queue })}>
              <option value="">Semua</option><option value="mine">Tugas saya</option><option value="unassigned">Belum ditugaskan</option><option value="overdue">Melewati SLA</option>
            </FilterSelect>
            <FilterSelect label="Status" value={filters.status} onChange={status => filter({ status: status as TicketStatus | '' })}>
              <option value="">Semua</option>{Object.entries(TICKET_STATUS_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </FilterSelect>
            <FilterSelect label="Kategori" value={filters.category} onChange={category => filter({ category: category as TicketCategory | '' })}>
              <option value="">Semua</option>{Object.entries(TICKET_CATEGORY_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </FilterSelect>
          </FilterBar>
        </div>
        {error ? <LoadError message={error} retry={refresh} /> : <>
          <DataTable presentation="resource" columns={columns} rows={tickets} rowKey={t => t.id} loading={loading}
            empty={<EmptyState title={filtered ? 'Tidak ada tiket yang cocok' : 'Belum ada tiket'} hint={filtered ? 'Ubah pencarian atau filter.' : 'Laporan pelanggan dari portal akan tampil di sini.'} />} />
          <Pagination page={filters.page} size={SIZE} total={total} busy={loading} onChange={page => setFilters(value => ({ ...value, page }))} />
        </>}
      </> : <>
        <CommandBar primary={{ key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, onClick: refresh }} />
        {summaryError ? <LoadError message="Ringkasan tiket belum berhasil dimuat." retry={refresh} /> : <dl className="helpdesk-metrics" aria-label="Ringkasan seluruh tiket">
          {([{ key: 'open', label: 'Baru', status: 'OPEN' }, { key: 'inProgress', label: 'Ditangani', status: 'IN_PROGRESS' },
            { key: 'resolved', label: 'Menunggu konfirmasi', status: 'RESOLVED' }, { key: 'unassigned', label: 'Belum ditugaskan', queue: 'unassigned' },
            { key: 'overdue', label: 'Melewati SLA', queue: 'overdue' }] as const).map(item => <div key={item.key}>
            <dt>{item.label}</dt><dd><button disabled={!summary} onClick={() => { setFilters({ ...EMPTY, status: 'status' in item ? item.status : '', queue: 'queue' in item ? item.queue : '' }); setTab('tickets') }}
              aria-label={`Lihat tiket ${item.label.toLowerCase()}`}>{summary ? summary[item.key].toLocaleString('id-ID') : '—'}</button></dd>
          </div>)}
        </dl>}
      </>}
    </section>
    {ticketId && <TicketBlade key={ticketId} id={ticketId} onClose={closeDetail} onChanged={refresh} />}
  </div>
}
function LoadError({ message, retry }: { message: string; retry: () => void }) {
  return <div className="helpdesk-error" role="alert"><p>{message}</p><Button onClick={retry}>Coba lagi</Button></div>
}
