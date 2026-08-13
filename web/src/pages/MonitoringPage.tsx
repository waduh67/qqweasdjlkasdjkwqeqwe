import { useCallback, useEffect, useMemo, useState } from 'react'
import { Activity, Check, Trash2, X } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import type {
  AlarmView,
  CollectorCreated,
  CollectorView,
  MonitoringDashboard,
  OnuHistoryView,
} from '../api/monitoring'
import { useCan } from '../auth/useCan'
import { AlarmThresholdPanel, DataTable, type Column, type RowAction } from '@/components/organisms'
import { Button, EmptyState, Segmented, StatusBadge, TextField, Toolbar } from '@/components/atoms'
import { Drawer, SearchInput } from '@/components/molecules'
import { useToast } from '@/system'
import { PageHeader } from '@/components/molecules'
import { OpticalChart } from '@/components/atoms'
import { IconAlert, IconPlus, IconSettings } from '@/components/atoms/icons'

/** Peringkat keparahan untuk pengurutan — makin tinggi makin genting (kritis di atas saat desc). */
const SEVERITY_RANK: Record<string, number> = { CRITICAL: 3, WARNING: 2, INFO: 1 }

/**
 * Dashboard monitoring: kesehatan collector, alarm aktif, pengelolaan agent, dan
 * tren redaman per ONU.
 *
 * Dua hal sengaja ditonjolkan karena mudah luput: collector yang membisu (tanpa
 * itu jaringan tampak sehat justru saat pemantauan buta) dan jumlah kemunculan
 * alarm (membedakan gangguan sekali lewat dari yang berulang).
 */
