import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import type { AssetStatus, OdcView, OdpView, OltView, PonPortView, SiteView, SnmpVersion, WebProtocol } from '../api/network'
import { Link2, Plus, RefreshCw, Trash2 } from 'lucide-react'
import { useCan } from '../auth/useCan'
import { DataTable, type Column, type RowAction } from '../components/DataTable'
import { CommandBar, type CommandAction } from '../components/CommandBar'
import { PageHeader } from '../components/PageHeader'
import { LocationPicker } from '../components/LocationPicker'
import { Blade } from '../components/Blade'
import { EmptyState, SearchInput, StatusBadge, Tabs, Toolbar, useConfirm, useToast } from '../components/ui'
import { IconInventory } from '../components/icons'

/**
 * Inventory jaringan dalam satu halaman bertab.
 *
 * Dijadikan satu halaman karena keempat aset ini dikelola berurutan — site dulu,
 * lalu OLT di atasnya, lalu ODC, lalu ODP — sehingga berpindah antar tab lebih
 * masuk akal daripada berpindah antar halaman. Tiap tab memakai tabel bisa-urut
 * yang seragam dengan bilah pencarian & filter di atasnya.
 */

type Tab = 'sites' | 'olts' | 'odcs' | 'odps'

const TABS: Array<{ key: Tab; label: string; permission: string }> = [
  { key: 'sites', label: 'Site / POP', permission: 'network.site.view' },
  { key: 'olts', label: 'OLT', permission: 'network.olt.view' },
  { key: 'odcs', label: 'ODC', permission: 'network.odc.view' },
  { key: 'odps', label: 'ODP', permission: 'network.odp.view' },
]

const SPLITTER_RATIOS = ['1:2', '1:4', '1:8', '1:16', '1:32', '1:64']
const VENDORS = ['ZTE', 'HUAWEI', 'FIBERHOME', 'NOKIA', 'HSGQ', 'OTHER']

const SNMP_VERSIONS: { value: SnmpVersion; label: string }[] = [
  { value: 'V1', label: 'v1' },
  { value: 'V2C', label: 'v2c' },
  { value: 'V3', label: 'v3' },
]

/**
 * HSGQ (EPON) punya kanal Web UI API (HTTP) sebagai manajemen langsung, DI SAMPING
 * SNMP — adapter EPON HSGQ tetap mem-polling perangkat lewat SNMP. Maka HSGQ
 * bersifat dual-channel: input SNMP tetap tersedia dan default-nya kedua kanal
 * menyala. Vendor lain (ZTE, Huawei, dst.) SNMP-first: Web hanya pelengkap metrik
 * suhu/optik dan mati kecuali dinyalakan. `isWebManaged` menandai vendor yang
 * memang mengekspos Web UI management (kini hanya HSGQ).
 */
function isWebManaged(vendor: string): boolean {
  return vendor === 'HSGQ'
}

const ASSET_STATUS_OPTIONS: { value: AssetStatus | ''; label: string }[] = [
  { value: '', label: 'Semua status' },
  { value: 'PLANNED', label: 'Rencana' },
  { value: 'ACTIVE', label: 'Aktif' },
  { value: 'MAINTENANCE', label: 'Perawatan' },
  { value: 'INACTIVE', label: 'Nonaktif' },
]

/** Apakah salah satu kolom teks memuat kata kunci (kata kunci sudah huruf kecil). */
function matchesQuery(fields: Array<string | null | undefined>, q: string): boolean {
  if (!q) return true
  return fields.some((f) => (f ?? '').toLowerCase().includes(q))
}

