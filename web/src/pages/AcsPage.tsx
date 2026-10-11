import { useCallback, useEffect, useMemo, useState } from 'react'
import { Copy, Download, RefreshCw, Radio, Activity } from 'lucide-react'
import { ApiError } from '@/api/client'
import { exportAcsDevicesCsv, getAcsHealth, getAcsServerInfo, getAcsStats, listAcsDevices, listAcsLogs, refreshAcsFleet,
  type AcsActivityView, type AcsDeviceQuery, type AcsDeviceRowView, type AcsHealthView, type AcsServerInfoView, type AcsSignalFilter, type AcsStatsView, type AcsStatusFilter } from '@/api/acs'
import { CPE_ACTION_LABEL, type CpeAction } from '@/api/cpe'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, Spinner } from '@/components/atoms'
import { CommandBar, PageHeader, Tabs } from '@/components/molecules'
import { DataTable, OntAcsSettingsCard, type Column } from '@/components/organisms'
import { FilterBar, FilterSearch, FilterSelect } from '@/components/organisms/ResourceFilters'
import { useToast } from '@/system'
import { copyText } from '@/utils/clipboard'
import { downloadBlob } from '@/utils/download'
import { timeAgo } from '@/utils/timeAgo'
import { CustomerDetailBlade } from './CustomerDetailPage'
import './network-workspaces.css'

