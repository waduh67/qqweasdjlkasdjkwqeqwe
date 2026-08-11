import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import type { AssetStatus, JointBoxView, OdcView, OdfView, OdpView, OltView, SiteView, SnmpVersion, WebProtocol } from '../api/network'
import { MapPin, Plus, RefreshCw, Trash2 } from 'lucide-react'
import { Checkbox } from '@fluentui/react-components'
import { useCan } from '../auth/useCan'
import { AccessNodeDetail, AssetDetailPanel, DataTable, OdfDetail, type Column, type RowAction } from '@/components/organisms'
import { CommandBar, type CommandAction } from '@/components/molecules'
import { PageHeader } from '@/components/molecules'
import { LocationPicker } from '@/components/organisms'
import { Blade } from '@/components/organisms'
import { OltDetail } from './OltDetailPage'
import { Badge, Button, EmptyState, SelectField, StatusBadge, TextField, Toolbar } from '@/components/atoms'
import { SearchInput, Tabs } from '@/components/molecules'
import { useConfirm, useToast } from '@/system'
import { IconInventory } from '@/components/atoms/icons'
import { mapFocusState } from '@/map/mapFocus'

/**
 * Inventory jaringan dalam satu halaman bertab.
 *
 * Dijadikan satu halaman karena keempat aset ini dikelola berurutan — site dulu,
 * lalu OLT di atasnya, lalu ODC, lalu ODP — sehingga berpindah antar tab lebih
 * masuk akal daripada berpindah antar halaman. Tiap tab memakai tabel bisa-urut
 * yang seragam dengan bilah pencarian & filter di atasnya.
 */

type Tab = 'sites' | 'olts' | 'odfs' | 'odcs' | 'odps' | 'joint_boxes'