export function InventoryPage() {
  const { can } = useCan()
  const visible = TABS.filter((tab) => can(tab.permission))
  const [tab, setTab] = useState<Tab>(visible[0]?.key ?? 'sites')

  if (visible.length === 0) {
    return (
      <div className="card">
        <h3 style={{ marginTop: 0 }}>Akses ditolak</h3>
        <p className="muted">Kamu tidak punya izin melihat inventory jaringan.</p>
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <PageHeader
        title="Inventory Jaringan"
        subtitle="Kelola site, OLT, ODC, dan ODP — dari POP sampai kotak terminasi."
      />
      <Tabs tabs={visible} active={tab} onChange={setTab} />
      {tab === 'sites' && <SitesTab />}
      {tab === 'olts' && <OltsTab />}
      {tab === 'odcs' && <OdcsTab />}
      {tab === 'odps' && <OdpsTab />}
    </div>
  )
}

/** Hook pemuat daftar bersama: menyeragamkan penanganan galat, toast, dan muat-ulang. */
function useList<T>(path: string) {
  const toast = useToast()
  const [items, setItems] = useState<T[]>([])
  const [loading, setLoading] = useState(true)

  const reload = useCallback(async () => {
    try {
      const page = await api.get<PageResponse<T>>(`${path}?size=100`)
      setItems(page.content)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat data')
    } finally {
      setLoading(false)
    }
  }, [path, toast])

  useEffect(() => {
    void reload()
  }, [reload])

  const run = async (action: () => Promise<unknown>, okMessage?: string) => {
    try {
      await action()
      await reload()
      if (okMessage) toast.success(okMessage)
      return true
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
      return false
    }
  }

  return { items, loading, reload, run }
}

function LocationFields({
  longitude,
  latitude,
  onChange,
}: {
  longitude: string
  latitude: string
  onChange: (longitude: string, latitude: string) => void
}) {
  return (
    <label>
      <span>Lokasi</span>
      <LocationPicker longitude={longitude} latitude={latitude} onChange={onChange} height={240} />
    </label>
  )
}

function SitesTab() {
  const { can } = useCan()
  const confirm = useConfirm()
  const { items, loading, reload, run } = useList<SiteView>('/api/sites')
  const empty = { code: '', name: '', address: '', longitude: '', latitude: '' }
  const [draft, setDraft] = useState<typeof empty | null>(null)
  const [initialDraft, setInitialDraft] = useState<typeof empty | null>(null)
  const openDraft = (d: typeof empty) => { setDraft(d); setInitialDraft(d) }
  const closeDraft = () => { setDraft(null); setInitialDraft(null) }
  const dirty = draft != null && JSON.stringify(draft) !== JSON.stringify(initialDraft)
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [deleting, setDeleting] = useState(false)
  const canDelete = can('network.site.delete')

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter((s) => matchesQuery([s.code, s.name, s.address], q))
  }, [items, query])

  const deleteSelected = async () => {
    const ids = [...selected]
    if (ids.length === 0) return
    if (!(await confirm({ title: 'Hapus site', message: `Hapus ${ids.length} site terpilih?`, confirmLabel: 'Hapus', danger: true }))) return
    setDeleting(true)
    await run(async () => {
      await Promise.all(ids.map((id) => api.del(`/api/sites/${id}`)))
      setSelected(new Set())
    })
    setDeleting(false)
  }

  const columns: Column<SiteView>[] = [
    { key: 'code', header: 'Kode', sortValue: (s) => s.code, cell: (s) => s.code },
    {
      key: 'name',
      header: 'Nama',
      sortValue: (s) => s.name,
      cell: (s) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <strong>{s.name}</strong>
          <span className="muted" style={{ fontSize: '0.8rem' }}>{s.address ?? '—'}</span>
        </div>
      ),
    },
    { key: 'olt', header: 'OLT', align: 'right', sortValue: (s) => s.oltCount, cell: (s) => s.oltCount },
    {
      key: 'coord',
      header: 'Koordinat',
      cell: (s) => (
        <span className="muted" style={{ fontSize: '0.8rem' }}>
          {s.location.latitude.toFixed(5)}, {s.location.longitude.toFixed(5)}
        </span>
      ),
    },
  ]

  const rowActions = (s: SiteView): RowAction[] =>
    canDelete
      ? [
          {
            key: 'delete',
            label: 'Hapus',
            icon: <Trash2 size={16} />,
            onClick: () => void (async () => {
              if (await confirm({ title: 'Hapus site', message: `Hapus site ${s.code}?`, confirmLabel: 'Hapus', danger: true })) void run(() => api.del(`/api/sites/${s.id}`))
            })(),
          },
        ]
      : []

  const primary: CommandAction | undefined = can('network.site.create')
    ? { key: 'create', label: 'Tambah site', icon: <Plus size={16} />, onClick: () => openDraft({ ...empty }) }
    : undefined
  const actions: CommandAction[] = []
  if (canDelete)
    actions.push({
      key: 'delete',
      label: 'Hapus',
      icon: <Trash2 size={16} />,
      onClick: () => void deleteSelected(),
      disabled: selected.size === 0 || deleting,
    })
  actions.push({
    key: 'refresh',
    label: 'Segarkan',
    icon: <RefreshCw size={16} />,
    onClick: () => void reload(),
    dividerBefore: canDelete,
  })

  return (
    <div className="stack">
      <CommandBar primary={primary} actions={actions} />

      <Blade
        open={draft != null}
        title="Tambah site"
        subtitle="Daftarkan site/POP baru."
        size="sm"
        dirty={dirty}
        onClose={closeDraft}
        footer={
          <>
            <button
              className="primary"
              onClick={() =>
                void run(async () => {
                  await api.post('/api/sites', {
                    code: draft!.code,
                    name: draft!.name,
                    address: draft!.address || null,
                    location: { longitude: Number(draft!.longitude), latitude: Number(draft!.latitude) },
                  })
                  closeDraft()
                })
              }
            >
              Simpan
            </button>
            <button onClick={closeDraft}>Batal</button>
          </>
        }
      >
        {draft && (
          <div className="stack">
            <div className="row">
              <label style={{ flex: 1 }}>
                <span>Kode</span>
                <input value={draft.code} onChange={(e) => setDraft({ ...draft, code: e.target.value })} placeholder="POP-BKS" />
              </label>
              <label style={{ flex: 2 }}>
                <span>Nama</span>
                <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
              </label>
            </div>
            <label>
              <span>Alamat</span>
              <input value={draft.address} onChange={(e) => setDraft({ ...draft, address: e.target.value })} />
            </label>
            <LocationFields
              longitude={draft.longitude}
              latitude={draft.latitude}
              onChange={(longitude, latitude) => setDraft({ ...draft, longitude, latitude })}
            />
          </div>
        )}
      </Blade>

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari kode, nama, atau alamat…" />
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(s) => s.id}
        loading={loading}
        initialSort={{ key: 'code', dir: 'asc' }}
        selection={canDelete ? { selected, onChange: setSelected } : undefined}
        rowActions={canDelete ? rowActions : undefined}
        empty={
          <EmptyState
            title={query ? 'Tidak ada site yang cocok' : 'Belum ada site'}
            hint={query ? 'Coba ubah kata kunci.' : 'Tambahkan site/POP pertama untuk mulai memasang OLT.'}
            icon={<IconInventory size={32} />}
          />
        }
      />
    </div>
  )
}

