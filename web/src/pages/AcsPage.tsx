import { useCallback, useEffect, useMemo, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { Copy } from 'lucide-react'
import { ApiError } from '@/api/client'
import {
  exportAcsDevicesCsv,
  getAcsHealth,
  getAcsServerInfo,
  getAcsStats,
  listAcsDevices,
  listAcsLogs,
  refreshAcsFleet,
  type AcsActivityView,
  type AcsDeviceQuery,
  type AcsDeviceRowView,
  type AcsHealthView,
  type AcsServerInfoView,
  type AcsSignalFilter,
  type AcsStatsView,
  type AcsStatusFilter,
} from '@/api/acs'
import { CPE_ACTION_LABEL, type CpeAction } from '@/api/cpe'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, SelectField, Spinner, StatusBadge, Toolbar } from '@/components/atoms'
import { IconDownload, IconWifi } from '@/components/atoms/icons'
import { PageHeader, SearchInput, Tabs } from '@/components/molecules'
import { DataTable, OntAcsSettingsCard, type Column } from '@/components/organisms'
import { useToast } from '@/system'
import { copyText } from '@/utils/clipboard'
import { downloadBlob } from '@/utils/download'
import { timeAgo } from '@/utils/timeAgo'
import { CustomerDetailBlade } from './CustomerDetailPage'

/**
 * Konsol ACS / TR-069 — pandangan se-armada atas ONT pelanggan yang melapor ke GenieACS.
 *
 * Melengkapi tab GenieACS di detail pelanggan (yang melayani satu perangkat): di sini ISP
 * melihat berapa ONT-nya online, mana yang sinyalnya jelek, dan menyalin setelan TR-069
 * yang harus diketik ke tiap ONT.
 *
 * DUA tingkat akses dalam satu halaman:
 * - `cpe.acs.view` (dipegang teknisi juga) — info server ACS + kesehatan. Nilainya global
 *   dari env deploy, nol data tenant.
 * - `cpe.device.view` — statistik, tabel perangkat, CSV, dan log aktivitas.
 *
 * Saat pengguna hanya punya yang pertama, strip tab tak dirender sama sekali dan
 * pengambilan data armada dipagari di awal effect — kalau tidak, teknisi kebanjiran toast
 * 403 tiap membuka halaman.
 */
