import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, tokenStore } from '@/api/client'
import { useAuth } from '@/auth/useAuth'
import { checkInVisit, checkOutVisit, listVisits, markVisitOnSite, submitVisit, type VisitHistory, type VisitListView, type VisitState } from '@/api/fieldservice'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState } from '@/components/atoms'
import { PageHeader, Tabs } from '@/components/molecules'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { useToast } from '@/system'

const LABELS: Record<VisitState, string> = { PLANNED: 'Belum masuk', CHECKED_IN: 'Sudah masuk', ON_SITE: 'Di lokasi', CHECKED_OUT: 'Sudah pulang', SUBMITTED: 'Terkirim', CANCELLED: 'Dibatalkan', CONFLICT: 'Perlu diperiksa' }
const NEXT: Partial<Record<VisitState, { readonly label: string; readonly run: (visit: VisitListView) => Promise<unknown> }>> = {
  PLANNED: { label: 'Check-in', run: visit => checkInVisit(visit.id, visit.revision, 'ACCEPTED', null) },
  CHECKED_IN: { label: 'Tiba di lokasi', run: visit => markVisitOnSite(visit.id, visit.revision) },
  ON_SITE: { label: 'Check-out', run: visit => checkOutVisit(visit.id, visit.revision) },
  CHECKED_OUT: { label: 'Kirim kunjungan', run: visit => submitVisit(visit.id, visit.revision) },
}
const date = (value: string | null) => value ? new Date(value).toLocaleString('id-ID') : 'Belum tercatat'

export function MyVisitsPage() {
  const toast = useToast(), { can } = useCan()
  const { user } = useAuth()
  const [history, setHistory] = useState<VisitHistory>('ACTIVE'), [page, setPage] = useState(0), [busy, setBusy] = useState<string | null>(null)
  const loader = useCallback(() => listVisits(history, page), [history, page]), result = useWarehouseQuery(loader, user?.tenantId + ':' + user?.id + ':' + tokenStore.getSessionVersion())
  const act = async (visit: VisitListView) => {
    const action = NEXT[visit.state]
    if (!action || !visit.actionable || busy) return
    setBusy(visit.id)
    try { await action.run(visit); toast.success(action.label + ' berhasil'); result.reload() }
    catch (caught) { toast.error(caught instanceof ApiError ? caught.message : 'Aksi kunjungan ditolak') }
    finally { setBusy(null) }
  }
  return <div className="stack visits-page">
    <PageHeader title="Kunjungan Saya" subtitle="Kehadiran pada pekerjaan yang ditugaskan: masuk, tiba di lokasi, pulang, lalu kirim kunjungan." actions={<Button onClick={result.reload} disabled={busy !== null}>Segarkan</Button>} />
    <Tabs idPrefix="my-visits" tabs={[{ key: 'ACTIVE', label: 'Aktif' }, { key: 'HISTORY', label: 'Riwayat' }]} active={history} onChange={value => { setHistory(value); setPage(0) }} />
    <div role="tabpanel" id={'my-visits-panel-' + history} aria-labelledby={'my-visits-tab-' + history}>
      <WarehouseState {...result}>{data => <div className="stack">
        {data.content.length === 0 ? <div className="card"><EmptyState title={history === 'ACTIVE' ? 'Belum ada kunjungan aktif' : 'Belum ada riwayat kunjungan'} hint="Kunjungan dibuat otomatis saat WO ditugaskan kepada Anda. Visit B2B tersedia di menu terpisah." /></div> : data.content.map(visit => {
          const action = NEXT[visit.state]
          return <article className="card stack visit-card" key={visit.id}>
            <div className="spread wrap"><div><Link to={'/my-work-orders/' + visit.workOrderId} className="text-action">{visit.workOrderCode}</Link><h2>{visit.title}</h2><p className="muted">{visit.customerName ?? 'Tanpa pelanggan'} · {visit.areaName ?? 'Tanpa area'}</p></div><Badge tone={visit.state === 'SUBMITTED' ? 'good' : 'accent'}>{LABELS[visit.state]}</Badge></div>
            <dl className="visit-times"><div><dt>Jadwal</dt><dd>{visit.scheduledAt ? date(visit.scheduledAt) : 'Belum dijadwalkan'}</dd></div><div><dt>Masuk</dt><dd>{date(visit.session.startedAt)}</dd></div><div><dt>Pulang</dt><dd>{date(visit.session.endedAt)}</dd></div><div><dt>Dikirim</dt><dd>{date(visit.session.submittedAt)}</dd></div></dl>
            <div className="spread wrap"><span className="muted">{visit.actionable ? 'Ikuti langkah kunjungan sesuai kegiatan di lapangan.' : 'Riwayat tersimpan; penugasan ini sudah tidak aktif.'}</span>{visit.actionable && action && <Button variant="primary" disabled={busy !== null || !can('workorder.order.field')} onClick={() => void act(visit)}>{busy === visit.id ? 'Menyimpan…' : action.label}</Button>}</div>
          </article>
        })}
        <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
      </div>}</WarehouseState>
    </div>
  </div>
}
