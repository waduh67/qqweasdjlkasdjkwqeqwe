import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { RefreshCw } from 'lucide-react'
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
import { onuStatusLabel } from '../api/network'
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
import {
  deleteAccess,
  getBrasSession,
  isolateAccess,
  listAccessForCustomer,
  listNas,
  provisionAccess,
  resetAccessLogin,
  resetAccessSecret,
  restoreAccess,
  updateAccess,
  type BrasSessionView,
  type NasView,
  type SubscriberAccessView,
} from '../api/bng'
import { mapFocusState, type MapFocusState } from './mapFocus'
import { useCan } from '../auth/useCan'
import { useAuth } from '../auth/useAuth'
import { Badge, Button, EmptyState, Segmented, SelectField, Spinner, StatusBadge, TextField } from '@/components/atoms'
import { CommandBar, Ess, Tabs, type CommandAction } from '@/components/molecules'
import { useConfirm, useToast } from '@/system'
import { OpticalChart } from '@/components/atoms'
import { SubscriberTrafficPanel } from '@/components/organisms'
import {
  IconAlert,
  IconAudit,
  IconChart,
  IconChevronDown,
  IconCustomers,
  IconFlask,
  IconGauge,
  IconInventory,
  IconMap,
  IconMonitor,
  IconPackage,
  IconReceipt,
  IconRoute,
  IconShield,
  IconUpload,
  IconUsers,
  IconWifi,
  IconWorkOrder,
} from '@/components/atoms/icons'
import type { SubscriptionView } from '../api/network'
import { listPlans as listCatalogPlans, SERVICE_TYPE_LABEL, type PlanView, type ServiceType } from '../api/catalog'
import { listInvoicesForCustomer, type InvoiceView } from '../api/billing'
import { payLink } from '../api/publicPayment'
import { listIncidentsForCustomer, type IncidentView } from '../api/incident'
import { listWorkOrdersForCustomer, type WorkOrderStatus, type WorkOrderView } from '../api/workorder'
import { getSubscriber360, type Sub360BillingSummary, type Subscriber360View } from '../api/subscriber360'
import { PortalCredentialCard } from '@/components/organisms'
import { Blade } from '@/components/organisms'

/**
 * Detail pelanggan sebagai flyout — SATU-SATUNYA cara membukanya di aplikasi ini.
 *
 * Sengaja tak ada rute `/customers/:id`: detail selalu muncul sebagai panel di atas
 * konteks asalnya (daftar pelanggan, panel telusur peta, daftar ONU sebuah OLT) supaya
 * operator tak kehilangan tempat berdirinya — menutup panel mengembalikan layar persis
 * seperti sebelum diklik, tanpa memutar ulang pencarian atau posisi peta.
 *
 * Dipusatkan di sini agar semua pemanggil memakai judul, ukuran, dan lebar yang sama.
 */
export function CustomerDetailBlade({
  customerId,
  onClose,
  onShowOnMap,
}: {
  /** `null` = tertutup. Menggantinya dengan id lain cukup menukar isi panel. */
  customerId: string | null
  onClose: () => void
  /** Diteruskan ke command bar detail; lihat [CustomerDetailPage]. */
  onShowOnMap?: (focus: MapFocusState) => void
}) {
  return (
    <Blade
      open={customerId != null}
      title="Detail pelanggan"
      size="full"
      className="blade-half"
      onClose={onClose}
    >
      {customerId && <CustomerDetailPage customerId={customerId} onShowOnMap={onShowOnMap} />}
    </Blade>
  )
}

/** Warna kesehatan optik selaras token status. */
const HEALTH_COLOR: Record<string, string> = {
  GOOD: 'var(--good-ink)',
  WARNING: 'var(--warning-ink)',
  CRITICAL: 'var(--critical-ink)',
  UNKNOWN: 'var(--muted)',
}

type Tab = 'ringkasan' | 'jalur' | 'tetangga' | 'metrik' | 'akses' | 'trafik' | 'cpe' | 'tagihan' | 'tiket' | 'timeline'

/**
 * Detail satu pelanggan bergaya blade Azure: kepala + command bar, blok "Essentials"
 * yang bisa dilipat, lalu tab-tab (Jalur, Tetangga, Metrik, Akses, Trafik, CPE,
 * Tagihan, Tiket & WO, Timeline). Dipakai sebagai isi flyout — baik dari daftar
 * pelanggan maupun dari panel telusur di halaman Peta.
 */
export function CustomerDetailPage({
  customerId,
  onShowOnMap,
}: {
  customerId: string
  /**
   * Perilaku aksi "Lihat di peta". Diisi oleh pembungkus yang PETANYA sudah tampil di
   * belakang flyout (halaman Peta): di sana cukup menutup flyout agar penanda pelanggan
   * terlihat, bukan berpindah rute ke peta yang sama. Bila kosong, aksi pindah ke /map
   * sambil membawa pesan agar peta memusat ke pelanggan ini.
   *
   * Pesan sorotnya ikut diserahkan supaya pembungkus yang perlu MENGGESER peta di
   * belakangnya (mis. flyout pelanggan yang bertumpuk di atas panel OLT) tinggal
   * meneruskannya ke `navigate('/map', focus)` setelah menutup panel-panelnya.
   */
  onShowOnMap?: (focus: MapFocusState) => void
}) {
  // Detail pelanggan kini tampil sebagai flyout fullscreen (dibuka dari daftar), bukan rute
  // tersendiri — jadi `id` datang lewat prop, bukan `useParams`. Alias `id` menjaga sisa berkas
  // tetap ringkas; penutupan panel ditangani Blade pembungkus di CustomersPage.
  const id = customerId
  const { can } = useCan()
  const toast = useToast()
  const navigate = useNavigate()
  // Penanda "segarkan": dinaikkan tombol command bar agar SEMUA tarikan halaman
  // (360°, jalur, tetangga, metrik) diputar ulang, bukan cuma profil pelanggan.
  const [refreshKey, setRefreshKey] = useState(0)

  const [customer, setCustomer] = useState<CustomerView | null>(null)
  const [odps, setOdps] = useState<OdpView[]>([])
  const [trace, setTrace] = useState<CustomerTrace | null>(null)
  const [neighbors, setNeighbors] = useState<SubscriberNeighbors | null>(null)
  const [metrics, setMetrics] = useState<OnuMetricView[]>([])
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [tab, setTab] = useState<Tab>('ringkasan')
  const [sub360, setSub360] = useState<Subscriber360View | null>(null)

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

  // Ringkasan 360° server-side: satu panggilan agregat yang menyusun tunggakan
  // (dihitung SERVER), status sesi PPPoE, CPE, dan work order terbuka — tiap facet
  // sudah digerbang izin per-modul di server. Aditif & toleran gagal: tab detail tetap
  // memakai tarikannya sendiri, jadi kegagalan di sini tak melumpuhkan halaman.
  useEffect(() => {
    let alive = true
    void getSubscriber360(id)
      .then((v) => alive && setSub360(v))
      .catch(() => alive && setSub360(null))
    return () => {
      alive = false
    }
  }, [id, refreshKey])

  // Gerbang izin sebagai boolean primitif: `can` dari useCan berganti identitas
  // tiap render, jadi tak boleh masuk daftar dependensi effect (memicu loop).
  const canAssign = can('customer.onu.assign')
  const canMetric = can('monitoring.metric.view')
  const canAccess = can('bng.access.view')
  // Tab Trafik digerbang izin baca sesi/trafik (sama dengan panel B-ras Check).
  const canTraffic = can('bng.session.view')
  const canCpe = can('cpe.device.view')
  // Facet sisi-bisnis Subscriber-360: tiap tab digerbang izin modul pemiliknya.
  const canBilling = can('billing.invoice.view')
  const canIncident = can('incident.ticket.view')
  const canWorkorder = can('workorder.order.view')

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
  }, [id, refreshKey])

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
  }, [canMetric, id, refreshKey])

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
        <div className="card">
          <EmptyState title="Pelanggan tidak ditemukan" hint="Mungkin sudah dihapus." icon={<IconCustomers size={32} />} />
        </div>
      </div>
    )
  }

  const connected = (trace?.hops.length ?? 0) > 1
  const odpCount = neighbors?.sameOdp.length ?? 0
  const ponCount = neighbors?.samePonPort.length ?? 0

  const tabDefs: { key: Tab; label: ReactNode; badge?: ReactNode }[] = [
    { key: 'ringkasan', label: 'Ringkasan' },
    { key: 'jalur', label: 'Jalur' },
    { key: 'tetangga', label: 'Tetangga', badge: ponCount || undefined },
    { key: 'metrik', label: 'Metrik' },
    ...(canAccess ? [{ key: 'akses' as Tab, label: 'Akses' }] : []),
    ...(canTraffic ? [{ key: 'trafik' as Tab, label: 'Trafik' }] : []),
    ...(canCpe ? [{ key: 'cpe' as Tab, label: 'CPE' }] : []),
    ...(canBilling ? [{ key: 'tagihan' as Tab, label: 'Tagihan' }] : []),
    ...(canIncident || canWorkorder ? [{ key: 'tiket' as Tab, label: 'Tiket & WO' }] : []),
    ...(canBilling || canIncident || canWorkorder ? [{ key: 'timeline' as Tab, label: 'Timeline' }] : []),
  ]

  // Command bar blade: aksi yang berlaku untuk pelanggan secara keseluruhan, bukan
  // untuk satu tab. Sengaja datar & seragam dengan command bar halaman tabel.
  const commands: CommandAction[] = [
    {
      key: 'refresh',
      label: 'Segarkan',
      icon: <RefreshCw size={16} />,
      onClick: () => {
        void reload()
        setRefreshKey((v) => v + 1)
      },
    },
    {
      key: 'map',
      label: 'Lihat di peta',
      icon: <IconMap size={16} />,
      onClick: () => {
        const focus = mapFocusState('customer', id, customer.location)
        if (onShowOnMap) onShowOnMap(focus)
        else navigate('/map', focus)
      },
      dividerBefore: true,
    },
  ]

  return (
    <div className="stack flat-sections" style={{ gap: '0.85rem' }}>
      <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap', alignItems: 'center' }}>
        <h1 className="page-title" style={{ margin: 0 }}>{customer.name}</h1>
        <span className="badge">{customer.code}</span>
        <StatusBadge status={customer.status} />
        {customer.awaitingInstallation && <StatusBadge status="PENDING" label="menunggu instalasi" />}
      </div>

      <CommandBar actions={commands} />

      <EssentialsBlock customer={customer} sub360={sub360} trace={trace} />

      <Tabs tabs={tabDefs} active={tab} onChange={setTab} />

      {tab === 'ringkasan' && <RingkasanTab customer={customer} odps={odps} run={run} />}
      {tab === 'jalur' && <JalurTab trace={trace} connected={connected} />}
      {tab === 'tetangga' && <TetanggaTab neighbors={neighbors} connected={connected} odpCount={odpCount} ponCount={ponCount} />}
      {tab === 'metrik' && <MetrikTab customer={customer} metrics={metrics} />}
      {tab === 'akses' && <NetworkAccessTab customerId={id} subscriptions={customer.subscriptions} />}
      {tab === 'trafik' && <TrafikTab customerId={id} />}
      {tab === 'cpe' && <CpeTab customerId={id} />}
      {tab === 'tagihan' && <TagihanTab customerId={id} billing={sub360?.billing ?? null} />}
      {tab === 'tiket' && <TiketWoTab customerId={id} canIncident={canIncident} canWorkorder={canWorkorder} />}
      {tab === 'timeline' && (
        <TimelineTab
          customerId={id}
          customer={customer}
          canBilling={canBilling}
          canIncident={canIncident}
          canWorkorder={canWorkorder}
        />
      )}
    </div>
  )
}

