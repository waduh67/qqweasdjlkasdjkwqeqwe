import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { b2b } from '@/api/b2b'
import { field } from '@/api/field'
import { listReferenceWork } from '@/api/warehouse/reference'
import { getRequestOverview, getStockOverview } from '@/api/warehouse/referenceOverview'
import { REQUEST_LABELS } from '@/api/warehouse/referenceRequests'
import { WarehouseDataError } from '@/api/warehouse/codec'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { currentMonth } from './b2b/shared'
import { monthError } from './field/filters'

function useDashboardScope() {
  const { user } = useAuth()
  return user?.tenantId + ':' + user?.id + ':' + user?.permissions.join(',') + ':' + tokenStore.getSessionVersion()
}
function Metric({ label, value, to, note }: { label: string; value: number; to?: string; note?: string }) {
  const body = <><span>{label}</span><strong>{value.toLocaleString('id-ID')}</strong>{note && <small className="muted">{note}</small>}</>
  return to ? <Link className="card operational-metric" to={to}>{body}</Link> : <div className="card operational-metric">{body}</div>
}
function Bars({ rows }: { rows: { label: string; value: number; total: number }[] }) {
  return <div className="operational-bars">{rows.map(row => <div className="operational-bar" key={row.label}>
    <div className="spread wrap"><span>{row.label}</span><strong>{row.value.toLocaleString('id-ID')}{row.total > 0 ? ' / ' + row.total.toLocaleString('id-ID') : ''}</strong></div>
    <div className="operational-bar-track" aria-hidden><div className="operational-bar-fill" style={{ width: Math.min(100, row.total > 0 ? row.value / row.total * 100 : 0) + '%' }} /></div>
  </div>)}</div>
}

export function ReferenceWarehouseOverview({ title = true }: { title?: boolean }) {
  const { hasPermission } = useCan(), scope = useDashboardScope()
  const requests = hasPermission('warehouse.request.view') || hasPermission('warehouse.request.own'), stock = hasPermission('warehouse.stock.view')
  const load = useCallback(async () => ({ requests: requests ? await getRequestOverview() : null, stock: stock ? await getStockOverview() : null }), [requests, stock])
  const result = useWarehouseQuery(load, scope)
  return <div className="stack">
    <PageHeader title={title ? 'Dashboard Gudang' : 'Gudang & Material'} subtitle="Permintaan dan stok yang perlu ditindaklanjuti pada akses gudang Anda." actions={<Button onClick={result.reload}>Segarkan</Button>} />
    <WarehouseState {...result}>{data => <>
      {data.requests && <>
        <div className="operational-metrics">
          <Metric label="Menunggu Admin" value={data.requests.states.find(s => s.state === 'SUBMITTED')?.count ?? 0} to="/warehouse/requests?state=SUBMITTED" />
          <Metric label="Menunggu Manager" value={data.requests.states.find(s => s.state === 'MANAGER_REVIEW')?.count ?? 0} to="/warehouse/requests?state=MANAGER_REVIEW" />
          <Metric label="Perlu tindakan Anda" value={data.requests.actionable} to="/warehouse/requests?actions=true" />
          {data.stock && <Metric label="Stok perlu perhatian" value={data.stock.low + data.stock.empty} to="/warehouse/stock" note={data.stock.empty + ' posisi gudang kosong'} />}
        </div>
        <div className="operational-grid">
          <section className="card stack"><h2>Status Request</h2><Bars rows={data.requests.states.map(s => ({ label: REQUEST_LABELS[s.state], value: s.count, total: data.requests?.states.reduce((sum, s) => sum + s.count, 0) ?? 0 }))} /></section>
          <section className="card stack"><div className="spread wrap"><h2>Request Terbaru</h2><Link to="/warehouse/requests">Lihat semua</Link></div>
            {data.requests.latest.length === 0 ? <EmptyState title="Belum ada request" hint="Ajukan kebutuhan material melalui Request Material." /> : data.requests.latest.map(r => <div className="operational-row" key={r.id}><div><Link to={'/warehouse/requests?id=' + r.id}>{r.reason}</Link><p>{r.technicianName ?? r.warehouseName} · {r.requesterName}</p></div><span>{REQUEST_LABELS[r.state]}</span></div>)}
          </section>
        </div>
      </>}
      {data.stock && <div className="operational-grid">
        <section className="card stack"><h2>Kondisi Stok</h2><p className="muted">Jumlah kombinasi barang dan gudang dalam akses Anda. Saldo gudang mencakup rak di dalamnya; minimum diperiksa pada tiap gudang.</p><Bars rows={[{ label: 'Aman', value: data.stock.safe, total: data.stock.safe + data.stock.low + data.stock.empty }, { label: 'Di bawah minimum', value: data.stock.low, total: data.stock.safe + data.stock.low + data.stock.empty }, { label: 'Kosong', value: data.stock.empty, total: data.stock.safe + data.stock.low + data.stock.empty }]} /></section>
        <section className="card stack"><h2>Stok Perlu Perhatian</h2>{data.stock.attention.length === 0 ? <EmptyState title="Tidak ada stok yang perlu perhatian" /> : data.stock.attention.map(s => <div className="operational-row" key={s.skuId + ':' + s.warehouseId}><div><Link to={'/warehouse/stock?skuId=' + s.skuId}>{s.name}</Link><p>{s.code} · {s.warehouseName}</p></div><div><WarehouseQuantity value={s.availableBase} unit={s.baseUnit} /><p>Minimum <WarehouseQuantity value={s.minimumBase} unit={s.baseUnit} /></p></div></div>)}</section>
      </div>}
    </>}</WarehouseState>
  </div>
}

