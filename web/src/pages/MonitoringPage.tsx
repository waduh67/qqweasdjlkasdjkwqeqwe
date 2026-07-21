import { useCallback, useEffect, useState } from 'react'
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
import { Drawer, EmptyState, SkeletonRows, StatusBadge, useToast } from '../components/ui'
import { OpticalChart } from '../components/OpticalChart'
import { IconAlert, IconPlus } from '../components/icons'

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
  const [loading, setLoading] = useState(true)
  const [newKey, setNewKey] = useState<CollectorCreated | null>(null)
  const [draftName, setDraftName] = useState('')
  const [trace, setTrace] = useState<{ label: string; history: OnuHistoryView } | null>(null)

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

  return (
    <div className="stack" style={{ gap: '1.5rem' }}>
      <div>
        <h1 className="page-title">Monitoring</h1>
        <p className="page-sub">Kesehatan collector, alarm jaringan, dan tren redaman optik.</p>
      </div>

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
        <div className="card pad-0">
          <div className="card-head">
            <h3>Collector</h3>
            {can('monitoring.collector.manage') && (
              <div className="row">
                <input
                  placeholder="Nama collector baru"
                  value={draftName}
                  onChange={(e) => setDraftName(e.target.value)}
                  style={{ width: 200 }}
                />
                <button
                  className="primary small"
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
                </button>
              </div>
            )}
          </div>

          {newKey && (
            <div className="card-body">
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
                  <button className="small" onClick={() => void navigator.clipboard?.writeText(newKey.apiKey).then(() => toast.success('API key disalin'))}>
                    Salin
                  </button>
                  <button className="ghost small" onClick={() => setNewKey(null)}>
                    Selesai
                  </button>
                </div>
              </div>
            </div>
          )}

          {collectors.length === 0 ? (
            <div className="card-body">
              <EmptyState title="Belum ada collector" hint="Buat satu, lalu jalankan agent dengan API key-nya di jaringan ISP." />
            </div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table>
                <thead>
                  <tr>
                    <th>Nama</th>
                    <th>Status</th>
                    <th>Agent</th>
                    <th>Terakhir melapor</th>
                    <th>Siklus terakhir</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {collectors.map((collector) => (
                    <tr key={collector.id}>
                      <td>
                        <div style={{ fontWeight: 550 }}>{collector.name}</div>
                        <div className="muted" style={{ fontSize: '0.78rem' }}>
                          {collector.apiKeyHint}… · tiap {collector.pollIntervalSeconds}s
                        </div>
                      </td>
                      <td>
                        <div className="row" style={{ gap: '0.35rem' }}>
                          <StatusBadge status={collector.status} />
                          {collector.silent && <StatusBadge status="CRITICAL" label="membisu" />}
                        </div>
                      </td>
                      <td className="muted">{collector.agentVersion ?? '—'}</td>
                      <td className="muted" style={{ fontSize: '0.83rem' }}>
                        {collector.lastSeenAt ? new Date(collector.lastSeenAt).toLocaleString('id-ID') : 'belum pernah'}
                      </td>
                      <td className="muted" style={{ fontSize: '0.8rem', maxWidth: 220 }}>
                        {collector.lastCycleSummary ?? '—'}
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        {can('monitoring.collector.manage') && (
                          <button
                            className="ghost small danger"
                            onClick={() => void run(() => api.del(`/api/monitoring/collectors/${collector.id}`), 'Collector dihapus')}
                          >
                            Hapus
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      <div className="card pad-0">
        <div className="card-head">
          <h3>Alarm</h3>
          <div className="segment">
            <button className={statusFilter === 'ACTIVE' ? 'active' : ''} onClick={() => setStatusFilter('ACTIVE')}>
              Aktif
            </button>
            <button className={statusFilter === 'ALL' ? 'active' : ''} onClick={() => setStatusFilter('ALL')}>
              Semua
            </button>
          </div>
        </div>

        {loading ? (
          <div className="card-body">
            <SkeletonRows rows={4} cols={5} />
          </div>
        ) : alarms.length === 0 ? (
          <div className="card-body">
            <EmptyState
              title={statusFilter === 'ACTIVE' ? 'Tidak ada alarm aktif' : 'Belum ada alarm'}
              hint="Jaringan tenang. Alarm baru muncul otomatis saat collector melaporkan gangguan."
              icon={<IconAlert size={32} />}
            />
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table>
              <thead>
                <tr>
                  <th>Keparahan</th>
                  <th>Entitas</th>
                  <th>Pesan</th>
                  <th>Terbuka</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {alarms.map((alarm) => (
                  <tr key={alarm.id}>
                    <td>
                      <StatusBadge status={alarm.severity} />
                    </td>
                    <td>
                      <div style={{ fontSize: '0.88rem' }}>{alarm.entityLabel}</div>
                      <div className="row" style={{ gap: '0.35rem', marginTop: '0.15rem' }}>
                        <span className="badge">{alarm.kind}</span>
                        {alarm.occurrenceCount > 1 && (
                          <span className="muted" style={{ fontSize: '0.75rem' }}>×{alarm.occurrenceCount}</span>
                        )}
                      </div>
                    </td>
                    <td className="muted" style={{ fontSize: '0.85rem', maxWidth: 320 }}>
                      {alarm.message}
                    </td>
                    <td className="muted" style={{ fontSize: '0.83rem', whiteSpace: 'nowrap' }}>
                      {formatDuration(alarm.openMinutes)}
                      <div>
                        <StatusBadge status={alarm.status} />
                      </div>
                    </td>
                    <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                      <div className="row" style={{ justifyContent: 'flex-end', gap: '0.35rem' }}>
                        {alarm.entityType === 'ONU' && can('monitoring.metric.view') && (
                          <button className="ghost small" onClick={() => void openHistory(alarm.entityId, alarm.entityLabel)}>
                            Redaman
                          </button>
                        )}
                        {can('monitoring.alarm.ack') && alarm.status === 'ACTIVE' && (
                          <button className="small" onClick={() => void run(() => api.post(`/api/monitoring/alarms/${alarm.id}/acknowledge`), 'Alarm diakui')}>
                            Akui
                          </button>
                        )}
                        {can('monitoring.alarm.ack') && alarm.status !== 'CLEARED' && (
                          <button className="ghost small" onClick={() => void run(() => api.post(`/api/monitoring/alarms/${alarm.id}/clear`), 'Alarm ditutup')}>
                            Tutup
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

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
