import { CustomerMigrationManagement } from './onboarding/CustomerMigrationManagement'
import { CreationSummary, useCreationReview } from '@/components/organisms/CreationReview'
import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Text } from '@fluentui/react-components'
import { useNavigate } from 'react-router-dom'
import { RefreshCw, Pencil, KeyRound, Unplug, Ban, Trash2, Play } from 'lucide-react'
import { api, ApiError } from '../api/client'
import { CustomerOnuPanel } from '@/components/organisms/customer/CustomerOnuPanel'
import { CustomerAssetPanel } from '@/components/organisms/customer/CustomerAssetPanel'
import type {
  CustomerTrace,
  CustomerView,
  NeighborView,
  SubscriberNeighbors,
} from '../api/network'
import { customerStatusLabel, hasCustomerLocation, onuStatusLabel } from '../api/network'
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
import { mapFocusState, type MapFocusState } from '@/map/mapFocus'
import { useCan } from '../auth/useCan'
import { useAuth } from '../auth/useAuth'
import { Badge, Button, EmptyState, Segmented, SelectField, Spinner, StatusBadge, TextField } from '@/components/atoms'
import { CommandBar, Ess, type CommandAction } from '@/components/molecules'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { ResourceNavigation, type ResourceNavigationItem } from '@/components/organisms/ResourceNavigation'
import * as NavIcons from '@/components/molecules/navigationIcons'
import './customer-detail.css'
import { useConfirm, useToast } from '@/system'
import { OpticalChart } from '@/components/atoms'
import { SubscriberTrafficPanel } from '@/components/organisms'
import {
  IconAlert,
  IconCustomers,
  IconMap,
} from '@/components/atoms/icons'
import type { SubscriptionView } from '../api/network'
import { listPlans as listCatalogPlans, SERVICE_TYPE_LABEL, type PlanView, type ServiceType } from '../api/catalog'
import { listInvoicesForCustomer, type InvoiceView } from '../api/billing'
import { payLink } from '../api/publicPayment'
import { listIncidentsForCustomer, type IncidentView } from '../api/incident'
import { listWorkOrdersForCustomer, type WorkOrderStatus, type WorkOrderView } from '../api/workorder'
import { getSubscriber360, type Sub360BillingSummary, type Subscriber360View } from '../api/subscriber360'
import { OntAcsSettingsCard, PortalCredentialCard } from '@/components/organisms'
import { Blade, DataTable } from '@/components/organisms'

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
  return customerId ? <CustomerResource key={customerId} customerId={customerId} onClose={onClose} onShowOnMap={onShowOnMap} /> : null
}

function CustomerResource({ customerId, onClose, onShowOnMap }: { customerId: string; onClose: () => void; onShowOnMap?: (focus: MapFocusState) => void }) {
  const [title, setTitle] = useState('Pelanggan')
  return <ResourceForm title={title} readOnly onClose={onClose} onBack={() => {}} className="customer-detail-resource">
    <CustomerDetailPage customerId={customerId} onShowOnMap={onShowOnMap} onTitleChange={setTitle} />
  </ResourceForm>
}

/** Warna kesehatan optik selaras token status. */
const HEALTH_COLOR: Record<string, string> = {
  GOOD: 'var(--good-ink)',
  WARNING: 'var(--warning-ink)',
  CRITICAL: 'var(--critical-ink)',
  UNKNOWN: 'var(--muted)',
}

type Tab = 'ringkasan' | 'langganan' | 'perangkat' | 'portal' | 'jalur' | 'tetangga' | 'metrik' | 'akses' | 'trafik' | 'cpe' | 'tagihan' | 'tiket' | 'timeline'

/**
 * Detail satu pelanggan bergaya blade Azure: kepala + command bar, blok "Essentials"
 * yang bisa dilipat, lalu tab-tab (Jalur, Tetangga, Metrik, Akses, Trafik, CPE,
 * Tagihan, Tiket & WO, Timeline). Dipakai sebagai isi flyout — baik dari daftar
 * pelanggan maupun dari panel telusur di halaman Peta.
 */
export function CustomerDetailPage({
  customerId,
  onShowOnMap,
  onTitleChange,
}: {
  customerId: string
  onTitleChange?: (title: string) => void
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
  // Detail pelanggan tampil sebagai flyout (dibuka dari daftar), bukan rute
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
  const [trace, setTrace] = useState<CustomerTrace | null>(null)
  const [neighbors, setNeighbors] = useState<SubscriberNeighbors | null>(null)
  const [metrics, setMetrics] = useState<OnuMetricView[]>([])
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [tab, setTab] = useState<Tab>('ringkasan')
  const [sub360, setSub360] = useState<Subscriber360View | null>(null)

  const reload = useCallback(async () => {
    try {
      const value = await api.get<CustomerView>(`/api/customers/${id}`)
      setCustomer(value); setNotFound(false); setLoadError('')
      onTitleChange?.(value.name)
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) setNotFound(true)
      else setLoadError(err instanceof ApiError ? err.message : 'Gagal memuat pelanggan. Coba lagi.')
    } finally {
      setLoading(false)
    }
  }, [id, onTitleChange])

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
  const canMetric = can('monitoring.metric.view')
  const canAccess = can('bng.access.view')
  // Tab Trafik digerbang izin baca sesi/trafik (sama dengan panel B-ras Check).
  const canTraffic = can('bng.session.view')
  const canCpe = can('cpe.device.view')
  // Facet sisi-bisnis Subscriber-360: tiap tab digerbang izin modul pemiliknya.
  const canBilling = can('billing.invoice.view')
  const canIncident = can('incident.ticket.view')
  const canWorkorder = can('workorder.order.view')

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

  if (!customer && loadError) return <div role="alert"><p>{loadError}</p><Button onClick={() => void reload()}>Coba lagi</Button></div>

  if (notFound || !customer) {
    return (
      <div className="customer-section">
        <div className="card">
          <EmptyState title="Pelanggan tidak ditemukan" hint="Pelanggan mungkin telah dihapus." icon={<IconCustomers size={32} />} />
        </div>
      </div>
    )
  }

  const connected = (trace?.hops.length ?? 0) > 1
  const odpCount = neighbors?.sameOdp.length ?? 0
  const ponCount = neighbors?.samePonPort.length ?? 0

  const tabDefs: ResourceNavigationItem<Tab>[] = [
    { key: 'ringkasan', label: 'Ringkasan', icon: NavIcons.Dashboard },
    { key: 'langganan', label: 'Langganan', group: 'Layanan', icon: NavIcons.Package },
    ...(canAccess ? [{ key: 'akses' as Tab, label: 'Akun jaringan', group: 'Layanan', icon: NavIcons.Shield }] : []),
    { key: 'perangkat', label: 'Perangkat', group: 'Layanan', icon: NavIcons.Storage },
    ...(can('portal.credential.view') ? [{ key: 'portal' as Tab, label: 'Portal pelanggan', group: 'Layanan', icon: NavIcons.Portal }] : []),
    { key: 'jalur', label: 'Jalur koneksi', group: 'Jaringan', icon: NavIcons.Route },
    { key: 'tetangga', label: 'Pelanggan sekitar', group: 'Jaringan', icon: NavIcons.Customers },
    ...(canMetric ? [{ key: 'metrik' as Tab, label: 'Metrik', group: 'Jaringan', icon: NavIcons.Gauge }] : []),
    ...(canTraffic ? [{ key: 'trafik' as Tab, label: 'Trafik', group: 'Jaringan', icon: NavIcons.Chart }] : []),
    ...(canCpe ? [{ key: 'cpe' as Tab, label: 'ACS', group: 'Jaringan', icon: NavIcons.Wifi }] : []),
    ...(canBilling ? [{ key: 'tagihan' as Tab, label: 'Tagihan', group: 'Operasional', icon: NavIcons.Receipt }] : []),
    ...(canIncident || canWorkorder ? [{ key: 'tiket' as Tab, label: 'Tiket & work order', group: 'Operasional', icon: NavIcons.WorkOrder }] : []),
    ...(canBilling || canIncident || canWorkorder ? [{ key: 'timeline' as Tab, label: 'Aktivitas', group: 'Operasional', icon: NavIcons.History }] : []),
  ]

  const activeTab = tabDefs.some(item => item.key === tab) ? tab : 'ringkasan'

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
      disabled: !hasCustomerLocation(customer.location),
      icon: <IconMap size={16} />,
      onClick: () => {
        if (!hasCustomerLocation(customer.location)) return
        const focus = mapFocusState('customer', id, customer.location)
        if (onShowOnMap) onShowOnMap(focus)
        else navigate('/map', focus)
      },
      dividerBefore: true,
    },
  ]

  return (
    <div className="customer-detail">
      {!onTitleChange && <h1 className="page-title">{customer.name}</h1>}
      <div className="customer-identity">
        <span>{customer.code}</span>
        <span>{customerStatusLabel(customer.status, customer.awaitingInstallation)}</span>
      </div>

      <CommandBar layout="wrap" primary={commands[0]} actions={commands.slice(1)} />
      <ResourceNavigation key={`${id}:${refreshKey}`} label="Bagian pelanggan" items={tabDefs} active={activeTab} onChange={setTab}>
      {activeTab === 'ringkasan' && <EssentialsBlock customer={customer} sub360={sub360} trace={trace} />}
      {activeTab === 'langganan' && <div className="stack"><SubscriptionManager customer={customer} run={run} /><CustomerMigrationManagement customerId={id} mode="billing" onUpdated={() => void reload()} /></div>}
      {activeTab === 'perangkat' && <><OnuManager customer={customer} run={run} />{customer.onus.length > 0 && <OntAcsSettingsCard />}</>}
      {activeTab === 'portal' && <PortalCredentialCard customerId={customer.id} />}
      {activeTab === 'jalur' && <JalurTab trace={trace} connected={connected} />}
      {activeTab === 'tetangga' && <TetanggaTab neighbors={neighbors} connected={connected} odpCount={odpCount} ponCount={ponCount} />}
      {activeTab === 'metrik' && <MetrikTab customer={customer} metrics={metrics} />}
      {activeTab === 'akses' && <CustomerMigrationManagement customerId={id} mode="network" onUpdated={() => void reload()} fallback={<NetworkAccessTab customerId={id} subscription={customer.subscription} />} />}
      {activeTab === 'trafik' && <TrafikTab customerId={id} />}
      {activeTab === 'cpe' && <CpeTab customerId={id} />}
      {activeTab === 'tagihan' && <TagihanTab customerId={id} billing={sub360?.billing ?? null} />}
      {activeTab === 'tiket' && <TiketWoTab customerId={id} canIncident={canIncident} canWorkorder={canWorkorder} />}
      {activeTab === 'timeline' && (
        <TimelineTab
          customerId={id}
          customer={customer}
          canBilling={canBilling}
          canIncident={canIncident}
          canWorkorder={canWorkorder}
        />
      )}
      </ResourceNavigation>
    </div>
  )
}