export function WorkOrderOverview() {
  const { hasPermission } = useCan(), operator = hasPermission('workorder.dashboard.view'), scope = useDashboardScope(), base = operator ? '/work-orders' : '/my-work-orders'
  const load = useCallback(async () => {
    const scope = operator ? 'AREA' : 'SELF'
    const pending = await listReferenceWork('', 0, 'PENDING', false, scope)
    const blocked = await listReferenceWork('', 0, 'BLOCKED', false, scope)
    const completed = await listReferenceWork('', 0, 'COMPLETED', false, scope)
    const overdue = await listReferenceWork('', 0, undefined, true, scope)
    return { pending, blocked, completed, overdue }
  }, [operator])
  const result = useWarehouseQuery(load, scope)
  return <div className="stack"><PageHeader title={operator ? 'Work Order' : 'Work Order Saya'} subtitle="Status pekerjaan dan tugas yang perlu ditindaklanjuti." actions={<Button onClick={result.reload}>Segarkan</Button>} />
    <WarehouseState {...result}>{data => <>
      <div className="operational-metrics"><Metric label="Belum selesai" value={data.pending.totalElements} to={base + '?state=PENDING'} /><Metric label="Overdue" value={data.overdue.totalElements} to={base + '?overdue=true'} /><Metric label="Ada kendala" value={data.blocked.totalElements} to={base + '?state=BLOCKED'} /><Metric label="Selesai" value={data.completed.totalElements} to={base + '?state=COMPLETED'} /></div>
      <div className="operational-grid"><section className="card stack"><h2>Ringkasan Work Order</h2><Bars rows={[{ label: 'Belum selesai', value: data.pending.totalElements, total: data.pending.totalElements + data.blocked.totalElements + data.completed.totalElements }, { label: 'Ada kendala', value: data.blocked.totalElements, total: data.pending.totalElements + data.blocked.totalElements + data.completed.totalElements }, { label: 'Selesai', value: data.completed.totalElements, total: data.pending.totalElements + data.blocked.totalElements + data.completed.totalElements }]} /></section>
        <section className="card stack"><div className="spread wrap"><h2>Perlu Ditindaklanjuti</h2><Link to={base}>Buka Work Order</Link></div>{data.overdue.items.length || data.blocked.items.length || data.pending.items.length ? [...new Map([...data.overdue.items, ...data.blocked.items, ...data.pending.items].map(r => [r.id, r])).values()].slice(0, 5).map(r => <div className="operational-row" key={r.id}><div><Link to={base + '/' + r.id}>{r.code} · {r.title}</Link><p>{r.technicianName} · {r.type.name}</p></div>{r.blockedReason && <span>{r.blockedReason}</span>}</div>) : <EmptyState title="Tidak ada pekerjaan yang perlu ditindaklanjuti" />}</section></div>
    </>}</WarehouseState>
  </div>
}