export function MonitoringPage() {
  const { can } = useCan()
  const toast = useToast()
  const [dashboard, setDashboard] = useState<MonitoringDashboard | null>(null)
  const [collectors, setCollectors] = useState<CollectorView[]>([])
  const [alarms, setAlarms] = useState<AlarmView[]>([])
  const [statusFilter, setStatusFilter] = useState<'ACTIVE' | 'ALL'>('ACTIVE')
  const [collectorQuery, setCollectorQuery] = useState('')
  const [alarmQuery, setAlarmQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [newKey, setNewKey] = useState<CollectorCreated | null>(null)
  const [draftName, setDraftName] = useState('')
  const [trace, setTrace] = useState<{ label: string; history: OnuHistoryView } | null>(null)
  const [thresholds, setThresholds] = useState(false)

  const reload = useCallback(async () => {
    try {
      const [dash, cols, alarmPage] = await Promise.all([
        api.get<MonitoringDashboard>('/api/monitoring/dashboard'),
        can('monitoring.collector.view')
          ? api.get<CollectorView[]>('/api/monitoring/collectors')
          : Promise.resolve<CollectorView[]>([]),
        api.get<PageResponse<AlarmView>>(
          `/api/monitoring/alarms?size=50${statusFilter === 'ACTIVE' ? '&status=ACTIVE' : ''}`,
        ),
      ])
      setDashboard(dash)
      setCollectors(cols)
      setAlarms(alarmPage.content)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat data monitoring')
    } finally {
      setLoading(false)
    }
  }, [can, statusFilter, toast])

  useEffect(() => {
    void reload()
  }, [reload])

  const run = async (action: () => Promise<unknown>, okMessage?: string) => {
    try {
      await action()
      await reload()
      if (okMessage) toast.success(okMessage)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
    }
  }

  const openHistory = async (onuId: string, label: string) => {
    try {
      const history = await api.get<OnuHistoryView>(`/api/monitoring/onus/${onuId}/history?hours=24`)
      setTrace({ label, history })
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat riwayat redaman')
    }
  }

  const canManageCollector = can('monitoring.collector.manage')

  const filteredCollectors = useMemo(() => {
    const q = collectorQuery.trim().toLowerCase()
    if (!q) return collectors
    return collectors.filter((c) =>
      [c.name, c.status, c.agentVersion ?? '', c.lastCycleSummary ?? ''].join(' ').toLowerCase().includes(q),
    )
  }, [collectors, collectorQuery])

  const collectorColumns: Column<CollectorView>[] = [
    {
      key: 'name',
      header: 'Nama',
      sortValue: (c) => c.name,
      cell: (c) => (
        <div>
          <div style={{ fontWeight: 600 }}>{c.name}</div>
          <div className="muted" style={{ fontSize: '0.78rem' }}>
            {c.apiKeyHint}… · tiap {c.pollIntervalSeconds}s
          </div>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (c) => c.status,
      cell: (c) => (
        <div className="row" style={{ gap: '0.35rem' }}>
          <StatusBadge status={c.status} />
          {c.silent && <StatusBadge status="CRITICAL" label="membisu" />}
        </div>
      ),
    },
    { key: 'agent', header: 'Agent', sortValue: (c) => c.agentVersion, cell: (c) => <span className="muted">{c.agentVersion ?? '—'}</span> },
    {
      key: 'lastSeen',
      header: 'Terakhir melapor',
      sortValue: (c) => c.lastSeenAt,
      cell: (c) => (
        <span className="muted" style={{ fontSize: '0.83rem' }}>
          {c.lastSeenAt ? new Date(c.lastSeenAt).toLocaleString('id-ID') : 'belum pernah'}
        </span>
      ),
    },
    {
      key: 'cycle',
      header: 'Siklus terakhir',
      cell: (c) => (
        <span className="muted" style={{ fontSize: '0.8rem', display: 'inline-block', maxWidth: 220 }}>
          {c.lastCycleSummary ?? '—'}
        </span>
      ),
    },
  ]

  // Aksi per-baris di menu `…` ala Azure DataGrid (seragam dengan Pelanggan), bukan tombol inline.
  const collectorActions = (c: CollectorView): RowAction[] => [
    {
      key: 'delete',
      label: 'Hapus',
      icon: <Trash2 size={16} />,
      onClick: () => void run(() => api.del(`/api/monitoring/collectors/${c.id}`), 'Collector dihapus'),
    },
  ]

  const filteredAlarms = useMemo(() => {
    const q = alarmQuery.trim().toLowerCase()
    if (!q) return alarms
    return alarms.filter((a) =>
      [a.entityLabel, a.kind, a.message, a.severity, a.status].join(' ').toLowerCase().includes(q),
    )
  }, [alarms, alarmQuery])

  const alarmColumns: Column<AlarmView>[] = [
    {
      key: 'severity',
      header: 'Keparahan',
      sortValue: (a) => SEVERITY_RANK[a.severity] ?? 0,
      cell: (a) => <StatusBadge status={a.severity} />,
    },
    {
      key: 'entity',
      header: 'Entitas',
      sortValue: (a) => a.entityLabel,
      cell: (a) => (
        <div>
          <div style={{ fontSize: '0.88rem' }}>{a.entityLabel}</div>
          <div className="row" style={{ gap: '0.35rem', marginTop: '0.15rem' }}>
            <span className="badge">{a.kind}</span>
            {a.occurrenceCount > 1 && <span className="muted" style={{ fontSize: '0.75rem' }}>×{a.occurrenceCount}</span>}
          </div>
        </div>
      ),
    },
    {
      key: 'message',
      header: 'Pesan',
      sortValue: (a) => a.message,
      cell: (a) => (
        <span className="muted" style={{ fontSize: '0.85rem', display: 'inline-block', maxWidth: 320 }}>
          {a.message}
        </span>
      ),
    },
    {
      key: 'open',
      header: 'Terbuka',
      sortValue: (a) => a.openMinutes,
      cell: (a) => (
        <span className="muted" style={{ fontSize: '0.83rem', whiteSpace: 'nowrap' }}>
          {formatDuration(a.openMinutes)}
          <div>
            <StatusBadge status={a.status} />
          </div>
        </span>
      ),
    },
  ]

  // Aksi per-baris di menu `…` ala Azure DataGrid (seragam dengan Pelanggan), bukan tombol inline.
  const canMetric = can('monitoring.metric.view')
  const canAck = can('monitoring.alarm.ack')
  const alarmActions = (a: AlarmView): RowAction[] => {
    const list: RowAction[] = []
    if (a.entityType === 'ONU' && canMetric)
      list.push({ key: 'history', label: 'Redaman', icon: <Activity size={16} />, onClick: () => void openHistory(a.entityId, a.entityLabel) })
    if (canAck && a.status === 'ACTIVE')
      list.push({ key: 'ack', label: 'Akui', icon: <Check size={16} />, onClick: () => void run(() => api.post(`/api/monitoring/alarms/${a.id}/acknowledge`), 'Alarm diakui') })
    if (canAck && a.status !== 'CLEARED')
      list.push({ key: 'clear', label: 'Tutup', icon: <X size={16} />, onClick: () => void run(() => api.post(`/api/monitoring/alarms/${a.id}/clear`), 'Alarm ditutup') })
    return list
  }

  return (
    <div className="stack" style={{ gap: '1.5rem' }}>
      <PageHeader title="Monitoring" subtitle="Kesehatan collector, alarm jaringan, dan tren redaman optik." />

      {dashboard && (
        <div className="stat-grid">
          <Stat label="Alarm aktif" value={dashboard.alarms.active} note={`${dashboard.alarms.acknowledged} diakui`} accent={dashboard.alarms.active ? 'crit' : undefined} />
          <Stat label="Kritis" value={dashboard.alarms.bySeverity.CRITICAL ?? 0} accent={(dashboard.alarms.bySeverity.CRITICAL ?? 0) > 0 ? 'crit' : undefined} />
          <Stat label="Peringatan" value={dashboard.alarms.bySeverity.WARNING ?? 0} accent={(dashboard.alarms.bySeverity.WARNING ?? 0) > 0 ? 'warn' : undefined} />
          <Stat label="Collector" value={dashboard.collectors} note={`${dashboard.collectorsSilent} membisu`} accent={dashboard.collectorsSilent ? 'warn' : undefined} />
          <Stat label="Metrik 24 jam" value={dashboard.metricsLast24h} />
        </div>
      )}

      {can('monitoring.collector.view') && (
        <section className="stack" style={{ gap: '0.85rem' }}>
          <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-end', gap: '0.75rem' }}>
            <h2 style={{ margin: 0, fontSize: '1.05rem' }}>Collector</h2>
            {canManageCollector && (
              <div className="row">
                <TextField
                  placeholder="Nama collector baru"
                  value={draftName}
                  onChange={(_, data) => setDraftName(data.value)}
                  style={{ width: 200 }}
                />
                <Button
                  variant="primary"
                  size="small"
                  disabled={!draftName.trim()}
                  onClick={() =>
                    void run(async () => {
                      const created = await api.post<CollectorCreated>('/api/monitoring/collectors', {
                        name: draftName,
                        pollIntervalSeconds: 300,
                      })
                      setNewKey(created)
                      setDraftName('')
                    })
                  }
                >
                  <IconPlus size={15} /> Buat
                </Button>
              </div>
            )}
          </div>

          {newKey && (
            <div className="card" style={{ borderColor: 'var(--warning)', background: 'color-mix(in srgb, var(--warning) 8%, var(--surface))' }}>
              <div className="row" style={{ gap: '0.5rem', marginBottom: '0.5rem' }}>
                <IconAlert size={17} style={{ color: 'var(--warning-ink)' }} />
                <strong>API key untuk “{newKey.collector.name}”</strong>
              </div>
              <code style={{ display: 'block', wordBreak: 'break-all', padding: '0.5rem', marginBottom: '0.5rem' }}>
                {newKey.apiKey}
              </code>
              <p className="muted" style={{ margin: '0 0 0.6rem', fontSize: '0.83rem' }}>
                Salin sekarang — kunci ini hanya ditampilkan sekali. Pasang di collector sebagai{' '}
                <code>FTTH_COLLECTOR_KEY</code>.
              </p>
              <div className="row">
                <Button size="small" onClick={() => void navigator.clipboard?.writeText(newKey.apiKey).then(() => toast.success('API key disalin'))}>
                  Salin
                </Button>
                <Button variant="subtle" size="small" onClick={() => setNewKey(null)}>
                  Selesai
                </Button>
              </div>
            </div>
          )}

          {collectors.length > 0 && (
            <Toolbar>
              <SearchInput value={collectorQuery} onChange={setCollectorQuery} placeholder="Cari collector…" />
            </Toolbar>
          )}
          <DataTable
            columns={collectorColumns}
            rows={filteredCollectors}
            rowKey={(c) => c.id}
            loading={loading}
            initialSort={{ key: 'name', dir: 'asc' }}
            rowActions={canManageCollector ? collectorActions : undefined}
            empty={
              <EmptyState
                title={collectorQuery ? 'Tidak ada collector yang cocok' : 'Belum ada collector'}
                hint={collectorQuery ? 'Coba ubah kata kunci.' : 'Buat satu, lalu jalankan agent dengan API key-nya di jaringan ISP.'}
              />
            }
          />
        </section>
      )}

      <section className="stack" style={{ gap: '0.85rem' }}>
        <h2 style={{ margin: 0, fontSize: '1.05rem' }}>Alarm</h2>
        <Toolbar>
          <SearchInput value={alarmQuery} onChange={setAlarmQuery} placeholder="Cari entitas, jenis, atau pesan…" />
          <Segmented
            ariaLabel="Saring status alarm"
            value={statusFilter}
            onChange={setStatusFilter}
            options={[
              { value: 'ACTIVE', label: 'Aktif' },
              { value: 'ALL', label: 'Semua' },
            ]}
          />
          {/* Ambang alarm sengaja duduk di sebelah daftarnya: pertanyaan "kenapa ini
              muncul / kenapa yang itu tidak" lahir saat menatap daftar ini. */}
          <Button
            variant="subtle"
            size="small"
            onClick={() => setThresholds(true)}
            title="Setel ambang munculnya alarm"
          >
            <IconSettings size={15} /> Ambang alarm
          </Button>
        </Toolbar>
        <DataTable
          columns={alarmColumns}
          rows={filteredAlarms}
          rowKey={(a) => a.id}
          loading={loading}
          initialSort={{ key: 'severity', dir: 'desc' }}
          rowActions={canMetric || canAck ? alarmActions : undefined}
          empty={
            <EmptyState
              title={alarmQuery ? 'Tidak ada alarm yang cocok' : statusFilter === 'ACTIVE' ? 'Tidak ada alarm aktif' : 'Belum ada alarm'}
              hint={alarmQuery ? 'Coba ubah kata kunci.' : 'Jaringan tenang. Alarm baru muncul otomatis saat collector melaporkan gangguan.'}
              icon={<IconAlert size={32} />}
            />
          }
        />
      </section>

      {thresholds && (
        <Drawer title="Ambang alarm" onClose={() => setThresholds(false)}>
          <div className="stack">
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
              Berlaku seketika, termasuk pada alarm yang sudah terbuka.
            </p>
            {/* Daftar alarm dimuat ulang tiap setelan berubah supaya keparahan di
                tabel belakang panel tak sempat berbohong. */}
            <AlarmThresholdPanel onChanged={() => void reload()} />
          </div>
        </Drawer>
      )}

      {trace && (
        <Drawer title={`Tren redaman — ${trace.label}`} onClose={() => setTrace(null)}>
          <div className="stack">
            <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(110px, 1fr))' }}>
              <MiniStat label="Rata-rata" value={fmt(trace.history.averageRxPowerDbm)} unit="dBm" />
              <MiniStat label="Minimum" value={fmt(trace.history.minRxPowerDbm)} unit="dBm" />
              <MiniStat label="Maksimum" value={fmt(trace.history.maxRxPowerDbm)} unit="dBm" />
              <MiniStat
                label="Tren"
                value={fmt(trace.history.trendDbPerDay)}
                unit="dB/hari"
                warn={trace.history.degrading}
              />
            </div>
            {trace.history.degrading && (
              <div className="row" style={{ gap: '0.5rem', color: 'var(--warning-ink)', fontSize: '0.85rem' }}>
                <IconAlert size={16} />
                Redaman memburuk cukup cepat — kandidat pemeliharaan preventif.
              </div>
            )}
            <OpticalChart points={trace.history.points} />
          </div>
        </Drawer>
      )}
    </div>
  )
}

function Stat({ label, value, note, accent }: { label: string; value: number; note?: string; accent?: 'crit' | 'warn' }) {
  return (
    <div className={`stat ${accent === 'crit' ? 'crit-bar' : accent === 'warn' ? 'warn-bar' : 'accent-bar'}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value.toLocaleString('id-ID')}</div>
      {note && <div className="stat-note">{note}</div>}
    </div>
  )
}

function MiniStat({ label, value, unit, warn }: { label: string; value: string; unit: string; warn?: boolean }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value" style={{ fontSize: '1.3rem', color: warn ? 'var(--warning-ink)' : undefined }}>
        {value}
        <span className="muted" style={{ fontSize: '0.7rem', fontWeight: 500 }}> {unit}</span>
      </div>
    </div>
  )
}

function fmt(v: number | null): string {
  return v == null ? '—' : v.toString()
}

function formatDuration(minutes: number): string {
  if (minutes < 60) return `${minutes} menit`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} jam`
  return `${Math.floor(hours / 24)} hari`
}
