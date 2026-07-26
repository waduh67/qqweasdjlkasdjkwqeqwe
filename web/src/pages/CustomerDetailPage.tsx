import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import type {
  CustomerTrace,
  CustomerView,
  NeighborView,
  OdpView,
  OnuView,
  SubscriberNeighbors,
} from '../api/network'
import { DOWN_CAUSE_LABEL, type OnuHistoryView, type OnuMetricView } from '../api/monitoring'
import {
  CPE_ACTION_LABEL,
  factoryResetCpe,
  getCpeDevice,
  getCpeLive,
  listCpeDevices,
  listCpeFirmware,
  rebootCpe,
  refreshCpeAcs,
  runCpePing,
  runCpeSpeedTest,
  setCpeWifi,
  upgradeCpeFirmware,
  type AcsRefreshView,
  type CpeActionView,
  type CpeDeviceDetail,
  type CpeDeviceView,
  type CpeLiveView,
  type FirmwareFileView,
  type PingDiagnosticView,
  type SetWifiRequest,
  type SpeedDirection,
  type SpeedTestDiagnosticView,
  type WifiView,
} from '../api/cpe'
import { useCan } from '../auth/useCan'
import { EmptyState, Spinner, StatusBadge, useToast } from '../components/ui'
import { OpticalChart } from '../components/OpticalChart'
import { IconAlert, IconCustomers, IconRoute } from '../components/icons'

/** Warna kesehatan optik selaras token status. */
const HEALTH_COLOR: Record<string, string> = {
  GOOD: 'var(--good-ink)',
  WARNING: 'var(--warning-ink)',
  CRITICAL: 'var(--critical-ink)',
  UNKNOWN: 'var(--muted)',
}

type Tab = 'ringkasan' | 'jalur' | 'tetangga' | 'metrik' | 'cpe'

/**
 * Halaman detail satu pelanggan sebagai rute tersendiri (`/customers/:id`), bukan
 * drawer — di sini ada ruang untuk banyak data (profil, perangkat, jalur, tetangga
 * sejalur, metrik hidup) yang dibagi ke dalam tab, dan siap ditambah tab CPE saat
 * integrasi GenieACS. Navigasinya tetap SPA: klik baris di daftar → rute berganti
 * tanpa memuat ulang halaman.
 */
export function CustomerDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { can } = useCan()
  const toast = useToast()

  const [customer, setCustomer] = useState<CustomerView | null>(null)
  const [odps, setOdps] = useState<OdpView[]>([])
  const [trace, setTrace] = useState<CustomerTrace | null>(null)
  const [neighbors, setNeighbors] = useState<SubscriberNeighbors | null>(null)
  const [metrics, setMetrics] = useState<OnuMetricView[]>([])
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [tab, setTab] = useState<Tab>('ringkasan')

  const reload = useCallback(async () => {
    try {
      setCustomer(await api.get<CustomerView>(`/api/customers/${id}`))
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) setNotFound(true)
      else toast.error(err instanceof ApiError ? err.message : 'Gagal memuat pelanggan')
    } finally {
      setLoading(false)
    }
  }, [id, toast])

  useEffect(() => {
    void reload()
  }, [reload])

  // Gerbang izin sebagai boolean primitif: `can` dari useCan berganti identitas
  // tiap render, jadi tak boleh masuk daftar dependensi effect (memicu loop).
  const canAssign = can('customer.onu.assign')
  const canMetric = can('monitoring.metric.view')
  const canCpe = can('cpe.device.view')

  // ODP untuk form pasang ONU — hanya bila boleh memasang.
  useEffect(() => {
    if (!canAssign) return
    void api
      .get<PageResponse<OdpView>>('/api/odps?size=100')
      .then((page) => setOdps(page.content))
      .catch(() => setOdps([]))
  }, [canAssign])

  // Jalur & tetangga: dua tarikan independen, toleran gagal (izin/opsional).
  useEffect(() => {
    let alive = true
    void api
      .get<CustomerTrace>(`/api/gis/trace/customers/${id}`)
      .then((t) => alive && setTrace(t))
      .catch(() => {})
    void api
      .get<SubscriberNeighbors>(`/api/gis/trace/customers/${id}/neighbors`)
      .then((n) => alive && setNeighbors(n))
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [id])

  // Bacaan hidup ONU — untuk tab Metrik; disaring per serial di bawah.
  useEffect(() => {
    if (!canMetric) return
    let alive = true
    void api
      .get<OnuMetricView[]>(`/api/monitoring/customers/${id}/metrics`)
      .then((m) => alive && setMetrics(m))
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [canMetric, id])

  const run = async (action: () => Promise<unknown>, okMessage?: string) => {
    try {
      await action()
      await reload()
      if (okMessage) toast.success(okMessage)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
    }
  }

  if (loading) {
    return (
      <div className="card" style={{ display: 'grid', placeItems: 'center', minHeight: 200 }}>
        <Spinner />
      </div>
    )
  }

  if (notFound || !customer) {
    return (
      <div className="stack" style={{ gap: '1rem' }}>
        <BackLink onClick={() => navigate('/customers')} />
        <div className="card">
          <EmptyState title="Pelanggan tidak ditemukan" hint="Mungkin sudah dihapus." icon={<IconCustomers size={32} />} />
        </div>
      </div>
    )
  }

  const connected = (trace?.hops.length ?? 0) > 1
  const odpCount = neighbors?.sameOdp.length ?? 0
  const ponCount = neighbors?.samePonPort.length ?? 0

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <BackLink onClick={() => navigate('/customers')} />

      <div className="spread">
        <div>
          <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap', alignItems: 'center' }}>
            <h1 className="page-title" style={{ margin: 0 }}>{customer.name}</h1>
            <span className="badge">{customer.code}</span>
            <StatusBadge status={customer.status} />
            {customer.awaitingInstallation && <StatusBadge status="PENDING" label="menunggu instalasi" />}
          </div>
          <p className="page-sub" style={{ margin: '0.25rem 0 0' }}>
            {customer.address} · {customer.phone ?? 'tanpa nomor'}
          </p>
        </div>
        {can('customer.customer.delete') && (
          <button
            className="ghost danger"
            onClick={() =>
              void (async () => {
                try {
                  await api.del(`/api/customers/${customer.id}`)
                  toast.success('Pelanggan dihapus')
                  navigate('/customers')
                } catch (err) {
                  toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus')
                }
              })()
            }
          >
            Hapus
          </button>
        )}
      </div>

      <div className="segment" style={{ alignSelf: 'flex-start' }}>
        <button className={tab === 'ringkasan' ? 'active' : ''} onClick={() => setTab('ringkasan')}>
          Ringkasan
        </button>
        <button className={tab === 'jalur' ? 'active' : ''} onClick={() => setTab('jalur')}>
          Jalur
        </button>
        <button className={tab === 'tetangga' ? 'active' : ''} onClick={() => setTab('tetangga')}>
          Tetangga{ponCount ? ` (${ponCount})` : ''}
        </button>
        <button className={tab === 'metrik' ? 'active' : ''} onClick={() => setTab('metrik')}>
          Metrik
        </button>
        {canCpe && (
          <button className={tab === 'cpe' ? 'active' : ''} onClick={() => setTab('cpe')}>
            CPE
          </button>
        )}
      </div>

      {tab === 'ringkasan' && <RingkasanTab customer={customer} odps={odps} run={run} />}
      {tab === 'jalur' && <JalurTab trace={trace} connected={connected} />}
      {tab === 'tetangga' && <TetanggaTab neighbors={neighbors} connected={connected} odpCount={odpCount} ponCount={ponCount} />}
      {tab === 'metrik' && <MetrikTab customer={customer} metrics={metrics} />}
      {tab === 'cpe' && <CpeTab customerId={id} />}
    </div>
  )
}