/* ---------- Essentials: ringkasan properti di kepala blade ---------- */

/**
 * Kepala seksi bergaya blade Azure: ikon beraksen + judul tebal, tanpa bingkai.
 * Pemisah antar-seksi dikerjakan garis rambut wadah `.flat-sections`.
 */
function SectionHead({ icon, title, aside }: { icon: ReactNode; title: string; aside?: ReactNode }) {
  return (
    <div className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
      <div className="section-head">
        <span className="ico" aria-hidden>
          {icon}
        </span>
        <h3 className="section-title">{title}</h3>
      </div>
      {aside}
    </div>
  )
}

/**
 * Blok "Essentials": dua kolom pasangan label:nilai yang menjawab pertanyaan pertama
 * operator — siapa orangnya, di mana, berlangganan apa, dan sedang sehat atau tidak —
 * tanpa perlu berpindah tab.
 *
 * Menggantikan dua kartu lama ("Ringkasan 360°" + "Profil"): isinya sama, tapi sebagai
 * daftar properti rapat ia memakan sekitar sepertiga tinggi kartu-kartu itu, dan bisa
 * dilipat saat operator ingin ruang penuh untuk tab di bawahnya. Angka bisnis (tunggakan,
 * sesi, CPE, WO) datang dari satu panggilan agregat `/api/subscriber-360/:id` yang tiap
 * facet-nya digerbang izin di server — facet tanpa izin ditulis "terkunci" agar berbeda
 * jelas dari "memang kosong". Detail penuhnya tetap di tab masing-masing.
 */
function EssentialsBlock({
  customer,
  sub360,
  trace,
}: {
  customer: CustomerView
  sub360: Subscriber360View | null
  trace: CustomerTrace | null
}) {
  const [open, setOpen] = useState(true)
  const billing = sub360?.billing ?? null
  const session = sub360?.session ?? null
  const cpeDevices = sub360?.cpeDevices ?? null
  const openWorkOrder = sub360?.openWorkOrder ?? null
  const access = sub360?.access ?? null
  const arrears = billing ? Number(billing.outstandingAmount) : 0
  const cpeOnline = cpeDevices?.filter((d) => d.online).length ?? 0
  const active = customer.subscriptions.filter((s) => s.status !== 'TERMINATED')
  const attachedOnu = customer.onus.find((o) => o.odpId) ?? customer.onus[0] ?? null

  // Facet yang belum diizinkan ditulis sekali di sini supaya keempat baris layanan
  // memakai kalimat yang sama persis. Selama `access` masih null (tarikan 360° belum
  // selesai / gagal) barisnya DIHILANGKAN, bukan diisi nol — "Rp 0" yang ternyata
  // cuma keadaan memuat adalah kebohongan yang mahal di layar tagihan.
  const locked = <span className="muted">terkunci</span>

  return (
    <section className="stack" style={{ gap: '0.6rem' }}>
      <button type="button" className="blade-disclosure" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <span className="chev" aria-hidden>
          <IconChevronDown size={14} />
        </span>
        Essentials
      </button>

      {open && (
        <div className="ess-cols">
          <dl className="essentials wide">
            <Ess label="Kode">
              <span className="tnum">{customer.code}</span>
            </Ess>
            <Ess label="Status">
              <StatusBadge status={customer.status} />
            </Ess>
            <Ess label="Alamat">{customer.address}</Ess>
            <Ess label="Telepon">{customer.phone}</Ess>
            <Ess label="Email">{customer.email}</Ess>
            <Ess label="NIK / identitas">{customer.idCardNumber}</Ess>
            <Ess label="Koordinat">
              <span className="tnum">
                {customer.location.latitude}, {customer.location.longitude}
              </span>
            </Ess>
          </dl>

          <dl className="essentials wide">
            <Ess label="Paket">
              {active.length === 0 ? (
                <span className="muted">belum berlangganan</span>
              ) : (
                active.map((s) => `${s.packageName} · ${s.bandwidthMbps} Mbps`).join(', ')
              )}
            </Ess>
            <Ess label="Tunggakan">
              {!access ? null : !access.billing ? (
                locked
              ) : (
                <span
                  className="tnum"
                  style={{ fontWeight: 600, color: arrears > 0 ? 'var(--critical-ink)' : 'var(--good-ink)' }}
                >
                  {fmtRupiah(arrears)}
                  {billing && billing.outstandingCount > 0 && (
                    <span className="muted" style={{ fontWeight: 400 }}> · {billing.outstandingCount} jatuh tempo</span>
                  )}
                </span>
              )}
            </Ess>
            <Ess label="Sesi PPPoE">
              {!access ? null : !access.session ? (
                locked
              ) : !session ? (
                <span className="muted">belum ada</span>
              ) : (
                <span className="row" style={{ gap: '0.4rem', alignItems: 'center', flexWrap: 'wrap' }}>
                  <Badge tone={session.online ? 'good' : 'neutral'}>{session.online ? 'Online' : 'Offline'}</Badge>
                  <span className="tnum muted">{session.framedIp ?? session.username}</span>
                </span>
              )}
            </Ess>
            <Ess label="ONU">
              {!attachedOnu ? (
                <span className="muted">belum ada</span>
              ) : (
                <span>
                  <span className="tnum">{attachedOnu.serialNumber}</span>
                  {attachedOnu.odpCode && (
                    <span className="muted"> · {attachedOnu.odpCode} port {attachedOnu.odpPortNumber}</span>
                  )}
                </span>
              )}
            </Ess>
            <Ess label="Redaman">
              {trace?.liveRxPowerDbm != null || trace?.installRxPowerDbm != null ? (
                <span className="tnum" style={{ color: HEALTH_COLOR[trace.opticalHealth ?? 'UNKNOWN'] }}>
                  {fmtDbm(trace.liveRxPowerDbm ?? trace.installRxPowerDbm)}
                </span>
              ) : null}
            </Ess>
            <Ess label="CPE">
              {!access ? null : !access.cpe ? (
                locked
              ) : !cpeDevices || cpeDevices.length === 0 ? (
                <span className="muted">tak ada</span>
              ) : (
                <span className="tnum">
                  {cpeOnline}/{cpeDevices.length} <span className="muted">online</span>
                </span>
              )}
            </Ess>
            <Ess label="WO terbuka">
              {!access ? null : !access.workOrder ? (
                locked
              ) : !openWorkOrder ? (
                <span className="muted">tak ada</span>
              ) : (
                <span className="tnum">
                  {openWorkOrder.code}
                  {openWorkOrder.scheduledAt && (
                    <span className="muted"> · {fmtDate(openWorkOrder.scheduledAt.slice(0, 10))}</span>
                  )}
                </span>
              )}
            </Ess>
          </dl>
        </div>
      )}
    </section>
  )
}

/* ---------- Tab: Ringkasan (langganan, perangkat ONU, portal) ---------- */