function OltsTab() {
  const { can } = useCan()
  const confirm = useConfirm()
  const navigate = useNavigate()
  const { items, loading, reload, run } = useList<OltView>('/api/olts')
  const { items: sites } = useList<SiteView>('/api/sites')
  const empty = {
    siteId: '',
    code: '',
    name: '',
    vendor: 'ZTE',
    model: '',
    description: '',
    managementIp: '',
    snmpEnabled: true,
    snmpCommunity: '',
    snmpVersion: 'V2C' as SnmpVersion,
    snmpPort: '161',
    webEnabled: false,
    webProtocol: 'HTTP' as WebProtocol,
    webPort: '',
    webUsername: '',
    webPassword: '',
    longitude: '',
    latitude: '',
  }
  type OltDraft = typeof empty
  const [draft, setDraft] = useState<OltDraft | null>(null)
  const [initialDraft, setInitialDraft] = useState<OltDraft | null>(null)
  const openDraft = (d: OltDraft) => { setDraft(d); setInitialDraft(d) }
  const closeDraft = () => { setDraft(null); setInitialDraft(null) }
  const dirty = draft != null && JSON.stringify(draft) !== JSON.stringify(initialDraft)

  // Ganti vendor = ganti kanal manajemen yang masuk akal: HSGQ dual-channel
  // (SNMP EPON + Web UI, keduanya menyala), selainnya SNMP-first (Web pelengkap,
  // mati kecuali dinyalakan).
  const changeVendor = (d: OltDraft, vendor: string): OltDraft =>
    isWebManaged(vendor)
      ? { ...d, vendor, snmpEnabled: true, webEnabled: true }
      : { ...d, vendor, snmpEnabled: true, webEnabled: d.webEnabled }
  const [ports, setPorts] = useState<Record<string, string>>({})
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<AssetStatus | ''>('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [deleting, setDeleting] = useState(false)
  const canDelete = can('network.olt.delete')

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter(
      (o) =>
        matchesQuery([o.code, o.name, o.siteName, o.vendor, o.managementIp], q) &&
        (!statusFilter || o.status === statusFilter),
    )
  }, [items, query, statusFilter])

  const deleteSelected = async () => {
    const ids = [...selected]
    if (ids.length === 0) return
    if (!(await confirm({ title: 'Hapus OLT', message: `Hapus ${ids.length} OLT terpilih?`, confirmLabel: 'Hapus', danger: true }))) return
    setDeleting(true)
    await run(async () => {
      await Promise.all(ids.map((id) => api.del(`/api/olts/${id}`)))
      setSelected(new Set())
    })
    setDeleting(false)
  }

  const columns: Column<OltView>[] = [
    {
      key: 'code',
      header: 'Kode',
      sortValue: (o) => o.code,
      cell: (o) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <strong>{o.code}</strong>
          <span className="muted" style={{ fontSize: '0.8rem' }}>{o.name}</span>
        </div>
      ),
    },
    { key: 'site', header: 'Site', sortValue: (o) => o.siteName, cell: (o) => <span className="muted">{o.siteName ?? '—'}</span> },
    {
      key: 'vendor',
      header: 'Vendor',
      sortValue: (o) => o.vendor,
      cell: (o) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <span>{o.vendor}</span>
          <span className="muted" style={{ fontSize: '0.8rem' }}>{o.managementIp ?? '—'}</span>
        </div>
      ),
    },
    { key: 'status', header: 'Status', sortValue: (o) => o.status, cell: (o) => <StatusBadge status={o.status} /> },
    {
      key: 'monitoring',
      header: 'Monitoring',
      sortValue: (o) => (o.pollable ? 1 : 0),
      cell: (o) => <span className="badge">{o.pollable ? 'siap dipolling' : 'belum lengkap'}</span>,
    },
    {
      key: 'ponPorts',
      header: 'PON port',
      sortValue: (o) => o.ponPortCount,
      cell: (o) => (
        <div className="stack" style={{ gap: '0.35rem' }}>
          <span>{o.ponPortCount}</span>
          {can('network.olt.update') && (
            // Baris kini bisa diklik menuju detail OLT; hentikan bubbling agar
            // mengetik/klik kontrol PON port di sel ini tak ikut bernavigasi.
            <div className="row" style={{ gap: '0.3rem' }} onClick={(e) => e.stopPropagation()}>
              <input
                style={{ width: '5.5rem' }}
                placeholder="1/2/3"
                value={ports[o.id] ?? ''}
                onChange={(e) => setPorts({ ...ports, [o.id]: e.target.value })}
              />
              <button
                onClick={() =>
                  void run(async () => {
                    await api.post(`/api/olts/${o.id}/pon-ports`, { label: ports[o.id] })
                    setPorts({ ...ports, [o.id]: '' })
                  })
                }
              >
                + port
              </button>
            </div>
          )}
        </div>
      ),
    },
  ]

  const rowActions = (o: OltView): RowAction[] =>
    canDelete
      ? [
          {
            key: 'delete',
            label: 'Hapus',
            icon: <Trash2 size={16} />,
            onClick: () => void (async () => {
              if (await confirm({ title: 'Hapus OLT', message: `Hapus OLT ${o.code}?`, confirmLabel: 'Hapus', danger: true })) void run(() => api.del(`/api/olts/${o.id}`))
            })(),
          },
        ]
      : []

  const primary: CommandAction | undefined = can('network.olt.create')
    ? {
        key: 'create',
        label: 'Tambah OLT',
        icon: <Plus size={16} />,
        onClick: () => openDraft({ ...empty, siteId: sites[0]?.id ?? '' }),
      }
    : undefined
  const actions: CommandAction[] = []
  if (canDelete)
    actions.push({
      key: 'delete',
      label: 'Hapus',
      icon: <Trash2 size={16} />,
      onClick: () => void deleteSelected(),
      disabled: selected.size === 0 || deleting,
    })
  actions.push({
    key: 'refresh',
    label: 'Segarkan',
    icon: <RefreshCw size={16} />,
    onClick: () => void reload(),
    dividerBefore: canDelete,
  })

  return (
    <div className="stack">
      <CommandBar primary={primary} actions={actions} />

      <Blade
        open={draft != null}
        title="Tambah OLT"
        subtitle="Pasang OLT di atas sebuah site."
        size="lg"
        dirty={dirty}
        onClose={closeDraft}
        footer={
          <>
            <button
              className="primary"
              onClick={() =>
                void run(async () => {
                  const { longitude, latitude } = draft!
                  await api.post('/api/olts', {
                    siteId: draft!.siteId,
                    code: draft!.code,
                    name: draft!.name,
                    vendor: draft!.vendor,
                    model: draft!.model || null,
                    description: draft!.description || null,
                    managementIp: draft!.managementIp || null,
                    snmpEnabled: draft!.snmpEnabled,
                    snmpCommunity: draft!.snmpCommunity || null,
                    snmpVersion: draft!.snmpVersion,
                    snmpPort: Number(draft!.snmpPort) || 161,
                    webEnabled: draft!.webEnabled,
                    webProtocol: draft!.webProtocol,
                    webPort: draft!.webPort ? Number(draft!.webPort) : null,
                    webUsername: draft!.webUsername || null,
                    webPassword: draft!.webPassword || null,
                    location:
                      longitude && latitude
                        ? { longitude: Number(longitude), latitude: Number(latitude) }
                        : null,
                  })
                  closeDraft()
                })
              }
            >
              Simpan
            </button>
            <button onClick={closeDraft}>Batal</button>
          </>
        }
      >
        {draft && (
          <div className="stack">
          {/* Identitas perangkat */}
          <div className="row">
            <label style={{ flex: 1 }}>
              <span>Site</span>
              <select value={draft.siteId} onChange={(e) => setDraft({ ...draft, siteId: e.target.value })}>
                {sites.map((site) => (
                  <option key={site.id} value={site.id}>
                    {site.code} — {site.name}
                  </option>
                ))}
              </select>
            </label>
            <label style={{ flex: 1 }}>
              <span>Kode</span>
              <input value={draft.code} onChange={(e) => setDraft({ ...draft, code: e.target.value })} placeholder="OLT-BKS-01" />
            </label>
            <label style={{ flex: 1 }}>
              <span>Nama</span>
              <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
            </label>
          </div>
          <div className="row">
            <label style={{ flex: 1 }}>
              <span>
                Vendor <span className="muted">(hardware type)</span>
              </span>
              <select value={draft.vendor} onChange={(e) => setDraft(changeVendor(draft, e.target.value))}>
                {VENDORS.map((vendor) => (
                  <option key={vendor}>{vendor}</option>
                ))}
              </select>
            </label>
            <label style={{ flex: 1 }}>
              <span>
                Model <span className="muted">(opsional)</span>
              </span>
              <input value={draft.model} onChange={(e) => setDraft({ ...draft, model: e.target.value })} placeholder="C320" />
            </label>
            <label style={{ flex: 1 }}>
              <span>
                IP manajemen <span className="muted">(opsional)</span>
              </span>
              <input
                value={draft.managementIp}
                onChange={(e) => setDraft({ ...draft, managementIp: e.target.value })}
                placeholder="10.10.1.2"
              />
            </label>
          </div>
          <label>
            <span>
              Deskripsi <span className="muted">(opsional)</span>
            </span>
            <input
              value={draft.description}
              onChange={(e) => setDraft({ ...draft, description: e.target.value })}
              placeholder="Lokasi rak, kontak vendor, atau ID kontrak…"
            />
          </label>

          {/* Kanal SNMP — utama untuk ZTE/Huawei/dst.; HSGQ EPON pun dipolling lewat SNMP, jadi tampil untuk semua vendor */}
          <div className="stack" style={{ gap: '0.6rem', borderTop: '1px solid var(--border)', paddingTop: '0.85rem' }}>
            <label className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
              <input
                type="checkbox"
                checked={draft.snmpEnabled}
                onChange={(e) => setDraft({ ...draft, snmpEnabled: e.target.checked })}
                style={{ width: 'auto' }}
              />
              <span style={{ fontWeight: 600 }}>Aktifkan SNMP untuk OLT ini</span>
            </label>
            {draft.snmpEnabled && (
              <div className="row">
                <label style={{ flex: 1 }}>
                  <span>
                    Community string <span className="muted">(RO/RW)</span>
                  </span>
                  <input
                    type="password"
                    value={draft.snmpCommunity}
                    onChange={(e) => setDraft({ ...draft, snmpCommunity: e.target.value })}
                    placeholder="public"
                  />
                </label>
                <label style={{ width: 130 }}>
                  <span>Versi</span>
                  <select
                    value={draft.snmpVersion}
                    onChange={(e) => setDraft({ ...draft, snmpVersion: e.target.value as SnmpVersion })}
                  >
                    {SNMP_VERSIONS.map((v) => (
                      <option key={v.value} value={v.value}>
                        {v.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label style={{ width: 110 }}>
                  <span>Port SNMP</span>
                  <input
                    type="number"
                    min={1}
                    max={65535}
                    value={draft.snmpPort}
                    onChange={(e) => setDraft({ ...draft, snmpPort: e.target.value })}
                    placeholder="161"
                  />
                </label>
              </div>
            )}
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
              Community string disimpan terenkripsi dan tidak pernah ditampilkan kembali.
            </p>
          </div>

          {/* Kanal Web UI — HSGQ pakai ini sebagai manajemen langsung; lainnya untuk metrik suhu/optik */}
          <div className="stack" style={{ gap: '0.6rem', borderTop: '1px solid var(--border)', paddingTop: '0.85rem' }}>
            {isWebManaged(draft.vendor) ? (
              <div className="stack" style={{ gap: '0.25rem' }}>
                <span style={{ fontWeight: 600 }}>Web UI API (HTTP Management)</span>
                <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
                  OLT HSGQ terhubung langsung lewat HTTP Web UI API — mengandalkan Port Web, Web Username, & Web Password di
                  bawah.
                </p>
              </div>
            ) : (
              <>
                <label className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
                  <input
                    type="checkbox"
                    checked={draft.webEnabled}
                    onChange={(e) => setDraft({ ...draft, webEnabled: e.target.checked })}
                    style={{ width: 'auto' }}
                  />
                  <span style={{ fontWeight: 600 }}>Aktifkan Web Management (metrik)</span>
                </label>
                <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
                  Digunakan untuk mengambil data suhu (temperature) &amp; daya optik (optical power) lewat Web UI.
                </p>
              </>
            )}
            {(isWebManaged(draft.vendor) || draft.webEnabled) && (
              <div className="row">
                <label style={{ width: 130 }}>
                  <span>Protokol</span>
                  <select
                    value={draft.webProtocol}
                    onChange={(e) => setDraft({ ...draft, webProtocol: e.target.value as WebProtocol })}
                  >
                    <option value="HTTP">HTTP</option>
                    <option value="HTTPS">HTTPS</option>
                  </select>
                </label>
                <label style={{ width: 130 }}>
                  <span>Port Web</span>
                  <input
                    type="number"
                    min={1}
                    max={65535}
                    value={draft.webPort}
                    onChange={(e) => setDraft({ ...draft, webPort: e.target.value })}
                    placeholder="80"
                  />
                </label>
                <label style={{ flex: 1 }}>
                  <span>
                    Web Username <span className="muted">(opsional)</span>
                  </span>
                  <input
                    value={draft.webUsername}
                    onChange={(e) => setDraft({ ...draft, webUsername: e.target.value })}
                    placeholder="admin"
                  />
                </label>
                <label style={{ flex: 1 }}>
                  <span>
                    Web Password <span className="muted">(opsional)</span>
                  </span>
                  <input
                    type="password"
                    value={draft.webPassword}
                    onChange={(e) => setDraft({ ...draft, webPassword: e.target.value })}
                    placeholder={isWebManaged(draft.vendor) ? 'password Web UI' : 'kalau beda dari Telnet'}
                  />
                </label>
              </div>
            )}
            {(isWebManaged(draft.vendor) || draft.webEnabled) && (
              <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
                Password Web disimpan terenkripsi dan tidak pernah ditampilkan kembali.
              </p>
            )}
          </div>

          <LocationFields
            longitude={draft.longitude}
            latitude={draft.latitude}
            onChange={(longitude, latitude) => setDraft({ ...draft, longitude, latitude })}
          />
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Kosongkan lokasi untuk mengikuti koordinat site. Isi bila ingin OLT tampil di titiknya sendiri di peta.
          </p>
          </div>
        )}
      </Blade>

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari kode, nama, site, vendor, atau IP…" />
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value as AssetStatus | '')}>
          {ASSET_STATUS_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </select>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(o) => o.id}
        onRowClick={(o) => navigate(`/olts/${o.id}`)}
        loading={loading}
        initialSort={{ key: 'code', dir: 'asc' }}
        selection={canDelete ? { selected, onChange: setSelected } : undefined}
        rowActions={canDelete ? rowActions : undefined}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada OLT yang cocok' : 'Belum ada OLT'}
            hint={query || statusFilter ? 'Coba ubah kata kunci atau filter.' : 'Tambahkan OLT di atas sebuah site untuk mulai membangun.'}
            icon={<IconInventory size={32} />}
          />
        }
      />
    </div>
  )
}

