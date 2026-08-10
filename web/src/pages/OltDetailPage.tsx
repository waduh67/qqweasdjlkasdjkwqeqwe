import { useCallback, useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type {
  AssetStatus,
  OdpInspection,
  OdpUtilization,
  OltView,
  PonOdcBranch,
  PonPortInspection,
  PonPortView,
  SiteView,
  SnmpVersion,
  WebProtocol,
} from '../api/network'
import { onuStatusLabel } from '../api/network'
import { type Tone } from '@/components/atoms'
import type { PageResponse } from '../api/types'
import { useCan } from '../auth/useCan'
import { LocationPicker } from '@/components/organisms'
import { Badge, Button, EmptyState, SelectField, Spinner, StatusBadge, TextField } from '@/components/atoms'
import { Checkbox } from '@fluentui/react-components'
import { CommandBar, Tabs, type CommandAction } from '@/components/molecules'
import { useToast } from '@/system'
import { Blade } from '@/components/organisms'
import { IconInventory, IconMap, IconPlus } from '@/components/atoms/icons'
import { Pencil, Trash2 } from 'lucide-react'
import { mapFocusState } from '@/map/mapFocus'
import { CustomerDetailBlade } from './CustomerDetailPage'
import { DiscoveredOnuInbox } from '@/components/organisms'
import { OltRegisteredOnus } from '@/components/organisms'
import { SnmpDiagnosticPanel } from '@/components/organisms'

/**
 * Detail satu OLT sebagai rute tersendiri (`/olts/:id`) — bukan panel peta.
 *
 * OLT lama menempel di koordinat site-nya dan tak pernah bisa dipindah; halaman
 * ini memberi tempat lapang untuk memindahkannya di peta (klik/seret pin lalu
 * simpan) plus mengubah identitas & kesiapan SNMP-nya. Dicapai dari dua arah —
 * baris tabel Inventaris dan marker peta — jadi tautan "kembali" menyesuaikan
 * asalnya lewat `location.state`.
 */

const VENDORS = ['ZTE', 'HUAWEI', 'FIBERHOME', 'NOKIA', 'HSGQ', 'OTHER']

const SNMP_VERSIONS: { value: SnmpVersion; label: string }[] = [
  { value: 'V1', label: 'v1' },
  { value: 'V2C', label: 'v2c' },
  { value: 'V3', label: 'v3' },
]

const STATUS_OPTIONS: { value: AssetStatus; label: string }[] = [
  { value: 'PLANNED', label: 'Rencana' },
  { value: 'ACTIVE', label: 'Aktif' },
  { value: 'MAINTENANCE', label: 'Perawatan' },
  { value: 'INACTIVE', label: 'Nonaktif' },
]

type Tab = 'ringkasan' | 'pon' | 'onu' | 'onubaru' | 'diagnostik'

/**
 * Konten detail satu OLT — komponen dipakai-ulang oleh DUA pemanggil:
 * 1. rute `/olts/:id` ([OltDetailPage]) sebagai halaman penuh (deep-link peta), dan
 * 2. blade non-modal di Inventory (klik baris OLT) — pola dua-blade Azure.
 *
 * `compact` menyembunyikan judul besar `<h1>` karena header blade sudah menampilkan
 * kode OLT. `onDeleted` dipanggil seusai OLT dihapus agar pemanggil menutup panel
 * (blade) atau bernavigasi kembali (halaman).
 */
export function OltDetail({
  oltId,
  compact = false,
  onDeleted,
  onShowOnMap,
}: {
  oltId: string
  compact?: boolean
  onDeleted?: () => void
  /**
   * Perilaku aksi "Lihat di peta". Diisi oleh pemanggil yang PETANYA sudah tampil di
   * belakang panel ini (blade di halaman Peta) — di sana cukup menutup panel, tak perlu
   * pindah rute. Bila kosong, aksinya bernavigasi ke `/map` sambil menyorot OLT ini.
   */
  onShowOnMap?: () => void
}) {
  const id = oltId
  const toast = useToast()
  const navigate = useNavigate()
  const { can } = useCan()
  const canUpdate = can('network.olt.update')
  const canDelete = can('network.olt.delete')
  const canMap = can('gis.map.view')
  // Drill-down PON → ODC → ODP menembus module gis (topologi + okupansi agregat),
  // jadi butuh izin peta & ODP; disembunyikan bila operator tak berhak memanggilnya.
  const canDrill = canMap && can('network.odp.view')
  // Daftar ONU per-OLT memuat identitas pelanggan (PII), jadi gerbangnya persis
  // seperti endpoint `/api/gis/olts/{id}/onus`: inspeksi OLT + lihat pelanggan.
  const canCustomer = can('customer.customer.view')
  const canOnuList =
    canMap && can('network.olt.view') && can('network.odp.view') && canCustomer
  // Tab "ONU Baru" memakai kotak masuk provisioning — sama seperti halaman Provisioning.
  const canProvisioning = can('monitoring.provisioning.view')
  // Diagnostik SNMP menyuruh server menembak perangkat sungguhan dan menyingkap peta OID
  // kami, jadi gerbangnya sama dengan menyetel polling: "Kelola collector & polling".
  // Sengaja TIDAK digerbang `snmpEnabled` — justru sebelum SNMP dinyalakan-lah operator
  // ingin membuktikan community & OID-nya benar.
  const canDiagnose = can('monitoring.collector.manage')

  const [olt, setOlt] = useState<OltView | null>(null)
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [tab, setTab] = useState<Tab>('ringkasan')
  const [editing, setEditing] = useState(false)
  // Pelanggan di daftar ONU dibuka sebagai flyout DI ATAS panel ini — bukan pindah rute.
  // Operator yang sedang membedah satu OLT biasanya memeriksa beberapa pelanggan
  // berturut-turut; membuang halaman OLT tiap kali berarti memuat & mencari ulang.
  const [detailCustomerId, setDetailCustomerId] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setOlt(await api.get<OltView>(`/api/olts/${id}`))
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) setNotFound(true)
      else toast.error(err instanceof ApiError ? err.message : 'Gagal memuat detail OLT')
    } finally {
      setLoading(false)
    }
  }, [id, toast])

  useEffect(() => {
    void load()
  }, [load])

  const remove = async () => {
    if (!olt) return
    try {
      await api.del(`/api/olts/${olt.id}`)
      toast.success(`OLT ${olt.code} dihapus`)
      onDeleted?.()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus OLT')
    }
  }

  if (loading) {
    return (
      <div className="card" style={{ display: 'grid', placeItems: 'center', padding: '3rem' }}>
        <Spinner />
      </div>
    )
  }

  if (notFound || !olt) {
    return (
      <div className="card">
        <EmptyState
          title="OLT tidak ditemukan"
          hint="Mungkin sudah dihapus atau kamu tak berizin melihatnya."
          icon={<IconInventory size={32} />}
        />
      </div>
    )
  }

  // Aksi tingkat-OLT di command bar datar ala Azure — sejajar dengan detail pelanggan &
  // panel detail ODC/ODP, jadi operator mencari "apa yang bisa kulakukan" di tempat yang
  // sama di seluruh aplikasi.
  const commands: CommandAction[] = []
  if (canMap)
    commands.push({
      key: 'map',
      label: 'Lihat di peta',
      icon: <IconMap size={16} />,
      onClick: () =>
        onShowOnMap ? onShowOnMap() : navigate('/map', mapFocusState('olt', olt.id, olt.location)),
    })
  if (canUpdate)
    commands.push({ key: 'edit', label: 'Edit', icon: <Pencil size={16} />, onClick: () => setEditing(true), dividerBefore: commands.length > 0 })
  if (canDelete)
    commands.push({ key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => void remove(), dividerBefore: commands.length > 0 })

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div className="stack" style={{ gap: '0.35rem' }}>
        <div className="row wrap" style={{ gap: '0.5rem', alignItems: 'center' }}>
          {!compact && <h1 className="page-title" style={{ margin: 0 }}>{olt.code}</h1>}
          <StatusBadge status={olt.status} />
          <Badge>{olt.vendor}</Badge>
          {olt.pollable ? <Badge tone="good">SNMP siap</Badge> : <Badge tone="neutral">SNMP belum lengkap</Badge>}
        </div>
        {/* Di blade, header-nya sudah memuat nama & site — jangan diulang di badan. */}
        {!compact && (
          <p className="page-sub" style={{ margin: 0 }}>
            {olt.name}
            {olt.siteName ? ` · Site ${olt.siteName}` : ''}
          </p>
        )}
      </div>

      {commands.length > 0 && <CommandBar actions={commands} />}

      <Tabs
        tabs={[
          { key: 'ringkasan' as Tab, label: 'Ringkasan' },
          { key: 'pon' as Tab, label: 'PON Port', badge: olt.ponPortCount || undefined },
          ...(canOnuList ? [{ key: 'onu' as Tab, label: 'ONU' }] : []),
          ...(canProvisioning ? [{ key: 'onubaru' as Tab, label: 'ONU Baru' }] : []),
          ...(canDiagnose ? [{ key: 'diagnostik' as Tab, label: 'Diagnostik' }] : []),
        ]}
        active={tab}
        onChange={setTab}
      />

      {tab === 'ringkasan' && <RingkasanTab olt={olt} canUpdate={canUpdate} onSaved={load} />}
      {tab === 'pon' && <PonPortTab oltId={olt.id} canUpdate={canUpdate} canDrill={canDrill} onChanged={load} />}
      {tab === 'onu' && canOnuList && (
        <OltRegisteredOnus oltId={olt.id} onOpenCustomer={canCustomer ? setDetailCustomerId : undefined} />
      )}
      {tab === 'onubaru' && canProvisioning && <DiscoveredOnuInbox oltId={olt.id} />}
      {tab === 'diagnostik' && canDiagnose && <SnmpDiagnosticPanel oltId={olt.id} />}

      {editing && (
        <EditOltModal
          olt={olt}
          onClose={() => setEditing(false)}
          onSaved={() => {
            setEditing(false)
            void load()
          }}
        />
      )}

      <CustomerDetailBlade
        customerId={detailCustomerId}
        onClose={() => setDetailCustomerId(null)}
        // Saat panel OLT ini sendiri menumpang di atas peta, "Lihat di peta" pelanggan
        // harus menyingkirkan KEDUA panel dulu — kalau tidak, peta memang bergeser ke
        // pelanggannya tapi tetap tertutup dan operator mengira tombolnya mati.
        onShowOnMap={
          onShowOnMap
            ? (focus) => {
                setDetailCustomerId(null)
                onShowOnMap()
                navigate('/map', focus)
              }
            : undefined
        }
      />
    </div>
  )
}