const TABS: Array<{ key: Tab; label: string; permission: string }> = [
  { key: 'sites', label: 'Site / POP', permission: 'network.site.view' },
  { key: 'olts', label: 'OLT', permission: 'network.olt.view' },
  // Sesudah OLT, sebelum ODC: itu memang tempat rak dalam alur hulu→hilir — kabel
  // distribusi berangkat dari ODF, bukan dari badan OLT.
  { key: 'odfs', label: 'ODF', permission: 'network.odf.view' },
  { key: 'odcs', label: 'ODC', permission: 'network.odc.view' },
  { key: 'odps', label: 'ODP', permission: 'network.odp.view' },
  // Paling kanan karena joint box tak ikut urutan hulu-ke-hilir itu: ia bisa
  // menclok di ruas mana saja, jadi menyelipkannya di tengah malah merusak alur baca.
  { key: 'joint_boxes', label: 'Joint box', permission: 'network.jointbox.view' },
]

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
        subtitle="Kelola site, OLT, ODF, ODC, ODP, dan joint box — dari rak POP sampai kotak terminasi."
      />
      <Tabs tabs={visible} active={tab} onChange={setTab} />
      {tab === 'sites' && <SitesTab />}
      {tab === 'olts' && <OltsTab />}
      {tab === 'odfs' && <OdfsTab />}
      {tab === 'odcs' && <OdcsTab />}
      {tab === 'odps' && <OdpsTab />}
      {tab === 'joint_boxes' && <JointBoxesTab />}
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
  const navigate = useNavigate()
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
  const canMap = can('gis.map.view')
  // Klik baris membuka DETAIL di blade — sama seperti tab OLT/ODC/ODP, supaya aksi
  // tingkat-aset (termasuk "Lihat di peta") punya satu rumah yang sama di keempat tab.
  const [openSite, setOpenSite] = useState<SiteView | null>(null)

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter((s) => matchesQuery([s.code, s.name, s.address], q))
  }, [items, query])

  // Selaraskan detail terbuka dengan data terbaru tiap daftar dimuat ulang — atau
  // tutup panel bila site-nya sudah terhapus.
  useEffect(() => {
    if (!openSite) return
    setOpenSite(items.find((it) => it.id === openSite.id) ?? null)
  }, [items, openSite])

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

  const removeSite = (s: SiteView) =>
    void (async () => {
      if (await confirm({ title: 'Hapus site', message: `Hapus site ${s.code}?`, confirmLabel: 'Hapus', danger: true })) {
        setOpenSite(null)
        void run(() => api.del(`/api/sites/${s.id}`))
      }
    })()

  const rowActions = (s: SiteView): RowAction[] => [
    { key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => removeSite(s) },
  ]

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
            <Button
              variant="primary"
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
            </Button>
            <Button onClick={closeDraft}>Batal</Button>
          </>
        }
      >
        {draft && (
          <div className="stack">
            <div className="row">
              <div style={{ flex: 1 }}>
                <TextField
                  label="Kode"
                  value={draft.code}
                  onChange={(_, data) => setDraft({ ...draft, code: data.value })}
                  placeholder="POP-BKS"
                />
              </div>
              <div style={{ flex: 2 }}>
                <TextField
                  label="Nama"
                  value={draft.name}
                  onChange={(_, data) => setDraft({ ...draft, name: data.value })}
                />
              </div>
            </div>
            <TextField
              label="Alamat"
              value={draft.address}
              onChange={(_, data) => setDraft({ ...draft, address: data.value })}
            />
            <LocationFields
              longitude={draft.longitude}
              latitude={draft.latitude}
              onChange={(longitude, latitude) => setDraft({ ...draft, longitude, latitude })}
            />
          </div>
        )}
      </Blade>

      {/* Blade DETAIL SITE (non-modal, lebar >½ layar): klik baris → panel ini. Site tak
          punya form sunting di inventory (identitas & titiknya diatur saat dibuat), jadi
          panelnya murni baca + aksi. */}
      <Blade
        open={openSite != null}
        size="full"
        className="blade-detail"
        title={openSite?.code ?? ''}
        subtitle={openSite?.name}
        onClose={() => setOpenSite(null)}
      >
        {openSite && (
          <AssetDetailPanel
            badges={<Badge>{openSite.oltCount} OLT</Badge>}
            fields={[
              { label: 'Nama', value: openSite.name },
              { label: 'Jumlah OLT', value: String(openSite.oltCount) },
            ]}
            address={openSite.address}
            location={openSite.location}
            canUpdate={false}
            canDelete={canDelete}
            onDelete={() => removeSite(openSite)}
            onShowOnMap={canMap ? () => navigate('/map', mapFocusState('site', openSite.id, openSite.location)) : undefined}
          />
        )}
      </Blade>

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari kode, nama, atau alamat…" />
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(s) => s.id}
        onRowClick={(s) => setOpenSite(s)}
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
  // Baris OLT membuka DETAIL di blade non-modal (bukan pindah halaman) — pola
  // dua-blade Azure. Simpan baris terpilih; ganti baris = tukar isi blade.
  const [openOlt, setOpenOlt] = useState<OltView | null>(null)
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
    // Hanya jumlah port (angka rata-kanan ala grid Azure). Menambah/menghapus PON port
    // dilakukan di tab "PON Port" halaman detail OLT — klik baris untuk membukanya —
    // supaya baris grid tetap satu-tinggi & rapat, bukan menyisipkan mini-form per baris.
    { key: 'ponPorts', header: 'PON port', align: 'right', sortValue: (o) => o.ponPortCount, cell: (o) => o.ponPortCount },
  ]

  const rowActions = (o: OltView): RowAction[] => [
    {
      key: 'delete',
      label: 'Hapus',
      icon: <Trash2 size={16} />,
      onClick: () => void (async () => {
        if (await confirm({ title: 'Hapus OLT', message: `Hapus OLT ${o.code}?`, confirmLabel: 'Hapus', danger: true })) void run(() => api.del(`/api/olts/${o.id}`))
      })(),
    },
  ]

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
            <Button
              variant="primary"
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
            </Button>
            <Button onClick={closeDraft}>Batal</Button>
          </>
        }
      >
        {draft && (
          <div className="stack">
          {/* Identitas perangkat */}
          <div className="row">
            <div style={{ flex: 1 }}>
              <SelectField
                label="Site"
                value={draft.siteId}
                onChange={(_, data) => setDraft({ ...draft, siteId: data.value })}
              >
                {sites.map((site) => (
                  <option key={site.id} value={site.id}>
                    {site.code} — {site.name}
                  </option>
                ))}
              </SelectField>
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label="Kode"
                value={draft.code}
                onChange={(_, data) => setDraft({ ...draft, code: data.value })}
                placeholder="OLT-BKS-01"
              />
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label="Nama"
                value={draft.name}
                onChange={(_, data) => setDraft({ ...draft, name: data.value })}
              />
            </div>
          </div>
          <div className="row">
            <div style={{ flex: 1 }}>
              <SelectField
                label={<>Vendor <span className="muted">(hardware type)</span></>}
                value={draft.vendor}
                onChange={(_, data) => setDraft(changeVendor(draft, data.value))}
              >
                {VENDORS.map((vendor) => (
                  <option key={vendor}>{vendor}</option>
                ))}
              </SelectField>
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label={<>Model <span className="muted">(opsional)</span></>}
                value={draft.model}
                onChange={(_, data) => setDraft({ ...draft, model: data.value })}
                placeholder="C320"
              />
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label={<>IP manajemen <span className="muted">(opsional)</span></>}
                value={draft.managementIp}
                onChange={(_, data) => setDraft({ ...draft, managementIp: data.value })}
                placeholder="10.10.1.2"
              />
            </div>
          </div>
          <TextField
            label={<>Deskripsi <span className="muted">(opsional)</span></>}
            value={draft.description}
            onChange={(_, data) => setDraft({ ...draft, description: data.value })}
            placeholder="Lokasi rak, kontak vendor, atau ID kontrak…"
          />

          {/* Kanal SNMP — utama untuk ZTE/Huawei/dst.; HSGQ EPON pun dipolling lewat SNMP, jadi tampil untuk semua vendor */}
          <div className="stack" style={{ gap: '0.6rem', borderTop: '1px solid var(--border)', paddingTop: '0.85rem' }}>
            <Checkbox
              label={<span style={{ fontWeight: 600 }}>Aktifkan SNMP untuk OLT ini</span>}
              checked={draft.snmpEnabled}
              onChange={(e) => setDraft({ ...draft, snmpEnabled: e.target.checked })}
            />
            {draft.snmpEnabled && (
              <div className="row">
                <div style={{ flex: 1 }}>
                  <TextField
                    label={<>Community string <span className="muted">(RO/RW)</span></>}
                    type="password"
                    value={draft.snmpCommunity}
                    onChange={(_, data) => setDraft({ ...draft, snmpCommunity: data.value })}
                    placeholder="public"
                  />
                </div>
                <div style={{ width: 130 }}>
                  <SelectField
                    label="Versi"
                    value={draft.snmpVersion}
                    onChange={(_, data) => setDraft({ ...draft, snmpVersion: data.value as SnmpVersion })}
                  >
                    {SNMP_VERSIONS.map((v) => (
                      <option key={v.value} value={v.value}>
                        {v.label}
                      </option>
                    ))}
                  </SelectField>
                </div>
                <div style={{ width: 110 }}>
                  <TextField
                    label="Port SNMP"
                    type="number"
                    min={1}
                    max={65535}
                    value={draft.snmpPort}
                    onChange={(_, data) => setDraft({ ...draft, snmpPort: data.value })}
                    placeholder="161"
                  />
                </div>
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
                <Checkbox
                  label={<span style={{ fontWeight: 600 }}>Aktifkan Web Management (metrik)</span>}
                  checked={draft.webEnabled}
                  onChange={(e) => setDraft({ ...draft, webEnabled: e.target.checked })}
                />
                <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
                  Digunakan untuk mengambil data suhu (temperature) &amp; daya optik (optical power) lewat Web UI.
                </p>
              </>
            )}
            {(isWebManaged(draft.vendor) || draft.webEnabled) && (
              <div className="row">
                <div style={{ width: 130 }}>
                  <SelectField
                    label="Protokol"
                    value={draft.webProtocol}
                    onChange={(_, data) => setDraft({ ...draft, webProtocol: data.value as WebProtocol })}
                  >
                    <option value="HTTP">HTTP</option>
                    <option value="HTTPS">HTTPS</option>
                  </SelectField>
                </div>
                <div style={{ width: 130 }}>
                  <TextField
                    label="Port Web"
                    type="number"
                    min={1}
                    max={65535}
                    value={draft.webPort}
                    onChange={(_, data) => setDraft({ ...draft, webPort: data.value })}
                    placeholder="80"
                  />
                </div>
                <div style={{ flex: 1 }}>
                  <TextField
                    label={<>Web Username <span className="muted">(opsional)</span></>}
                    value={draft.webUsername}
                    onChange={(_, data) => setDraft({ ...draft, webUsername: data.value })}
                    placeholder="admin"
                  />
                </div>
                <div style={{ flex: 1 }}>
                  <TextField
                    label={<>Web Password <span className="muted">(opsional)</span></>}
                    type="password"
                    value={draft.webPassword}
                    onChange={(_, data) => setDraft({ ...draft, webPassword: data.value })}
                    placeholder={isWebManaged(draft.vendor) ? 'password Web UI' : 'kalau beda dari Telnet'}
                  />
                </div>
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
        <SelectField value={statusFilter} onChange={(_, data) => setStatusFilter(data.value as AssetStatus | '')}>
          {ASSET_STATUS_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </SelectField>
      </Toolbar>

      {/* Blade DETAIL OLT (non-modal): tab Ringkasan/PON/ONU + Edit, tanpa pindah
          halaman. `key` per-id memaksa muat-ulang saat baris lain diklik (tukar data).
          Tutup/hapus menyegarkan daftar agar perubahan langsung tampak. */}
      <Blade
        open={openOlt != null}
        size="full"
        className="blade-detail"
        title={openOlt?.code ?? ''}
        subtitle={openOlt ? `${openOlt.name}${openOlt.siteName ? ` · Site ${openOlt.siteName}` : ''}` : undefined}
        onClose={() => {
          setOpenOlt(null)
          void reload()
        }}
      >
        {openOlt && (
          <OltDetail
            key={openOlt.id}
            oltId={openOlt.id}
            compact
            onDeleted={() => {
              setOpenOlt(null)
              void reload()
            }}
          />
        )}
      </Blade>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(o) => o.id}
        onRowClick={(o) => setOpenOlt(o)}
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
 * Daftar ODF — rak terminasi di dalam POP, tempat kabel luar berhenti.
 *
 * Kolomnya sengaja tak menyalin tab ODC/ODP: rak tak beralamat sendiri (alamatnya
 * alamat POP-nya), jadi yang menggantikan kolom Alamat adalah POP induknya. Dua kolom
 * angkanya menjawab dua pertanyaan berbeda — "masih ada adapter kosong?" (port
 * terpakai) dan "seberapa sibuk isinya?" (sambungan) — sebab satu port memuat dua
 * sambungan, belakang & depan, jadi keduanya tak bisa saling menyimpulkan.
 */
function OdfsTab() {
  const { can } = useCan()
  const confirm = useConfirm()
  const { items, loading, reload, run } = useList<OdfView>('/api/odfs')
  const navigate = useNavigate()
  const [openOdf, setOpenOdf] = useState<OdfView | null>(null)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<AssetStatus | ''>('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [deleting, setDeleting] = useState(false)
  const canDelete = can('network.odf.delete')
  const canMap = can('gis.map.view')

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter(
      (o) => matchesQuery([o.code, o.name, o.siteName], q) && (!statusFilter || o.status === statusFilter),
    )
  }, [items, query, statusFilter])

  useEffect(() => {
    if (!openOdf) return
    setOpenOdf(items.find((it) => it.id === openOdf.id) ?? null)
  }, [items, openOdf])

  const removeOdf = (o: OdfView) =>
    void (async () => {
      if (
        await confirm({
          title: 'Hapus ODF',
          message: `Hapus ODF ${o.code}?`,
          confirmLabel: 'Hapus',
          danger: true,
        })
      ) {
        setOpenOdf(null)
        void run(() => api.del(`/api/odfs/${o.id}`))
      }
    })()

  const deleteSelected = async () => {
    const ids = [...selected]
    if (ids.length === 0) return
    if (
      !(await confirm({
        title: 'Hapus ODF',
        message: `Hapus ${ids.length} ODF terpilih?`,
        confirmLabel: 'Hapus',
        danger: true,
      }))
    )
      return
    setDeleting(true)
    await run(async () => {
      await Promise.all(ids.map((id) => api.del(`/api/odfs/${id}`)))
      setSelected(new Set())
    })
    setDeleting(false)
  }

  const columns: Column<OdfView>[] = [
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
      key: 'site',
      header: 'POP',
      sortValue: (o) => o.siteName ?? '',
      cell: (o) => <span className="muted">{o.siteName ?? '—'}</span>,
    },
    {
      key: 'port',
      header: 'Port',
      align: 'right',
      // Diurut menurut SISA port kosong, bukan jumlah terpakai: yang dicari orang
      // saat mengurut kolom ini adalah rak yang hampir penuh — itulah yang menentukan
      // kabel berikutnya boleh mendarat di sini atau harus cari rak lain.
      sortValue: (o) => o.portCount - o.usedPortCount,
      cell: (o) => (
        <span className="tnum">
          {o.usedPortCount}/{o.portCount}
        </span>
      ),
    },
    {
      key: 'splice',
      header: 'Sambungan',
      align: 'right',
      sortValue: (o) => o.spliceCount,
      cell: (o) => <span className="tnum">{o.spliceCount}</span>,
    },
    { key: 'status', header: 'Status', sortValue: (o) => o.status, cell: (o) => <StatusBadge status={o.status} /> },
  ]

  const rowActions = (o: OdfView): RowAction[] => [
    { key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => removeOdf(o) },
  ]

  // Sama seperti aset titik lain: rak lahir dari titik di peta, jadi di sini cuma pintasan.
  const primary: CommandAction | undefined = can('network.odf.create')
    ? { key: 'map', label: 'Tambah di peta', icon: <MapPin size={16} />, onClick: () => navigate('/map') }
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
        open={openOdf != null}
        size="full"
        className="blade-detail"
        title={openOdf?.code ?? ''}
        subtitle={openOdf?.name}
        onClose={() => setOpenOdf(null)}
      >
        {openOdf && (
          <OdfDetail
            odfId={openOdf.id}
            onChanged={() => void reload()}
            onDeleted={() => {
              setOpenOdf(null)
              void reload()
            }}
            onShowOnMap={canMap ? (focus) => navigate('/map', focus) : undefined}
          />
        )}
      </Blade>

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari kode, nama, atau POP…" />
        <SelectField value={statusFilter} onChange={(_, data) => setStatusFilter(data.value as AssetStatus | '')}>
          {ASSET_STATUS_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(o) => o.id}
        onRowClick={(o) => setOpenOdf(o)}
        loading={loading}
        initialSort={{ key: 'code', dir: 'asc' }}
        selection={canDelete ? { selected, onChange: setSelected } : undefined}
        rowActions={canDelete ? rowActions : undefined}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada ODF yang cocok' : 'Belum ada ODF'}
            hint={
              query || statusFilter
                ? 'Coba ubah kata kunci atau filter.'
                : 'Tambahkan ODF lewat Peta Jaringan — klik titik POP-nya, lalu pilih "ODF (rak POP)".'
            }
            icon={<IconInventory size={32} />}
          />
        }
      />
    </div>
  )
}