/**
 * Pemilih uplink ODC dalam dua langkah: pilih OLT dulu, lalu PON port-nya. ODC
 * "nyantol" ke sebuah PON port (`ponPortId`) — inilah sambungan LOGIS yang bikin
 * ODC teraliri (energized), terpisah dari kabel feeder fisik di peta. Port di-fetch
 * per-OLT (`/api/olts/{id}/pon-ports`) begitu OLT dipilih. Nilai keluaran = id PON
 * port ('' berarti belum di-uplink). OLT-nya sendiri disimpan sebagai state internal
 * karena server cukup butuh id port (port sudah tahu OLT-nya).
 */
function PonPortPicker({ value, onChange }: { value: string; onChange: (ponPortId: string) => void }) {
  const { items: olts } = useList<OltView>('/api/olts')
  const [oltId, setOltId] = useState('')
  const [ports, setPorts] = useState<PonPortView[]>([])
  const [loadingPorts, setLoadingPorts] = useState(false)

  useEffect(() => {
    if (!oltId) {
      setPorts([])
      return
    }
    let alive = true
    setLoadingPorts(true)
    api
      .get<PonPortView[]>(`/api/olts/${oltId}/pon-ports`)
      .then((list) => alive && setPorts(list))
      .catch(() => alive && setPorts([]))
      .finally(() => alive && setLoadingPorts(false))
    return () => {
      alive = false
    }
  }, [oltId])

  return (
    <div className="row">
      <label style={{ flex: 1 }}>
        <span>OLT hulu</span>
        <select
          value={oltId}
          onChange={(e) => {
            setOltId(e.target.value)
            onChange('')
          }}
        >
          <option value="">— belum di-uplink —</option>
          {olts.map((o) => (
            <option key={o.id} value={o.id}>
              {o.code} · {o.name}
            </option>
          ))}
        </select>
      </label>
      <label style={{ flex: 1 }}>
        <span>PON port</span>
        <select value={value} onChange={(e) => onChange(e.target.value)} disabled={!oltId || loadingPorts}>
          <option value="">
            {!oltId ? '— pilih OLT dulu —' : loadingPorts ? 'memuat…' : '— pilih port —'}
          </option>
          {ports.map((p) => (
            <option key={p.id} value={p.id}>
              {p.label}
              {p.odcCount > 0 ? ` · ${p.odcCount} ODC` : ''}
            </option>
          ))}
        </select>
      </label>
    </div>
  )
}