function RingkasanTab({
  customer,
  odps,
  run,
}: {
  customer: CustomerView
  odps: OdpView[]
  run: (action: () => Promise<unknown>, okMessage?: string) => Promise<void>
}) {
  // Profil & angka 360° kini hidup di blok Essentials permanen di atas tab, jadi tab
  // ini tinggal berisi yang benar-benar bisa DIKERJAKAN: langganan, ONU, akun portal.
  return (
    <div className="stack" style={{ gap: '0.25rem' }}>
      <SubscriptionManager customer={customer} run={run} />

      <OnuManager customer={customer} odps={odps} run={run} />

      <PortalCredentialCard customerId={customer.id} />
    </div>
  )
}

/**
 * Kelola langganan pelanggan: pilih paket dari katalog (bukan lagi ketik bebas), harga
 * & kecepatan ikut paket dengan opsi harga negosiasi per-pelanggan, plus kendali daur
 * hidup. Sisi komersial di-snapshot server saat simpan; sisi jaringan dibaca live.
 */
function SubscriptionManager({
  customer,
  run,
}: {
  customer: CustomerView
  run: (action: () => Promise<unknown>, okMessage?: string) => Promise<void>
}) {
  const { can } = useCan()
  const canManage = can('customer.subscription.update')
  const [plans, setPlans] = useState<PlanView[]>([])
  const [planId, setPlanId] = useState('')
  const [priceOverride, setPriceOverride] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!canManage) return
    void listCatalogPlans()
      .then((all) => setPlans(all.filter((p) => p.active)))
      .catch(() => setPlans([]))
  }, [canManage])

  const selected = plans.find((p) => p.id === planId) ?? null

  const submit = async () => {
    if (!planId) return
    setSaving(true)
    const override = priceOverride.trim()
    await run(
      () =>
        api.post(`/api/customers/${customer.id}/subscriptions`, {
          planId,
          monthlyFeeOverride: override === '' ? null : Number(override),
        }),
      'Langganan ditambahkan',
    )
    setSaving(false)
    setPlanId('')
    setPriceOverride('')
  }

  return (
    <div className="card stack" style={{ gap: '0.75rem' }}>
      <SectionHead icon={<IconPackage size={16} />} title="Langganan" />

      {customer.subscriptions.length === 0 ? (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada langganan.</p>
      ) : (
        customer.subscriptions.map((sub) => (
          <div key={sub.id} className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
            <span style={{ fontSize: '0.88rem' }}>
              {sub.packageName} · {sub.bandwidthMbps} Mbps · Rp {sub.monthlyFee}
            </span>
            <div className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
              <StatusBadge status={sub.status} />
              {canManage && sub.status !== 'TERMINATED' && <SubscriptionActions sub={sub} run={run} />}
            </div>
          </div>
        ))
      )}

      {canManage && (
        <div className="stack" style={{ gap: '0.5rem', borderTop: '1px solid var(--border)', paddingTop: '0.75rem' }}>
          {plans.length === 0 ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
              Belum ada paket aktif — buat dulu di menu Paket Internet.
            </p>
          ) : (
            <>
              <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
                <SelectField
                  label="Paket"
                  value={planId}
                  onChange={(_, data) => setPlanId(data.value)}
                  style={{ flex: '2 1 200px' }}
                >
                  <option value="">— pilih paket —</option>
                  {plans.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name} · {p.downMbps}/{p.upMbps} Mbps · {fmtRupiah(p.price)}
                    </option>
                  ))}
                </SelectField>
                <TextField
                  label="Harga negosiasi"
                  type="number"
                  min={0}
                  value={priceOverride}
                  onChange={(_, data) => setPriceOverride(data.value)}
                  placeholder={selected ? String(selected.price) : 'ikut paket'}
                  style={{ flex: '1 1 140px' }}
                />
                <Button variant="primary" disabled={!planId || saving} onClick={() => void submit()}>
                  Tambah
                </Button>
              </div>
              {selected && (
                <p className="muted tnum" style={{ margin: 0, fontSize: '0.82rem' }}>
                  {selected.downMbps}/{selected.upMbps} Mbps · {fmtRupiah(selected.price)}/bln
                  {selected.fupEnabled ? ' · FUP' : ''} — kecepatan &amp; QoS mengikuti paket secara live.
                </p>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}

/** Kendali daur hidup satu langganan sesuai statusnya kini. */
function SubscriptionActions({
  sub,
  run,
}: {
  sub: SubscriptionView
  run: (action: () => Promise<unknown>, okMessage?: string) => Promise<void>
}) {
  const act = (verb: string, okMessage: string) =>
    run(() => api.post(`/api/customers/subscriptions/${sub.id}/${verb}`, {}), okMessage)
  return (
    <div className="row" style={{ gap: '0.35rem' }}>
      {(sub.status === 'PENDING' || sub.status === 'ISOLATED') && (
        <Button variant="subtle" onClick={() => void act('activate', 'Langganan diaktifkan')}>
          Aktifkan
        </Button>
      )}
      {sub.status === 'ACTIVE' && (
        <Button variant="subtle" onClick={() => void act('isolate', 'Langganan diisolir')}>
          Isolir
        </Button>
      )}
      <Button variant="danger" onClick={() => void act('terminate', 'Langganan diakhiri')}>
        Akhiri
      </Button>
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
  const confirm = useConfirm()
  const [serial, setSerial] = useState('')
  const [attach, setAttach] = useState<{ onuId: string; odpId: string; port: string; rx: string } | null>(null)

  return (
    <div className="card stack" style={{ gap: '0.5rem' }}>
      <SectionHead icon={<IconInventory size={16} />} title="Perangkat ONU" />
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
                // Masih terpasang: lepas dulu — hapus sengaja tak ditawarkan agar port
                // ODP tak menggantung (aturan sama yang ditegakkan OnuService.delete).
                <Button onClick={() => void run(() => api.post(`/api/customers/onus/${onu.id}/detach`), 'ONU dilepas')}>
                  Lepas
                </Button>
              ) : (
                <>
                  <Button onClick={() => setAttach({ onuId: onu.id, odpId: odps[0]?.id ?? '', port: '1', rx: '' })}>
                    Pasang ke ODP
                  </Button>
                  <Button
                    variant="danger"
                    onClick={() =>
                      void (async () => {
                        if (
                          !(await confirm({
                            title: 'Hapus ONU',
                            message: `Hapus permanen ONU ${onu.serialNumber} dari pelanggan ini?`,
                            confirmLabel: 'Hapus',
                            danger: true,
                          }))
                        )
                          return
                        void run(() => api.del(`/api/customers/onus/${onu.id}`), 'ONU dihapus')
                      })()
                    }
                  >
                    Hapus
                  </Button>
                </>
              )}
            </div>
          )}
        </div>
      ))}

      {attach && (
        <div className="row" style={{ marginTop: '0.4rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <SelectField
            label="ODP"
            value={attach.odpId}
            onChange={(_, data) => setAttach({ ...attach, odpId: data.value })}
            style={{ flex: 2, minWidth: 160 }}
          >
            {odps.map((odp) => (
              <option key={odp.id} value={odp.id}>
                {odp.code} ({odp.capacity} port)
              </option>
            ))}
          </SelectField>
          <TextField
            label="Port"
            value={attach.port}
            onChange={(_, data) => setAttach({ ...attach, port: data.value })}
            style={{ flex: 1, minWidth: 80 }}
          />
          <TextField
            label="Redaman (dBm)"
            value={attach.rx}
            onChange={(_, data) => setAttach({ ...attach, rx: data.value })}
            placeholder="-22.5"
            style={{ flex: 1, minWidth: 100 }}
          />
          <Button
            variant="primary"
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
          </Button>
          <Button onClick={() => setAttach(null)}>Batal</Button>
        </div>
      )}

      {can('customer.onu.assign') && (
        <div className="row" style={{ marginTop: '0.4rem' }}>
          <TextField
            placeholder="Serial ONU baru, mis. ZTEG-C0FFEE01"
            value={serial}
            onChange={(_, data) => setSerial(data.value)}
          />
          <Button
            onClick={() =>
              void run(async () => {
                await api.post(`/api/customers/${customer.id}/onus`, { serialNumber: serial })
                setSerial('')
              }, 'ONU didaftarkan')
            }
          >
            Daftarkan ONU
          </Button>
        </div>
      )}
    </div>
  )
}

/* ---------- Tab: Jalur (topologi hulu + anggaran redaman) ---------- */

/** Label lapangan tiap simpul jalur: RADIUS→BRAS→OLT→FDT→FAT→ONT. */
const HOP_LABEL: Record<string, string> = {
  CUSTOMER: 'ONT',
  ODP: 'FAT',
  ODC: 'FDT',
  PON_PORT: 'PON',
  OLT: 'OLT',
  SITE: 'POP',
  BRAS: 'BRAS',
}

/** Durasi ringkas dari detik uptime sesi, mis. "2j 5m" / "5m" / "40d". */
function fmtUptime(seconds: number | null): string {
  if (seconds == null) return ''
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  if (h > 0) return `${h}j ${m}m`
  if (m > 0) return `${m}m`
  return `${seconds}d`
}