function BackLink({ onClick }: { onClick: () => void }) {
  return (
    <button className="ghost" onClick={onClick} style={{ alignSelf: 'flex-start', gap: '0.35rem' }}>
      <span aria-hidden>←</span> Pelanggan
    </button>
  )
}

/* ---------- Tab: Ringkasan (profil, langganan, perangkat ONU) ---------- */

function RingkasanTab({
  customer,
  odps,
  run,
}: {
  customer: CustomerView
  odps: OdpView[]
  run: (action: () => Promise<unknown>, okMessage?: string) => Promise<void>
}) {
  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="card stack" style={{ gap: '0.75rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Profil</strong>
        <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))' }}>
          <Field label="Alamat" value={customer.address} />
          <Field label="Telepon" value={customer.phone ?? '—'} />
          <Field label="Email" value={customer.email ?? '—'} />
          <Field label="Koordinat" value={`${customer.location.latitude}, ${customer.location.longitude}`} />
        </div>
      </div>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Langganan</strong>
        {customer.subscriptions.length === 0 ? (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada langganan.</p>
        ) : (
          customer.subscriptions.map((sub) => (
            <div key={sub.id} className="spread" style={{ alignItems: 'center' }}>
              <span style={{ fontSize: '0.88rem' }}>
                {sub.packageName} · {sub.bandwidthMbps} Mbps · Rp {sub.monthlyFee}
              </span>
              <StatusBadge status={sub.status} />
            </div>
          ))
        )}
      </div>

      <OnuManager customer={customer} odps={odps} run={run} />
    </div>
  )
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div style={{ fontSize: '0.9rem', color: 'var(--text-2)', wordBreak: 'break-word' }}>{value}</div>
    </div>
  )
}