/**
 * Detail satu OLT sebagai rute tersendiri (`/olts/:id`) — bukan panel peta.
 *
 * Pembungkus tipis di atas [OltDetail]: menambah tautan "kembali" yang menyesuaikan
 * asalnya (baris Inventaris / marker peta) lewat `location.state`, lalu mendelegasikan
 * seluruh isi ke komponen bersama. Hapus OLT → kembali ke asal.
 */
export function OltDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const back = (location.state as { backTo?: string; backLabel?: string } | null) ?? null
  const backTo = back?.backTo ?? '/inventory'
  const backLabel = back?.backLabel ?? 'Inventaris'
  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <BackLink label={backLabel} onClick={() => navigate(backTo)} />
      <OltDetail oltId={id} onDeleted={() => navigate(backTo)} />
    </div>
  )
}

/** Tab ringkasan: identitas perangkat, kesiapan SNMP, dan peta lokasi yang bisa dipindah. */
function RingkasanTab({ olt, canUpdate, onSaved }: { olt: OltView; canUpdate: boolean; onSaved: () => void }) {
  const toast = useToast()
  const [lon, setLon] = useState(String(olt.location.longitude))
  const [lat, setLat] = useState(String(olt.location.latitude))
  const [saving, setSaving] = useState(false)

  // Selaraskan pin dengan koordinat tersimpan setiap kali OLT dimuat ulang
  // (mis. setelah lokasi disimpan), agar tombol "Simpan" balik tersembunyi.
  useEffect(() => {
    setLon(String(olt.location.longitude))
    setLat(String(olt.location.latitude))
  }, [olt.location.longitude, olt.location.latitude])

  // Bandingkan sebagai angka agar beda format desimal ("106.995" vs "106.995000")
  // tak dianggap "pindah".
  const moved =
    lon.trim() !== '' &&
    lat.trim() !== '' &&
    (Number(lon) !== olt.location.longitude || Number(lat) !== olt.location.latitude)

  const saveLocation = async () => {
    if (!moved) return
    setSaving(true)
    try {
      // PUT butuh badan OltRequest utuh; kirim ulang SEMUA field kini + koordinat
      // baru. Field non-rahasia (deskripsi, SNMP/Web) di-set langsung oleh domain,
      // jadi kalau dihilangkan di sini PUT akan meresetnya ke default — makanya
      // ikut dikirim. `snmpCommunity`/`webPassword` sengaja diabaikan (kosong =
      // pertahankan yang terenkripsi).
      await api.put(`/api/olts/${olt.id}`, {
        siteId: olt.siteId,
        code: olt.code,
        name: olt.name,
        vendor: olt.vendor,
        model: olt.model,
        managementIp: olt.managementIp,
        snmpPort: olt.snmpPort,
        description: olt.description,
        snmpEnabled: olt.snmpEnabled,
        snmpVersion: olt.snmpVersion,
        webEnabled: olt.webEnabled,
        webProtocol: olt.webProtocol,
        webPort: olt.webPort,
        webUsername: olt.webUsername,
        location: { longitude: Number(lon), latitude: Number(lat) },
      })
      toast.success('Lokasi OLT diperbarui')
      onSaved()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan lokasi')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div className="card stack">
        <h3 style={{ margin: 0 }}>Informasi perangkat</h3>
        <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))' }}>
          <Field label="Site" value={olt.siteName ?? '—'} />
          <Field label="Vendor (hardware type)" value={olt.vendor} />
          <Field label="Model" value={olt.model ?? '—'} />
          <Field label="IP manajemen" value={olt.managementIp ?? '—'} />
          <Field label="Jumlah PON port" value={String(olt.ponPortCount)} />
        </div>
        {olt.description && <Field label="Deskripsi" value={olt.description} />}
      </div>

      <div className="card stack">
        <h3 style={{ margin: 0 }}>Monitoring SNMP</h3>
        <div className="row wrap" style={{ gap: '0.5rem' }}>
          {olt.snmpEnabled ? <Badge tone="accent">SNMP aktif</Badge> : <Badge tone="neutral">SNMP nonaktif</Badge>}
          {olt.pollable ? <Badge tone="good">Siap dipolling</Badge> : <Badge tone="neutral">Belum lengkap</Badge>}
          {olt.snmpConfigured ? <Badge tone="accent">Community tersimpan</Badge> : <Badge tone="neutral">Community belum diset</Badge>}
          <Badge>{olt.snmpVersion.toLowerCase()}</Badge>
          <Badge>Port {olt.snmpPort}</Badge>
        </div>
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          {!olt.snmpEnabled
            ? 'Kanal SNMP dimatikan — OLT ini dikelola lewat Web UI (mis. HSGQ). Tak ada polling SNMP yang dilakukan.'
            : olt.pollable
              ? 'Server memolling OLT ini via SNMP untuk menaik-turunkan alarm jangkauan & membaca telemetri ONU di hilirnya.'
              : 'Lengkapi vendor yang didukung, IP manajemen, dan SNMP community lewat tombol Edit agar OLT bisa dipolling.'}
        </p>
      </div>

      <div className="card stack">
        <h3 style={{ margin: 0 }}>Web UI / HTTP</h3>
        <div className="row wrap" style={{ gap: '0.5rem' }}>
          {olt.webEnabled ? <Badge tone="accent">Web aktif</Badge> : <Badge tone="neutral">Web nonaktif</Badge>}
          <Badge>{olt.webProtocol}</Badge>
          {olt.webPort != null && <Badge>Port {olt.webPort}</Badge>}
          {olt.webUsername && <Badge>User {olt.webUsername}</Badge>}
          {olt.webPasswordConfigured ? <Badge tone="accent">Password tersimpan</Badge> : <Badge tone="neutral">Password belum diset</Badge>}
        </div>
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          {olt.webEnabled
            ? 'Kanal Web UI dipakai untuk mengambil metrik suhu & daya optik, atau (mis. HSGQ) sebagai manajemen langsung lewat HTTP.'
            : 'Kanal Web UI dimatikan. Aktifkan lewat tombol Edit bila ingin menarik metrik suhu/optik atau mengelola OLT via HTTP.'}
        </p>
      </div>

      <div className="card stack">
        <h3 style={{ margin: 0 }}>Lokasi</h3>
        <p className="muted tnum" style={{ margin: 0, fontSize: '0.85rem' }}>
          {olt.location.latitude.toFixed(6)}, {olt.location.longitude.toFixed(6)}
        </p>
        {canUpdate ? (
          <>
            <LocationPicker
              longitude={lon}
              latitude={lat}
              onChange={(lo, la) => {
                setLon(lo)
                setLat(la)
              }}
              height={320}
            />
            {moved && (
              <div className="row">
                <Button variant="primary" disabled={saving} onClick={() => void saveLocation()}>
                  Simpan lokasi
                </Button>
                <Button
                  variant="subtle"
                  disabled={saving}
                  onClick={() => {
                    setLon(String(olt.location.longitude))
                    setLat(String(olt.location.latitude))
                  }}
                >
                  Batal
                </Button>
              </div>
            )}
            <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
              Klik peta atau seret pin untuk memindahkan OLT, lalu simpan. Kosong = mengikuti koordinat site saat dibuat.
            </p>
          </>
        ) : (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Kamu tak punya izin memindahkan OLT ini.
          </p>
        )}
      </div>
    </div>
  )
}