function OdcsTab() {
  const { can } = useCan()
  const confirm = useConfirm()
  const { items, loading, reload, run } = useList<OdcView>('/api/odcs')
  const empty = { code: '', name: '', longitude: '', latitude: '', ponPortId: '', splitterRatio: '1:8', capacity: '64' }
  const [draft, setDraft] = useState<typeof empty | null>(null)
  const [initialDraft, setInitialDraft] = useState<typeof empty | null>(null)
  const openDraft = (d: typeof empty) => { setDraft(d); setInitialDraft(d) }
  const closeDraft = () => { setDraft(null); setInitialDraft(null) }
  const dirty = draft != null && JSON.stringify(draft) !== JSON.stringify(initialDraft)
  const [uplinkFor, setUplinkFor] = useState<OdcView | null>(null)
  const [uplinkPort, setUplinkPort] = useState('')
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<AssetStatus | ''>('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [deleting, setDeleting] = useState(false)
  const canUpdate = can('network.odc.update')
  const canDelete = can('network.odc.delete')

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter(
      (o) => matchesQuery([o.code, o.name, o.oltName], q) && (!statusFilter || o.status === statusFilter),
    )
  }, [items, query, statusFilter])

  const deleteSelected = async () => {
    const ids = [...selected]
    if (ids.length === 0) return
    if (!(await confirm({ title: 'Hapus ODC', message: `Hapus ${ids.length} ODC terpilih?`, confirmLabel: 'Hapus', danger: true }))) return
    setDeleting(true)
    await run(async () => {
      await Promise.all(ids.map((id) => api.del(`/api/odcs/${id}`)))
      setSelected(new Set())
    })
    setDeleting(false)
  }

  const columns: Column<OdcView>[] = [
    {
      key: 'code',
      header: 'Kode',
      sortValue: (o) => o.code,
      cell: (o) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <strong>{o.code}</strong>
          <span className="muted" style={{ fontSize: '0.8rem' }}>{o.name}</span>
        </div>
      ),
    },
    {
      key: 'upstream',
      header: 'Hulu',
      sortValue: (o) => o.oltName,
      cell: (o) => (
        <span className="muted" style={{ fontSize: '0.85rem' }}>
          {o.oltName ? `${o.oltName} · ${o.ponPortLabel}` : 'belum di-uplink'}
        </span>
      ),
    },
    { key: 'splitter', header: 'Splitter', sortValue: (o) => o.splitterRatio, cell: (o) => o.splitterRatio },
    { key: 'odp', header: 'ODP', align: 'right', sortValue: (o) => o.odpCount, cell: (o) => o.odpCount },
    {
      key: 'status',
      header: 'Status',
      sortValue: (o) => (o.energized ? 'ACTIVE' : o.status),
      cell: (o) => (o.energized ? <StatusBadge status="ACTIVE" label="teraliri" /> : <StatusBadge status={o.status} />),
    },
  ]

  const rowActions = (o: OdcView): RowAction[] => {
    const list: RowAction[] = []
    if (canUpdate)
      list.push({
        key: 'uplink',
        label: 'Uplink',
        icon: <Link2 size={16} />,
        onClick: () => {
          setUplinkFor(o)
          setUplinkPort('')
        },
      })
    if (canDelete)
      list.push({
        key: 'delete',
        label: 'Hapus',
        icon: <Trash2 size={16} />,
        onClick: () => void (async () => {
          if (await confirm({ title: 'Hapus ODC', message: `Hapus ODC ${o.code}?`, confirmLabel: 'Hapus', danger: true })) void run(() => api.del(`/api/odcs/${o.id}`))
        })(),
      })
    return list
  }
  const hasRowActions = canUpdate || canDelete

  const primary: CommandAction | undefined = can('network.odc.create')
    ? { key: 'create', label: 'Tambah ODC', icon: <Plus size={16} />, onClick: () => openDraft({ ...empty }) }
    : undefined
  const actions: CommandAction[] = []
  if (canDelete)
    actions.push({
      key: 'delete',
      label: 'Hapus',
      icon: <Trash2 size={16} />,
      onClick: () => void deleteSelected(),
      disabled: selected.size === 0 || deleting,
    })
  actions.push({
    key: 'refresh',
    label: 'Segarkan',
    icon: <RefreshCw size={16} />,
    onClick: () => void reload(),
    dividerBefore: canDelete,
  })

  return (
    <div className="stack">
      <CommandBar primary={primary} actions={actions} />

      <Blade
        open={draft != null}
        title="Tambah ODC"
        subtitle="Daftarkan ODC untuk memecah distribusi ke ODP."
        size="sm"
        dirty={dirty}
        onClose={closeDraft}
        footer={
          <>
            <button
              className="primary"
              onClick={() =>
                void run(async () => {
                  await api.post('/api/odcs', {
                    code: draft!.code,
                    name: draft!.name,
                    location: { longitude: Number(draft!.longitude), latitude: Number(draft!.latitude) },
                    ponPortId: draft!.ponPortId || null,
                    splitterRatio: draft!.splitterRatio,
                    capacity: Number(draft!.capacity),
                  })
                  closeDraft()
                })
              }
            >
              Simpan
            </button>
            <button onClick={closeDraft}>Batal</button>
          </>
        }
      >
        {draft && (
          <div className="stack">
            <div className="row">
              <label style={{ flex: 1 }}>
                <span>Kode</span>
                <input value={draft.code} onChange={(e) => setDraft({ ...draft, code: e.target.value })} placeholder="ODC-MGH-01" />
              </label>
              <label style={{ flex: 2 }}>
                <span>Nama</span>
                <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
              </label>
            </div>
            <div className="row">
              <label style={{ flex: 1 }}>
                <span>Rasio splitter</span>
                <select value={draft.splitterRatio} onChange={(e) => setDraft({ ...draft, splitterRatio: e.target.value })}>
                  {SPLITTER_RATIOS.map((ratio) => (
                    <option key={ratio}>{ratio}</option>
                  ))}
                </select>
              </label>
              <label style={{ flex: 1 }}>
                <span>Kapasitas</span>
                <input value={draft.capacity} onChange={(e) => setDraft({ ...draft, capacity: e.target.value })} />
              </label>
            </div>
            <LocationFields
              longitude={draft.longitude}
              latitude={draft.latitude}
              onChange={(longitude, latitude) => setDraft({ ...draft, longitude, latitude })}
            />
            <PonPortPicker value={draft.ponPortId} onChange={(ponPortId) => setDraft({ ...draft, ponPortId })} />
          </div>
        )}
      </Blade>

      <Blade
        open={uplinkFor != null}
        title={uplinkFor ? `Uplink ${uplinkFor.code}` : 'Uplink'}
        subtitle={
          uplinkFor?.oltName ? `sekarang: ${uplinkFor.oltName} · ${uplinkFor.ponPortLabel}` : 'sekarang: belum di-uplink'
        }
        size="sm"
        dirty={uplinkPort !== ''}
        onClose={() => {
          setUplinkFor(null)
          setUplinkPort('')
        }}
        footer={
          <>
            <button
              className="primary"
              onClick={() =>
                void run(async () => {
                  await api.put(`/api/odcs/${uplinkFor!.id}/uplink`, { targetId: uplinkPort || null })
                  setUplinkFor(null)
                  setUplinkPort('')
                }, 'Uplink ODC diperbarui')
              }
            >
              Simpan uplink
            </button>
            <button
              onClick={() => {
                setUplinkFor(null)
                setUplinkPort('')
              }}
            >
              Batal
            </button>
          </>
        }
      >
        {uplinkFor && <PonPortPicker value={uplinkPort} onChange={setUplinkPort} />}
      </Blade>

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari kode, nama, atau OLT hulu…" />
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value as AssetStatus | '')}>
          {ASSET_STATUS_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </select>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(o) => o.id}
        loading={loading}
        initialSort={{ key: 'code', dir: 'asc' }}
        selection={canDelete ? { selected, onChange: setSelected } : undefined}
        rowActions={hasRowActions ? rowActions : undefined}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada ODC yang cocok' : 'Belum ada ODC'}
            hint={query || statusFilter ? 'Coba ubah kata kunci atau filter.' : 'Tambahkan ODC untuk memecah distribusi ke ODP.'}
            icon={<IconInventory size={32} />}
          />
        }
      />
    </div>
  )
}