export function AcsPage() {
  const { can } = useCan()
  const canView = can('cpe.acs.view')
  const canDevices = can('cpe.device.view')
  const canManage = can('cpe.device.manage')
  const canCustomer = can('customer.customer.view')
  const [tab, setTab] = useState<'dashboard' | 'devices'>('dashboard')

  if (!canView) {
    return (
      <div className="card">
        <Text as="h3" size={400} weight="semibold" style={{ marginTop: 0 }}>Akses ditolak</Text>
        <Text as="p" className="muted" size={300}>Kamu tidak punya izin melihat konsol ACS.</Text>
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title="ACS / TR-069"
        subtitle="Pusat kendali ONT pelanggan lewat GenieACS: pantau perangkat yang melapor, salin setelan yang harus diketik di ONT, dan sapu perintah refresh ke armada."
      />

      {canDevices && (
        <Tabs
          tabs={[
            { key: 'dashboard' as const, label: 'Dashboard' },
            { key: 'devices' as const, label: 'Devices' },
          ]}
          active={tab}
          onChange={setTab}
        />
      )}

      {(!canDevices || tab === 'dashboard') && (
        <DashboardTab canDevices={canDevices} canManage={canManage} />
      )}
      {canDevices && tab === 'devices' && <DevicesTab canCustomer={canCustomer} />}
    </div>
  )
}

/* ============================== Dashboard ============================== */

function DashboardTab({ canDevices, canManage }: { canDevices: boolean; canManage: boolean }) {
  const toast = useToast()
  const [stats, setStats] = useState<AcsStatsView | null>(null)
  const [health, setHealth] = useState<AcsHealthView | null>(null)
  const [info, setInfo] = useState<AcsServerInfoView | null>(null)
  const [busy, setBusy] = useState(false)
  const [logsOpen, setLogsOpen] = useState(false)

  const loadStats = useCallback(() => {
    // Teknisi tak punya `cpe.device.view`; memanggilnya tetap hanya menghasilkan 403.
    if (!canDevices) return
    void getAcsStats()
      .then(setStats)
      .catch(() => setStats(null))
  }, [canDevices])

  const checkHealth = useCallback(
    (announce: boolean) => {
      void getAcsHealth()
        .then((h) => {
          setHealth(h)
          if (announce) (h.status === 'ONLINE' ? toast.success : toast.error)(h.message)
        })
        .catch((err) => {
          setHealth(null)
          if (announce) toast.error(err instanceof ApiError ? err.message : 'Gagal memeriksa server ACS')
        })
    },
    [toast],
  )

  useEffect(() => loadStats(), [loadStats])
  useEffect(() => checkHealth(false), [checkHealth])
  useEffect(() => {
    void getAcsServerInfo()
      .then(setInfo)
      .catch(() => setInfo(null))
  }, [])

  const runBulkRefresh = async () => {
    setBusy(true)
    try {
      const res = await refreshAcsFleet()
      toast.success(res.message)
      loadStats()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Sapuan refresh gagal')
    } finally {
      setBusy(false)
    }
  }

  const exportCsv = async () => {
    try {
      downloadBlob(await exportAcsDevicesCsv(), 'perangkat-acs.csv')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Ekspor gagal')
    }
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      {canDevices && <StatTiles stats={stats} />}

      {/* Aksi cepat se-armada. Tombol tulis menyembunyikan diri sendiri saat langganan
          terkunci — `useCan` sudah mencerminkan aturan write di server. */}
      <div className="card stack" style={{ gap: '0.6rem' }}>
        <h3 style={{ margin: 0 }}>Aksi cepat</h3>
        <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap' }}>
          {canManage && (
            <Button type="button" variant="primary" disabled={busy} onClick={() => void runBulkRefresh()}>
              {busy ? 'Menyapu…' : 'Segarkan Batch'}
            </Button>
          )}
          {canDevices && (
            <Button type="button" icon={<IconDownload size={16} />} onClick={() => void exportCsv()}>
              Export CSV
            </Button>
          )}
          {canDevices && (
            <Button type="button" onClick={() => setLogsOpen((v) => !v)}>
              {logsOpen ? 'Sembunyikan Log' : 'Lihat Log'}
            </Button>
          )}
          <Button type="button" onClick={() => checkHealth(true)}>
            Health Check
          </Button>
        </div>
        {canManage && (
          <Text as="span" size={300} className="muted" >
            "Segarkan Batch" menyapu sejumlah terbatas perangkat yang sedang online tiap klik —
            yang paling lama tak melapor didahulukan. Perangkat sisanya menyusul di klik
            berikutnya atau saat inform berkalanya jatuh tempo.
          </Text>
        )}
      </div>

      <ServerInfoCard info={info} health={health} lastSyncAt={stats?.lastSyncAt ?? null} lastSyncOk={stats?.lastSyncOk ?? null} />

      <OntAcsSettingsCard />

      {canDevices && logsOpen && <ActivityLog />}
    </div>
  )
}

/**
 * Empat tile ringkasan armada.
 *
 * Catatan penting yang ditulis di `stat-note`: "online" di sini berarti *melapor ke ACS*,
 * bukan *ONU hidup di OLT* — ONT yang menyala tapi klien CWMP-nya macet akan terhitung
 * offline di sini sementara `/monitoring` menyebutnya online. Angkanya memang akan
 * berselisih; itu bukan bug.
 */
function StatTiles({ stats }: { stats: AcsStatsView | null }) {
  if (!stats) {
    return (
      <div className="card">
        <Spinner />
      </div>
    )
  }
  const signalNote =
    stats.signalSampleCount > 0
      ? `rata-rata dari ${stats.signalSampleCount} / ${stats.totalDevices} perangkat`
      : 'belum ada bacaan optik'
  return (
    <div className="stat-grid">
      <div className="stat accent-bar">
        <div className="stat-label">Perangkat online</div>
        <div className="stat-value">{stats.onlineDevices.toLocaleString('id-ID')}</div>
        <div className="stat-note">melapor ke ACS baru-baru ini (bukan status ONU di OLT)</div>
      </div>
      <div className="stat accent-bar">
        <div className="stat-label">Total perangkat</div>
        <div className="stat-value">{stats.totalDevices.toLocaleString('id-ID')}</div>
        <div className="stat-note">ONT milik tenant ini yang sudah dikenali ACS</div>
      </div>
      <div className={`stat ${stats.offlineDevices > 0 ? 'warn-bar' : 'accent-bar'}`}>
        <div className="stat-label">Perangkat offline</div>
        <div className="stat-value">{stats.offlineDevices.toLocaleString('id-ID')}</div>
        <div className="stat-note">belum melapor melewati ambang basi</div>
      </div>
      <div className={`stat ${signalTone(stats.avgRxPowerDbm)}`}>
        <div className="stat-label">Rata-rata sinyal</div>
        <div className="stat-value">{stats.avgRxPowerDbm != null ? `${stats.avgRxPowerDbm.toLocaleString('id-ID')} dBm` : '—'}</div>
        <div className="stat-note">{signalNote}</div>
      </div>
    </div>
  )
}

/** Kelas bar tile sinyal — ambang sama dengan halaman monitoring. */
function signalTone(rx: number | null): string {
  if (rx == null) return 'stat'
  if (rx < -27) return 'crit-bar'
  if (rx < -25) return 'warn-bar'
  return 'accent-bar'
}

/** Blok "Informasi Server ACS": alamat, status probe, dan waktu sinkronisasi terakhir. */
function ServerInfoCard({
  info,
  health,
  lastSyncAt,
  lastSyncOk,
}: {
  info: AcsServerInfoView | null
  health: AcsHealthView | null
  lastSyncAt: string | null
  lastSyncOk: boolean | null
}) {
  const toast = useToast()
  const copy = async (label: string, value: string) => {
    if (await copyText(value)) toast.success(`${label} disalin`)
    else toast.error('Browser menolak akses papan klip — salin manual')
  }

  return (
    <div className="card stack" style={{ gap: '0.75rem' }}>
      <div className="row" style={{ gap: '0.45rem', alignItems: 'center' }}>
        <IconWifi size={18} />
        <h3 style={{ margin: 0 }}>Informasi Server ACS</h3>
      </div>

      <InfoLine label="Server URL (NBI)" value={info?.nbiBaseUrl || null} onCopy={copy} />
      <InfoLine label="CWMP URL (diketik ke ONT)" value={info?.cwmpUrl ?? null} onCopy={copy} />

      <div className="stack" style={{ gap: '0.2rem' }}>
        <Text as="span" size={300} className="muted" style={{  }} >Status</Text>
        {health ? (
          <div className="row" style={{ gap: '0.45rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <StatusBadge status={health.status} />
            <Text as="span" className="muted" size={200}>{health.message}
            {health.latencyMs != null && ` · ${health.latencyMs} ms`}</Text>
          </div>
        ) : (
          <Text as="span" size={300} className="muted" >—</Text>
        )}
      </div>

      <div className="stack" style={{ gap: '0.2rem' }}>
        <Text as="span" size={300} className="muted" style={{  }} >Sinkronisasi terakhir</Text>
        {lastSyncAt ? (
          <div className="row" style={{ gap: '0.45rem', alignItems: 'center' }}>
            <Text as="span" size={300}  >{timeAgo(lastSyncAt)}</Text>
            {lastSyncOk === false && <Badge tone="warning">gagal</Badge>}
          </div>
        ) : (
          <Text as="span" size={300} className="muted" >
            belum ada ronde sinkronisasi sejak server terakhir dinyalakan
          </Text>
        )}
      </div>

      {info && (
        <Text as="span" size={300} className="muted" >
          Aplikasi menarik daftar perangkat dari ACS tiap {Math.round(info.syncIntervalSeconds / 60) || 1} menit.
        </Text>
      )}
    </div>
  )
}

/** Satu baris alamat monospace + tombol salin. */
function InfoLine({
  label,
  value,
  onCopy,
}: {
  label: string
  value: string | null
  onCopy: (label: string, value: string) => void
}) {
  return (
    <div className="stack" style={{ gap: '0.2rem' }}>
      <Text as="span" size={300} className="muted" style={{  }} >{label}</Text>
      <div className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
        {value ? (
          <>
            <code style={{ flex: 1, overflowX: 'auto', padding: '0.3rem 0.5rem',  }}>{value}</code>
            <Button
              type="button"
              size="small"
              variant="subtle"
              icon={<Copy size={14} />}
              aria-label={`Salin ${label}`}
              title={`Salin ${label}`}
              onClick={() => onCopy(label, value)}
            />
          </>
        ) : (
          <Text as="span" size={300} className="muted" style={{ fontStyle: 'italic' }} >belum dikonfigurasi</Text>
        )}
      </div>
    </div>
  )
}

/** Jendela "Lihat Log": jejak aksi ACS terbaru lintas perangkat milik tenant. */
function ActivityLog() {
  const toast = useToast()
  const [rows, setRows] = useState<AcsActivityView[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    void listAcsLogs(100)
      .then(setRows)
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat log'))
      .finally(() => setLoading(false))
  }, [toast])

  return (
    <div className="card stack" style={{ gap: '0.6rem' }}>
      <h3 style={{ margin: 0 }}>Log aktivitas ACS</h3>
      {loading && <Spinner />}
      {!loading && rows.length === 0 && (
        <p className="muted" style={{ margin: 0 }}>Belum ada aksi ACS yang tercatat.</p>
      )}
      {rows.map((row) => (
        <div key={row.id} className="stack" style={{ gap: '0.15rem' }}>
          <div className="row" style={{ gap: '0.45rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <StatusBadge status={row.status === 'SUCCESS' ? 'GOOD' : 'CRITICAL'} label={row.status === 'SUCCESS' ? 'Sukses' : 'Gagal'} />
            <Text as="strong" size={300}  >{CPE_ACTION_LABEL[row.action as CpeAction] ?? row.action}</Text>
            <Text as="span" className="muted" size={200}>{row.serialNumber ?? '—'}
            {row.customerName && ` · ${row.customerName}`}</Text>
          </div>
          <Text as="span" size={300} className="muted" >{timeAgo(row.requestedAt)}
          {row.requestedByEmail && ` · ${row.requestedByEmail}`}
          {row.detail && ` · ${row.detail}`}</Text>
        </div>
      ))}
    </div>
  )
}

/* =============================== Devices =============================== */

const STATUS_OPTIONS: { value: AcsStatusFilter; label: string }[] = [
  { value: 'ALL', label: 'Semua status' },
  { value: 'ONLINE', label: 'Online' },
  { value: 'OFFLINE', label: 'Offline' },
]

const SIGNAL_OPTIONS: { value: AcsSignalFilter; label: string }[] = [
  { value: 'ALL', label: 'Semua sinyal' },
  { value: 'GOOD', label: 'Sinyal baik (≥ −25 dBm)' },
  { value: 'WARN', label: 'Sinyal waspada (−25 … −27 dBm)' },
  { value: 'CRITICAL', label: 'Sinyal kritis (< −27 dBm)' },
  { value: 'UNKNOWN', label: 'Tanpa bacaan' },
]

function DevicesTab({ canCustomer }: { canCustomer: boolean }) {
  const toast = useToast()
  const [q, setQ] = useState('')
  const [status, setStatus] = useState<AcsStatusFilter>('ALL')
  const [signal, setSignal] = useState<AcsSignalFilter>('ALL')
  const [brand, setBrand] = useState('')
  const [rows, setRows] = useState<AcsDeviceRowView[]>([])
  const [brands, setBrands] = useState<string[]>([])
  const [loading, setLoading] = useState(true)
  const [detailId, setDetailId] = useState<string | null>(null)

  const filter = useMemo<AcsDeviceQuery>(() => ({ q, status, signal, brand: brand || undefined }), [q, status, signal, brand])

  // Pencarian diketik huruf demi huruf; tunda sesaat supaya tiap ketukan tak jadi request.
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setLoading(true)
      void listAcsDevices(filter)
        .then((data) => {
          setRows(data)
          // Pilihan merek dikumpulkan hanya saat saringan merek kosong — kalau tidak,
          // memilih satu merek akan menyusutkan daftar pilihannya jadi merek itu saja.
          if (!filter.brand) {
            setBrands([...new Set(data.map((d) => d.manufacturer).filter((m): m is string => !!m))].sort())
          }
        })
        .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat perangkat'))
        .finally(() => setLoading(false))
    }, 250)
    return () => window.clearTimeout(timer)
  }, [filter, toast])

  const exportCsv = async () => {
    try {
      downloadBlob(await exportAcsDevicesCsv(filter), 'perangkat-acs.csv')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Ekspor gagal')
    }
  }

  const columns = useMemo<Column<AcsDeviceRowView>[]>(
    () => [
      {
        key: 'device',
        header: 'Device info',
        sortValue: (d) => d.serialNumber,
        cell: (d) => (
          <div className="stack" style={{ gap: '0.15rem' }}>
            <strong>{d.serialNumber}</strong>
            <Text as="span" size={300} className="muted" >{[d.customerName, [d.manufacturer, d.model].filter(Boolean).join(' ')].filter(Boolean).join(' · ') || '—'}</Text>
          </div>
        ),
      },
      {
        key: 'status',
        header: 'Status',
        sortValue: (d) => (d.online ? 1 : 0),
        cell: (d) => <StatusBadge status={d.online ? 'ONLINE' : 'OFFLINE'} />,
      },
      {
        key: 'ip',
        header: 'IP address',
        sortValue: (d) => d.ipAddress,
        cell: (d) => d.ipAddress ?? <span className="muted">—</span>,
      },
      {
        key: 'ssid',
        header: 'WiFi / SSID',
        sortValue: (d) => d.ssid,
        cell: (d) => d.ssid ?? <span className="muted">—</span>,
      },
      {
        key: 'pppoe',
        header: 'PPPoE',
        sortValue: (d) => d.pppoeUsername,
        cell: (d) =>
          d.pppoeUsername ? (
            <div className="stack" style={{ gap: '0.15rem' }}>
              <span>{d.pppoeUsername}</span>
              {d.pppoeOnline != null && (
                <Text as="span" size={300} className="muted" >{d.pppoeOnline ? 'sesi aktif' : 'tak ada sesi'}</Text>
              )}
            </div>
          ) : (
            <span className="muted">—</span>
          ),
      },
      {
        key: 'signal',
        header: 'Sinyal (RX / TX)',
        align: 'right',
        sortValue: (d) => d.rxPowerDbm,
        cell: (d) => <SignalCell rx={d.rxPowerDbm} tx={d.txPowerDbm} />,
      },
      {
        key: 'temp',
        header: 'Suhu',
        align: 'right',
        sortValue: (d) => d.temperatureC,
        // Parameter vendor: kosong di hampir semua armada sampai path suhunya dikonfigurasi.
        cell: (d) => (d.temperatureC != null ? `${d.temperatureC} °C` : <span className="muted">—</span>),
      },
      {
        key: 'lastSeen',
        header: 'Terakhir terlihat',
        sortValue: (d) => d.lastInformAt,
        cell: (d) => (d.lastInformAt ? timeAgo(d.lastInformAt) : <span className="muted">belum pernah</span>),
      },
    ],
    [],
  )

  return (
    <div className="stack" style={{ gap: '0.85rem' }}>
      <Toolbar>
        <SearchInput value={q} onChange={setQ} placeholder="Cari serial, SSID, PPPoE, atau nama pelanggan…" />
        <SelectField value={status} onChange={(_, data) => setStatus(data.value as AcsStatusFilter)}>
          {STATUS_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </SelectField>
        <SelectField value={signal} onChange={(_, data) => setSignal(data.value as AcsSignalFilter)}>
          {SIGNAL_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </SelectField>
        <SelectField value={brand} onChange={(_, data) => setBrand(data.value)}>
          <option value="">Semua merek</option>
          {brands.map((b) => (
            <option key={b} value={b}>{b}</option>
          ))}
        </SelectField>
        <Button type="button" icon={<IconDownload size={16} />} onClick={() => void exportCsv()}>
          Export CSV
        </Button>
      </Toolbar>

      <div className="spread">
        <span className="muted">{rows.length} perangkat</span>
        {canCustomer && (
          <Text as="span" className="muted" size={200}>
            Klik baris untuk membuka detail pelanggan (tab GenieACS) — reboot, WiFi, dan diagnostik ada di sana.
          </Text>
        )}
      </div>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(d) => d.id}
        loading={loading}
        initialSort={{ key: 'device', dir: 'asc' }}
        // Aksi per perangkat hidup di detail pelanggan; baris tanpa pelanggan tak bisa dibuka.
        onRowClick={canCustomer ? (d) => d.customerId && setDetailId(d.customerId) : undefined}
        empty={
          <EmptyState
            icon={<IconWifi size={28} />}
            title="Belum ada perangkat"
            hint="ONT baru muncul di sini setelah melapor ke ACS dan serialnya cocok dengan ONU yang terdaftar di tenant ini."
          />
        }
      />

      <CustomerDetailBlade customerId={detailId} onClose={() => setDetailId(null)} />
    </div>
  )
}

/** Sel RX/TX; RX diwarnai sesuai ambang, TX ditampilkan apa adanya sebagai pelengkap. */
function SignalCell({ rx, tx }: { rx: number | null; tx: number | null }) {
  if (rx == null && tx == null) return <span className="muted">—</span>
  const color = rx == null ? undefined : rx < -27 ? 'var(--critical)' : rx < -25 ? 'var(--warning)' : undefined
  return (
    <div className="stack" style={{ gap: '0.1rem', alignItems: 'flex-end' }}>
      <span style={{ color,  }}>
        {rx != null ? `${rx} dBm` : '—'}
      </span>
      <Text as="span" size={300} className="muted" >TX {tx != null ? `${tx} dBm` : '—'}</Text>
    </div>
  )
}