function OdcsTab() {
  const { can } = useCan()
  const confirm = useConfirm()
  const { items, loading, reload, run } = useList<OdcView>('/api/odcs')
  const navigate = useNavigate()
  // Klik baris membuka DETAIL bersama [AccessNodeDetail] — sunting & hapusnya diurus di
  // sana, jadi tab ini tinggal jadi daftar. Membuat ODC & menyetel uplink (feeder ke
  // OLT/PON) tetap di peta, karena keduanya butuh titik koordinat & tarikan kabel.
  const [openOdc, setOpenOdc] = useState<OdcView | null>(null)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<AssetStatus | ''>('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [deleting, setDeleting] = useState(false)
  const canDelete = can('network.odc.delete')
  const canMap = can('gis.map.view')

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter(
      (o) => matchesQuery([o.code, o.name, o.oltName], q) && (!statusFilter || o.status === statusFilter),
    )
  }, [items, query, statusFilter])

  // Selaraskan detail terbuka dengan data terbaru tiap daftar dimuat ulang (mis. usai
  // Edit) — atau tutup panel bila ODC-nya sudah terhapus. Konvergen: find mengembalikan
  // ref yang sama saat data tak berubah sehingga setState di-bail React.
  useEffect(() => {
    if (!openOdc) return
    setOpenOdc(items.find((it) => it.id === openOdc.id) ?? null)
  }, [items, openOdc])

  const removeOdc = (o: OdcView) =>
    void (async () => {
      if (await confirm({ title: 'Hapus ODC', message: `Hapus ODC ${o.code}?`, confirmLabel: 'Hapus', danger: true })) {
        setOpenOdc(null)
        void run(() => api.del(`/api/odcs/${o.id}`))
      }
    })()

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

  const rowActions = (o: OdcView): RowAction[] => [
    { key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => removeOdc(o) },
  ]

  // ODC dibuat di peta (butuh titik koordinat) — di sini cuma pintasan ke peta.
  const primary: CommandAction | undefined = can('network.odc.create')
    ? { key: 'map', label: 'Tambah di peta', icon: <MapPin size={16} />, onClick: () => navigate('/map') }
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

      {/* Blade DETAIL ODC (non-modal, lebar >½ layar): klik baris → panel ini; tombol
          Edit membuka drawer sunting yang lebih sempit di atasnya tanpa menutup penuh. */}
      <Blade
        open={openOdc != null}
        size="full"
        className="blade-detail"
        title={openOdc?.code ?? ''}
        subtitle={openOdc?.name}
        onClose={() => setOpenOdc(null)}
      >
        {openOdc && (
          <AccessNodeDetail
            kind="odc"
            nodeId={openOdc.id}
            onChanged={() => void reload()}
            onDeleted={() => {
              setOpenOdc(null)
              void reload()
            }}
            onShowOnMap={canMap ? (focus) => navigate('/map', focus) : undefined}
          />
        )}
      </Blade>

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari kode, nama, atau OLT hulu…" />
        <SelectField value={statusFilter} onChange={(_, data) => setStatusFilter(data.value as AssetStatus | '')}>
          {ASSET_STATUS_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(o) => o.id}
        onRowClick={(o) => setOpenOdc(o)}
        loading={loading}
        initialSort={{ key: 'code', dir: 'asc' }}
        selection={canDelete ? { selected, onChange: setSelected } : undefined}
        rowActions={canDelete ? rowActions : undefined}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada ODC yang cocok' : 'Belum ada ODC'}
            hint={query || statusFilter ? 'Coba ubah kata kunci atau filter.' : 'Tambahkan ODC lewat Peta Jaringan — klik titik lokasinya di peta.'}
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
  const navigate = useNavigate()
  // Sama seperti tab ODC: daftar di sini, detail/sunting/hapus di [AccessNodeDetail].
  // Membuat ODP & menetapkan ODC induk tetap di peta lewat kabel distribusi.
  const [openOdp, setOpenOdp] = useState<OdpView | null>(null)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<AssetStatus | ''>('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [deleting, setDeleting] = useState(false)
  const canDelete = can('network.odp.delete')
  const canMap = can('gis.map.view')

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter(
      (o) => matchesQuery([o.code, o.name, o.odcName], q) && (!statusFilter || o.status === statusFilter),
    )
  }, [items, query, statusFilter])

  // Selaraskan detail terbuka dengan data terbaru tiap daftar dimuat ulang (mis. usai
  // Edit) — atau tutup panel bila ODP-nya sudah terhapus.
  useEffect(() => {
    if (!openOdp) return
    setOpenOdp(items.find((it) => it.id === openOdp.id) ?? null)
  }, [items, openOdp])

  const removeOdp = (o: OdpView) =>
    void (async () => {
      if (await confirm({ title: 'Hapus ODP', message: `Hapus ODP ${o.code}?`, confirmLabel: 'Hapus', danger: true })) {
        setOpenOdp(null)
        void run(() => api.del(`/api/odps/${o.id}`))
      }
    })()

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

  const rowActions = (o: OdpView): RowAction[] => [
    { key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => removeOdp(o) },
  ]

  // ODP dibuat di peta (butuh titik koordinat) — di sini cuma pintasan ke peta.
  const primary: CommandAction | undefined = can('network.odp.create')
    ? { key: 'map', label: 'Tambah di peta', icon: <MapPin size={16} />, onClick: () => navigate('/map') }
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

      {/* Blade DETAIL ODP (non-modal, lebar >½ layar): klik baris → panel ini; tombol
          Edit membuka drawer sunting yang lebih sempit di atasnya tanpa menutup penuh. */}
      <Blade
        open={openOdp != null}
        size="full"
        className="blade-detail"
        title={openOdp?.code ?? ''}
        subtitle={openOdp?.name}
        onClose={() => setOpenOdp(null)}
      >
        {openOdp && (
          <AccessNodeDetail
            kind="odp"
            nodeId={openOdp.id}
            onChanged={() => void reload()}
            onDeleted={() => {
              setOpenOdp(null)
              void reload()
            }}
            onShowOnMap={canMap ? (focus) => navigate('/map', focus) : undefined}
          />
        )}
      </Blade>

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari kode, nama, atau ODC induk…" />
        <SelectField value={statusFilter} onChange={(_, data) => setStatusFilter(data.value as AssetStatus | '')}>
          {ASSET_STATUS_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(o) => o.id}
        onRowClick={(o) => setOpenOdp(o)}
        loading={loading}
        initialSort={{ key: 'code', dir: 'asc' }}
        selection={canDelete ? { selected, onChange: setSelected } : undefined}
        rowActions={canDelete ? rowActions : undefined}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada ODP yang cocok' : 'Belum ada ODP'}
            hint={query || statusFilter ? 'Coba ubah kata kunci atau filter.' : 'Tambahkan ODP lewat Peta Jaringan — klik titik lokasinya di peta.'}
            icon={<IconInventory size={32} />}
          />
        }
      />
    </div>
  )
}