/** Tab PON port: daftar port slot/kartu OLT — dasar penautan ODC di hilir. */
function PonPortTab({
  oltId,
  canUpdate,
  canDrill,
  onChanged,
}: {
  oltId: string
  canUpdate: boolean
  canDrill: boolean
  onChanged: () => void
}) {
  const toast = useToast()
  const [ports, setPorts] = useState<PonPortView[] | null>(null)
  const [label, setLabel] = useState('')
  const [busy, setBusy] = useState(false)
  // Satu PON port terbuka pada satu waktu — drill-down memuat topologinya on-demand.
  const [expanded, setExpanded] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setPorts(await api.get<PonPortView[]>(`/api/olts/${oltId}/pon-ports`))
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat PON port')
    }
  }, [oltId, toast])

  useEffect(() => {
    void load()
  }, [load])

  const add = async () => {
    if (!label.trim()) return
    setBusy(true)
    try {
      await api.post(`/api/olts/${oltId}/pon-ports`, { label: label.trim() })
      setLabel('')
      await load()
      onChanged()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menambah PON port')
    } finally {
      setBusy(false)
    }
  }

  const remove = async (p: PonPortView) => {
    try {
      await api.del(`/api/olts/pon-ports/${p.id}`)
      await load()
      onChanged()
      toast.success(`Port ${p.label} dihapus`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus PON port')
    }
  }

  return (
    <div className="card stack">
      <div className="spread" style={{ gap: '0.75rem', alignItems: 'center' }}>
        <h3 style={{ margin: 0 }}>PON Port</h3>
        {canUpdate && (
          <div className="row" style={{ gap: '0.35rem' }}>
            <TextField
              style={{ width: '7rem' }}
              placeholder="1/2/3"
              value={label}
              onChange={(_, data) => setLabel(data.value)}
            />
            <Button variant="primary" disabled={busy || !label.trim()} onClick={() => void add()}>
              <IconPlus size={14} /> Tambah
            </Button>
          </div>
        )}
      </div>
      {ports == null ? (
        <div style={{ display: 'grid', placeItems: 'center', padding: '1.5rem' }}>
          <Spinner />
        </div>
      ) : ports.length === 0 ? (
        <EmptyState
          title="Belum ada PON port"
          hint="Tambahkan port slot/kartu OLT untuk mulai menautkan ODC di hilir."
          icon={<IconInventory size={30} />}
        />
      ) : (
        <div className="stack" style={{ gap: 0 }}>
          {ports.map((p) => {
            const drillable = canDrill && p.odcCount > 0
            const isOpen = expanded === p.id
            return (
              <div key={p.id} style={{ borderTop: '1px solid var(--border)' }}>
                <div className="spread" style={{ gap: '0.5rem', alignItems: 'center', padding: '0.55rem 0' }}>
                  <button
                    type="button"
                    onClick={() => drillable && setExpanded(isOpen ? null : p.id)}
                    disabled={!drillable}
                    title={drillable ? 'Lihat ODC & ODP di bawah port ini' : undefined}
                    style={{
                      flex: 1,
                      minWidth: 0,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'flex-start',
                      gap: '0.5rem',
                      font: 'inherit',
                      color: 'inherit',
                      background: 'none',
                      border: 'none',
                      padding: 0,
                      cursor: drillable ? 'pointer' : 'default',
                    }}
                  >
                    <span aria-hidden style={{ width: '0.9rem', color: 'var(--text-3)' }}>
                      {drillable ? (isOpen ? '▾' : '▸') : ''}
                    </span>
                    <strong className="tnum">{p.label}</strong>
                    <StatusBadge status={p.status} />
                    {p.description && (
                      <span className="muted" style={{ fontSize: '0.85rem' }}>
                        {p.description}
                      </span>
                    )}
                  </button>
                  <div className="row" style={{ gap: '0.5rem', alignItems: 'center', flexShrink: 0 }}>
                    <span className="badge">{p.odcCount} ODC</span>
                    {canUpdate && (
                      <Button variant="danger" disabled={p.odcCount > 0} onClick={() => void remove(p)}>
                        Hapus
                      </Button>
                    )}
                  </div>
                </div>
                {isOpen && <PonDrilldown ponPortId={p.id} canDrillOdp={canDrill} />}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

/**
 * Drill-down satu PON port: memuat topologi ODC → ODP (FAT) beserta utilisasi port,
 * lewat `GET /api/gis/pon-ports/{id}`. Dimuat saat baris PON dibuka, bukan di awal.
 */
function PonDrilldown({ ponPortId, canDrillOdp }: { ponPortId: string; canDrillOdp: boolean }) {
  const toast = useToast()
  const [data, setData] = useState<PonPortInspection | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let alive = true
    api
      .get<PonPortInspection>(`/api/gis/pon-ports/${ponPortId}`)
      .then((d) => {
        if (alive) setData(d)
      })
      .catch((err) => {
        if (!alive) return
        setFailed(true)
        toast.error(err instanceof ApiError ? err.message : 'Gagal memuat drill-down PON')
      })
    return () => {
      alive = false
    }
  }, [ponPortId, toast])

  if (failed) return null
  if (!data) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', padding: '1rem' }}>
        <Spinner />
      </div>
    )
  }

  if (data.odcs.length === 0) {
    return (
      <p className="muted" style={{ margin: 0, padding: '0 0 0.85rem 1.4rem', fontSize: '0.85rem' }}>
        Belum ada ODC di bawah port ini.
      </p>
    )
  }

  return (
    <div className="stack" style={{ gap: '0.6rem', padding: '0.15rem 0 0.85rem 1.4rem' }}>
      <div className="muted tnum" style={{ fontSize: '0.82rem' }}>
        {data.odcCount} ODC · {data.odpCount} ODP · {data.used}/{data.capacity} port terpakai ({data.utilizationPercent}%)
      </div>
      {data.odcs.map((odc) => (
        <OdcBranchCard key={odc.odcId} odc={odc} canDrillOdp={canDrillOdp} />
      ))}
    </div>
  )
}

/** Kartu satu ODC di bawah PON: rekap utilisasi port + daftar ODP (FAT) anaknya. */
function OdcBranchCard({ odc, canDrillOdp }: { odc: PonOdcBranch; canDrillOdp: boolean }) {
  return (
    <div className="card stack" style={{ gap: '0.5rem', padding: '0.7rem', background: 'var(--surface-2)' }}>
      <div className="spread" style={{ gap: '0.5rem', alignItems: 'center' }}>
        <div className="row" style={{ gap: '0.5rem', alignItems: 'center', minWidth: 0 }}>
          <strong>{odc.code}</strong>
          {odc.energized ? <Badge tone="good">Berenergi</Badge> : <Badge tone="neutral">Tanpa uplink</Badge>}
          <span className="muted tnum" style={{ fontSize: '0.8rem' }}>
            {odc.odpCount}/{odc.legCapacity} kaki
          </span>
        </div>
        <span className="tnum" style={{ fontSize: '0.85rem', flexShrink: 0 }}>
          {odc.used}/{odc.capacity} port
        </span>
      </div>
      <UtilBar percent={odc.utilizationPercent} />
      {odc.odps.length === 0 ? (
        <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
          Belum ada ODP di ODC ini.
        </p>
      ) : (
        <div className="stack" style={{ gap: 0 }}>
          {odc.odps.map((odp) => (
            <OdpRow key={odp.odpId} odp={odp} canDrillOdp={canDrillOdp} />
          ))}
        </div>
      )}
    </div>
  )
}

/** Satu baris ODP (FAT): utilisasi port; bisa dibuka untuk melihat daftar penghuninya. */
function OdpRow({ odp, canDrillOdp }: { odp: OdpUtilization; canDrillOdp: boolean }) {
  const [open, setOpen] = useState(false)
  return (
    <div style={{ borderTop: '1px solid var(--border)' }}>
      <button
        type="button"
        onClick={() => canDrillOdp && setOpen(!open)}
        disabled={!canDrillOdp}
        title={canDrillOdp ? 'Lihat pelanggan di ODP ini' : undefined}
        style={{
          width: '100%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: '0.5rem',
          font: 'inherit',
          color: 'inherit',
          background: 'none',
          border: 'none',
          padding: '0.4rem 0',
          cursor: canDrillOdp ? 'pointer' : 'default',
        }}
      >
        <span className="row" style={{ gap: '0.4rem', alignItems: 'center', minWidth: 0 }}>
          <span aria-hidden style={{ width: '0.9rem', color: 'var(--text-3)' }}>
            {canDrillOdp ? (open ? '▾' : '▸') : ''}
          </span>
          <strong style={{ fontSize: '0.88rem' }}>{odp.code}</strong>
          <span className="muted" style={{ fontSize: '0.8rem', overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {odp.name}
          </span>
        </span>
        <span className="row" style={{ gap: '0.5rem', alignItems: 'center', flexShrink: 0 }}>
          <span className="tnum" style={{ fontSize: '0.82rem' }}>
            {odp.used}/{odp.capacity}
          </span>
          <Badge tone={utilTone(odp.utilizationPercent)}>{odp.utilizationPercent}%</Badge>
        </span>
      </button>
      {open && <OdpOccupants odpId={odp.odpId} />}
    </div>
  )
}

/** Daftar penghuni satu ODP (FAT), dimuat lewat inspeksi ODP gis saat baris dibuka. */
function OdpOccupants({ odpId }: { odpId: string }) {
  const toast = useToast()
  const [data, setData] = useState<OdpInspection | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let alive = true
    api
      .get<OdpInspection>(`/api/gis/odps/${odpId}`)
      .then((d) => {
        if (alive) setData(d)
      })
      .catch((err) => {
        if (!alive) return
        setFailed(true)
        toast.error(err instanceof ApiError ? err.message : 'Gagal memuat penghuni ODP')
      })
    return () => {
      alive = false
    }
  }, [odpId, toast])

  if (failed) return null
  if (!data) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', padding: '0.6rem' }}>
        <Spinner />
      </div>
    )
  }

  if (data.occupants.length === 0) {
    return (
      <p className="muted" style={{ margin: 0, padding: '0 0 0.6rem 1.3rem', fontSize: '0.82rem' }}>
        Belum ada pelanggan terpasang.
      </p>
    )
  }

  return (
    <div className="stack" style={{ gap: 0, padding: '0 0 0.5rem 1.3rem' }}>
      {data.occupants.map((o) => (
        <div
          key={o.onuId}
          className="spread"
          style={{ gap: '0.5rem', alignItems: 'center', padding: '0.3rem 0', fontSize: '0.84rem' }}
        >
          <span className="row" style={{ gap: '0.45rem', alignItems: 'center', minWidth: 0 }}>
            <span className="badge">Port {o.portNumber}</span>
            <strong style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{o.customerName}</strong>
            <span className="muted tnum">{o.customerCode}</span>
          </span>
          <StatusBadge status={o.onuStatus} label={onuStatusLabel(o.onuStatus)} />
        </div>
      ))}
    </div>
  )
}

/** Bar utilisasi port: hijau→kuning→merah menurut persentase pemakaian. */
function UtilBar({ percent }: { percent: number }) {
  const clamped = Math.max(0, Math.min(100, percent))
  return (
    <div
      style={{ height: 6, borderRadius: 999, background: 'var(--border)', overflow: 'hidden' }}
      role="progressbar"
      aria-valuenow={clamped}
      aria-valuemin={0}
      aria-valuemax={100}
    >
      <div style={{ width: `${clamped}%`, height: '100%', background: `var(--${utilTone(percent)})` }} />
    </div>
  )
}

/** Ambang warna utilisasi: penuh (≥90%) merah, padat (≥70%) kuning, lega hijau. */
function utilTone(percent: number): Tone {
  if (percent >= 90) return 'critical'
  if (percent >= 70) return 'warning'
  return 'good'
}

/** Modal ubah identitas & SNMP OLT. Kode tak bisa diubah; lokasi diedit di tab Ringkasan. */
function EditOltModal({ olt, onClose, onSaved }: { olt: OltView; onClose: () => void; onSaved: () => void }) {
  const toast = useToast()
  const [sites, setSites] = useState<SiteView[]>([])
  const [siteId, setSiteId] = useState(olt.siteId)
  const [name, setName] = useState(olt.name)
  const [vendor, setVendor] = useState(olt.vendor)
  const [model, setModel] = useState(olt.model ?? '')
  const [managementIp, setManagementIp] = useState(olt.managementIp ?? '')
  const [description, setDescription] = useState(olt.description ?? '')
  const [snmpEnabled, setSnmpEnabled] = useState(olt.snmpEnabled)
  const [snmpCommunity, setSnmpCommunity] = useState('')
  const [snmpVersion, setSnmpVersion] = useState<SnmpVersion>(olt.snmpVersion)
  const [snmpPort, setSnmpPort] = useState(String(olt.snmpPort))
  const [webEnabled, setWebEnabled] = useState(olt.webEnabled)
  const [webProtocol, setWebProtocol] = useState<WebProtocol>(olt.webProtocol)
  const [webPort, setWebPort] = useState(olt.webPort != null ? String(olt.webPort) : '')
  const [webUsername, setWebUsername] = useState(olt.webUsername ?? '')
  const [webPassword, setWebPassword] = useState('')
  const [status, setStatus] = useState<AssetStatus>(olt.status)
  const [saving, setSaving] = useState(false)

  // Form kotor bila salah satu field menyimpang dari nilai awal OLT; rahasia
  // (community/password) dianggap perubahan begitu diisi walau field aslinya kosong.
  const dirty =
    siteId !== olt.siteId ||
    name !== olt.name ||
    vendor !== olt.vendor ||
    model !== (olt.model ?? '') ||
    managementIp !== (olt.managementIp ?? '') ||
    description !== (olt.description ?? '') ||
    snmpEnabled !== olt.snmpEnabled ||
    snmpCommunity !== '' ||
    snmpVersion !== olt.snmpVersion ||
    snmpPort !== String(olt.snmpPort) ||
    webEnabled !== olt.webEnabled ||
    webProtocol !== olt.webProtocol ||
    webPort !== (olt.webPort != null ? String(olt.webPort) : '') ||
    webUsername !== (olt.webUsername ?? '') ||
    webPassword !== '' ||
    status !== olt.status

  useEffect(() => {
    let alive = true
    api
      .get<PageResponse<SiteView>>('/api/sites?size=100')
      .then((page) => {
        if (alive) setSites(page.content)
      })
      .catch(() => {
        /* pemilih site opsional untuk pemuatan — site kini tetap terpilih */
      })
    return () => {
      alive = false
    }
  }, [])

  const save = async () => {
    if (!name.trim() || !siteId) return
    setSaving(true)
    try {
      const body: Record<string, unknown> = {
        siteId,
        code: olt.code,
        name: name.trim(),
        vendor,
        model: model.trim() || null,
        managementIp: managementIp.trim() || null,
        snmpPort: Number(snmpPort) || 161,
        description: description.trim() || null,
        snmpEnabled,
        snmpVersion,
        webEnabled,
        webProtocol,
        webPort: webPort.trim() ? Number(webPort) : null,
        webUsername: webUsername.trim() || null,
      }
      // Kosong = pertahankan rahasia terenkripsi; hanya kirim saat diisi.
      if (snmpCommunity.trim()) body.snmpCommunity = snmpCommunity.trim()
      if (webPassword.trim()) body.webPassword = webPassword.trim()
      await api.put(`/api/olts/${olt.id}`, body)
      // Status punya endpoint tersendiri; ubah hanya bila berbeda.
      if (status !== olt.status) await api.put(`/api/olts/${olt.id}/status`, { status })
      toast.success(`OLT ${olt.code} diperbarui`)
      onSaved()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memperbarui OLT')
      setSaving(false)
    }
  }

  return (
    <Blade
      open
      title={`Edit ${olt.code}`}
      subtitle="Identitas, SNMP & akses Web UI OLT. Kode & lokasi diubah di tab Ringkasan."
      size="full"
      className="blade-edit"
      dirty={dirty}
      onClose={onClose}
      footer={
        <>
          <Button variant="primary" onClick={() => void save()} disabled={saving || !name.trim() || !siteId}>
            Simpan
          </Button>
          <Button variant="subtle" onClick={onClose} disabled={saving}>
            Batal
          </Button>
        </>
      }
    >
      <div className="stack" style={{ gap: '0.75rem' }}>
        <div className="row" style={{ gap: '0.5rem' }}>
          <SelectField label="Site" value={siteId} onChange={(_, data) => setSiteId(data.value)} style={{ flex: 1 }}>
            {sites.map((s) => (
              <option key={s.id} value={s.id}>
                {s.code} — {s.name}
              </option>
            ))}
          </SelectField>
          <TextField label="Nama" value={name} onChange={(_, data) => setName(data.value)} style={{ flex: 1 }} />
        </div>
        <div className="row" style={{ gap: '0.5rem' }}>
          <SelectField label="Vendor" value={vendor} onChange={(_, data) => setVendor(data.value)} style={{ flex: 1 }}>
            {VENDORS.map((v) => (
              <option key={v} value={v}>
                {v}
              </option>
            ))}
            {!VENDORS.includes(vendor) && <option value={vendor}>{vendor}</option>}
          </SelectField>
          <TextField
            label={
              <>
                Model <span className="muted">(opsional)</span>
              </>
            }
            value={model}
            onChange={(_, data) => setModel(data.value)}
            placeholder="C320"
            style={{ flex: 1 }}
          />
          <SelectField
            label="Status"
            value={status}
            onChange={(_, data) => setStatus(data.value as AssetStatus)}
            style={{ flex: 1 }}
          >
            {STATUS_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </SelectField>
        </div>
        <div className="row" style={{ gap: '0.5rem' }}>
          <TextField
            label={
              <>
                IP manajemen <span className="muted">(opsional)</span>
              </>
            }
            value={managementIp}
            onChange={(_, data) => setManagementIp(data.value)}
            placeholder="10.10.1.2"
            style={{ flex: 1 }}
          />
          <TextField
            label={
              <>
                Deskripsi <span className="muted">(opsional)</span>
              </>
            }
            value={description}
            onChange={(_, data) => setDescription(data.value)}
            placeholder="Lokasi rak, kontak vendor, atau ID kontrak…"
            style={{ flex: 2 }}
          />
        </div>

        {/* Kanal SNMP */}
        <div className="stack" style={{ gap: '0.6rem', borderTop: '1px solid var(--border)', paddingTop: '0.85rem' }}>
          <Checkbox
            label="Aktifkan SNMP untuk OLT ini"
            checked={snmpEnabled}
            onChange={(_, data) => setSnmpEnabled(!!data.checked)}
          />
          {snmpEnabled && (
            <div className="row" style={{ gap: '0.5rem' }}>
              <TextField
                label={
                  <>
                    Community string <span className="muted">(RO/RW)</span>
                  </>
                }
                type="password"
                value={snmpCommunity}
                onChange={(_, data) => setSnmpCommunity(data.value)}
                placeholder={olt.snmpConfigured ? '(tersimpan)' : 'public'}
                style={{ flex: 1 }}
              />
              <SelectField
                label="Versi"
                value={snmpVersion}
                onChange={(_, data) => setSnmpVersion(data.value as SnmpVersion)}
                style={{ width: 120 }}
              >
                {SNMP_VERSIONS.map((v) => (
                  <option key={v.value} value={v.value}>
                    {v.label}
                  </option>
                ))}
              </SelectField>
              <TextField
                label="Port SNMP"
                type="number"
                min={1}
                max={65535}
                value={snmpPort}
                onChange={(_, data) => setSnmpPort(data.value)}
                style={{ width: 110 }}
              />
            </div>
          )}
        </div>

        {/* Kanal Web UI / HTTP */}
        <div className="stack" style={{ gap: '0.6rem', borderTop: '1px solid var(--border)', paddingTop: '0.85rem' }}>
          <Checkbox
            label="Aktifkan Web UI / HTTP"
            checked={webEnabled}
            onChange={(_, data) => setWebEnabled(!!data.checked)}
          />
          {webEnabled && (
            <div className="row" style={{ gap: '0.5rem' }}>
              <SelectField
                label="Protokol"
                value={webProtocol}
                onChange={(_, data) => setWebProtocol(data.value as WebProtocol)}
                style={{ width: 120 }}
              >
                <option value="HTTP">HTTP</option>
                <option value="HTTPS">HTTPS</option>
              </SelectField>
              <TextField
                label="Port Web"
                type="number"
                min={1}
                max={65535}
                value={webPort}
                onChange={(_, data) => setWebPort(data.value)}
                placeholder="80"
                style={{ width: 120 }}
              />
              <TextField
                label={
                  <>
                    Web Username <span className="muted">(opsional)</span>
                  </>
                }
                value={webUsername}
                onChange={(_, data) => setWebUsername(data.value)}
                placeholder="admin"
                style={{ flex: 1 }}
              />
              <TextField
                label="Web Password"
                type="password"
                value={webPassword}
                onChange={(_, data) => setWebPassword(data.value)}
                placeholder={olt.webPasswordConfigured ? '(tersimpan)' : 'password Web UI'}
                style={{ flex: 1 }}
              />
            </div>
          )}
        </div>

        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Community string &amp; password Web disimpan terenkripsi dan tak pernah ditampilkan. Kosongkan untuk
          mempertahankan yang tersimpan. Kode OLT tak bisa diubah.
        </p>
      </div>
    </Blade>
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

function BackLink({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <Button variant="subtle" onClick={onClick} style={{ alignSelf: 'flex-start', gap: '0.35rem' }}>
      <span aria-hidden>←</span> {label}
    </Button>
  )
}