/* ---------- Essentials: ringkasan properti di kepala blade ---------- */

/**
 * Kepala seksi bergaya blade Azure: ikon beraksen + judul tebal, tanpa bingkai.
 * Pemisah antar-seksi dikerjakan garis rambut wadah `.flat-sections`.
 */
function SectionHead({ title, aside }: { title: string; aside?: ReactNode }) {
  return <header className="customer-section-heading"><h2>{title}</h2>{aside}</header>
}

function Properties({ rows }: { rows: [string, ReactNode][] }) {
  return <dl className="essentials">{rows.map(([label, value]) => <Ess key={label} label={label}>{value ?? '—'}</Ess>)}</dl>
}

function DetailEmpty({ title, children }: { title: string; children: ReactNode }) {
  return <section className="customer-section"><h2>{title}</h2><p className="muted">{children}</p></section>
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
function EssentialsBlock({ customer, sub360, trace }: {
  customer: CustomerView; sub360: Subscriber360View | null; trace: CustomerTrace | null
}) {
  const sub = customer.subscription
  const onu = customer.onus.find(row => row.odpId) ?? customer.onus[0]
  return <section className="customer-section">
    <h2>Ringkasan</h2>
    <div className="customer-property-groups">
      <section className="customer-section"><h3>Informasi pelanggan</h3><Properties rows={[
        ['Kode pelanggan', customer.code], ['Status', customerStatusLabel(customer.status, customer.awaitingInstallation)],
        ['Alamat', customer.address || '—'], ['Telepon', customer.phone || '—'], ['Email', customer.email || '—'],
        ['NIK / identitas', customer.idCardNumber || '—'],
        ['Koordinat', hasCustomerLocation(customer.location) ? `${customer.location.latitude}, ${customer.location.longitude}` : 'Belum ditentukan'],
      ]} /></section>
      <section className="customer-section"><h3>Layanan</h3><Properties rows={[
        ['Paket', sub?.packageName ?? 'Belum ditetapkan'], ['Kecepatan', sub ? `${sub.bandwidthMbps} Mbps` : '—'],
        ['Status langganan', sub ? <StatusBadge key="subscription-status" status={sub.status} label={({ ACTIVE: 'Aktif', PENDING: 'Menunggu aktivasi', ISOLATED: 'Diisolir', TERMINATED: 'Berakhir' }[sub.status])} /> : '—'],
        ['Biaya bulanan', sub ? fmtRupiah(Number(sub.monthlyFee)) : '—'],
        ...(sub360?.access.billing ? [['Tunggakan', sub360.billing ? fmtRupiah(Number(sub360.billing.outstandingAmount)) : '—']] as [string, ReactNode][] : []),
        ...(sub360?.access.workOrder ? [['Work order aktif', sub360.openWorkOrder?.code ?? 'Tidak ada']] as [string, ReactNode][] : []),
      ]} /></section>
      <section className="customer-section"><h3>Koneksi jaringan</h3><Properties rows={[
        ['ONU', onu?.serialNumber ?? 'Belum terpasang'], ['ODP', onu?.odpCode ?? '—'], ['Port ODP', onu?.odpPortNumber ?? '—'],
        ['Rx optik', fmtDbm(trace?.liveRxPowerDbm ?? trace?.installRxPowerDbm ?? null)],
        ...(sub360?.access.session ? [['Sesi jaringan', sub360.session ? (sub360.session.online ? 'Online' : 'Offline') : 'Belum ada sesi'], ['Alamat IP', sub360.session?.framedIp ?? '—']] as [string, ReactNode][] : []),
        ...(sub360?.access.cpe ? [['CPE online', sub360.cpeDevices ? `${sub360.cpeDevices.filter(row => row.online).length} dari ${sub360.cpeDevices.length}` : '—']] as [string, ReactNode][] : []),
      ]} /></section>
    </div>
  </section>
}

/* ---------- Tab: Ringkasan (langganan, perangkat ONU, portal) ---------- */

/**
 * Kelola langganan pelanggan: pilih paket dari katalog (bukan lagi ketik bebas), harga
 * & kecepatan ikut paket dengan opsi harga negosiasi per-pelanggan, plus kendali daur
 * hidup. Sisi komersial di-snapshot server saat simpan; sisi jaringan dibaca live.
 *
 * SATU PELANGGAN SATU LANGGANAN — selamanya, bukan sekadar "satu yang hidup". Formulir
 * di bawah hanya menetapkan paket: mengisi bila belum berpaket, memindahkan bila sedang
 * berjalan, menghidupkan lagi baris yang sama bila pernah berhenti. Tak ada tombol tambah.
 *
 * Sisi fisik menempel pada pelanggan (ONU, koordinat rumah, CPE) dan akun PPPoE-nya ikut
 * langganan itu, jadi langganan kedua tak pernah punya perangkat maupun identitas sendiri —
 * yang ada hanya tagihan dobel. Layanan kedua di lokasi lain = daftarkan pelanggan baru.
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

  // Langganannya cuma satu — yang membedakan hanya apa dia sedang berjalan atau sudah
  // berakhir. Yang berakhir bukan "tak ada": barisnya tetap dipakai bila pelanggan kembali,
  // supaya username PPPoE & riwayat tagihannya tak pecah jadi dua identitas.
  const sub = customer.subscription
  const live = sub != null && sub.status !== 'TERMINATED' ? sub : null
  const ended = sub != null && sub.status === 'TERMINATED'

  useEffect(() => {
    if (!canManage) return
    void listCatalogPlans()
      .then((all) => setPlans(all.filter((p) => p.active)))
      .catch(() => setPlans([]))
  }, [canManage])

  // Mengganti paket berangkat dari paket yang SEDANG berjalan, bukan dari daftar kosong:
  // operator tinggal memindahkannya, dan pilihan "tak sengaja jatuh ke paket pertama"
  // tak mungkin terjadi. Paket yang sudah dinonaktifkan dari katalog tak ada di daftar →
  // kotak tetap kosong dan operator memilih penggantinya secara sadar.
  useEffect(() => {
    setPlanId(live?.planId ?? '')
    setPriceOverride('')
  }, [live?.id, live?.planId])

  const [editingForm, setEditingForm] = useState(false)
  const creation = useCreationReview(editingForm, sub !== null)
  const selected = plans.find((p) => p.id === planId) ?? null
  const override = priceOverride.trim()
  // Tak ada yang berubah → tombol mati, biar tak ada perintah kosong yang mengantre
  // pekerjaan RADIUS untuk paket yang sama.
  const unchanged = live != null && planId === live.planId && override === ''

  // Satu tombol, satu pintu (PUT), tiga kalimat berbeda — operator perlu tahu mana yang
  // sedang terjadi, sebab "berlangganan lagi" menghidupkan langganan yang dulu berhenti
  // (tagihan jalan lagi), bukan sekadar menyunting paket.
  const actionLabel = live ? 'Ganti paket' : ended ? 'Berlangganan lagi' : 'Tetapkan paket'

  const submit = async () => {
    if (!planId || unchanged) return
    const body = { planId, monthlyFeeOverride: override === '' ? null : Number(override) }
    if (override && (!Number.isFinite(Number(override)) || Number(override) < 0)) return
    if (creation.beforeSave()) return
    setSaving(true)
    await run(
      async () => { await api.put(`/api/customers/${customer.id}/subscription`, body); setEditingForm(false); setPriceOverride('') },
      live ? 'Paket langganan diganti' : ended ? 'Langganan dihidupkan kembali' : 'Paket ditetapkan',
    )
    setSaving(false); creation.finish()
  }

  return (
    <div className="customer-section">
      <SectionHead title="Langganan" />

      {canManage && <div className="customer-actions" role="group" aria-label="Aksi langganan">
        <Button variant="subtle" onClick={() => setEditingForm(true)}>{actionLabel}</Button>
        {sub && sub.status !== 'TERMINATED' && <SubscriptionActions sub={sub} run={run} />}
      </div>}
      {sub ? <Properties rows={[
        ['Paket', sub.packageName], ['Status', <StatusBadge key="subscription-status" status={sub.status} label={({ ACTIVE: 'Aktif', PENDING: 'Menunggu aktivasi', ISOLATED: 'Diisolir', TERMINATED: 'Berakhir' }[sub.status])} />],
        ['Kecepatan', `${sub.bandwidthMbps} Mbps`], ['Biaya bulanan', fmtRupiah(Number(sub.monthlyFee))],
        ['Diaktifkan', fmtInstant(sub.activatedAt)], ['Diakhiri', fmtInstant(sub.terminatedAt)],
      ]} /> : <p className="muted">Belum ada langganan.</p>}
      {canManage && (<>
        <Blade open={editingForm} title={actionLabel} onClose={() => setEditingForm(false)}
          creation={{ ...creation, busy: saving, prepare: () => void submit(), summary: <><CreationSummary rows={[
            ['Pelanggan', customer.name], ['Paket', selected?.name], ['Biaya bulanan', fmtRupiah(override ? Number(override) : selected?.price ?? 0)],
          ]} /><p>{ended ? 'Langganan akan diaktifkan kembali dengan akun dan riwayat sebelumnya.' : 'Paket dan tagihan periode berikutnya mengikuti pilihan ini.'}</p></> }}
          footer={<><Button disabled={saving} onClick={() => setEditingForm(false)}>Batal</Button><Button variant="primary" disabled={!planId || unchanged || saving} onClick={() => void submit()}>{actionLabel}</Button></>}>
        <div className="stack">
          {plans.length === 0 ? (
            <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
              Tidak ada paket aktif.
            </Text>
          ) : (
            <>
              <div className="customer-form-fields">
                <SelectField
                  label="Paket" required
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

              </div>
              {selected && (
                <p className="muted tnum" style={{ margin: 0,  }}>
              {selected.downMbps}/{selected.upMbps} Mbps · {fmtRupiah(selected.price)}/bln
              {selected.fupEnabled ? ' · FUP' : ''}
                </p>
              )}
              <Text as="p" className="muted" size={200} style={{ margin: 0 }}>{ended ? 'Username jaringan dan riwayat tagihan tetap digunakan.' : 'Harga negosiasi bersifat opsional.'}</Text>
            </>
          )}
        </div></Blade></>
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
    <div className="customer-action-group">
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
function OnuManager({ customer, run }: {
  customer: CustomerView
  run: (action: () => Promise<unknown>, okMessage?: string) => Promise<void>
}) {
  return <>
    <CustomerAssetPanel customerId={customer.id} areaId={customer.areaId} onChanged={() => void run(async () => undefined, 'Data perangkat diperbarui')} />
    <CustomerOnuPanel key={customer.id} customerId={customer.id} onus={customer.onus} onChanged={() => void run(async () => undefined, 'ONU terhubung')} />
  </>
}

/* ---------- Tab: Jalur (topologi hulu + anggaran redaman) ---------- */

/** Label lapangan tiap simpul jalur: RADIUS→BRAS→OLT→FDT→FAT→ONT. */
const HOP_LABEL: Record<string, string> = {
  CUSTOMER: 'ONT',
  ODP: 'ODP',
  ODC: 'ODC',
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
    return <DetailEmpty title="Jalur koneksi">Pelanggan belum tersambung ke jaringan.</DetailEmpty>
  }
  const bras = trace.bras
  return (
    <div className="customer-section">
      <div className="customer-section">
        <SectionHead title="Jalur koneksi" />
        <DataTable rows={trace.hops.map((hop, index) => ({ ...hop, index }))} rowKey={hop => String(hop.index)} columns={[
          { key: 'order', header: 'Urutan', minWidth: 64, cell: hop => hop.index + 1 },
          { key: 'kind', header: 'Jenis', minWidth: 100, cell: hop => HOP_LABEL[hop.kind] ?? hop.kind },
          { key: 'code', header: 'Titik jaringan', minWidth: 180, cell: hop => hop.code ?? '—' },
          { key: 'detail', header: 'Keterangan', minWidth: 220, cell: hop => hop.detail ?? '—' },
          { key: 'state', header: 'Status', minWidth: 100, cell: hop => hop.online == null ? '—' : hop.online ? 'Online' : 'Offline' },
        ]} />
      </div>

      {/* Angka segmen optik & sesi BRAS dulu satu kalimat panjang bertitik-tengah; sebagai
          daftar properti tiap angka punya label sendiri, jadi bisa dibaca sekilas dan
          disalin tanpa memotong kalimat. */}
      {connected && (
        <div className="customer-section">
          <SectionHead title="Segmen optik" />
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
            <Ess label="Rx terkini">
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
        <div className="customer-section">
          <SectionHead title="Sesi BRAS" />
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
            <Ess label="Profil kecepatan">{bras.rateProfileName}</Ess>
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
    return <DetailEmpty title="Pelanggan sekitar">Pelanggan belum tersambung ke jaringan.</DetailEmpty>
  }
  return (
    <div className="customer-section">
      <SectionHead title="Pelanggan sekitar" />
      <div className="customer-filters"><Segmented ariaLabel="Cakupan pelanggan" value={scope} onChange={setScope} options={[
        { value: 'odp', label: `ODP yang sama (${odpCount})` }, { value: 'pon', label: `PON yang sama (${ponCount})` },
      ]} /></div>
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
  const [query, setQuery] = useState('')
  const rows = (items ?? []).filter(row => `${row.customerName} ${row.onuSerialNumber} ${row.odpCode}`.toLowerCase().includes(query.toLowerCase()))
  return <div className="customer-section">
    <div className="customer-filters"><TextField label="Cari pelanggan sekitar" placeholder="Nama atau nomor seri" value={query} onChange={(_, data) => setQuery(data.value)} /></div>
    {items == null ? <Spinner /> : <DataTable rows={rows} rowKey={row => row.customerId} columns={[
      { key: 'name', header: 'Pelanggan', minWidth: 220, cell: row => `${row.customerName}${row.self ? ' (pelanggan ini)' : ''}`, sortValue: row => row.customerName },
      ...(showOdp ? [{ key: 'odp', header: 'ODP', cell: (row: NeighborView) => row.odpCode ?? '—' }] : []),
      { key: 'port', header: 'Port ODP', minWidth: 90, cell: row => row.portNumber },
      { key: 'serial', header: 'Nomor seri ONU', minWidth: 180, cell: row => row.onuSerialNumber },
      { key: 'status', header: 'Status', cell: row => onuStatusLabel(row.liveStatus ?? row.onuStatus) },
      { key: 'rx', header: 'Rx optik', cell: row => fmtDbm(row.liveRxPowerDbm ?? row.installRxPowerDbm) },
      { key: 'distance', header: 'Jarak', cell: row => row.distanceMeters == null ? '—' : `${row.distanceMeters} m` },
    ]} empty={<p>{query ? 'Tidak ada pelanggan yang cocok.' : 'Belum ada pelanggan pada jalur ini.'}</p>} />}
  </div>
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
  const [historyFailed, setHistoryFailed] = useState(false)

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
    setHistory(null); setHistoryFailed(false)
    void api
      .get<OnuHistoryView>(`/api/monitoring/onus/${onuId}/history?hours=24`)
      .then((h) => alive && setHistory(h))
      .catch(() => { if (alive) setHistoryFailed(true) })
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
    return <DetailEmpty title="Metrik">Belum ada ONU untuk dipantau.</DetailEmpty>
  }

  return (
    <div className="customer-section">
      <div className="customer-section">
        <SectionHead title="Metrik" />
        <Properties rows={[
          ['Nomor seri ONU', onu.serialNumber], ['Status', live ? onuStatusLabel(live.status) : 'Belum tersedia'],
          ['Rx terkini', fmtDbm(live?.rxPowerDbm ?? null)], ['Jarak dari OLT', live?.distanceMeters == null ? '—' : `${live.distanceMeters} m`],
          ['Penyebab putus terakhir', live?.downCause ? DOWN_CAUSE_LABEL[live.downCause] : '—'],
          ['Riwayat koneksi', live && (live.lastOffAt || live.lastOnAt) ? describeOutage(live) : '—'],
        ]} />
      </div>

      <div className="customer-section">
        <SectionHead title="Tren redaman 24 jam" />
        {history ? (
          <>
            <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(110px, 1fr))' }}>
              <MiniStat label="Rata-rata" value={fmt(history.averageRxPowerDbm)} unit="dBm" />
              <MiniStat label="Minimum" value={fmt(history.minRxPowerDbm)} unit="dBm" />
              <MiniStat label="Maksimum" value={fmt(history.maxRxPowerDbm)} unit="dBm" />
              <MiniStat label="Tren" value={fmt(history.trendDbPerDay)} unit="dB/hari" warn={history.degrading} />
            </div>
            {history.degrading && (
              <div className="row" style={{ gap: '0.5rem', color: 'var(--warning-ink)',  }}>
                <IconAlert size={16} />
                Redaman meningkat. Jadwalkan pemeriksaan.
              </div>
            )}
            <div className="customer-chart"><OpticalChart points={history.points} /></div>
          </>
        ) : (
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>{historyFailed ? 'Tren redaman gagal dimuat.' : 'Memuat tren…'}</Text>
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
      <div className="stat-value" style={{ color: warn ? 'var(--warning-ink)' : undefined }}>{value}
      <Text as="span" weight="semibold" size={200} className="muted"> {unit}</Text></div>
    </div>
  )
}

/* ---------- Tab: Akses (identitas jaringan / akun PPPoE) ---------- */

/**
 * Kelola akun PPPoE (identitas jaringan) langganan pelanggan — satu pelanggan satu
 * langganan, satu langganan paling banyak satu akun. Paket & BRAS ditarik hanya untuk
 * mengisi dropdown, dan hanya bila operator boleh mengelola akun sekaligus melihat
 * keduanya. Password (secret) tak pernah dibaca balik: cuma bisa diisi saat provisi
 * atau di-reset.
 */
function NetworkAccessTab({
  customerId,
  subscription,
}: {
  customerId: string
  subscription: SubscriptionView | null
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

  if (subscription == null) {
    return (
      <div className="card">
        <p className="muted" style={{ margin: 0 }}>
          Paket langganan belum ditetapkan.
        </p>
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '0.75rem' }}>
      <SubscriptionAccessCard
        sub={subscription}
        account={accounts.find((a) => a.subscriptionId === subscription.id) ?? null}
        plans={plans}
        nasList={nasList}
        canManage={canManage}
        canSession={canSession}
        canIsolate={canIsolate}
        canReset={canReset}
        run={run}
      />
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

/**
 * Penanda selisih paket-akun vs paket-tagihan. Server hanya mengisi `subscriptionPlanName`
 * bila keduanya berbeda, jadi badge ini muncul persis saat ada yang salah: pelanggan ditagih
 * satu paket sementara BRAS menjalankan paket lain. Sengaja hanya ditandai, tidak diperbaiki
 * otomatis — menyelaraskan diam-diam berarti mengubah kecepatan pelanggan tanpa ada yang
 * memutuskan. Operator yang menilai mana yang benar, lalu membetulkannya lewat dropdown Paket.
 */
function PlanMismatchBadge({ account }: { account: SubscriberAccessView }) {
  if (!account.subscriptionPlanName) return null
  return <p role="status" className="customer-notice">Paket tagihan ({account.subscriptionPlanName}) berbeda dari paket jaringan ({account.planName ?? '—'}).</p>
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
    // Paket akun DIAWALI dari paket langganan — itu yang ditagih, jadi itu pula yang harus
    // dieksekusi jaringan. Sebelumnya dropdown ini selalu jatuh ke paket PERTAMA katalog:
    // operator yang tak menyentuhnya membuat akun di paket yang salah sejak hari pertama, dan
    // selisihnya tak muncul di layar mana pun. Tetap boleh diganti (akses cadangan/kesepakatan
    // khusus), tapi harus disengaja. Paket langganan yang sudah dinonaktifkan dari katalog tak
    // ada di daftar → mundur ke paket pertama seperti dulu.
    const preselected = plans.find((p) => p.id === sub.planId) ?? plans[0]
    setPlanId(preselected?.id ?? '')
    setAuthType(preselected?.serviceTypes[0] ?? 'PPPOE')
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

  const accessCreation = useCreationReview(form === 'provision')
  const submitProvision = () => {
    if (provisionInvalid || accessCreation.beforeSave()) return
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
    }, 'Akun jaringan dibuat').finally(accessCreation.finish)
  }

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
      if (await confirm({ title: `Hapus akun ${account.username}?`, message: 'Akun jaringan akan dihapus permanen.', confirmLabel: 'Hapus', danger: true })) {
        void run(() => deleteAccess(account.id), 'Akun dihapus')
      }
    })()

  // Kendali jaringan (jalur tulis ke BRAS): efeknya nyata pada sesi pelanggan —
  // memutus koneksi — jadi tiap aksi minta konfirmasi eksplisit lebih dulu.
  const isolate = () =>
    void (async () => {
      if (!account) return
      if (await confirm({ title: `Isolir akun ${account.username}?`, message: 'Sesi PPPoE aktif akan diputus.', confirmLabel: 'Isolir', danger: true })) {
        void run(() => isolateAccess(account.id), 'Akun diisolir')
      }
    })()

  const restore = () =>
    void (async () => {
      if (!account) return
      if (await confirm({ title: `Pulihkan akun ${account.username}?`, message: 'Akses jaringan akan dipulihkan.', confirmLabel: 'Pulihkan' })) {
        void run(() => restoreAccess(account.id), 'Akun dipulihkan')
      }
    })()

  const resetLogin = () =>
    void (async () => {
      if (!account) return
      if (await confirm({ title: `Reset Login ${account.username}?`, message: 'Sesi aktif akan diputus.', confirmLabel: 'Reset Login' })) {
        void run(() => resetAccessLogin(account.id), 'Perintah Reset Login dikirim')
      }
    })()

  return (
    <div className="customer-section">
      <SectionHead title="Akun jaringan" />
      {account ? (
        <div className="customer-section">
          {form === null && (canManage || canReset || canIsolate) && (
            <CommandBar layout="wrap" actions={[
              ...(canManage ? [{ key: 'edit', label: 'Ganti paket / BRAS', icon: <Pencil size={16} />, onClick: openEdit }, { key: 'password', label: 'Reset password', icon: <KeyRound size={16} />, onClick: openReset }] : []),
              ...(canReset && account.status !== 'TERMINATED' ? [{ key: 'session', label: 'Reset Login', icon: <Unplug size={16} />, onClick: resetLogin }] : []),
              ...(canIsolate && account.status === 'ACTIVE' ? [{ key: 'isolate', label: 'Isolir', icon: <Ban size={16} />, onClick: isolate }] : []),
              ...(canIsolate && account.status === 'ISOLATED' ? [{ key: 'restore', label: 'Pulihkan', icon: <Play size={16} />, onClick: restore }] : []),
              ...(canManage ? [{ key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: remove }] : []),
            ]} />
          )}

          <Properties rows={[
            ['Username', account.username], ['Jenis koneksi', SERVICE_TYPE_LABEL[account.authType]],
            ['Status', <StatusBadge key="account-status" status={account.status} label={({ ACTIVE: 'Aktif', PENDING: 'Menunggu aktivasi', ISOLATED: 'Diisolir', TERMINATED: 'Berakhir' }[account.status])} />], ['Paket jaringan', account.planName ?? '—'],
            ['BRAS', account.nasName ?? 'Belum ditentukan'], ['Alamat IP reservasi', account.framedIp ?? '—'],
            ['FUP', account.fupEnabled ? (account.fupThrottled ? 'Kecepatan dibatasi' : 'Aktif') : 'Nonaktif'],
            ...(account.fupEnabled ? [['Pemakaian FUP', `${account.periodUsageMb ?? 0} / ${account.fupQuotaMb ?? '—'} MB`]] as [string, ReactNode][] : []),
          ]} />
          <PlanMismatchBadge account={account} />
          {form === 'edit' && <ResourceForm title="Ganti paket / BRAS" dirty={() => planId !== account.planId || nasId !== (account.nasId ?? '')} editing navigation={<></>} onClose={close} onBack={() => {}}
            footer={<><Button onClick={close}>Batal</Button><Button variant="primary" onClick={submitEdit} disabled={!planId}>Simpan</Button></>}>
            <div className="customer-form-fields"><PlanField plans={plans} value={planId} onChange={setPlanId} /><NasField nasList={nasList} value={nasId} onChange={setNasId} /></div>
          </ResourceForm>}
          {form === 'reset' && <ResourceForm title="Reset password jaringan" dirty={() => secret.length > 0} editing navigation={<></>} onClose={close} onBack={() => {}}
            footer={<><Button onClick={close}>Batal</Button><Button variant="primary" onClick={submitReset} disabled={!secret}>Simpan</Button></>}>
            <div className="customer-form-fields"><TextField label="Password baru" required autoComplete="new-password" type={showSecret ? 'text' : 'password'} value={secret} onChange={(_, data) => setSecret(data.value)} />
              <div><Button onClick={() => setShowSecret(value => !value)}>{showSecret ? 'Sembunyikan password' : 'Tampilkan password'}</Button></div>
            </div>
          </ResourceForm>}
          {canSession && <BrasSessionPanel accessId={account.id} />}
        </div>
      ) : sub.status === 'TERMINATED' ? (
        <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
          Langganan sudah berakhir.
        </Text>
      ) : !canManage ? (
        <Text as="p" className="muted" size={300} style={{ margin: 0 }}>Belum ada akun jaringan untuk langganan ini.</Text>
      ) : plans.length === 0 ? (
        <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
          Belum ada paket internet yang tersedia.
        </Text>
      ) : form === 'provision' ? (
        <Blade open title="Buat akun jaringan" onClose={close}
          creation={{ ...accessCreation, prepare: submitProvision, summary: <CreationSummary rows={[
            ['Username', username], ['Layanan', SERVICE_TYPE_LABEL[authType]], ['Paket', plans.find(plan => plan.id === planId)?.name], ['IP reservasi', framedIp],
          ]} /> }} footer={<><Button disabled={accessCreation.busy} onClick={close}>Batal</Button><Button variant="primary" onClick={submitProvision} disabled={provisionInvalid || accessCreation.busy}>Buat</Button></>}>
        <div className="customer-form-fields">
          <div className="customer-form-fields">
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
            <div className="customer-form-fields">
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
            <div className="customer-form-fields">
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

          <div className="customer-form-fields">
            <NasField nasList={nasList} value={nasId} onChange={setNasId} />

          </div>
          <Text as="p" className="muted" size={200} style={{ margin: 0 }}>{macBased
            ? 'DHCP/Static memakai MAC sebagai identitas. Static memerlukan IP reservasi.'
            : 'Kata sandi disimpan terenkripsi dan hanya dapat di-reset.'}</Text>
        </div></Blade>
      ) : (
        <div className="spread" style={{ alignItems: 'center' }}>
          <Text as="span" size={300} className="muted" >Belum ada akun jaringan untuk langganan ini.</Text>
          <Button variant="primary" onClick={openProvision}>
            Buat akun jaringan
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
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let alive = true
    setLoading(true); setFailed(false)
    void getBrasSession(accessId).then(value => { if (alive) setSession(value) })
      .catch(() => { if (alive) setFailed(true) }).finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [accessId])
  return <section className="customer-section customer-section-divider"><h3>Sesi jaringan</h3>
    {loading ? <Spinner /> : failed ? <p role="alert">Sesi jaringan gagal dimuat.</p> : session ? <Properties rows={[
      ['Status sesi', session.online ? 'Online' : 'Offline'], ['Alamat IP', session.framedIp], ['BRAS', session.nasName],
      ['IP BRAS', session.nasIp], ['Alamat MAC', session.callingStationId],
      ['Durasi sesi', session.online && session.uptimeSeconds != null ? humanizeDuration(session.uptimeSeconds * 1000) : '—'],
      ['Terpantau terakhir', fmtInstant(session.lastSeenAt)],
    ]} /> : <p className="muted">Belum ada sesi jaringan.</p>}
  </section>
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
    return <DetailEmpty title="Trafik">Belum ada akun jaringan untuk dipantau.</DetailEmpty>
  }

  return (
    <div className="stack" style={{ gap: '0.75rem' }}>
      {accounts.map((account) => (
        <div key={account.id} className="customer-section">
          <SectionHead title="Trafik" />
          <Properties rows={[
            ['Username', account.username], ['Paket', account.planName ?? '—'], ['BRAS', account.nasName ?? '—'],
          ]} />
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
  const [failed, setFailed] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)

  const load = useCallback(() => {
    setFailed(false)
    void listCpeDevices(customerId)
      .then((list) => {
        setDevices(list)
        setSelected((cur) => cur ?? list[0]?.id ?? null)
      })
      .catch(() => { setDevices([]); setFailed(true) })
  }, [customerId])

  useEffect(() => load(), [load])

  if (devices == null) {
    return (
      <div className="card" style={{ display: 'grid', placeItems: 'center', minHeight: 120 }}>
        <Spinner />
      </div>
    )
  }
  if (failed) return <section className="customer-section"><h2>ACS</h2><p role="alert">Perangkat ACS gagal dimuat.</p><div><Button onClick={load}>Coba lagi</Button></div></section>
  if (devices.length === 0) {
    return <DetailEmpty title="ACS">Belum ada perangkat pelanggan yang terhubung ke ACS.</DetailEmpty>
  }

  return (
    <div className="customer-section">
      <h2>ACS</h2>
      {devices.length > 1 && <div className="customer-filters"><SelectField label="Perangkat" value={selected ?? ''} onChange={(_, data) => setSelected(data.value)}>
        {devices.map(device => <option key={device.id} value={device.id}>{device.model ?? 'CPE'} · {device.serialNumber}</option>)}
      </SelectField></div>}
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
  const [detailFailed, setDetailFailed] = useState(false)
  const [liveFailed, setLiveFailed] = useState(false)
  const [rebooting, setRebooting] = useState(false)

  const canReboot = can('cpe.device.reboot')
  const canWifiView = can('cpe.wifi.view')
  const canWifiManage = can('cpe.wifi.manage')
  const canDiag = can('cpe.diagnostic.run')
  const canFirmware = can('cpe.firmware.manage')
  const canManage = can('cpe.device.manage')

  const loadDetail = useCallback(() => {
    setDetailFailed(false)
    void getCpeDevice(deviceId)
      .then(setDetail)
      .catch(() => { setDetail(null); setDetailFailed(true) })
  }, [deviceId])

  const loadLive = useCallback(() => {
    if (!canWifiView) return
    setLiveFailed(false)
    void getCpeLive(deviceId)
      .then(setLive)
      .catch(() => { setLive({ wifi: [], hosts: [] }); setLiveFailed(true) })
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
      else throw new Error(action.detail ?? 'Perubahan WiFi belum berhasil.')
      loadLive()
      loadDetail()
    } catch (err) {
      throw new Error(err instanceof Error ? err.message : 'Perubahan WiFi belum berhasil.')
    }
  }

  if (detailFailed) return <section className="customer-section"><p role="alert">Detail perangkat gagal dimuat.</p><div><Button onClick={loadDetail}>Coba lagi</Button></div></section>
  if (!detail) {
    return (
      <div className="card" style={{ display: 'grid', placeItems: 'center', minHeight: 120 }}>
        <Spinner />
      </div>
    )
  }

  const d = detail.device
  return (
    <div className="customer-section">
      <div className="customer-section">
        <h3>Informasi perangkat</h3>
        {canReboot && <div className="customer-actions"><Button variant="subtle" onClick={() => void reboot()} disabled={rebooting}>{rebooting ? 'Mengirim…' : 'Reboot'}</Button></div>}
        <Properties rows={[
          ['Model', d.model ?? d.productClass ?? '—'], ['Status', d.online ? 'Online' : 'Offline'],
          ['Produsen', d.manufacturer], ['Nomor seri', d.serialNumber], ['Versi firmware', d.softwareVersion],
          ['Alamat IP', d.ipAddress], ['Inform terakhir', fmtInstant(d.lastInformAt)], ['OUI', d.oui],
          ['Kelas produk', d.productClass], ['ID ACS', d.genieacsId],
        ]} />
      </div>

      {canWifiView && (
        <div className="customer-section">
          <SectionHead title="WiFi" />
          {liveFailed ? <p role="alert">Data perangkat gagal dimuat.</p> : live == null ? (
            <Text as="p" className="muted" size={300} style={{ margin: 0 }}>Memuat dari ACS…</Text>
          ) : live.wifi.length === 0 ? (
            <Text as="p" className="muted" size={300} style={{ margin: 0 }}>Tak ada jaringan WiFi terbaca.</Text>
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
        <div className="customer-section">
          <SectionHead title="Perangkat tersambung" />
          {liveFailed ? <p role="alert">Data perangkat gagal dimuat.</p> : live == null ? (
            <Text as="p" className="muted" size={300} style={{ margin: 0 }}>Memuat…</Text>
          ) : live.hosts.length === 0 ? (
            <Text as="p" className="muted" size={300} style={{ margin: 0 }}>Tak ada host aktif.</Text>
          ) : (
            <DataTable rows={live.hosts} rowKey={h => h.macAddress ?? h.ipAddress ?? h.hostName ?? 'host'} columns={[
              { key: 'name', header: 'Nama perangkat', cell: h => h.hostName ?? '—' },
              { key: 'ip', header: 'Alamat IP', cell: h => h.ipAddress ?? '—' },
              { key: 'mac', header: 'Alamat MAC', cell: h => h.macAddress ?? '—' },
              { key: 'status', header: 'Status', cell: h => h.active ? 'Aktif' : 'Tidak aktif' },
            ]} />
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
    <div className="customer-section">
      <SectionHead title="Diagnostik" />
      {!online && (
        <p className="muted" style={{ margin: 0,  }}>
          Perangkat offline; diagnostik mungkin gagal.
        </p>
      )}
      <div className="customer-filters">
        <TextField
          label="Sasaran ping"
          value={host}
          placeholder="Alamat IP atau nama host"
          onChange={(_, data) => setHost(data.value)}
          style={{ flex: 2, minWidth: 160 }}
        />
      </div>
      <div className="customer-actions">
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
        <Text as="span" size={300}  ><Text as="span" weight="semibold">Ping {ping.host}</Text>
        {ping.ok && total > 0 && (
          <span className="muted">
            {' '}· {ping.successCount ?? 0}/{total} sukses
            {ping.averageResponseMs != null && ` · avg ${ping.averageResponseMs} ms`}
          </span>
        )}</Text>
        {!ping.ok && (
          <Text as="span" size={300} className="muted" >{ping.message}</Text>
        )}
      </div>
      <span
        className="badge"
        style={{ color: ping.ok ? 'var(--good-ink)' : 'var(--critical-ink)',  }}
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
        <Text as="span" size={300}  ><Text as="span" weight="semibold">{label}</Text>
        {speed.ok && speed.throughputMbps != null ? (
          <span className="muted"> · {speed.throughputMbps.toFixed(1)} Mbps</span>
        ) : (
          <span className="muted"> · {speed.message}</span>
        )}</Text>
      </div>
      <span
        className="badge"
        style={{ color: speed.ok ? 'var(--good-ink)' : 'var(--critical-ink)',  }}
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
    if (!(await confirm({ title: `Pasang firmware ${file.name}${versi}?`, message: 'Perangkat akan reboot saat pemasangan.', confirmLabel: 'Pasang' }))) {
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
    <div className="customer-section">
      <SectionHead
        title="Firmware"
        aside={
          <Text as="span" size={300} className="muted" >
            terpasang: {currentVersion ?? '—'}</Text>
        }
      />
      {files == null ? (
        <Text as="p" className="muted" size={300} style={{ margin: 0 }}>Memuat dari ACS…</Text>
      ) : files.length === 0 ? (
        <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
          Tak ada firmware tersedia untuk model ini.
        </Text>
      ) : (
        <DataTable rows={files} rowKey={file => file.name} columns={[
          { key: 'name', header: 'Nama berkas', minWidth: 220, cell: file => file.name },
          { key: 'version', header: 'Versi', cell: file => file.version ?? '—' },
          { key: 'size', header: 'Ukuran', cell: file => fmtBytes(file.sizeBytes) },
          { key: 'action', header: 'Tindakan', cell: file => <Button variant="subtle" disabled={busy} onClick={() => void upgrade(file)}>{pushing === file.name ? 'Mengirim…' : 'Pasang'}</Button> },
        ]} />
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
        title: 'Reset pabrik perangkat?',
        message:
          'Semua setelan perangkat kembali ke bawaan dan koneksi pelanggan terputus.',
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
    <div className="customer-section">
      <SectionHead
        title="ACS & pemeliharaan"
        aside={
          acs != null && (
            <span
              className="badge"
              title={acs.message}
              style={{ color: acs.connected ? 'var(--good-ink)' : 'var(--muted)',  }}
            >
              {acs.connected ? 'Terhubung' : 'Tidak terhubung'}
            </span>
          )
        }
      />
      <div className="customer-actions">
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
function WifiCard({ wifi, canManage, onSave }: { wifi: WifiView; canManage: boolean; onSave: (body: SetWifiRequest) => Promise<void> }) {
  const [editing, setEditing] = useState(false)
  const [ssid, setSsid] = useState(wifi.ssid)
  const [passphrase, setPassphrase] = useState('')
  const [saving, setSaving] = useState(false), [error, setError] = useState('')
  const dirty = ssid !== wifi.ssid || !!passphrase
  const close = () => { setEditing(false); setSsid(wifi.ssid); setPassphrase(''); setError('') }
  async function save() {
    if (saving || !ssid.trim() || !dirty) return
    setSaving(true); setError('')
    try { await onSave({ ref: wifi.ref, ssid: ssid !== wifi.ssid ? ssid.trim() : null, passphrase: passphrase || null }); close() }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Gagal menyimpan WiFi.') }
    finally { setSaving(false) }
  }
  return <section className="customer-wifi">
    {canManage && <div className="customer-actions"><Button variant="subtle" onClick={() => setEditing(true)}>Ubah WiFi</Button></div>}
    <dl className="essentials wide">
      <Ess label="SSID">{wifi.ssid}</Ess><Ess label="Frekuensi">{wifi.band ?? '—'}</Ess>
      <Ess label="Status">{wifi.enabled ? 'Aktif' : 'Nonaktif'}</Ess>
    </dl>
    {editing && <ResourceForm title="Ubah WiFi" editing navigation={<></>} dirty={dirty} busy={saving} onClose={close} onBack={() => {}}
      footer={<><Button disabled={saving} onClick={close}>Batal</Button><Button variant="primary" disabled={saving || !dirty} onClick={() => void save()}>{saving ? 'Menyimpan…' : 'Simpan'}</Button></>}>
      <div className="stack">{error && <p role="alert">{error}</p>}
        <TextField label="SSID" required value={ssid} onChange={(_, data) => setSsid(data.value)} />
        <TextField label="Password baru" type="password" autoComplete="new-password" value={passphrase} onChange={(_, data) => setPassphrase(data.value)} hint="Kosongkan jika tidak diubah." />
      </div>
    </ResourceForm>}
  </section>
}

/** Jejak aksi terakhir ke perangkat — reboot / ubah WiFi, berhasil atau gagal. */
function CpeActionLog({ actions }: { actions: CpeActionView[] }) {
  if (actions.length === 0) return null
  return <section className="card stack"><SectionHead title="Riwayat tindakan" />
    <DataTable rows={actions} rowKey={action => action.id} columns={[
      { key: 'action', header: 'Tindakan', cell: action => CPE_ACTION_LABEL[action.action] },
      { key: 'status', header: 'Status', cell: action => action.status === 'SUCCESS' ? 'Berhasil' : 'Gagal' },
      { key: 'date', header: 'Waktu', cell: action => fmtInstant(action.requestedAt) },
      { key: 'user', header: 'Pengguna', cell: action => action.requestedByEmail ?? '—' },
      { key: 'detail', header: 'Keterangan', cell: action => action.detail ?? '—' },
    ]} />
  </section>
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

const INVOICE_LABEL: Record<InvoiceView['status'], string> = {
  PAID: 'Lunas',
  ISSUED: 'Terbit',
  OVERDUE: 'Jatuh tempo',
  VOID: 'Batal',
  REFUNDED: 'Dikembalikan',
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
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('')
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())

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

  const today = todayLocalDate()
  const tunggakan =
    billing != null
      ? Number(billing.outstandingAmount)
      : invoices.filter((inv) => isOutstanding(inv, today)).reduce((s, inv) => s + Number(inv.amount), 0)

  const selected = selectedIds.size === 1 ? invoices.find(inv => selectedIds.has(inv.id)) : undefined
  const payable = selected && (selected.status === 'ISSUED' || selected.status === 'OVERDUE')
  const rows = invoices.filter(inv => (!status || inv.status === status) && inv.number.toLowerCase().includes(query.toLowerCase()))
  return <section className="customer-section">
    <h2>Tagihan</h2>
    <div className="stat-grid">
      <MiniStat label="Tunggakan" value={fmtRupiah(tunggakan)} unit="" warn={tunggakan > 0} />
      <MiniStat label="Belum dibayar" value={String(invoices.filter(inv => ['ISSUED', 'OVERDUE'].includes(inv.status)).length)} unit="tagihan" />
      <MiniStat label="Lunas" value={String(invoices.filter(inv => inv.status === 'PAID').length)} unit="tagihan" />
    </div>
    <div className="customer-list">
      {canManage && <CommandBar layout="wrap" actions={[
        { key: 'pay', label: 'Buka pembayaran', disabled: !payable, onClick: () => { if (selected && payable) window.open(shareLink(selected), '_blank', 'noopener') } },
        { key: 'copy', label: 'Salin tautan pembayaran', disabled: !payable, onClick: () => { if (selected && payable) void copyLink(selected) } },
      ]} />}
      <div className="customer-filters">
        <TextField label="Cari tagihan" placeholder="Nomor tagihan" value={query} onChange={(_, data) => { setQuery(data.value); setSelectedIds(new Set()) }} />
        <SelectField label="Status tagihan" value={status} onChange={(_, data) => { setStatus(data.value); setSelectedIds(new Set()) }}><option value="">Semua status</option>{Object.entries(INVOICE_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</SelectField>
        <span className="customer-result-count">{rows.length} tagihan{selectedIds.size > 0 ? ` · ${selectedIds.size} dipilih` : ''}</span>
      </div>
      <DataTable rows={rows} rowKey={inv => inv.id} selection={canManage ? { selected: selectedIds, onChange: setSelectedIds } : undefined} columns={[
        { key: 'number', header: 'Nomor', minWidth: 180, cell: inv => inv.number, sortValue: inv => inv.number },
        { key: 'period', header: 'Periode', minWidth: 230, cell: inv => `${fmtDate(inv.periodStart)} – ${fmtDate(inv.periodEnd)}` },
        { key: 'due', header: 'Jatuh tempo', minWidth: 145, cell: inv => fmtDate(inv.dueDate), sortValue: inv => inv.dueDate },
        { key: 'amount', header: 'Jumlah', minWidth: 150, align: 'right', cell: inv => fmtRupiah(Number(inv.amount)), sortValue: inv => Number(inv.amount) },
        { key: 'tax', header: 'Termasuk PPN', minWidth: 150, align: 'right', cell: inv => fmtRupiah(Number(inv.taxAmount)) },
        { key: 'prorata', header: 'Prorata', minWidth: 100, cell: inv => inv.prorated ? inv.proratedDays == null ? 'Ya' : `${inv.proratedDays} hari` : 'Tidak' },
        { key: 'status', header: 'Status', cell: inv => INVOICE_LABEL[inv.status] },
      ]} empty={<p>{query || status ? 'Tidak ada tagihan yang cocok.' : 'Belum ada tagihan.'}</p>} />
    </div>
  </section>
}

/* ---------- Tab: Tiket insiden & Work Order (module incident + workorder) ---------- */

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
    return () => { alive = false }
  }, [customerId, canIncident])
  useEffect(() => {
    if (!canWorkorder) return
    let alive = true
    void listWorkOrdersForCustomer(customerId).then(value => { if (alive) setOrders(value) }).catch(() => { if (alive) setOrders([]) })
    return () => { alive = false }
  }, [customerId, canWorkorder])
  return <div className="customer-section">
    <h2>Tiket &amp; work order</h2>
    {canIncident && <section className="customer-section"><h3>Insiden aktif</h3>
      {incidents == null ? <Spinner /> : <DataTable rows={incidents} rowKey={row => row.id} columns={[
        { key: 'title', header: 'Insiden', minWidth: 240, cell: row => row.title },
        { key: 'root', header: 'Titik gangguan', minWidth: 180, cell: row => row.rootLabel },
        { key: 'severity', header: 'Keparahan', cell: row => ({ CRITICAL: 'Kritis', WARNING: 'Peringatan', INFO: 'Informasi' }[row.severity] ?? row.severity) },
        { key: 'status', header: 'Status', cell: row => ({ OPEN: 'Terbuka', ACKNOWLEDGED: 'Ditangani', RESOLVED: 'Selesai' }[row.status] ?? row.status) },
        { key: 'opened', header: 'Dibuka', minWidth: 160, cell: row => fmtInstant(row.openedAt), sortValue: row => row.openedAt },
      ]} empty={<p>Tidak ada insiden aktif.</p>} />}
    </section>}
    {canWorkorder && <section className="customer-section customer-section-divider"><h3>Work order</h3>
      {orders == null ? <Spinner /> : <DataTable rows={orders} rowKey={row => row.id} columns={[
        { key: 'code', header: 'Nomor', minWidth: 150, cell: row => row.code },
        { key: 'title', header: 'Pekerjaan', minWidth: 240, cell: row => row.title },
        { key: 'type', header: 'Jenis', minWidth: 140, cell: row => ({ PSB: 'Pasang baru', REPAIR: 'Perbaikan', MIGRATION: 'Migrasi', DISMANTLE: 'Pembongkaran', PREVENTIVE: 'Pemeliharaan' }[row.type]) },
        { key: 'status', header: 'Status', cell: row => WO_STATUS_LABEL[row.status] },
        { key: 'assignees', header: 'Petugas', minWidth: 180, cell: row => row.assignees.map(person => person.name ?? '—').join(', ') || 'Belum ditugaskan' },
        { key: 'source', header: 'Sumber', cell: row => row.incidentId ? 'Insiden' : '—' },
        { key: 'date', header: 'Dibuat', minWidth: 160, cell: row => fmtInstant(row.createdAt), sortValue: row => row.createdAt },
      ]} empty={<p>Belum ada work order.</p>} />}
    </section>}
  </div>
}

/* ---------- Tab: Timeline aktivitas gabungan (gabung sisi klien) ---------- */

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
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState('')
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

  const entries: Array<{ at: string; label: string; category: string }> = []
  const push = (at: string | null, label: string, category: string) => {
    if (at) entries.push({ at, label, category })
  }

  const sub = customer.subscription
  if (sub) {
    push(sub.activatedAt, `Langganan ${sub.packageName} diaktifkan`, 'Langganan')
    push(sub.terminatedAt, `Langganan ${sub.packageName} dihentikan`, 'Langganan')
  }
  customer.onus.forEach((onu) => push(onu.installedAt, `ONU ${onu.serialNumber} dipasang`, 'Perangkat'))
  invoices.forEach((inv) => {
    push(inv.issuedAt, `Tagihan ${inv.number} terbit`, 'Tagihan')
    push(inv.paidAt, `Tagihan ${inv.number} dibayar`, 'Tagihan')
  })
  incidents.forEach((inc) => push(inc.openedAt, `Insiden ${inc.title} dibuka`, 'Insiden'))
  orders.forEach((wo) => {
    push(wo.createdAt, `WO ${wo.code} dibuat`, 'Work order')
    push(wo.completedAt, `WO ${wo.code} selesai`, 'Work order')
  })

  // Terbaru lebih dulu; timestamp ISO 8601 aman dibandingkan leksikografis.
  entries.sort((a, b) => b.at.localeCompare(a.at))

  const rows = entries.filter(entry => (!category || entry.category === category) && entry.label.toLowerCase().includes(query.toLowerCase()))
  return <section className="customer-section">
    <h2>Aktivitas</h2>
    <div className="customer-list"><div className="customer-filters">
      <TextField label="Cari aktivitas" value={query} onChange={(_, data) => setQuery(data.value)} placeholder="Cari dalam riwayat" />
      <SelectField label="Kategori aktivitas" value={category} onChange={(_, data) => setCategory(data.value)}><option value="">Semua kategori</option>{['Langganan', 'Perangkat', 'Tagihan', 'Insiden', 'Work order'].map(value => <option key={value} value={value}>{value}</option>)}</SelectField>
      <span className="customer-result-count">{rows.length} aktivitas</span>
    </div><DataTable rows={rows.map((entry, index) => ({ ...entry, index }))} rowKey={entry => `${entry.at}-${entry.index}`} columns={[
      { key: 'at', header: 'Waktu', minWidth: 170, cell: entry => fmtInstant(entry.at), sortValue: entry => entry.at },
      { key: 'category', header: 'Kategori', minWidth: 140, cell: entry => entry.category },
      { key: 'activity', header: 'Aktivitas', minWidth: 380, cell: entry => entry.label },
    ]} empty={<p>{query || category ? 'Tidak ada aktivitas yang cocok.' : 'Belum ada aktivitas.'}</p>} /></div>
  </section>
}