/**
 * Daftar joint box — kotak sambung di tengah jalur (dua haspel bertemu, jalur
 * bercabang di persimpangan, kabel putus disambung darurat).
 *
 * Bentuknya kembar tab ODC/ODP, tapi kolomnya beda karena isinya beda: tak ada
 * splitter dan tak ada "induk" untuk ditampilkan — yang ingin diketahui orang dari
 * sebuah kotak sambung cuma seberapa penuh ia, jadi kolomnya tray & sambungan.
 */
function JointBoxesTab() {
  const { can } = useCan()
  const confirm = useConfirm()
  const { items, loading, reload, run } = useList<JointBoxView>('/api/joint-boxes')
  const navigate = useNavigate()
  const [openBox, setOpenBox] = useState<JointBoxView | null>(null)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<AssetStatus | ''>('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [deleting, setDeleting] = useState(false)
  const canDelete = can('network.jointbox.delete')
  const canMap = can('gis.map.view')

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter(
      (b) => matchesQuery([b.code, b.name, b.address], q) && (!statusFilter || b.status === statusFilter),
    )
  }, [items, query, statusFilter])

  useEffect(() => {
    if (!openBox) return
    setOpenBox(items.find((it) => it.id === openBox.id) ?? null)
  }, [items, openBox])

  const removeBox = (b: JointBoxView) =>
    void (async () => {
      if (
        await confirm({
          title: 'Hapus joint box',
          message: `Hapus joint box ${b.code}?`,
          confirmLabel: 'Hapus',
          danger: true,
        })
      ) {
        setOpenBox(null)
        void run(() => api.del(`/api/joint-boxes/${b.id}`))
      }
    })()

  const deleteSelected = async () => {
    const ids = [...selected]
    if (ids.length === 0) return
    if (
      !(await confirm({
        title: 'Hapus joint box',
        message: `Hapus ${ids.length} joint box terpilih?`,
        confirmLabel: 'Hapus',
        danger: true,
      }))
    )
      return
    setDeleting(true)
    await run(async () => {
      await Promise.all(ids.map((id) => api.del(`/api/joint-boxes/${id}`)))
      setSelected(new Set())
    })
    setDeleting(false)
  }

  const columns: Column<JointBoxView>[] = [
    {
      key: 'code',
      header: 'Kode',
      sortValue: (b) => b.code,
      cell: (b) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <strong>{b.code}</strong>
          <span className="muted" style={{ fontSize: '0.8rem' }}>{b.name}</span>
        </div>
      ),
    },
    {
      key: 'address',
      header: 'Alamat',
      sortValue: (b) => b.address,
      cell: (b) => <span className="muted">{b.address ?? '—'}</span>,
    },
    { key: 'tray', header: 'Tray', align: 'right', sortValue: (b) => b.trayCount, cell: (b) => b.trayCount },
    {
      key: 'splice',
      header: 'Sambungan',
      align: 'right',
      // Diurut berdasarkan JUMLAH sambungan, bukan persen: yang dicari operator saat
      // mengurut kolom ini adalah kotak tergemuk, bukan yang rasionya paling ketat.
      sortValue: (b) => b.spliceCount,
      cell: (b) => (
        <span className="tnum">
          {b.spliceCount}/{b.capacity}
        </span>
      ),
    },
    { key: 'status', header: 'Status', sortValue: (b) => b.status, cell: (b) => <StatusBadge status={b.status} /> },
  ]

  const rowActions = (b: JointBoxView): RowAction[] => [
    { key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => removeBox(b) },
  ]

  // Sama seperti ODC/ODP: joint box lahir dari titik di peta, jadi di sini cuma pintasan.
  const primary: CommandAction | undefined = can('network.jointbox.create')
    ? { key: 'map', label: 'Tambah di peta', icon: <MapPin size={16} />, onClick: () => navigate('/map') }
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
        open={openBox != null}
        size="full"
        className="blade-detail"
        title={openBox?.code ?? ''}
        subtitle={openBox?.name}
        onClose={() => setOpenBox(null)}
      >
        {openBox && (
          <AccessNodeDetail
            kind="joint_box"
            nodeId={openBox.id}
            onChanged={() => void reload()}
            onDeleted={() => {
              setOpenBox(null)
              void reload()
            }}
            onShowOnMap={canMap ? (focus) => navigate('/map', focus) : undefined}
          />
        )}
      </Blade>

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari kode, nama, atau alamat…" />
        <SelectField value={statusFilter} onChange={(_, data) => setStatusFilter(data.value as AssetStatus | '')}>
          {ASSET_STATUS_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(b) => b.id}
        onRowClick={(b) => setOpenBox(b)}
        loading={loading}
        initialSort={{ key: 'code', dir: 'asc' }}
        selection={canDelete ? { selected, onChange: setSelected } : undefined}
        rowActions={canDelete ? rowActions : undefined}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada joint box yang cocok' : 'Belum ada joint box'}
            hint={
              query || statusFilter
                ? 'Coba ubah kata kunci atau filter.'
                : 'Tambahkan joint box lewat Peta Jaringan — klik titik sambungnya di peta.'
            }
            icon={<IconInventory size={32} />}
          />
        }
      />
    </div>
  )
}