type AcsTab = 'overview' | 'devices' | 'settings' | 'activity'
export function AcsPage() {
  const { can } = useCan()
  return can('cpe.acs.view') ? <AcsWorkspace /> : <EmptyState title="Akses ditolak" hint="Anda tidak memiliki izin melihat ACS." />
}
function AcsWorkspace() {
  const { can } = useCan(), toast = useToast()
  const canDevices = can('cpe.device.view'), canManage = canDevices && can('cpe.device.manage')
  const [tab, setTab] = useState<AcsTab>('overview')
  const [visited, setVisited] = useState<AcsTab[]>(['overview'])
  const [info, setInfo] = useState<AcsServerInfoView | null>(null), [health, setHealth] = useState<AcsHealthView | null>(null)
  const [stats, setStats] = useState<AcsStatsView | null>(null), [statsFailed, setStatsFailed] = useState(false)
  const [serverFailed, setServerFailed] = useState(false), [revision, setRevision] = useState(0)
  const [checking, setChecking] = useState(false)
  const [updating, setUpdating] = useState(false), [updateResult, setUpdateResult] = useState('')
  const checkHealth = useCallback(async (announce = false) => {
    setChecking(true)
    try { const value = await getAcsHealth(); setHealth(value); if (announce) (value.status === 'ONLINE' ? toast.success : toast.error)(value.status === 'ONLINE' ? 'Server ACS terhubung.' : 'Server ACS tidak dapat dihubungi.') }
    catch { setHealth(null); if (announce) toast.error('Pemeriksaan koneksi gagal.') }
    finally { setChecking(false) }
  }, [toast])
  useEffect(() => { void checkHealth() }, [checkHealth, revision])
  useEffect(() => {
    let alive = true, pending = false
    async function load() {
      if (pending) return
      pending = true
      try { const value = await getAcsServerInfo(); if (alive) { setInfo(value); setServerFailed(false) } }
      catch { if (alive) setServerFailed(true) }
      finally { pending = false }
    }
    void load()
    const timer = window.setInterval(() => void load(), 30_000)
    window.addEventListener('focus', load)
    return () => { alive = false; window.clearInterval(timer); window.removeEventListener('focus', load) }
  }, [revision])
  useEffect(() => {
    if (!canDevices) return
    let alive = true
    void getAcsStats().then(value => { if (alive) { setStats(value); setStatsFailed(false) } }).catch(() => { if (alive) setStatsFailed(true) })
    return () => { alive = false }
  }, [canDevices, revision])
  const tabs: { key: AcsTab; label: string }[] = [
    { key: 'overview', label: 'Ringkasan' }, ...(canDevices ? [{ key: 'devices' as const, label: 'Perangkat' }] : []),
    { key: 'settings', label: 'Setelan ONT' }, ...(canDevices ? [{ key: 'activity' as const, label: 'Aktivitas' }] : []),
  ]
  const active = tabs.some(item => item.key === tab) ? tab : 'overview'
  const refresh = () => setRevision(value => value + 1)
  async function updateDevices() {
    if (updating) return
    setUpdating(true); setUpdateResult('')
    try {
      const result = await refreshAcsFleet()
      setUpdateResult(`Pembaruan perangkat: ${result.connected} terhubung, ${result.queued} dalam antrean, ${result.failed} gagal, ${result.skipped} belum diproses.`)
      refresh()
    } catch (caught) { toast.error(caught instanceof ApiError ? caught.message : 'Pembaruan perangkat gagal.') }
    finally { setUpdating(false) }
  }
  return <div className="network-page">
    <PageHeader title="ACS / TR-069" />
    <Tabs idPrefix="acs" tabs={tabs} active={active} onChange={next => { setTab(next); setVisited(current => current.includes(next) ? current : [...current, next]) }} />
    <section role="tabpanel" id="acs-panel-overview" aria-labelledby="acs-tab-overview" hidden={active !== 'overview'} className="network-panel">
      <CommandBar actions={[
        { key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, onClick: refresh },
        ...(canManage ? [{ key: 'update', label: 'Perbarui perangkat online', icon: <Radio size={16} />, disabled: updating, onClick: () => void updateDevices() }] : []),
        { key: 'health', label: 'Periksa koneksi', icon: <Activity size={16} />, disabled: checking, onClick: () => void checkHealth(true) },
      ]} />
      {updateResult && <p role="status" className="network-note">{updateResult}</p>}
      {canDevices && (statsFailed ? <LoadError message="Ringkasan perangkat gagal dimuat." retry={refresh} /> : <AcsMetrics stats={stats} />)}
      {serverFailed ? <LoadError message="Informasi server gagal dimuat." retry={refresh} /> : <ServerInfo info={info} health={health} stats={canDevices ? stats : null} />}
    </section>
    {canDevices && visited.includes('devices') && <section role="tabpanel" id="acs-panel-devices" aria-labelledby="acs-tab-devices" hidden={active !== 'devices'} className="network-panel">
      <DevicesTab canCustomer={can('customer.customer.view')} revision={revision} />
    </section>}
    {visited.includes('settings') && <section role="tabpanel" id="acs-panel-settings" aria-labelledby="acs-tab-settings" hidden={active !== 'settings'} className="network-panel">
      <CommandBar actions={[{ key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, onClick: refresh }]} />
      <OntAcsSettingsCard server={{ info, failed: serverFailed }} layout="section" />
    </section>}
    {canDevices && visited.includes('activity') && <section role="tabpanel" id="acs-panel-activity" aria-labelledby="acs-tab-activity" hidden={active !== 'activity'} className="network-panel"><ActivityLog /></section>}
  </div>
}
function LoadError({ message, retry }: { message: string; retry: () => void }) {
  return <div className="network-error" role="alert"><p>{message}</p><Button onClick={retry}>Coba lagi</Button></div>
}
function AcsMetrics({ stats }: { stats: AcsStatsView | null }) {
  if (!stats) return <Spinner />
  return <>
    <dl className="network-metrics" aria-label="Ringkasan perangkat">
      <div><dt>Total perangkat</dt><dd>{stats.totalDevices.toLocaleString('id-ID')}</dd></div>
      <div><dt>Online</dt><dd>{stats.onlineDevices.toLocaleString('id-ID')}</dd></div>
      <div><dt>Offline</dt><dd>{stats.offlineDevices.toLocaleString('id-ID')}</dd></div>
      <div><dt>Rata-rata RX</dt><dd>{stats.avgRxPowerDbm == null ? '—' : `${stats.avgRxPowerDbm.toLocaleString('id-ID')} dBm`}</dd>
        <small>{stats.signalSampleCount ? `${stats.signalSampleCount} perangkat terukur` : 'Belum ada pengukuran'}</small></div>
    </dl>
    <p className="network-note">Status online mengikuti laporan terakhir perangkat ke ACS.</p>
  </>
}
function ServerInfo({ info, health, stats }: { info: AcsServerInfoView | null; health: AcsHealthView | null; stats: AcsStatsView | null }) {
  const toast = useToast()
  if (!info) return <Spinner />
  return <section className="network-section" aria-label="Server ACS">
    <h2>Server ACS</h2>
    <dl className="network-properties">
      {([['Alamat API (NBI)', info.nbiBaseUrl], ['Alamat perangkat (CWMP)', info.cwmpUrl]] as const).map(([label, value]) => <div key={label}>
        <dt>{label}</dt><dd className="network-property-value"><span>{value || 'Belum diatur'}</span>{value && <Button variant="subtle" icon={<Copy size={14} />} aria-label={`Salin ${label}`}
          onClick={() => void copyText(value).then(ok => ok ? toast.success('Alamat disalin.') : toast.error('Gagal menyalin alamat.'))} />}</dd>
      </div>)}
      <div><dt>Koneksi server</dt><dd>{health ? health.status === 'ONLINE' ? 'Terhubung' : 'Tidak terhubung' : 'Belum tersedia'}</dd></div>
      <div><dt>Waktu respons</dt><dd>{health?.latencyMs != null ? `${health.latencyMs} ms` : '—'}</dd></div>
      <div><dt>Terakhir diperiksa</dt><dd>{health?.checkedAt ? timeAgo(health.checkedAt) : '—'}</dd></div>
      {stats && <div><dt>Sinkronisasi terakhir</dt><dd>{stats.lastSyncAt ? `${timeAgo(stats.lastSyncAt)}${stats.lastSyncOk === false ? ' · Gagal' : ''}` : 'Belum ada sinkronisasi'}</dd></div>}
    </dl>
  </section>
}
const SIGNAL_OPTIONS: { value: AcsSignalFilter; label: string }[] = [
  { value: 'ALL', label: 'Semua' }, { value: 'GOOD', label: 'Baik (≥ −25 dBm)' },
  { value: 'WARN', label: 'Lemah (−27 hingga < −25 dBm)' }, { value: 'CRITICAL', label: 'Kritis (< −27 dBm)' }, { value: 'UNKNOWN', label: 'Belum terukur' },
]
function DevicesTab({ canCustomer, revision }: { canCustomer: boolean; revision: number }) {
  const toast = useToast()
  const [q, setQ] = useState(''), [status, setStatus] = useState<AcsStatusFilter>('ALL'), [signal, setSignal] = useState<AcsSignalFilter>('ALL'), [brand, setBrand] = useState('')
  const [rows, setRows] = useState<AcsDeviceRowView[]>([]), [brands, setBrands] = useState<string[]>([])
  const [loading, setLoading] = useState(true), [error, setError] = useState(''), [refresh, setRefresh] = useState(0)
  const [exporting, setExporting] = useState(false)
  const [detailId, setDetailId] = useState<string | null>(null)
  const filter = useMemo<AcsDeviceQuery>(() => ({ q, status, signal, brand: brand || undefined }), [q, status, signal, brand])
  useEffect(() => {
    let alive = true
    setLoading(true); setError('')
    void listAcsDevices(filter).then(data => {
      if (!alive) return
      setRows(data)
      setBrands(current => [...new Set([...current, ...data.flatMap(item => item.manufacturer ? [item.manufacturer] : [])])].sort())
    }).catch(caught => { if (alive) setError(caught instanceof ApiError ? caught.message : 'Daftar perangkat gagal dimuat.') })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [filter, refresh, revision])
  async function exportCsv() {
    setExporting(true)
    try { downloadBlob(await exportAcsDevicesCsv(filter), 'perangkat-acs.csv') }
    catch (caught) { toast.error(caught instanceof ApiError ? caught.message : 'Ekspor perangkat gagal.') }
    finally { setExporting(false) }
  }
  const columns: Column<AcsDeviceRowView>[] = [
    { key: 'serial', header: 'Serial', sortValue: item => item.serialNumber, cell: item => item.serialNumber,
      onCellClick: canCustomer ? item => { if (item.customerId) setDetailId(item.customerId) } : undefined },
    { key: 'customer', header: 'Pelanggan', sortValue: item => item.customerName, cell: item => item.customerName || '—' },
    { key: 'manufacturer', header: 'Merek', sortValue: item => item.manufacturer, cell: item => item.manufacturer || '—' },
    { key: 'model', header: 'Model', sortValue: item => item.model, cell: item => item.model || '—' },
    { key: 'status', header: 'Status ACS', sortValue: item => item.online ? 1 : 0, cell: item => item.online ? 'Online' : 'Offline' },
    { key: 'ip', header: 'Alamat IP', sortValue: item => item.ipAddress, cell: item => item.ipAddress || '—' },
    { key: 'ssid', header: 'SSID', sortValue: item => item.ssid, cell: item => item.ssid || '—' },
    { key: 'pppoe', header: 'Akun PPPoE', sortValue: item => item.pppoeUsername, cell: item => item.pppoeUsername || '—' },
    { key: 'session', header: 'Sesi PPPoE', sortValue: item => item.pppoeOnline == null ? -1 : item.pppoeOnline ? 1 : 0, cell: item => item.pppoeOnline == null ? '—' : item.pppoeOnline ? 'Aktif' : 'Tidak aktif' },
    { key: 'rx', header: 'RX (dBm)', align: 'right', sortValue: item => item.rxPowerDbm, cell: item => item.rxPowerDbm?.toLocaleString('id-ID') ?? '—' },
    { key: 'tx', header: 'TX (dBm)', align: 'right', sortValue: item => item.txPowerDbm, cell: item => item.txPowerDbm?.toLocaleString('id-ID') ?? '—' },
    { key: 'temperature', header: 'Suhu (°C)', align: 'right', sortValue: item => item.temperatureC, cell: item => item.temperatureC?.toLocaleString('id-ID') ?? '—' },
    { key: 'lastInform', header: 'Laporan terakhir', sortValue: item => item.lastInformAt, cell: item => item.lastInformAt ? timeAgo(item.lastInformAt) : 'Belum melapor' },
  ]
  const filtered = !!q || status !== 'ALL' || signal !== 'ALL' || !!brand
  return <div className="network-panel">
    <div className="resource-list-controls">
      <CommandBar actions={[
        { key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, disabled: loading, onClick: () => setRefresh(value => value + 1) },
        { key: 'export', label: 'Ekspor CSV', icon: <Download size={16} />, disabled: exporting, onClick: () => void exportCsv() },
      ]} />
      <FilterBar label="Filter perangkat" search={<FilterSearch label="Cari perangkat" value={q} onChange={setQ} />}>
        <FilterSelect label="Status ACS" value={status} defaultValue="ALL" onChange={value => setStatus(value as AcsStatusFilter)}><option value="ALL">Semua</option><option value="ONLINE">Online</option><option value="OFFLINE">Offline</option></FilterSelect>
        <FilterSelect label="Sinyal RX" value={signal} defaultValue="ALL" onChange={value => setSignal(value as AcsSignalFilter)}>{SIGNAL_OPTIONS.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}</FilterSelect>
        <FilterSelect label="Merek" value={brand} onChange={setBrand}><option value="">Semua</option>{brands.map(value => <option key={value} value={value}>{value}</option>)}</FilterSelect>
      </FilterBar>
    </div>
    {error ? <LoadError message={error} retry={() => setRefresh(value => value + 1)} /> : <>
      <p className="network-count">{rows.length} perangkat</p>
      <DataTable presentation="resource" rows={rows} columns={columns} rowKey={item => item.id} loading={loading} initialSort={{ key: 'serial', dir: 'asc' }}
        empty={<EmptyState title={filtered ? 'Tidak ada perangkat yang cocok' : 'Belum ada perangkat'} hint={filtered ? 'Ubah pencarian atau filter.' : 'Perangkat muncul setelah terhubung ke ACS dan serialnya terdaftar pada pelanggan.'} />} />
    </>}
    <CustomerDetailBlade customerId={detailId} onClose={() => setDetailId(null)} />
  </div>
}
function ActivityLog() {
  const [rows, setRows] = useState<AcsActivityView[]>([]), [loading, setLoading] = useState(true), [error, setError] = useState('')
  const [revision, setRevision] = useState(0), [q, setQ] = useState(''), [status, setStatus] = useState('')
  useEffect(() => {
    let alive = true
    setLoading(true); setError('')
    void listAcsLogs(100).then(value => { if (alive) setRows(value) }).catch(() => { if (alive) setError('Aktivitas gagal dimuat.') }).finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [revision])
  const filtered = rows.filter(item => (!status || item.status === status) && [item.serialNumber, item.customerName, item.requestedByEmail, CPE_ACTION_LABEL[item.action as CpeAction] ?? item.action].some(value => value?.toLowerCase().includes(q.toLowerCase().trim())))
  const columns: Column<AcsActivityView>[] = [
    { key: 'time', header: 'Waktu', sortValue: item => item.requestedAt, cell: item => new Date(item.requestedAt).toLocaleString('id-ID') },
    { key: 'action', header: 'Tindakan', cell: item => CPE_ACTION_LABEL[item.action as CpeAction] ?? item.action },
    { key: 'serial', header: 'Serial', cell: item => item.serialNumber || '—' },
    { key: 'customer', header: 'Pelanggan', cell: item => item.customerName || '—' },
    { key: 'status', header: 'Hasil', cell: item => item.status === 'SUCCESS' ? 'Berhasil' : 'Gagal' },
    { key: 'actor', header: 'Pengguna', cell: item => item.requestedByEmail || '—' },
    { key: 'detail', header: 'Keterangan', cell: item => item.detail || '—' },
  ]
  return <div className="network-panel">
    <div className="resource-list-controls">
      <CommandBar actions={[{ key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, disabled: loading, onClick: () => setRevision(value => value + 1) }]} />
      <FilterBar label="Filter aktivitas" search={<FilterSearch label="Cari aktivitas" value={q} onChange={setQ} />}>
        <FilterSelect label="Hasil" value={status} onChange={setStatus}><option value="">Semua</option><option value="SUCCESS">Berhasil</option><option value="FAILED">Gagal</option></FilterSelect>
      </FilterBar>
    </div>
    {error ? <LoadError message={error} retry={() => setRevision(value => value + 1)} /> : <>
      <p className="network-count">{filtered.length} dari {rows.length} aktivitas terbaru</p>
      <DataTable presentation="resource" rows={filtered} columns={columns} rowKey={item => item.id} loading={loading} initialSort={{ key: 'time', dir: 'desc' }}
        empty={<EmptyState title={q || status ? 'Tidak ada aktivitas yang cocok' : 'Belum ada aktivitas'} hint={q || status ? 'Ubah pencarian atau filter.' : 'Tindakan pada perangkat akan tampil di sini.'} />} />
    </>}
  </div>
}