function OdpsTab() {
  const { can } = useCan()
  const confirm = useConfirm()
  const { items, loading, reload, run } = useList<OdpView>('/api/odps')
  const { items: odcs } = useList<OdcView>('/api/odcs')
  const empty = { code: '', name: '', longitude: '', latitude: '', odcId: '', splitterRatio: '1:8', capacity: '8' }
  const [draft, setDraft] = useState<typeof empty | null>(null)
  const [initialDraft, setInitialDraft] = useState<typeof empty | null>(null)
  const openDraft = (d: typeof empty) => { setDraft(d); setInitialDraft(d) }
  const closeDraft = () => { setDraft(null); setInitialDraft(null) }
  const dirty = draft != null && JSON.stringify(draft) !== JSON.stringify(initialDraft)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<AssetStatus | ''>('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [deleting, setDeleting] = useState(false)
  const canDelete = can('network.odp.delete')

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter(
      (o) => matchesQuery([o.code, o.name, o.odcName], q) && (!statusFilter || o.status === statusFilter),
    )
  }, [items, query, statusFilter])

  const deleteSelected = async () => {
    const ids = [...selected]
    if (ids.length === 0) return
    if (!(await confirm({ title: 'Hapus ODP', message: `Hapus ${ids.length} ODP terpilih?`, confirmLabel: 'Hapus', danger: true }))) return
    setDeleting(true)
    await run(async () => {
      await Promise.all(ids.map((id) => api.del(`/api/odps/${id}`)))
      setSelected(new Set())
    })
    setDeleting(false)
  }

  const columns: Column<OdpView>[] = [
    {
      key: 'code',
      header: 'Kode',
      sortValue: (o) => o.code,
      cell: (o) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <strong>{o.code}</strong>
          <span className="muted" style={{ fontSize: '0.8rem' }}>{o.name}</span>
        </div>
      ),
    },
    { key: 'odc', header: 'ODC induk', sortValue: (o) => o.odcName, cell: (o) => <span className="muted">{o.odcName ?? '—'}</span> },
    { key: 'splitter', header: 'Splitter', sortValue: (o) => o.splitterRatio, cell: (o) => o.splitterRatio },
    { key: 'port', header: 'Port', align: 'right', sortValue: (o) => o.capacity, cell: (o) => o.capacity },
    { key: 'status', header: 'Status', sortValue: (o) => o.status, cell: (o) => <StatusBadge status={o.status} /> },
  ]

  const rowActions = (o: OdpView): RowAction[] =>
    canDelete
      ? [
          {
            key: 'delete',
            label: 'Hapus',
            icon: <Trash2 size={16} />,
            onClick: () => void (async () => {
              if (await confirm({ title: 'Hapus ODP', message: `Hapus ODP ${o.code}?`, confirmLabel: 'Hapus', danger: true })) void run(() => api.del(`/api/odps/${o.id}`))
            })(),
          },
        ]
      : []

  const primary: CommandAction | undefined = can('network.odp.create')
    ? {
        key: 'create',
        label: 'Tambah ODP',
        icon: <Plus size={16} />,
        onClick: () => openDraft({ ...empty, odcId: odcs[0]?.id ?? '' }),
      }
    : undefined
  const actions: CommandAction[] = []
  if (canDelete)
    actions.push({
      key: 'delete',
      label: 'Hapus',
      icon: <Trash2 size={16} />,
      onClick: () => void deleteSelected(),
      disabled: selected.size === 0 || deleting,
    })
  actions.push({
    key: 'refresh',
    label: 'Segarkan',
    icon: <RefreshCw size={16} />,
    onClick: () => void reload(),
    dividerBefore: canDelete,
  })

  return (
    <div className="stack">
      <CommandBar primary={primary} actions={actions} />

      <Blade
        open={draft != null}
        title="Tambah ODP"
        subtitle="Daftarkan ODP sebagai kotak terminasi ke pelanggan."
        size="sm"
        dirty={dirty}
        onClose={closeDraft}
        footer={
          <>
            <button
              className="primary"
              onClick={() =>
                void run(async () => {
                  await api.post('/api/odps', {
                    code: draft!.code,
                    name: draft!.name,
                    location: { longitude: Number(draft!.longitude), latitude: Number(draft!.latitude) },
                    odcId: draft!.odcId || null,
                    splitterRatio: draft!.splitterRatio,
                    capacity: Number(draft!.capacity),
                  })
                  closeDraft()
                })
              }
            >
              Simpan
            </button>
            <button onClick={closeDraft}>Batal</button>
          </>
        }
      >
        {draft && (
          <div className="stack">
            <div className="row">
              <label style={{ flex: 1 }}>
                <span>Kode</span>
                <input value={draft.code} onChange={(e) => setDraft({ ...draft, code: e.target.value })} placeholder="ODP-MGH-007" />
              </label>
              <label style={{ flex: 2 }}>
                <span>Nama</span>
                <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
              </label>
              <label style={{ flex: 1 }}>
                <span>ODC induk</span>
                <select value={draft.odcId} onChange={(e) => setDraft({ ...draft, odcId: e.target.value })}>
                  <option value="">— belum tersambung —</option>
                  {odcs.map((odc) => (
                    <option key={odc.id} value={odc.id}>
                      {odc.code}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <div className="row">
              <label style={{ flex: 1 }}>
                <span>Rasio splitter</span>
                <select value={draft.splitterRatio} onChange={(e) => setDraft({ ...draft, splitterRatio: e.target.value })}>
                  {SPLITTER_RATIOS.map((ratio) => (
                    <option key={ratio}>{ratio}</option>
                  ))}
                </select>
              </label>
              <label style={{ flex: 1 }}>
                <span>Jumlah port</span>
                <input value={draft.capacity} onChange={(e) => setDraft({ ...draft, capacity: e.target.value })} />
              </label>
            </div>
            <LocationFields
              longitude={draft.longitude}
              latitude={draft.latitude}
              onChange={(longitude, latitude) => setDraft({ ...draft, longitude, latitude })}
            />
          </div>
        )}
      </Blade>

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari kode, nama, atau ODC induk…" />
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value as AssetStatus | '')}>
          {ASSET_STATUS_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </select>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(o) => o.id}
        loading={loading}
        initialSort={{ key: 'code', dir: 'asc' }}
        selection={canDelete ? { selected, onChange: setSelected } : undefined}
        rowActions={canDelete ? rowActions : undefined}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada ODP yang cocok' : 'Belum ada ODP'}
            hint={query || statusFilter ? 'Coba ubah kata kunci atau filter.' : 'Tambahkan ODP sebagai kotak terminasi ke pelanggan.'}
            icon={<IconInventory size={32} />}
          />
        }
      />
    </div>
  )
}