function JalurTab({ trace, connected }: { trace: CustomerTrace | null; connected: boolean }) {
  // Pelanggan tanpa ONU terpasang tetap bisa punya identitas jaringan (akun PPPoE);
  // trace-nya sah selama ada, jadi cukup butuh trace — bukan status tersambung fisik.
  if (!trace) {
    return (
      <div className="card">
        <p className="muted" style={{ margin: 0 }}>Pelanggan ini belum tersambung ke jaringan.</p>
      </div>
    )
  }
  const bras = trace.bras
  return (
    <div className="stack" style={{ gap: '0.25rem' }}>
      <div className="card stack" style={{ gap: '0.75rem' }}>
        <SectionHead icon={<IconRoute size={16} />} title="Jalur ke hulu" />
        <div className="row" style={{ flexWrap: 'wrap', gap: '0.4rem' }}>
          {trace.hops.map((hop, index) => {
            // Hop BRAS diwarnai menurut keadaan sesi: hijau online, merah offline.
            const brasClass = hop.kind === 'BRAS' ? (hop.online ? 'badge good' : 'badge critical') : 'badge'
            return (
              <span key={`${hop.kind}-${index}`} className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
                <span
                  className={brasClass}
                  title={hop.detail ?? undefined}
                  style={{ display: 'inline-flex', flexDirection: 'column', gap: 1, alignItems: 'flex-start' }}
                >
                  <span>
                    {HOP_LABEL[hop.kind] ?? hop.kind}
                    {hop.code && ` ${hop.code}`}
                  </span>
                  {hop.detail && (
                    <span style={{ fontSize: '0.68rem', opacity: 0.8, fontWeight: 400 }}>{hop.detail}</span>
                  )}
                </span>
                {index < trace.hops.length - 1 && <span className="muted">→</span>}
              </span>
            )
          })}
        </div>
      </div>

      {/* Angka segmen optik & sesi BRAS dulu satu kalimat panjang bertitik-tengah; sebagai
          daftar properti tiap angka punya label sendiri, jadi bisa dibaca sekilas dan
          disalin tanpa memotong kalimat. */}
      {connected && (
        <div className="card stack" style={{ gap: '0.6rem' }}>
          <SectionHead icon={<IconGauge size={16} />} title="Segmen optik" />
          <dl className="essentials wide">
            <Ess label="ONU">
              <span className="tnum">{trace.onuSerialNumber}</span>
            </Ess>
            <Ess label="Port ODP">
              <span className="tnum">{trace.odpPortNumber}</span>
            </Ess>
            <Ess label="Redaman pasang">
              <span className="tnum" style={{ color: HEALTH_COLOR[trace.opticalHealth ?? 'UNKNOWN'] }}>
                {trace.installRxPowerDbm != null ? `${trace.installRxPowerDbm} dBm` : '—'}
              </span>
            </Ess>
            <Ess label="Rx hidup">
              {trace.liveRxPowerDbm != null && <span className="tnum">{fmtDbm(trace.liveRxPowerDbm)}</span>}
            </Ess>
            <Ess label="Jarak dari OLT">
              {trace.distanceMeters != null && <span className="tnum">{trace.distanceMeters} m</span>}
            </Ess>
            <Ess label="Perkiraan rugi">
              {trace.estimatedLossDb != null && (
                <span className="tnum">{trace.estimatedLossDb.toFixed(1)} dB</span>
              )}
            </Ess>
          </dl>
        </div>
      )}

      {bras && (
        <div className="card stack" style={{ gap: '0.6rem' }}>
          <SectionHead icon={<IconChart size={16} />} title="Sesi BRAS" />
          <dl className="essentials wide">
            <Ess label="Username">
              <span className="tnum">{bras.username}</span>
            </Ess>
            <Ess label="Status">
              <Badge tone={bras.online ? 'good' : 'critical'}>{bras.online ? 'Online' : 'Offline'}</Badge>
            </Ess>
            <Ess label="IP">{bras.framedIp && <span className="tnum">{bras.framedIp}</span>}</Ess>
            <Ess label="NAS">{bras.nasName}</Ess>
            <Ess label="Uptime">
              {bras.online && bras.uptimeSeconds != null && (
                <span className="tnum">{fmtUptime(bras.uptimeSeconds)}</span>
              )}
            </Ess>
            <Ess label="Profil laju">{bras.rateProfileName}</Ess>
          </dl>
        </div>
      )}
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
    <div className="card stack" style={{ gap: '0.6rem' }}>
      {/* Pemilih cakupan duduk di baris judul, bukan sebagai baris sendiri: ia menyetel
          seksi yang sama, jadi memisahkannya cuma menambah tinggi. */}
      <SectionHead
        icon={<IconUsers size={16} />}
        title="Tetangga sejalur"
        aside={
          <Segmented
            ariaLabel="Cakupan tetangga"
            value={scope}
            onChange={setScope}
            options={[
              { value: 'odp', label: `Se-ODP${odpCount ? ` (${odpCount})` : ''}` },
              { value: 'pon', label: `Se-PON${ponCount ? ` (${ponCount})` : ''}` },
            ]}
          />
        }
      />
      <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
        {scope === 'odp'
          ? 'Penghuni ODP yang sama — berbagi kabel drop & splitter ODP.'
          : 'Seluruh ODP di bawah PON port yang sama — berbagi port OLT (superset se-ODP).'}
      </p>
      <NeighborList items={scope === 'odp' ? neighbors?.sameOdp ?? null : neighbors?.samePonPort ?? null} showOdp={scope === 'pon'} />
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
            <StatusBadge status={n.liveStatus ?? n.onuStatus} label={onuStatusLabel(n.liveStatus ?? n.onuStatus)} />
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
        <SectionHead icon={<IconGauge size={16} />} title={`Bacaan hidup — ${onu.serialNumber}`} />
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
        <SectionHead icon={<IconChart size={16} />} title="Tren redaman 24 jam" />
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

/* ---------- Tab: Akses (identitas jaringan / akun PPPoE) ---------- */

/**
 * Kelola akun PPPoE (identitas jaringan) tiap langganan pelanggan — satu langganan
 * paling banyak satu akun. Paket & BRAS ditarik hanya untuk mengisi dropdown, dan
 * hanya bila operator boleh mengelola akun sekaligus melihat keduanya. Password
 * (secret) tak pernah dibaca balik: cuma bisa diisi saat provisi atau di-reset.
 */
function NetworkAccessTab({
  customerId,
  subscriptions,
}: {
  customerId: string
  subscriptions: SubscriptionView[]
}) {
  const { can } = useCan()
  const toast = useToast()
  const [accounts, setAccounts] = useState<SubscriberAccessView[] | null>(null)
  const [plans, setPlans] = useState<PlanView[]>([])
  const [nasList, setNasList] = useState<NasView[]>([])

  const canManage = can('bng.access.manage')
  const canPlanView = can('catalog.plan.view')
  const canNasView = can('bng.nas.view')
  const canSession = can('bng.session.view')
  const canIsolate = can('bng.access.isolate')
  const canReset = can('bng.session.reset')

  const load = useCallback(() => {
    void listAccessForCustomer(customerId)
      .then(setAccounts)
      .catch(() => setAccounts([]))
  }, [customerId])

  useEffect(() => load(), [load])

  // Paket (catalog) & BRAS hanya untuk mengisi dropdown provisi/ganti — ditarik bila
  // boleh mengelola akun sekaligus melihat paket; gagal senyap agar tab tetap tampil.
  useEffect(() => {
    if (!canManage || !canPlanView) return
    void listCatalogPlans()
      .then((all) => setPlans(all.filter((p) => p.active)))
      .catch(() => setPlans([]))
  }, [canManage, canPlanView])

  useEffect(() => {
    if (!canManage || !canNasView) return
    void listNas()
      .then(setNasList)
      .catch(() => setNasList([]))
  }, [canManage, canNasView])

  const run = async (action: () => Promise<unknown>, okMessage?: string) => {
    try {
      await action()
      load()
      if (okMessage) toast.success(okMessage)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
    }
  }

  if (accounts == null) {
    return (
      <div className="card" style={{ display: 'grid', placeItems: 'center', minHeight: 120 }}>
        <Spinner />
      </div>
    )
  }

  if (subscriptions.length === 0) {
    return (
      <div className="card">
        <p className="muted" style={{ margin: 0 }}>
          Pelanggan belum punya langganan — buat langganan dulu sebelum memberi akun PPPoE.
        </p>
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '0.75rem' }}>
      {subscriptions.map((sub) => (
        <SubscriptionAccessCard
          key={sub.id}
          sub={sub}
          account={accounts.find((a) => a.subscriptionId === sub.id) ?? null}
          plans={plans}
          nasList={nasList}
          canManage={canManage}
          canSession={canSession}
          canIsolate={canIsolate}
          canReset={canReset}
          run={run}
        />
      ))}
    </div>
  )
}

/** Dropdown paket — nama + kecepatan agar mudah dibedakan. */
function PlanField({
  plans,
  value,
  onChange,
}: {
  plans: PlanView[]
  value: string
  onChange: (v: string) => void
}) {
  return (
    <SelectField
      label="Paket"
      value={value}
      onChange={(_, data) => onChange(data.value)}
      style={{ flex: 1, minWidth: 160 }}
    >
      {plans.map((p) => (
        <option key={p.id} value={p.id}>
          {p.name} ({p.downMbps}/{p.upMbps} Mbps)
        </option>
      ))}
    </SelectField>
  )
}

/** Dropdown BRAS — opsional; "tanpa BRAS" berarti belum ditautkan. */
function NasField({
  nasList,
  value,
  onChange,
}: {
  nasList: NasView[]
  value: string
  onChange: (v: string) => void
}) {
  return (
    <SelectField
      label="BRAS"
      value={value}
      onChange={(_, data) => onChange(data.value)}
      style={{ flex: 1, minWidth: 160 }}
    >
      <option value="">— tanpa BRAS —</option>
      {nasList.map((n) => (
        <option key={n.id} value={n.id}>
          {n.name}
        </option>
      ))}
    </SelectField>
  )
}

/**
 * Satu langganan dan akun PPPoE-nya (0..1). Bila belum ada akun, tampilkan tombol
 * provisi (kecuali langganan sudah dihentikan atau belum ada paket). Bila sudah,
 * tampilkan identitasnya beserta aksi ganti paket/BRAS, reset password, dan hapus.
 */
function SubscriptionAccessCard({
  sub,
  account,
  plans,
  nasList,
  canManage,
  canSession,
  canIsolate,
  canReset,
  run,
}: {
  sub: SubscriptionView
  account: SubscriberAccessView | null
  plans: PlanView[]
  nasList: NasView[]
  canManage: boolean
  canSession: boolean
  canIsolate: boolean
  canReset: boolean
  run: (action: () => Promise<unknown>, okMessage?: string) => Promise<void>
}) {
  const confirm = useConfirm()
  const [form, setForm] = useState<'provision' | 'edit' | 'reset' | null>(null)
  const [username, setUsername] = useState('')
  const [secret, setSecret] = useState('')
  const [showSecret, setShowSecret] = useState(false)
  const [planId, setPlanId] = useState('')
  const [nasId, setNasId] = useState('')
  const [authType, setAuthType] = useState<ServiceType>('PPPOE')
  const [framedIp, setFramedIp] = useState('')

  // Tipe layanan yang boleh dipilih ditentukan paket yang dipilih (`serviceTypes`-nya);
  // tipe berbasis MAC (DHCP/Static) memakai MAC sebagai identitas + password, bukan login.
  const provisionPlan = plans.find((p) => p.id === planId) ?? null
  const availableTypes: ServiceType[] = provisionPlan?.serviceTypes ?? []
  const macBased = authType === 'DHCP' || authType === 'STATIC'

  const close = () => setForm(null)

  const openProvision = () => {
    setUsername('')
    setSecret('')
    setFramedIp('')
    setShowSecret(false)
    const first = plans[0]
    setPlanId(first?.id ?? '')
    setAuthType(first?.serviceTypes[0] ?? 'PPPOE')
    setNasId('')
    setForm('provision')
  }

  // Ganti paket saat provisi: bila paket baru tak melayani tipe terpilih, jatuhkan ke
  // tipe pertama yang dilayaninya agar dropdown & guard server tetap konsisten.
  const changeProvisionPlan = (id: string) => {
    setPlanId(id)
    const p = plans.find((x) => x.id === id)
    if (p && !p.serviceTypes.includes(authType)) setAuthType(p.serviceTypes[0] ?? 'PPPOE')
  }

  const openEdit = () => {
    if (!account) return
    setPlanId(account.planId)
    setNasId(account.nasId ?? '')
    setForm('edit')
  }

  const openReset = () => {
    setSecret('')
    setShowSecret(false)
    setForm('reset')
  }

  const submitProvision = () =>
    void run(async () => {
      await provisionAccess({
        subscriptionId: sub.id,
        username,
        // Tipe berbasis MAC tak pakai password (MAC jadi password di server).
        secret: macBased ? undefined : secret,
        planId,
        nasId: nasId || null,
        authType,
        framedIp: macBased ? framedIp || null : null,
      })
      close()
    }, 'Akun jaringan dibuat')

  // Validasi form provisi per-tipe: login butuh username+password; MAC butuh MAC (+ IP
  // wajib untuk Static). Paket wajib dipilih di semua kasus.
  const provisionInvalid =
    !username ||
    !planId ||
    (!macBased && !secret) ||
    (authType === 'STATIC' && !framedIp)

  const submitEdit = () => {
    if (!account) return
    void run(async () => {
      await updateAccess(account.id, { planId, nasId: nasId || null })
      close()
    }, 'Akun diperbarui')
  }

  const submitReset = () => {
    if (!account) return
    void run(async () => {
      await resetAccessSecret(account.id, secret)
      close()
    }, 'Password diganti')
  }

  const remove = () =>
    void (async () => {
      if (!account) return
      if (await confirm({ title: 'Hapus akun', message: `Hapus akun jaringan ${account.username}?`, confirmLabel: 'Hapus', danger: true })) {
        void run(() => deleteAccess(account.id), 'Akun dihapus')
      }
    })()

  // Kendali jaringan (jalur tulis ke BRAS): efeknya nyata pada sesi pelanggan —
  // memutus koneksi — jadi tiap aksi minta konfirmasi eksplisit lebih dulu.
  const isolate = () =>
    void (async () => {
      if (!account) return
      if (await confirm({ title: 'Isolir akun', message: `Isolir akun ${account.username}? Sesi PPPoE-nya akan diputus sekarang.`, confirmLabel: 'Isolir', danger: true })) {
        void run(() => isolateAccess(account.id), 'Akun diisolir')
      }
    })()

  const restore = () =>
    void (async () => {
      if (!account) return
      if (await confirm({ title: 'Pulihkan akun', message: `Pulihkan akun ${account.username} dari isolir?`, confirmLabel: 'Pulihkan' })) {
        void run(() => restoreAccess(account.id), 'Akun dipulihkan')
      }
    })()

  const resetLogin = () =>
    void (async () => {
      if (!account) return
      if (await confirm({ title: 'Reset Login', message: `Reset Login ${account.username}? Sesi diputus agar perangkat login ulang.`, confirmLabel: 'Reset Login' })) {
        void run(() => resetAccessLogin(account.id), 'Perintah Reset Login dikirim')
      }
    })()

  return (
    <div className="card stack" style={{ gap: '0.6rem' }}>
      <div className="spread" style={{ alignItems: 'center' }}>
        <span style={{ fontSize: '0.9rem', fontWeight: 600 }}>
          {sub.packageName} · {sub.bandwidthMbps} Mbps · Rp {sub.monthlyFee}
        </span>
        <StatusBadge status={sub.status} />
      </div>

      {account ? (
        <div className="stack" style={{ gap: '0.5rem' }}>
          <div className="row" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <span className="badge neutral tnum">{account.username}</span>
            <span className="badge">{SERVICE_TYPE_LABEL[account.authType]}</span>
            <span className="badge accent">{account.planName ?? 'paket tak dikenal'}</span>
            <span className="badge neutral">{account.nasName ?? 'tanpa BRAS'}</span>
            {account.framedIp && <span className="badge neutral tnum">IP {account.framedIp}</span>}
            <StatusBadge status={account.status} />
            {account.fupEnabled && (
              <span
                className={`badge ${account.fupThrottled ? 'critical' : 'neutral'} tnum`}
                title={account.fupThrottled ? 'Kuota FUP terlampaui — kecepatan diturunkan' : 'Pemakaian FUP siklus berjalan'}
              >
                FUP {account.periodUsageMb ?? 0}
                {account.fupQuotaMb != null ? ` / ${account.fupQuotaMb}` : ''} MB
                {account.fupThrottled ? ' · throttle' : ''}
              </span>
            )}
          </div>

          {form === null && (canManage || canReset || canIsolate) && (
            <div className="row" style={{ gap: '0.4rem', flexWrap: 'wrap' }}>
              {canManage && <Button onClick={openEdit}>Ganti paket / BRAS</Button>}
              {canManage && <Button onClick={openReset}>Reset password</Button>}
              {/* Reset Login: putus sesi agar CPE dial ulang — tak berlaku pada akun terhenti. */}
              {canReset && account.status !== 'TERMINATED' && (
                <Button onClick={resetLogin}>Reset Login</Button>
              )}
              {/* Isolir/Pulihkan: saling meniadakan sesuai status akun. */}
              {canIsolate && account.status === 'ACTIVE' && (
                <Button variant="danger" onClick={isolate}>
                  Isolir
                </Button>
              )}
              {canIsolate && account.status === 'ISOLATED' && (
                <Button variant="primary" onClick={restore}>
                  Pulihkan
                </Button>
              )}
              {canManage && (
                <Button variant="danger" onClick={remove}>
                  Hapus
                </Button>
              )}
            </div>
          )}

          {form === 'edit' && (
            <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
              <PlanField plans={plans} value={planId} onChange={setPlanId} />
              <NasField nasList={nasList} value={nasId} onChange={setNasId} />
              <Button variant="primary" onClick={submitEdit} disabled={!planId}>
                Simpan
              </Button>
              <Button onClick={close}>Batal</Button>
            </div>
          )}

          {form === 'reset' && (
            <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
              <TextField
                label="Password baru"
                type={showSecret ? 'text' : 'password'}
                value={secret}
                onChange={(_, data) => setSecret(data.value)}
                style={{ flex: 2, minWidth: 180 }}
              />
              <Button onClick={() => setShowSecret((v) => !v)}>{showSecret ? 'Sembunyikan' : 'Lihat'}</Button>
              <Button variant="primary" onClick={submitReset} disabled={!secret}>
                Simpan
              </Button>
              <Button onClick={close}>Batal</Button>
            </div>
          )}

          {canSession && <BrasSessionPanel accessId={account.id} />}
        </div>
      ) : sub.status === 'TERMINATED' ? (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Langganan sudah dihentikan — tak bisa diberi akun jaringan.
        </p>
      ) : !canManage ? (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada akun jaringan untuk langganan ini.</p>
      ) : plans.length === 0 ? (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Belum ada akun jaringan. Buat paket dulu di menu <strong>Paket Internet</strong> sebelum memprovisi akun.
        </p>
      ) : form === 'provision' ? (
        <div className="stack" style={{ gap: '0.5rem' }}>
          <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
            <PlanField plans={plans} value={planId} onChange={changeProvisionPlan} />
            <SelectField
              label="Tipe layanan"
              value={authType}
              onChange={(_, data) => setAuthType(data.value as ServiceType)}
              disabled={availableTypes.length <= 1}
              style={{ flex: 1, minWidth: 140 }}
            >
              {availableTypes.map((t) => (
                <option key={t} value={t}>
                  {SERVICE_TYPE_LABEL[t]}
                </option>
              ))}
            </SelectField>
          </div>

          {macBased ? (
            <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
              <TextField
                label="MAC Address"
                value={username}
                onChange={(_, data) => setUsername(data.value)}
                placeholder="AA:BB:CC:DD:EE:FF"
                style={{ flex: 2, minWidth: 180 }}
              />
              <TextField
                label={`Reserved IP${authType === 'STATIC' ? '' : ' (opsional)'}`}
                value={framedIp}
                onChange={(_, data) => setFramedIp(data.value)}
                placeholder="100.64.0.10"
                style={{ flex: 2, minWidth: 160 }}
              />
            </div>
          ) : (
            <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
              <TextField
                label="Username"
                value={username}
                onChange={(_, data) => setUsername(data.value)}
                placeholder="pelanggan@isp"
                style={{ flex: 2, minWidth: 160 }}
              />
              <TextField
                label="Password"
                type={showSecret ? 'text' : 'password'}
                value={secret}
                onChange={(_, data) => setSecret(data.value)}
                style={{ flex: 2, minWidth: 160 }}
              />
              <Button onClick={() => setShowSecret((v) => !v)}>{showSecret ? 'Sembunyikan' : 'Lihat'}</Button>
            </div>
          )}

          <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
            <NasField nasList={nasList} value={nasId} onChange={setNasId} />
            <Button variant="primary" onClick={submitProvision} disabled={provisionInvalid}>
              Provisi
            </Button>
            <Button onClick={close}>Batal</Button>
          </div>
          <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
            {macBased
              ? 'DHCP/Static memakai MAC sebagai identitas sekaligus password (konvensi use-radius). Static butuh IP yang direservasi.'
              : 'Password disimpan terenkripsi dan tidak pernah ditampilkan kembali — hanya bisa di-reset.'}
          </p>
        </div>
      ) : (
        <div className="spread" style={{ alignItems: 'center' }}>
          <span className="muted" style={{ fontSize: '0.85rem' }}>Belum ada akun jaringan untuk langganan ini.</span>
          <Button variant="primary" onClick={openProvision}>
            Provisi akun
          </Button>
        </div>
      )}
    </div>
  )
}

/**
 * Panel "B-ras Check": keadaan sesi PPPoE terkini akun (online, IP framed, BRAS,
 * uptime, MAC) plus tren trafik Down/Up. Murni baca — datanya dari laporan collector,
 * panel tak pernah menyentuh BRAS. Sesi ditarik di sini; tren didelegasikan ke
 * [SubscriberTrafficPanel] (satu implementasi dipakai bersama tab Trafik) dan keduanya
 * toleran gagal agar satu yang kosong tak menutup yang lain.
 */
function BrasSessionPanel({ accessId }: { accessId: string }) {
  const [session, setSession] = useState<BrasSessionView | null>(null)

  useEffect(() => {
    let alive = true
    void getBrasSession(accessId)
      .then((s) => alive && setSession(s))
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [accessId])

  if (!session) {
    return <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>Memuat sesi…</p>
  }

  const neverSeen = session.lastSeenAt == null
  return (
    <div className="stack" style={{ gap: '0.6rem', borderTop: '1px solid var(--border)', paddingTop: '0.6rem' }}>
      <div className="row" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
        <span
          className="badge"
          title={session.online ? 'BRAS melaporkan sesi aktif' : 'BRAS tak melaporkan sesi aktif'}
          style={{ color: session.online ? 'var(--good-ink)' : 'var(--muted)', fontWeight: 600 }}
        >
          {session.online ? '● online' : '○ offline'}
        </span>
        {session.framedIp && (
          <span className="badge neutral tnum" title="IP yang diberikan ke pelanggan">
            {session.framedIp}
          </span>
        )}
        {session.nasName && (
          <span className="badge neutral" title="BRAS yang menaungi sesi">{session.nasName}</span>
        )}
        {session.online && session.uptimeSeconds != null && (
          <span className="badge neutral" title="Lama sesi berjalan">
            uptime {humanizeDuration(session.uptimeSeconds * 1000)}
          </span>
        )}
      </div>

      {neverSeen ? (
        <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
          Akun ini belum pernah terpantau BRAS — pastikan BRAS-nya terhubung ke collector.
        </p>
      ) : (
        <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
          {session.callingStationId && `MAC ${session.callingStationId} · `}
          {session.nasIp && `NAS ${session.nasIp} · `}
          terpantau terakhir {fmtInstant(session.lastSeenAt)}
        </p>
      )}

      <SubscriberTrafficPanel accessId={accessId} />
    </div>
  )
}

/* ---------- Tab: Trafik (tren bandwidth per akun jaringan) ---------- */

/**
 * Tab Trafik: satu [SubscriberTrafficPanel] per akun jaringan pelanggan, masing-masing
 * dengan pemilih rentang + ringkasan throughput/pemakaian sendiri. Menarik daftar akun
 * lewat [listAccessForCustomer] (jalur baca yang sama dengan tab Akses); toleran gagal.
 * Digerbang izin `bng.session.view` di pemanggil.
 */
function TrafikTab({ customerId }: { customerId: string }) {
  const [accounts, setAccounts] = useState<SubscriberAccessView[] | null>(null)

  useEffect(() => {
    let alive = true
    void listAccessForCustomer(customerId)
      .then((a) => alive && setAccounts(a))
      .catch(() => alive && setAccounts([]))
    return () => {
      alive = false
    }
  }, [customerId])

  if (accounts == null) {
    return (
      <div className="card" style={{ display: 'grid', placeItems: 'center', minHeight: 120 }}>
        <Spinner />
      </div>
    )
  }

  if (accounts.length === 0) {
    return (
      <div className="card">
        <p className="muted" style={{ margin: 0 }}>
          Belum ada akun jaringan untuk pelanggan ini — provisi akun dulu di tab <strong>Akses</strong>.
        </p>
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '0.75rem' }}>
      {accounts.map((account) => (
        <div key={account.id} className="card stack" style={{ gap: '0.6rem' }}>
          <div className="row" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <span className="badge neutral tnum">{account.username}</span>
            <span className="badge">{SERVICE_TYPE_LABEL[account.authType]}</span>
            <span className="badge accent">{account.planName ?? 'paket tak dikenal'}</span>
            <span className="badge neutral">{account.nasName ?? 'tanpa BRAS'}</span>
            <StatusBadge status={account.status} />
          </div>
          <SubscriberTrafficPanel accessId={account.id} />
        </div>
      ))}
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
        <Segmented
          ariaLabel="Pilih perangkat CPE"
          value={selected ?? ''}
          onChange={setSelected}
          options={devices.map((d) => ({ value: d.id, label: d.model ?? d.serialNumber }))}
        />
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
              <div className="section-head">
                <span className="ico" aria-hidden>
                  <IconMonitor size={16} />
                </span>
                <h3 className="section-title">{d.model ?? d.productClass ?? 'CPE'}</h3>
              </div>
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
            <Button variant="danger" onClick={() => void reboot()} disabled={rebooting}>
              {rebooting ? 'Mengirim…' : 'Reboot'}
            </Button>
          )}
        </div>
        <dl className="essentials wide">
          <Ess label="Inform terakhir">{fmtInstant(d.lastInformAt)}</Ess>
          <Ess label="OUI">{d.oui}</Ess>
          <Ess label="Kelas produk">{d.productClass}</Ess>
          <Ess label="GenieACS ID">
            <span className="tnum">{d.genieacsId}</span>
          </Ess>
        </dl>
      </div>

      {canWifiView && (
        <div className="card stack" style={{ gap: '0.75rem' }}>
          <SectionHead icon={<IconWifi size={16} />} title="WiFi" />
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
          <SectionHead icon={<IconMonitor size={16} />} title="Perangkat tersambung" />
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
      <SectionHead icon={<IconFlask size={16} />} title="Diagnostik" />
      {!online && (
        <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
          Perangkat sedang offline — diagnostik bisa gagal atau menunggu lama.
        </p>
      )}
      <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <TextField
          label="Sasaran ping"
          value={host}
          placeholder="kosong = bawaan (mis. 8.8.8.8)"
          onChange={(_, data) => setHost(data.value)}
          style={{ flex: 2, minWidth: 160 }}
        />
        <Button onClick={() => void doPing()} disabled={busy}>
          {running === 'ping' ? 'Menguji…' : 'Ping'}
        </Button>
        <Button onClick={() => void doSpeed('DOWNLOAD')} disabled={busy}>
          {running === 'DOWNLOAD' ? 'Menguji…' : 'Uji unduh'}
        </Button>
        <Button onClick={() => void doSpeed('UPLOAD')} disabled={busy}>
          {running === 'UPLOAD' ? 'Menguji…' : 'Uji unggah'}
        </Button>
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
  const confirm = useConfirm()
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
    if (!(await confirm({ title: 'Pasang firmware', message: `Pasang firmware ${file.name}${versi}? Perangkat akan reboot saat memasang.`, confirmLabel: 'Pasang' }))) {
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
      <SectionHead
        icon={<IconUpload size={16} />}
        title="Firmware"
        aside={
          <span className="muted" style={{ fontSize: '0.82rem' }}>
            terpasang: {currentVersion ?? '—'}
          </span>
        }
      />
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
              <Button onClick={() => void upgrade(f)} disabled={busy}>
                {pushing === f.name ? 'Mengirim…' : 'Pasang'}
              </Button>
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
  const confirm = useConfirm()
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
      !(await confirm({
        title: 'Reset pabrik',
        message:
          'Reset pabrik mengembalikan SEMUA setelan perangkat (WiFi, dll) ke bawaan dan memutus koneksi pelanggan. Lanjutkan?',
        confirmLabel: 'Reset pabrik',
        danger: true,
      }))
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
      <SectionHead
        icon={<IconShield size={16} />}
        title="ACS & pemeliharaan"
        aside={
          acs != null && (
            <span
              className="badge"
              title={acs.message}
              style={{ color: acs.connected ? 'var(--good-ink)' : 'var(--muted)', fontWeight: 600 }}
            >
              {acs.connected ? 'ACS Connect' : 'Not Connect'}
            </span>
          )
        }
      />
      <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
        Refresh memaksa perangkat menghubungi ACS sekarang; reset pabrik mengembalikan setelan ke bawaan.
      </p>
      <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap' }}>
        <Button onClick={() => void refresh()} disabled={busy}>
          {refreshing ? 'Menghubungi…' : 'Refresh ACS'}
        </Button>
        <Button variant="danger" onClick={() => void factoryReset()} disabled={busy}>
          {resetting ? 'Mengirim…' : 'Reset pabrik'}
        </Button>
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
          <TextField
            label="SSID"
            value={ssid}
            onChange={(_, data) => setSsid(data.value)}
            style={{ flex: 2, minWidth: 160 }}
          />
          <TextField
            label="Password"
            type={showPass ? 'text' : 'password'}
            value={passphrase}
            placeholder={wifi.passphrase == null ? 'tersembunyi — isi untuk mengganti' : ''}
            onChange={(_, data) => setPassphrase(data.value)}
            style={{ flex: 2, minWidth: 160 }}
          />
          <Button onClick={() => setShowPass((v) => !v)}>{showPass ? 'Sembunyikan' : 'Lihat'}</Button>
          <Button variant="primary" onClick={() => void save()} disabled={!dirty || saving}>
            {saving ? 'Menyimpan…' : 'Simpan'}
          </Button>
        </div>
      ) : (
        <dl className="essentials wide">
          <Ess label="SSID">{wifi.ssid}</Ess>
          <Ess label="Password">{wifi.passphrase ?? <span className="muted">tersembunyi</span>}</Ess>
        </dl>
      )}
    </div>
  )
}