/** Kelola perangkat ONU pelanggan: daftarkan, pasang ke port ODP, lepas. */
function OnuManager({
  customer,
  odps,
  run,
}: {
  customer: CustomerView
  odps: OdpView[]
  run: (action: () => Promise<unknown>, okMessage?: string) => Promise<void>
}) {
  const { can } = useCan()
  const [serial, setSerial] = useState('')
  const [attach, setAttach] = useState<{ onuId: string; odpId: string; port: string; rx: string } | null>(null)

  return (
    <div className="card stack" style={{ gap: '0.5rem' }}>
      <strong style={{ fontSize: '0.95rem' }}>Perangkat ONU</strong>
      {customer.onus.length === 0 && (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada ONU terdaftar.</p>
      )}
      {customer.onus.map((onu: OnuView) => (
        <div key={onu.id} className="spread" style={{ alignItems: 'center' }}>
          <span style={{ fontSize: '0.85rem' }}>
            {onu.serialNumber}{' '}
            {onu.odpCode ? (
              <span className="badge accent">
                {onu.odpCode} port {onu.odpPortNumber}
              </span>
            ) : (
              <span className="badge">belum terpasang</span>
            )}{' '}
            <span style={{ color: HEALTH_COLOR[onu.opticalHealth], fontWeight: 600 }}>
              {onu.installRxPowerDbm != null ? `${onu.installRxPowerDbm} dBm` : onu.opticalHealth}
            </span>
          </span>
          {can('customer.onu.assign') && (
            <div className="row">
              {onu.odpId ? (
                <button onClick={() => void run(() => api.post(`/api/customers/onus/${onu.id}/detach`), 'ONU dilepas')}>
                  Lepas
                </button>
              ) : (
                <button onClick={() => setAttach({ onuId: onu.id, odpId: odps[0]?.id ?? '', port: '1', rx: '' })}>
                  Pasang ke ODP
                </button>
              )}
            </div>
          )}
        </div>
      ))}

      {attach && (
        <div className="row" style={{ marginTop: '0.4rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <label style={{ flex: 2, minWidth: 160 }}>
            <span>ODP</span>
            <select value={attach.odpId} onChange={(e) => setAttach({ ...attach, odpId: e.target.value })}>
              {odps.map((odp) => (
                <option key={odp.id} value={odp.id}>
                  {odp.code} ({odp.capacity} port)
                </option>
              ))}
            </select>
          </label>
          <label style={{ flex: 1, minWidth: 80 }}>
            <span>Port</span>
            <input value={attach.port} onChange={(e) => setAttach({ ...attach, port: e.target.value })} />
          </label>
          <label style={{ flex: 1, minWidth: 100 }}>
            <span>Redaman (dBm)</span>
            <input value={attach.rx} onChange={(e) => setAttach({ ...attach, rx: e.target.value })} placeholder="-22.5" />
          </label>
          <button
            className="primary"
            onClick={() =>
              void run(async () => {
                await api.post(`/api/customers/onus/${attach.onuId}/attach`, {
                  odpId: attach.odpId,
                  portNumber: Number(attach.port),
                  installRxPowerDbm: attach.rx ? Number(attach.rx) : null,
                })
                setAttach(null)
              }, 'ONU dipasang')
            }
          >
            Pasang
          </button>
          <button onClick={() => setAttach(null)}>Batal</button>
        </div>
      )}

      {can('customer.onu.assign') && (
        <div className="row" style={{ marginTop: '0.4rem' }}>
          <input
            placeholder="Serial ONU baru, mis. ZTEG-C0FFEE01"
            value={serial}
            onChange={(e) => setSerial(e.target.value)}
          />
          <button
            onClick={() =>
              void run(async () => {
                await api.post(`/api/customers/${customer.id}/onus`, { serialNumber: serial })
                setSerial('')
              }, 'ONU didaftarkan')
            }
          >
            Daftarkan ONU
          </button>
        </div>
      )}
    </div>
  )
}

/* ---------- Tab: Jalur (topologi hulu + anggaran redaman) ---------- */

function JalurTab({ trace, connected }: { trace: CustomerTrace | null; connected: boolean }) {
  if (!trace || !connected) {
    return (
      <div className="card">
        <p className="muted" style={{ margin: 0 }}>Pelanggan ini belum tersambung ke jaringan.</p>
      </div>
    )
  }
  return (
    <div className="card stack" style={{ gap: '0.75rem' }}>
      <div className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
        <IconRoute size={16} />
        <strong style={{ fontSize: '0.95rem' }}>Jalur ke hulu</strong>
      </div>
      <div className="row" style={{ flexWrap: 'wrap', gap: '0.4rem' }}>
        {trace.hops.map((hop, index) => (
          <span key={`${hop.kind}-${index}`} className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
            <span className="badge">
              {hop.kind}
              {hop.code && ` ${hop.code}`}
            </span>
            {index < trace.hops.length - 1 && <span className="muted">→</span>}
          </span>
        ))}
      </div>
      <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
        ONU {trace.onuSerialNumber} · port {trace.odpPortNumber} · redaman terpasang{' '}
        <span style={{ color: HEALTH_COLOR[trace.opticalHealth ?? 'UNKNOWN'] }}>
          {trace.installRxPowerDbm != null ? `${trace.installRxPowerDbm} dBm` : '—'}
        </span>
        {trace.estimatedLossDb != null && ` · perkiraan rugi jalur ${trace.estimatedLossDb.toFixed(1)} dB`}
      </p>
    </div>
  )
}

/* ---------- Tab: Tetangga (se-ODP / se-PON) ---------- */

function TetanggaTab({
  neighbors,
  connected,
  odpCount,
  ponCount,
}: {
  neighbors: SubscriberNeighbors | null
  connected: boolean
  odpCount: number
  ponCount: number
}) {
  const [scope, setScope] = useState<'odp' | 'pon'>('odp')
  if (!connected) {
    return (
      <div className="card">
        <p className="muted" style={{ margin: 0 }}>Pelanggan ini belum tersambung ke jaringan.</p>
      </div>
    )
  }
  return (
    <div className="stack" style={{ gap: '0.75rem' }}>
      <div className="segment" style={{ alignSelf: 'flex-start' }}>
        <button className={scope === 'odp' ? 'active' : ''} onClick={() => setScope('odp')}>
          Se-ODP{odpCount ? ` (${odpCount})` : ''}
        </button>
        <button className={scope === 'pon' ? 'active' : ''} onClick={() => setScope('pon')}>
          Se-PON{ponCount ? ` (${ponCount})` : ''}
        </button>
      </div>
      <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
        {scope === 'odp'
          ? 'Penghuni ODP yang sama — berbagi kabel drop & splitter ODP.'
          : 'Seluruh ODP di bawah PON port yang sama — berbagi port OLT (superset se-ODP).'}
      </p>
      <div className="card">
        <NeighborList items={scope === 'odp' ? neighbors?.sameOdp ?? null : neighbors?.samePonPort ?? null} showOdp={scope === 'pon'} />
      </div>
    </div>
  )
}

/** Redaman ringkas: "-21.0 dBm" atau "—" bila belum ada bacaan. */
function fmtDbm(v: number | null): string {
  return v != null ? `${v.toFixed(1)} dBm` : '—'
}

/**
 * Daftar tetangga sejalur: siapa lagi di ODP/PON yang sama dan kondisi hidupnya —
 * penentu apakah masalahnya di rumah pelanggan atau di hulu. [showOdp] memunculkan
 * kode ODP tiap baris, berguna di lingkup se-PON yang mencakup beberapa ODP.
 */
function NeighborList({ items, showOdp }: { items: NeighborView[] | null; showOdp: boolean }) {
  if (items == null) return <p className="muted" style={{ margin: 0 }}>Memuat tetangga…</p>
  if (items.length === 0) return <p className="muted" style={{ margin: 0 }}>Tidak ada tetangga di lingkup ini.</p>
  return (
    <div className="stack" style={{ gap: '0.4rem' }}>
      {items.map((n) => (
        <div
          key={n.customerId}
          className="row"
          style={{
            gap: '0.6rem',
            alignItems: 'center',
            padding: '0.45rem 0.55rem',
            borderRadius: 8,
            background: n.self ? 'var(--accent-soft)' : 'transparent',
            border: `1px solid ${n.self ? 'var(--border-strong)' : 'var(--border)'}`,
          }}
        >
          <span className="badge neutral tnum" title="Nomor port ODP">#{n.portNumber}</span>
          <div className="stack" style={{ gap: 2, flex: 1, minWidth: 0 }}>
            <span style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {n.customerName}
              {n.self && <span className="muted" style={{ fontWeight: 400 }}> · pelanggan ini</span>}
            </span>
            <span className="muted" style={{ fontSize: '0.78rem' }}>
              {showOdp && `${n.odpCode} · `}
              {n.onuSerialNumber}
            </span>
          </div>
          <div className="stack" style={{ gap: 3, alignItems: 'flex-end' }}>
            <StatusBadge status={n.liveStatus ?? n.onuStatus} />
            <span className="tnum muted" style={{ fontSize: '0.78rem' }}>
              {fmtDbm(n.liveRxPowerDbm ?? n.installRxPowerDbm)}
              {n.distanceMeters != null && ` · ${n.distanceMeters} m`}
            </span>
          </div>
        </div>
      ))}
    </div>
  )
}

/* ---------- Tab: Metrik (bacaan hidup + tren redaman) ---------- */

/** Waktu ringkas untuk baris gangguan, mis. "20 Jul 14:05". */
function fmtMoment(d: Date): string {
  return d.toLocaleString('id-ID', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' })
}

/** Durasi manusiawi dari milidetik, mis. "3 jam 12 menit" atau "8 menit". */
function humanizeDuration(ms: number): string {
  const mins = Math.max(0, Math.floor(ms / 60_000))
  if (mins < 60) return `${mins} menit`
  const hours = Math.floor(mins / 60)
  const days = Math.floor(hours / 24)
  if (days >= 1) return `${days} hari ${hours % 24} jam`
  return `${hours} jam ${mins % 60} menit`
}

/**
 * Merangkai register "last off / last on" OLT menjadi satu kalimat: masih putus
 * sejak kapan, atau terakhir putus lalu pulih berapa lama.
 */
function describeOutage(m: OnuMetricView): string {
  const off = m.lastOffAt ? new Date(m.lastOffAt) : null
  const on = m.lastOnAt ? new Date(m.lastOnAt) : null
  const recovered = off != null && on != null && on.getTime() >= off.getTime()
  if (off && !recovered) {
    return `Putus sejak ${fmtMoment(off)} · sudah ${humanizeDuration(Date.now() - off.getTime())}`
  }
  if (off && on) {
    return `Terakhir putus ${fmtMoment(off)}, pulih ${fmtMoment(on)} · lama ${humanizeDuration(on.getTime() - off.getTime())}`
  }
  if (on) return `Terakhir online ${fmtMoment(on)}`
  return ''
}

function MetrikTab({ customer, metrics }: { customer: CustomerView; metrics: OnuMetricView[] }) {
  const { can } = useCan()
  const [history, setHistory] = useState<OnuHistoryView | null>(null)

  // ONU yang dipantau adalah yang terpasang; kalau tak ada yang terpasang, ambil
  // yang pertama agar tren instalasi tetap bisa dilihat.
  const onu = customer.onus.find((o) => o.odpId) ?? customer.onus[0] ?? null
  const live = onu
    ? metrics.find((m) => m.serialNumber.toUpperCase() === onu.serialNumber.toUpperCase()) ?? null
    : null
  const canMetric = can('monitoring.metric.view')
  const onuId = onu?.id ?? null

  useEffect(() => {
    if (!onuId || !canMetric) return
    let alive = true
    void api
      .get<OnuHistoryView>(`/api/monitoring/onus/${onuId}/history?hours=24`)
      .then((h) => alive && setHistory(h))
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [onuId, canMetric])

  if (!canMetric) {
    return (
      <div className="card">
        <p className="muted" style={{ margin: 0 }}>Tidak punya izin melihat metrik.</p>
      </div>
    )
  }
  if (!onu) {
    return (
      <div className="card">
        <p className="muted" style={{ margin: 0 }}>Belum ada ONU untuk dipantau.</p>
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="card stack" style={{ gap: '0.6rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Bacaan hidup — {onu.serialNumber}</strong>
        {live ? (
          <>
            <div className="row" style={{ gap: '0.4rem', alignItems: 'center', flexWrap: 'wrap' }}>
              <StatusBadge status={live.status} />
              {live.rxPowerDbm != null && (
                <span className="badge neutral tnum" title="Rx power hidup terakhir dari OLT">
                  Rx {live.rxPowerDbm.toFixed(1)} dBm
                </span>
              )}
              {live.distanceMeters != null && (
                <span className="badge neutral tnum" title="Jarak ONU dari OLT (ukur OLT)">
                  {live.distanceMeters} m
                </span>
              )}
              {live.downCause && (
                <span
                  className="badge"
                  title={`Sebab putus terakhir: ${live.downCause}`}
                  style={{ color: 'var(--warning-ink)', fontWeight: 600 }}
                >
                  Ldc: {DOWN_CAUSE_LABEL[live.downCause]}
                </span>
              )}
            </div>
            {(live.lastOffAt || live.lastOnAt) && (
              <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>{describeOutage(live)}</p>
            )}
          </>
        ) : (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada bacaan hidup dari monitoring.</p>
        )}
      </div>

      <div className="card stack" style={{ gap: '0.75rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Tren redaman 24 jam</strong>
        {history ? (
          <>
            <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(110px, 1fr))' }}>
              <MiniStat label="Rata-rata" value={fmt(history.averageRxPowerDbm)} unit="dBm" />
              <MiniStat label="Minimum" value={fmt(history.minRxPowerDbm)} unit="dBm" />
              <MiniStat label="Maksimum" value={fmt(history.maxRxPowerDbm)} unit="dBm" />
              <MiniStat label="Tren" value={fmt(history.trendDbPerDay)} unit="dB/hari" warn={history.degrading} />
            </div>
            {history.degrading && (
              <div className="row" style={{ gap: '0.5rem', color: 'var(--warning-ink)', fontSize: '0.85rem' }}>
                <IconAlert size={16} />
                Redaman memburuk cukup cepat — kandidat pemeliharaan preventif.
              </div>
            )}
            <OpticalChart points={history.points} />
          </>
        ) : (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Memuat tren…</p>
        )}
      </div>
    </div>
  )
}

function fmt(v: number | null): string {
  return v != null ? v.toFixed(1) : '—'
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

/* ---------- Tab: CPE (router/ONT pelanggan via GenieACS) ---------- */

/**
 * Kelola & pantau CPE pelanggan. Daftar perangkat dibaca dari proyeksi tersimpan
 * (cepat); saat sebuah perangkat dipilih, keadaan langsung (WiFi & host) ditarik
 * dari ACS. Setiap aksi (reboot, ubah WiFi) digerbangi izin dan tercatat di jejak.
 */
function CpeTab({ customerId }: { customerId: string }) {
  const [devices, setDevices] = useState<CpeDeviceView[] | null>(null)
  const [selected, setSelected] = useState<string | null>(null)

  const load = useCallback(() => {
    void listCpeDevices(customerId)
      .then((list) => {
        setDevices(list)
        setSelected((cur) => cur ?? list[0]?.id ?? null)
      })
      .catch(() => setDevices([]))
  }, [customerId])

  useEffect(() => load(), [load])

  if (devices == null) {
    return (
      <div className="card" style={{ display: 'grid', placeItems: 'center', minHeight: 120 }}>
        <Spinner />
      </div>
    )
  }
  if (devices.length === 0) {
    return (
      <div className="card">
        <p className="muted" style={{ margin: 0 }}>
          Belum ada perangkat CPE tertaut. Penautan otomatis saat serial ONU pelanggan cocok dengan
          perangkat di GenieACS.
        </p>
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      {devices.length > 1 && (
        <div className="segment" style={{ alignSelf: 'flex-start', flexWrap: 'wrap' }}>
          {devices.map((d) => (
            <button key={d.id} className={selected === d.id ? 'active' : ''} onClick={() => setSelected(d.id)}>
              {d.model ?? d.serialNumber}
            </button>
          ))}
        </div>
      )}
      {selected && <CpeDevicePanel key={selected} deviceId={selected} />}
    </div>
  )
}

/** Waktu ringkas dari string ISO, mis. "20 Jul 14:05"; "—" bila kosong. */
function fmtInstant(iso: string | null): string {
  return iso ? fmtMoment(new Date(iso)) : '—'
}

/** Satu perangkat CPE: ringkasan, kontrol (reboot/WiFi), host, dan jejak aksi. */
function CpeDevicePanel({ deviceId }: { deviceId: string }) {
  const { can } = useCan()
  const toast = useToast()
  const [detail, setDetail] = useState<CpeDeviceDetail | null>(null)
  const [live, setLive] = useState<CpeLiveView | null>(null)
  const [rebooting, setRebooting] = useState(false)

  const canReboot = can('cpe.device.reboot')
  const canWifiView = can('cpe.wifi.view')
  const canWifiManage = can('cpe.wifi.manage')
  const canDiag = can('cpe.diagnostic.run')
  const canFirmware = can('cpe.firmware.manage')
  const canManage = can('cpe.device.manage')

  const loadDetail = useCallback(() => {
    void getCpeDevice(deviceId)
      .then(setDetail)
      .catch(() => setDetail(null))
  }, [deviceId])

  const loadLive = useCallback(() => {
    if (!canWifiView) return
    void getCpeLive(deviceId)
      .then(setLive)
      .catch(() => setLive({ wifi: [], hosts: [] }))
  }, [deviceId, canWifiView])

  useEffect(() => loadDetail(), [loadDetail])
  useEffect(() => loadLive(), [loadLive])

  const reboot = async () => {
    setRebooting(true)
    try {
      const action = await rebootCpe(deviceId)
      if (action.status === 'SUCCESS') toast.success('Perintah reboot terkirim')
      else toast.error(action.detail ?? 'Reboot gagal di ACS')
      loadDetail()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Reboot gagal')
    } finally {
      setRebooting(false)
    }
  }

  const saveWifi = async (body: SetWifiRequest) => {
    try {
      const action = await setCpeWifi(deviceId, body)
      if (action.status === 'SUCCESS') toast.success('Perubahan WiFi terkirim')
      else toast.error(action.detail ?? 'Ubah WiFi gagal di ACS')
      loadLive()
      loadDetail()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Ubah WiFi gagal')
    }
  }

  if (!detail) {
    return (
      <div className="card" style={{ display: 'grid', placeItems: 'center', minHeight: 120 }}>
        <Spinner />
      </div>
    )
  }

  const d = detail.device
  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="card stack" style={{ gap: '0.75rem' }}>
        <div className="spread" style={{ alignItems: 'flex-start', gap: '0.5rem', flexWrap: 'wrap' }}>
          <div className="stack" style={{ gap: '0.35rem' }}>
            <div className="row" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
              <strong style={{ fontSize: '0.95rem' }}>{d.model ?? d.productClass ?? 'CPE'}</strong>
              <span
                className="badge"
                title={d.online ? 'Inform terakhir masih baru' : 'Tak ada inform terbaru dari ACS'}
                style={{ color: d.online ? 'var(--good-ink)' : 'var(--muted)', fontWeight: 600 }}
              >
                {d.online ? 'online' : 'offline'}
              </span>
              {d.manufacturer && <span className="badge neutral">{d.manufacturer}</span>}
            </div>
            <span className="muted" style={{ fontSize: '0.82rem' }}>
              {d.serialNumber}
              {d.softwareVersion && ` · fw ${d.softwareVersion}`}
              {d.ipAddress && ` · ${d.ipAddress}`}
            </span>
          </div>
          {canReboot && (
            <button className="ghost danger" onClick={() => void reboot()} disabled={rebooting}>
              {rebooting ? 'Mengirim…' : 'Reboot'}
            </button>
          )}
        </div>
        <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))' }}>
          <Field label="Inform terakhir" value={fmtInstant(d.lastInformAt)} />
          <Field label="OUI" value={d.oui ?? '—'} />
          <Field label="Kelas produk" value={d.productClass ?? '—'} />
          <Field label="GenieACS ID" value={d.genieacsId} />
        </div>
      </div>

      {canWifiView && (
        <div className="card stack" style={{ gap: '0.75rem' }}>
          <strong style={{ fontSize: '0.95rem' }}>WiFi</strong>
          {live == null ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Memuat dari ACS…</p>
          ) : live.wifi.length === 0 ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Tak ada jaringan WiFi terbaca.</p>
          ) : (
            live.wifi.map((w) => (
              <WifiCard
                key={`${w.ref}:${w.ssid}:${w.passphrase ?? ''}`}
                wifi={w}
                canManage={canWifiManage}
                onSave={saveWifi}
              />
            ))
          )}
        </div>
      )}

      {canWifiView && (
        <div className="card stack" style={{ gap: '0.6rem' }}>
          <strong style={{ fontSize: '0.95rem' }}>Perangkat tersambung</strong>
          {live == null ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Memuat…</p>
          ) : live.hosts.length === 0 ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Tak ada host aktif.</p>
          ) : (
            <div className="stack" style={{ gap: '0.35rem' }}>
              {live.hosts.map((h, i) => (
                <div key={`${h.macAddress ?? i}`} className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
                  <div className="stack" style={{ gap: 2, minWidth: 0 }}>
                    <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>{h.hostName ?? '(tanpa nama)'}</span>
                    <span className="muted tnum" style={{ fontSize: '0.78rem' }}>
                      {h.ipAddress ?? '—'}
                      {h.macAddress && ` · ${h.macAddress}`}
                    </span>
                  </div>
                  <span
                    className="badge"
                    style={{ color: h.active ? 'var(--good-ink)' : 'var(--muted)', fontWeight: 600 }}
                  >
                    {h.active ? 'aktif' : 'idle'}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {canDiag && <DiagnosticsCard deviceId={deviceId} online={d.online} onRan={loadDetail} />}

      {canFirmware && (
        <FirmwareCard deviceId={deviceId} currentVersion={d.softwareVersion} onRan={loadDetail} />
      )}

      {canManage && <AcsCard deviceId={deviceId} onRan={loadDetail} />}

      <CpeActionLog actions={detail.recentActions} />
    </div>
  )
}

/**
 * Diagnostik on-demand: ping ke sasaran (kosong = bawaan server) dan uji kecepatan
 * unduh/unggah TR-143. Hasilnya tak tersimpan — ditampilkan inline dan tiap jalan
 * menulis jejak audit, jadi [onRan] menyegarkan panel jejak di atasnya. Tombol
 * dikunci selagi satu uji berjalan (perangkat hanya melayani satu diagnostik).
 */
function DiagnosticsCard({
  deviceId,
  online,
  onRan,
}: {
  deviceId: string
  online: boolean
  onRan: () => void
}) {
  const toast = useToast()
  const [host, setHost] = useState('')
  const [running, setRunning] = useState<'ping' | 'DOWNLOAD' | 'UPLOAD' | null>(null)
  const [ping, setPing] = useState<PingDiagnosticView | null>(null)
  const [speed, setSpeed] = useState<SpeedTestDiagnosticView | null>(null)

  const doPing = async () => {
    setRunning('ping')
    try {
      const result = await runCpePing(deviceId, host.trim() || undefined)
      setPing(result)
      if (!result.ok) toast.error(result.message)
      onRan()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Ping gagal')
    } finally {
      setRunning(null)
    }
  }

  const doSpeed = async (direction: SpeedDirection) => {
    setRunning(direction)
    try {
      const result = await runCpeSpeedTest(deviceId, direction)
      setSpeed(result)
      if (!result.ok) toast.error(result.message)
      onRan()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Uji kecepatan gagal')
    } finally {
      setRunning(null)
    }
  }

  const busy = running !== null
  return (
    <div className="card stack" style={{ gap: '0.75rem' }}>
      <strong style={{ fontSize: '0.95rem' }}>Diagnostik</strong>
      {!online && (
        <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
          Perangkat sedang offline — diagnostik bisa gagal atau menunggu lama.
        </p>
      )}
      <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <label style={{ flex: 2, minWidth: 160 }}>
          <span>Sasaran ping</span>
          <input
            value={host}
            placeholder="kosong = bawaan (mis. 8.8.8.8)"
            onChange={(e) => setHost(e.target.value)}
          />
        </label>
        <button onClick={() => void doPing()} disabled={busy}>
          {running === 'ping' ? 'Menguji…' : 'Ping'}
        </button>
        <button onClick={() => void doSpeed('DOWNLOAD')} disabled={busy}>
          {running === 'DOWNLOAD' ? 'Menguji…' : 'Uji unduh'}
        </button>
        <button onClick={() => void doSpeed('UPLOAD')} disabled={busy}>
          {running === 'UPLOAD' ? 'Menguji…' : 'Uji unggah'}
        </button>
      </div>
      {ping && <DiagPingResult ping={ping} />}
      {speed && <DiagSpeedResult speed={speed} />}
    </div>
  )
}

/** Baris hasil ping: host, ringkasan (avg/paket), dan status tuntas/gagal. */
function DiagPingResult({ ping }: { ping: PingDiagnosticView }) {
  const total = (ping.successCount ?? 0) + (ping.failureCount ?? 0)
  return (
    <div className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
      <div className="stack" style={{ gap: 2, minWidth: 0 }}>
        <span style={{ fontSize: '0.85rem' }}>
          <span style={{ fontWeight: 600 }}>Ping {ping.host}</span>
          {ping.ok && total > 0 && (
            <span className="muted">
              {' '}· {ping.successCount ?? 0}/{total} sukses
              {ping.averageResponseMs != null && ` · avg ${ping.averageResponseMs} ms`}
            </span>
          )}
        </span>
        {!ping.ok && (
          <span className="muted" style={{ fontSize: '0.78rem' }}>{ping.message}</span>
        )}
      </div>
      <span
        className="badge"
        style={{ color: ping.ok ? 'var(--good-ink)' : 'var(--critical-ink)', fontWeight: 600 }}
      >
        {ping.ok ? 'tuntas' : 'gagal'}
      </span>
    </div>
  )
}

/** Baris hasil uji kecepatan: arah, throughput Mbps, status. */
function DiagSpeedResult({ speed }: { speed: SpeedTestDiagnosticView }) {
  const label = speed.direction === 'DOWNLOAD' ? 'Unduh' : 'Unggah'
  return (
    <div className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
      <div className="stack" style={{ gap: 2, minWidth: 0 }}>
        <span style={{ fontSize: '0.85rem' }}>
          <span style={{ fontWeight: 600 }}>{label}</span>
          {speed.ok && speed.throughputMbps != null ? (
            <span className="muted"> · {speed.throughputMbps.toFixed(1)} Mbps</span>
          ) : (
            <span className="muted"> · {speed.message}</span>
          )}
        </span>
      </div>
      <span
        className="badge"
        style={{ color: speed.ok ? 'var(--good-ink)' : 'var(--critical-ink)', fontWeight: 600 }}
      >
        {speed.ok ? 'tuntas' : 'gagal'}
      </span>
    </div>
  )
}

/** Ukuran berkas ringkas (mis. "12,0 MB"); null → "—". */
function fmtBytes(bytes: number | null): string {
  if (bytes == null) return '—'
  if (bytes >= 1_000_000) return `${(bytes / 1_000_000).toFixed(1)} MB`
  if (bytes >= 1_000) return `${(bytes / 1_000).toFixed(0)} KB`
  return `${bytes} B`
}

/**
 * Upgrade firmware: menampilkan versi terpasang sekarang dan daftar berkas firmware
 * di ACS yang cocok untuk model perangkat. Menekan "Pasang" memicu unduh TR-069 (via
 * konfirmasi, karena upgrade me-reboot perangkat) dan menulis jejak audit, jadi [onRan]
 * menyegarkan panel jejak. Tombol dikunci selagi satu upgrade dikirim.
 */
function FirmwareCard({
  deviceId,
  currentVersion,
  onRan,
}: {
  deviceId: string
  currentVersion: string | null
  onRan: () => void
}) {
  const toast = useToast()
  const [files, setFiles] = useState<FirmwareFileView[] | null>(null)
  const [pushing, setPushing] = useState<string | null>(null)

  const load = useCallback(() => {
    void listCpeFirmware(deviceId)
      .then(setFiles)
      .catch(() => setFiles([]))
  }, [deviceId])

  useEffect(() => load(), [load])

  const upgrade = async (file: FirmwareFileView) => {
    const versi = file.version ? ` (${file.version})` : ''
    if (!window.confirm(`Pasang firmware ${file.name}${versi}? Perangkat akan reboot saat memasang.`)) {
      return
    }
    setPushing(file.name)
    try {
      const action = await upgradeCpeFirmware(deviceId, file.name)
      if (action.status === 'SUCCESS') toast.success('Perintah upgrade firmware terkirim')
      else toast.error(action.detail ?? 'Upgrade firmware gagal di ACS')
      onRan()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Upgrade firmware gagal')
    } finally {
      setPushing(null)
    }
  }

  const busy = pushing !== null
  return (
    <div className="card stack" style={{ gap: '0.75rem' }}>
      <div className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Firmware</strong>
        <span className="muted" style={{ fontSize: '0.82rem' }}>
          terpasang: {currentVersion ?? '—'}
        </span>
      </div>
      {files == null ? (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Memuat dari ACS…</p>
      ) : files.length === 0 ? (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Tak ada firmware tersedia untuk model ini.
        </p>
      ) : (
        <div className="stack" style={{ gap: '0.35rem' }}>
          {files.map((f) => (
            <div key={f.name} className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
              <div className="stack" style={{ gap: 2, minWidth: 0 }}>
                <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>{f.version ?? f.name}</span>
                <span className="muted" style={{ fontSize: '0.78rem' }}>
                  {f.name}
                  {f.sizeBytes != null && ` · ${fmtBytes(f.sizeBytes)}`}
                </span>
              </div>
              <button onClick={() => void upgrade(f)} disabled={busy}>
                {pushing === f.name ? 'Mengirim…' : 'Pasang'}
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * ACS & pemeliharaan: "Refresh ACS" memaksa perangkat membuka sesi ke ACS sekarang
 * (connection request) dan melaporkan status "ACS Connect / Not Connect"; "Reset
 * pabrik" mengembalikan seluruh konfigurasi ke setelan awal (destruktif, jadi pakai
 * konfirmasi tegas). Keduanya menulis jejak audit, jadi [onRan] menyegarkan panel
 * jejak. Butuh izin `cpe.device.manage`.
 */
function AcsCard({ deviceId, onRan }: { deviceId: string; onRan: () => void }) {
  const toast = useToast()
  const [acs, setAcs] = useState<AcsRefreshView | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const [resetting, setResetting] = useState(false)

  const refresh = async () => {
    setRefreshing(true)
    try {
      const result = await refreshCpeAcs(deviceId)
      setAcs(result)
      if (result.connected) toast.success('ACS terhubung ke perangkat')
      else toast.error(result.message)
      onRan()
    } catch (err) {
      setAcs(null)
      toast.error(err instanceof ApiError ? err.message : 'Refresh ACS gagal')
    } finally {
      setRefreshing(false)
    }
  }

  const factoryReset = async () => {
    if (
      !window.confirm(
        'Reset pabrik mengembalikan SEMUA setelan perangkat (WiFi, dll) ke bawaan dan memutus koneksi pelanggan. Lanjutkan?',
      )
    ) {
      return
    }
    setResetting(true)
    try {
      const action = await factoryResetCpe(deviceId)
      if (action.status === 'SUCCESS') toast.success('Perintah reset pabrik terkirim')
      else toast.error(action.detail ?? 'Reset pabrik gagal di ACS')
      onRan()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Reset pabrik gagal')
    } finally {
      setResetting(false)
    }
  }

  const busy = refreshing || resetting
  return (
    <div className="card stack" style={{ gap: '0.75rem' }}>
      <div className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>ACS &amp; pemeliharaan</strong>
        {acs != null && (
          <span
            className="badge"
            title={acs.message}
            style={{ color: acs.connected ? 'var(--good-ink)' : 'var(--muted)', fontWeight: 600 }}
          >
            {acs.connected ? 'ACS Connect' : 'Not Connect'}
          </span>
        )}
      </div>
      <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
        Refresh memaksa perangkat menghubungi ACS sekarang; reset pabrik mengembalikan setelan ke bawaan.
      </p>
      <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap' }}>
        <button onClick={() => void refresh()} disabled={busy}>
          {refreshing ? 'Menghubungi…' : 'Refresh ACS'}
        </button>
        <button className="ghost danger" onClick={() => void factoryReset()} disabled={busy}>
          {resetting ? 'Mengirim…' : 'Reset pabrik'}
        </button>
      </div>
    </div>
  )
}

/**
 * Kartu satu jaringan WiFi dengan editor SSID/password. State edit dimiliki lokal
 * dan hanya field yang benar-benar berubah yang dikirim (server menolak "tanpa
 * perubahan"), jadi tombol Simpan mati sampai ada yang diubah.
 */
function WifiCard({
  wifi,
  canManage,
  onSave,
}: {
  wifi: WifiView
  canManage: boolean
  onSave: (body: SetWifiRequest) => Promise<void>
}) {
  const [ssid, setSsid] = useState(wifi.ssid)
  const [passphrase, setPassphrase] = useState(wifi.passphrase ?? '')
  const [showPass, setShowPass] = useState(false)
  const [saving, setSaving] = useState(false)

  const ssidChanged = ssid.trim() !== '' && ssid !== wifi.ssid
  const passChanged = passphrase !== '' && passphrase !== (wifi.passphrase ?? '')
  const dirty = ssidChanged || passChanged

  const save = async () => {
    setSaving(true)
    try {
      await onSave({
        ref: wifi.ref,
        ssid: ssidChanged ? ssid : null,
        passphrase: passChanged ? passphrase : null,
      })
    } finally {
      setSaving(false)
    }
  }

  return (
    <div
      className="stack"
      style={{ gap: '0.5rem', padding: '0.6rem 0.7rem', border: '1px solid var(--border)', borderRadius: 8 }}
    >
      <div className="row" style={{ gap: '0.4rem', alignItems: 'center', flexWrap: 'wrap' }}>
        {wifi.band && <span className="badge neutral">{wifi.band}</span>}
        <span className="badge" style={{ color: wifi.enabled ? 'var(--good-ink)' : 'var(--muted)', fontWeight: 600 }}>
          {wifi.enabled ? 'aktif' : 'nonaktif'}
        </span>
      </div>
      {canManage ? (
        <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <label style={{ flex: 2, minWidth: 160 }}>
            <span>SSID</span>
            <input value={ssid} onChange={(e) => setSsid(e.target.value)} />
          </label>
          <label style={{ flex: 2, minWidth: 160 }}>
            <span>Password</span>
            <input
              type={showPass ? 'text' : 'password'}
              value={passphrase}
              placeholder={wifi.passphrase == null ? 'tersembunyi — isi untuk mengganti' : ''}
              onChange={(e) => setPassphrase(e.target.value)}
            />
          </label>
          <button onClick={() => setShowPass((v) => !v)}>{showPass ? 'Sembunyikan' : 'Lihat'}</button>
          <button className="primary" onClick={() => void save()} disabled={!dirty || saving}>
            {saving ? 'Menyimpan…' : 'Simpan'}
          </button>
        </div>
      ) : (
        <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))' }}>
          <Field label="SSID" value={wifi.ssid} />
          <Field label="Password" value={wifi.passphrase ?? 'tersembunyi'} />
        </div>
      )}
    </div>
  )
}

/** Jejak aksi terakhir ke perangkat — reboot / ubah WiFi, berhasil atau gagal. */
function CpeActionLog({ actions }: { actions: CpeActionView[] }) {
  if (actions.length === 0) return null
  return (
    <div className="card stack" style={{ gap: '0.5rem' }}>
      <strong style={{ fontSize: '0.95rem' }}>Jejak aksi</strong>
      <div className="stack" style={{ gap: '0.35rem' }}>
        {actions.map((a) => (
          <div key={a.id} className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
            <div className="stack" style={{ gap: 2, minWidth: 0 }}>
              <span style={{ fontSize: '0.85rem' }}>
                <span style={{ fontWeight: 600 }}>{CPE_ACTION_LABEL[a.action]}</span>
                {a.detail && <span className="muted"> · {a.detail}</span>}
              </span>
              <span className="muted" style={{ fontSize: '0.78rem' }}>
                {fmtInstant(a.requestedAt)}
                {a.requestedByEmail && ` · ${a.requestedByEmail}`}
              </span>
            </div>
            <span
              className="badge"
              style={{
                color: a.status === 'SUCCESS' ? 'var(--good-ink)' : 'var(--critical-ink)',
                fontWeight: 600,
              }}
            >
              {a.status === 'SUCCESS' ? 'berhasil' : 'gagal'}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}