function jakartaDate() { return new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Jakarta' }) }
export function DailyOverview() {
  const scope = useDashboardScope(), [day, setDay] = useState(jakartaDate)
  const load = useCallback(async () => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day) || !Number.isFinite(Date.parse(day)) || new Date(day).toISOString().slice(0, 10) !== day) throw new Error('Pilih tanggal laporan yang valid.')
    const days = []
    for (let offset = 6; offset >= 0; offset--) { const date = new Date(day + 'T12:00:00Z'); date.setUTCDate(date.getUTCDate() - offset); days.push(await field.completeness(date.toISOString().slice(0, 10), '')) }
    const today = days.find(d => d.date === day)
    if (!today) throw new WarehouseDataError('report date')
    return { today, days }
  }, [day])
  const result = useWarehouseQuery(load, scope)
  return <div className="stack"><PageHeader title="Daily Report" subtitle="Kelengkapan laporan pagi dan sore serta rekap tujuh hari terakhir." actions={<Button onClick={result.reload}>Segarkan</Button>} /><TextField label="Tanggal laporan" type="date" value={day} onChange={(_, d) => setDay(d.value)} />
    <WarehouseState {...result}>{data => <>
      {data.today.calendarError && <p role="alert">Kalender: {data.today.calendarError}</p>}
      <div className="operational-metrics"><Metric label="Laporan pagi" value={data.today.morning} note={'dari ' + data.today.total + ' teknisi'} /><Metric label="Laporan sore" value={data.today.evening} note={'dari ' + data.today.total + ' teknisi'} /><Metric label="Belum lengkap" value={data.today.incomplete} to="/daily-reports" /><Metric label="Hari libur" value={data.today.workingDay ? 0 : 1} note={data.today.workingDay ? 'Hari kerja' : 'Tidak wajib laporan'} /></div>
      <div className="operational-grid"><section className="card stack"><h2>Laporan 7 Hari</h2><Bars rows={data.days.map(d => ({ label: d.date + (d.workingDay ? '' : ' · Libur'), value: d.morning + d.evening, total: d.total * 2 }))} /></section><section className="card stack"><h2>Kelengkapan Teknisi</h2>{data.today.rows.length ? data.today.rows.map(r => <div className="operational-row" key={r.technician.id}><div><strong>{r.technician.name}</strong><p>Teknisi {r.technician.team}</p></div><span>Pagi: {r.morning ? 'Terkirim' : 'Belum'} · Sore: {r.evening ? 'Terkirim' : 'Belum'}</span></div>) : <EmptyState title="Belum ada teknisi dalam akses Anda" />}<Link to="/daily-reports">Buka Daily Report</Link></section></div>
    </>}</WarehouseState>
  </div>
}

export function B2BOverview() {
  const scope = useDashboardScope(), [month, setMonth] = useState(currentMonth)
  const load = useCallback(() => { const error = monthError(month); return error ? Promise.reject(new Error(error)) : b2b.overview(month) }, [month])
  const result = useWarehouseQuery(load, scope)
  return <div className="stack"><PageHeader title="Visit B2B" subtitle="Target bulanan client dan kunjungan yang sudah dilaporkan." actions={<Button onClick={result.reload}>Segarkan</Button>} /><TextField label="Bulan B2B" type="month" value={month} onChange={(_, d) => setMonth(d.value)} />
    <WarehouseState {...result}>{data => <>
      <div className="operational-metrics"><Metric label="Target visit" value={data.target} /><Metric label="Visit tercatat" value={data.counted} to="/b2b/visits" /><Metric label="Sisa target" value={data.remaining} to="/b2b/reports" /><Metric label="Minggu terlewat" value={data.missedWeeks} note="Client tanpa visit pada minggu yang sudah selesai" /></div>
      <section className="card stack"><div className="spread wrap"><h2>Progres Client</h2><Link to="/b2b/reports">Lihat semua client</Link></div>{data.clients.length ? data.clients.map(c => <div className="stack" key={c.clientId}><div className="spread wrap"><strong>{c.setting.name}</strong><span>{c.setting.technicianName}</span></div><Bars rows={[{ label: 'Visit bulanan · Sisa ' + c.remaining, value: c.counted, total: c.setting.target }]} /></div>) : <EmptyState title="Belum ada target B2B pada bulan ini" hint="Pengelola dapat menambahkan client melalui Client B2B." />}</section>
    </>}</WarehouseState>
  </div>
}