/** Jejak aksi terakhir ke perangkat — reboot / ubah WiFi, berhasil atau gagal. */
function CpeActionLog({ actions }: { actions: CpeActionView[] }) {
  if (actions.length === 0) return null
  return (
    <div className="card stack" style={{ gap: '0.5rem' }}>
      <SectionHead icon={<IconAudit size={16} />} title="Jejak aksi" />
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

/* ---------- Tab: Tagihan & tunggakan (module billing) ---------- */

/** Rupiah ringkas dari nilai numerik, mis. "Rp 150.000". */
function fmtRupiah(n: number): string {
  return `Rp ${n.toLocaleString('id-ID')}`
}

/** LocalDate "YYYY-MM-DD" → "15 Jul 2026"; "—" bila kosong. */
function fmtDate(localDate: string | null): string {
  if (!localDate) return '—'
  const d = new Date(`${localDate}T00:00:00`)
  return Number.isNaN(d.getTime())
    ? localDate
    : d.toLocaleDateString('id-ID', { day: '2-digit', month: 'short', year: 'numeric' })
}

/** Tanggal lokal hari ini "YYYY-MM-DD" untuk membandingkan jatuh tempo (bandingkan leksikografis). */
function todayLocalDate(): string {
  const n = new Date()
  return `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, '0')}-${String(n.getDate()).padStart(2, '0')}`
}

const INVOICE_TONE: Record<InvoiceView['status'], 'good' | 'warning' | 'critical' | 'neutral'> = {
  PAID: 'good',
  ISSUED: 'warning',
  OVERDUE: 'critical',
  VOID: 'neutral',
}

const INVOICE_LABEL: Record<InvoiceView['status'], string> = {
  PAID: 'Lunas',
  ISSUED: 'Terbit',
  OVERDUE: 'Jatuh tempo',
  VOID: 'Batal',
}

/** Tagihan menunggak: berstatus OVERDUE, atau ISSUED yang sudah lewat jatuh tempo. */
function isOutstanding(inv: InvoiceView, today: string): boolean {
  return inv.status === 'OVERDUE' || (inv.status === 'ISSUED' && inv.dueDate < today)
}

/**
 * Tagihan & tunggakan pelanggan. Daftar tagihan (semua status) dari module billing,
 * plus ringkas tunggakan. Nilai tunggakan diambil dari agregat 360° yang **dihitung
 * server** (satu sumber kebenaran, sama dengan strip Ringkasan); bila agregat tak
 * tersedia (gagal muat) jatuh balik ke hitung sisi klien. Digerbang `billing.invoice.view`.
 */
function TagihanTab({ customerId, billing }: { customerId: string; billing: Sub360BillingSummary | null }) {
  const { can } = useCan()
  const { user } = useAuth()
  const toast = useToast()
  const [invoices, setInvoices] = useState<InvoiceView[] | null>(null)
  const canManage = can('billing.invoice.manage')

  // Bayar tak lagi terjadi di modal sini: satu-satunya jalur adalah halaman bayar publik, supaya
  // tautan yang sama bisa dipakai operator (buka tab) maupun pelanggan (kirim via WhatsApp).
  const shareLink = (inv: InvoiceView) => payLink(user?.tenantSlug ?? '', inv.id)

  const copyLink = async (inv: InvoiceView) => {
    try {
      await navigator.clipboard.writeText(shareLink(inv))
      toast.success('Link bayar disalin')
    } catch {
      toast.error('Gagal menyalin link — buka tautannya lalu salin dari bilah alamat')
    }
  }

  useEffect(() => {
    let alive = true
    void listInvoicesForCustomer(customerId)
      .then((list) => alive && setInvoices(list))
      .catch(() => alive && setInvoices([]))
    return () => {
      alive = false
    }
  }, [customerId])

  if (invoices == null) {
    return (
      <div className="card" style={{ display: 'grid', placeItems: 'center', minHeight: 120 }}>
        <Spinner />
      </div>
    )
  }

  if (invoices.length === 0) {
    return (
      <div className="card">
        <p className="muted" style={{ margin: 0 }}>Belum ada tagihan untuk pelanggan ini.</p>
      </div>
    )
  }

  const today = todayLocalDate()
  const tunggakan =
    billing != null
      ? Number(billing.outstandingAmount)
      : invoices.filter((inv) => isOutstanding(inv, today)).reduce((s, inv) => s + Number(inv.amount), 0)

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="card stack" style={{ gap: '0.4rem' }}>
        <SectionHead
          icon={<IconReceipt size={16} />}
          title="Tunggakan"
          aside={
            <span
              className="tnum"
              style={{ fontWeight: 600, color: tunggakan > 0 ? 'var(--critical-ink)' : 'var(--good-ink)' }}
            >
              {fmtRupiah(tunggakan)}
            </span>
          }
        />
        <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
          Jumlah tagihan jatuh tempo yang belum dibayar.
        </p>
      </div>

      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Nomor</th>
              <th>Periode</th>
              <th>Jatuh tempo</th>
              <th style={{ textAlign: 'right' }}>Jumlah</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {invoices.map((inv) => (
              <tr key={inv.id}>
                <td className="tnum">{inv.number}</td>
                <td className="muted">
                  {fmtDate(inv.periodStart)} – {fmtDate(inv.periodEnd)}
                </td>
                <td className="muted">{fmtDate(inv.dueDate)}</td>
                <td className="tnum" style={{ textAlign: 'right' }}>
                  {fmtRupiah(Number(inv.amount))}
                  {inv.prorated && (
                    <span
                      className="badge neutral"
                      style={{ marginLeft: '0.4rem' }}
                      title={
                        inv.proratedDays != null
                          ? `Diprorata ${inv.proratedDays} hari (aktivasi tengah periode)`
                          : 'Diprorata (aktivasi tengah periode)'
                      }
                    >
                      prorata{inv.proratedDays != null ? ` ${inv.proratedDays}h` : ''}
                    </span>
                  )}
                  {Number(inv.taxAmount) > 0 && (
                    <div className="muted" style={{ fontSize: '0.72rem' }}>
                      termasuk PPN {fmtRupiah(Number(inv.taxAmount))}
                    </div>
                  )}
                </td>
                <td>
                  <Badge tone={INVOICE_TONE[inv.status]}>{INVOICE_LABEL[inv.status]}</Badge>
                  {/* Halaman bayar publik melayani KEDUA mode gateway (VA/QRIS Pivot maupun
                      instruksi transfer manual), jadi tak perlu lagi dibedakan di sini. */}
                  {canManage && (inv.status === 'ISSUED' || inv.status === 'OVERDUE') && (
                    <>
                      <Button
                        type="button"
                        variant="subtle"
                        onClick={() => window.open(shareLink(inv), '_blank', 'noopener')}
                        style={{ marginLeft: '0.5rem', fontSize: '0.8rem' }}
                      >
                        bayar ↗
                      </Button>
                      <Button
                        type="button"
                        variant="subtle"
                        onClick={() => void copyLink(inv)}
                        style={{ marginLeft: '0.3rem', fontSize: '0.8rem' }}
                      >
                        salin link
                      </Button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
        "Bayar ↗" membuka halaman bayar publik tagihan itu — tautan yang sama bisa disalin dan
        dikirim ke pelanggan lewat WhatsApp, lengkap dengan instruksi VA/QRIS atau transfer manual.
      </p>
    </div>
  )
}

/* ---------- Tab: Tiket insiden & Work Order (module incident + workorder) ---------- */

const WO_STATUS_TONE: Record<WorkOrderStatus, 'neutral' | 'accent' | 'warning' | 'good'> = {
  DRAFT: 'neutral',
  ASSIGNED: 'accent',
  IN_PROGRESS: 'warning',
  DONE: 'good',
  CANCELLED: 'neutral',
}

const WO_STATUS_LABEL: Record<WorkOrderStatus, string> = {
  DRAFT: 'Draft',
  ASSIGNED: 'Ditugaskan',
  IN_PROGRESS: 'Dikerjakan',
  DONE: 'Selesai',
  CANCELLED: 'Dibatalkan',
}

/**
 * Insiden aktif yang berdampak (module incident) + riwayat work order penuh
 * (module workorder). Tiap bagian ditarik & digerbang izin modulnya sendiri;
 * WO yang lahir dari insiden ditandai agar tautan silangnya terlihat. Riwayat
 * insiden *resolved* sengaja tak ditampilkan — diwakili riwayat work order.
 */
function TiketWoTab({
  customerId,
  canIncident,
  canWorkorder,
}: {
  customerId: string
  canIncident: boolean
  canWorkorder: boolean
}) {
  const [incidents, setIncidents] = useState<IncidentView[] | null>(null)
  const [orders, setOrders] = useState<WorkOrderView[] | null>(null)

  useEffect(() => {
    if (!canIncident) return
    let alive = true
    void listIncidentsForCustomer(customerId)
      .then((list) => alive && setIncidents(list))
      .catch(() => alive && setIncidents([]))
    return () => {
      alive = false
    }
  }, [customerId, canIncident])

  useEffect(() => {
    if (!canWorkorder) return
    let alive = true
    void listWorkOrdersForCustomer(customerId)
      .then((list) => alive && setOrders(list))
      .catch(() => alive && setOrders([]))
    return () => {
      alive = false
    }
  }, [customerId, canWorkorder])

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      {canIncident && (
        <div className="card stack" style={{ gap: '0.6rem' }}>
          <SectionHead
            icon={<IconAlert size={16} />}
            title="Insiden aktif"
            aside={<span className="muted" style={{ fontSize: '0.8rem' }}>gangguan yang sedang berdampak</span>}
          />
          {incidents == null ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Memuat insiden…</p>
          ) : incidents.length === 0 ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Tak ada insiden aktif yang berdampak.</p>
          ) : (
            incidents.map((inc) => (
              <div key={inc.id} className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
                <div className="stack" style={{ gap: 2, minWidth: 0 }}>
                  <span style={{ fontSize: '0.88rem', fontWeight: 600 }}>{inc.title}</span>
                  <span className="muted" style={{ fontSize: '0.78rem' }}>
                    {inc.rootLabel} · dibuka {fmtInstant(inc.openedAt)}
                  </span>
                </div>
                <div className="row" style={{ gap: '0.35rem' }}>
                  <StatusBadge status={inc.severity} />
                  <StatusBadge status={inc.status} />
                </div>
              </div>
            ))
          )}
        </div>
      )}

      {canWorkorder && (
        <div className="card stack" style={{ gap: '0.6rem' }}>
          <SectionHead
            icon={<IconWorkOrder size={16} />}
            title="Riwayat work order"
            aside={<span className="muted" style={{ fontSize: '0.8rem' }}>semua status</span>}
          />
          {orders == null ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Memuat work order…</p>
          ) : orders.length === 0 ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada work order untuk pelanggan ini.</p>
          ) : (
            orders.map((wo) => (
              <div key={wo.id} className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
                <div className="stack" style={{ gap: 2, minWidth: 0 }}>
                  <span style={{ fontSize: '0.88rem' }}>
                    <span className="tnum" style={{ fontWeight: 600 }}>{wo.code}</span> · {wo.title}
                    {wo.incidentId && (
                      <span className="badge accent" style={{ marginLeft: '0.4rem' }}>dari insiden</span>
                    )}
                  </span>
                  <span className="muted" style={{ fontSize: '0.78rem' }}>
                    {wo.type} · dibuat {fmtInstant(wo.createdAt)}
                    {wo.assignees.length > 0 &&
                      ` · ${wo.assignees.map((a) => a.name ?? '—').join(', ')}`}
                  </span>
                </div>
                <Badge tone={WO_STATUS_TONE[wo.status]}>{WO_STATUS_LABEL[wo.status]}</Badge>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  )
}

/* ---------- Tab: Timeline aktivitas gabungan (gabung sisi klien) ---------- */

type TimelineTone = 'good' | 'warning' | 'critical' | 'neutral' | 'accent'

const TONE_INK: Record<TimelineTone, string> = {
  good: 'var(--good-ink)',
  warning: 'var(--warning-ink)',
  critical: 'var(--critical-ink)',
  accent: 'var(--accent)',
  neutral: 'var(--muted)',
}

/**
 * Riwayat aktivitas satu pelanggan sebagai satu garis waktu — gabungan murni sisi
 * klien dari tagihan, insiden, work order, langganan, dan ONU yang sudah ditarik
 * masing-masing endpoint (tanpa endpoint timeline khusus). Tiap sumber ikut hanya
 * bila izin modulnya dimiliki; langganan & ONU selalu ada (dari data pelanggan).
 */
function TimelineTab({
  customerId,
  customer,
  canBilling,
  canIncident,
  canWorkorder,
}: {
  customerId: string
  customer: CustomerView
  canBilling: boolean
  canIncident: boolean
  canWorkorder: boolean
}) {
  const [invoices, setInvoices] = useState<InvoiceView[]>([])
  const [incidents, setIncidents] = useState<IncidentView[]>([])
  const [orders, setOrders] = useState<WorkOrderView[]>([])

  useEffect(() => {
    if (!canBilling) return
    let alive = true
    void listInvoicesForCustomer(customerId)
      .then((l) => alive && setInvoices(l))
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [customerId, canBilling])

  useEffect(() => {
    if (!canIncident) return
    let alive = true
    void listIncidentsForCustomer(customerId)
      .then((l) => alive && setIncidents(l))
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [customerId, canIncident])

  useEffect(() => {
    if (!canWorkorder) return
    let alive = true
    void listWorkOrdersForCustomer(customerId)
      .then((l) => alive && setOrders(l))
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [customerId, canWorkorder])

  const entries: Array<{ at: string; label: string; tone: TimelineTone }> = []
  const push = (at: string | null, label: string, tone: TimelineTone) => {
    if (at) entries.push({ at, label, tone })
  }

  customer.subscriptions.forEach((sub) => {
    push(sub.activatedAt, `Langganan ${sub.packageName} diaktifkan`, 'good')
    push(sub.terminatedAt, `Langganan ${sub.packageName} dihentikan`, 'critical')
  })
  customer.onus.forEach((onu) => push(onu.installedAt, `ONU ${onu.serialNumber} dipasang`, 'accent'))
  invoices.forEach((inv) => {
    push(inv.issuedAt, `Tagihan ${inv.number} terbit`, 'warning')
    push(inv.paidAt, `Tagihan ${inv.number} dibayar`, 'good')
  })
  incidents.forEach((inc) => push(inc.openedAt, `Insiden ${inc.title} dibuka`, 'critical'))
  orders.forEach((wo) => {
    push(wo.createdAt, `WO ${wo.code} dibuat`, 'accent')
    push(wo.completedAt, `WO ${wo.code} selesai`, 'good')
  })

  // Terbaru lebih dulu; timestamp ISO 8601 aman dibandingkan leksikografis.
  entries.sort((a, b) => b.at.localeCompare(a.at))

  if (entries.length === 0) {
    return (
      <div className="card">
        <p className="muted" style={{ margin: 0 }}>Belum ada aktivitas yang tercatat.</p>
      </div>
    )
  }

  return (
    <div className="card stack" style={{ gap: '0.5rem' }}>
      <SectionHead icon={<IconAudit size={16} />} title="Aktivitas terbaru" />
      <div className="stack" style={{ gap: '0.4rem' }}>
        {entries.map((e, i) => (
          <div key={`${e.at}-${i}`} className="row" style={{ gap: '0.6rem', alignItems: 'center' }}>
            <span className="muted tnum" style={{ fontSize: '0.78rem', minWidth: 92 }}>
              {fmtInstant(e.at)}
            </span>
            <span
              aria-hidden
              style={{ width: 8, height: 8, borderRadius: '50%', background: TONE_INK[e.tone], flexShrink: 0 }}
            />
            <span style={{ fontSize: '0.85rem' }}>{e.label}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
