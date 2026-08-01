import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import type { AssetStatus, OdcView, OdpView, OltView, SiteView } from '../api/network'
import { useCan } from '../auth/useCan'
import { DataTable, type Column } from '../components/DataTable'
import { LocationPicker } from '../components/LocationPicker'
import { EmptyState, SearchInput, StatusBadge, Toolbar, useToast } from '../components/ui'
import { IconInventory, IconPlus } from '../components/icons'

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
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div>
        <h1 className="page-title">Inventory Jaringan</h1>
        <p className="page-sub">Kelola site, OLT, ODC, dan ODP — dari POP sampai kotak terminasi.</p>
      </div>
      <div className="segment" style={{ alignSelf: 'flex-start' }}>
        {visible.map((item) => (
          <button key={item.key} className={tab === item.key ? 'active' : ''} onClick={() => setTab(item.key)}>
            {item.label}
          </button>
        ))}
      </div>
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
  const { items, loading, run } = useList<SiteView>('/api/sites')
  const empty = { code: '', name: '', address: '', longitude: '', latitude: '' }
  const [draft, setDraft] = useState<typeof empty | null>(null)
  const [query, setQuery] = useState('')

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter((s) => matchesQuery([s.code, s.name, s.address], q))
  }, [items, query])

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
    {
      key: 'actions',
      header: '',
      align: 'right',
      width: '1%',
      cell: (s) =>
        can('network.site.delete') ? (
          <button onClick={() => void run(() => api.del(`/api/sites/${s.id}`))}>Hapus</button>
        ) : null,
    },
  ]

  return (
    <div className="stack">
      <div className="spread">
        <span className="muted">{items.length} site</span>
        {can('network.site.create') && (
          <button className="primary" onClick={() => setDraft({ ...empty })}>
            <IconPlus size={15} /> Tambah site
          </button>
        )}
      </div>

      {draft && (
        <div className="card stack">
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
          <div className="row">
            <button
              className="primary"
              onClick={() =>
                void run(async () => {
                  await api.post('/api/sites', {
                    code: draft.code,
                    name: draft.name,
                    address: draft.address || null,
                    location: { longitude: Number(draft.longitude), latitude: Number(draft.latitude) },
                  })
                  setDraft(null)
                })
              }
            >
              Simpan
            </button>
            <button onClick={() => setDraft(null)}>Batal</button>
          </div>
        </div>
      )}

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari kode, nama, atau alamat…" />
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(s) => s.id}
        loading={loading}
        initialSort={{ key: 'code', dir: 'asc' }}
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
  const { items, loading, run } = useList<OltView>('/api/olts')
  const { items: sites } = useList<SiteView>('/api/sites')
  const empty = { siteId: '', code: '', name: '', vendor: 'ZTE', model: '', managementIp: '', snmpCommunity: '', snmpPort: '161' }
  const [draft, setDraft] = useState<typeof empty | null>(null)
  const [ports, setPorts] = useState<Record<string, string>>({})
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<AssetStatus | ''>('')

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter(
      (o) =>
        matchesQuery([o.code, o.name, o.siteName, o.vendor, o.managementIp], q) &&
        (!statusFilter || o.status === statusFilter),
    )
  }, [items, query, statusFilter])

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
            <div className="row" style={{ gap: '0.3rem' }}>
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
    {
      key: 'actions',
      header: '',
      align: 'right',
      width: '1%',
      cell: (o) =>
        can('network.olt.delete') ? (
          <button onClick={() => void run(() => api.del(`/api/olts/${o.id}`))}>Hapus</button>
        ) : null,
    },
  ]

  return (
    <div className="stack">
      <div className="spread">
        <span className="muted">{items.length} OLT</span>
        {can('network.olt.create') && (
          <button className="primary" onClick={() => setDraft({ ...empty, siteId: sites[0]?.id ?? '' })}>
            <IconPlus size={15} /> Tambah OLT
          </button>
        )}
      </div>

      {draft && (
        <div className="card stack">
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
              <span>Vendor</span>
              <select value={draft.vendor} onChange={(e) => setDraft({ ...draft, vendor: e.target.value })}>
                {VENDORS.map((vendor) => (
                  <option key={vendor}>{vendor}</option>
                ))}
              </select>
            </label>
            <label style={{ flex: 1 }}>
              <span>Model</span>
              <input value={draft.model} onChange={(e) => setDraft({ ...draft, model: e.target.value })} placeholder="C320" />
            </label>
            <label style={{ flex: 1 }}>
              <span>IP manajemen</span>
              <input
                value={draft.managementIp}
                onChange={(e) => setDraft({ ...draft, managementIp: e.target.value })}
                placeholder="10.10.1.2"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>SNMP community</span>
              <input
                type="password"
                value={draft.snmpCommunity}
                onChange={(e) => setDraft({ ...draft, snmpCommunity: e.target.value })}
              />
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
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Community string disimpan terenkripsi dan tidak pernah ditampilkan kembali.
          </p>
          <div className="row">
            <button
              className="primary"
              onClick={() =>
                void run(async () => {
                  await api.post('/api/olts', {
                    ...draft,
                    model: draft.model || null,
                    managementIp: draft.managementIp || null,
                    snmpCommunity: draft.snmpCommunity || null,
                    snmpPort: Number(draft.snmpPort) || 161,
                  })
                  setDraft(null)
                })
              }
            >
              Simpan
            </button>
            <button onClick={() => setDraft(null)}>Batal</button>
          </div>
        </div>
      )}

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
        loading={loading}
        initialSort={{ key: 'code', dir: 'asc' }}
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

function OdcsTab() {
  const { can } = useCan()
  const { items, loading, run } = useList<OdcView>('/api/odcs')
  const empty = { code: '', name: '', longitude: '', latitude: '', splitterRatio: '1:8', capacity: '64' }
  const [draft, setDraft] = useState<typeof empty | null>(null)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<AssetStatus | ''>('')

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter(
      (o) => matchesQuery([o.code, o.name, o.oltName], q) && (!statusFilter || o.status === statusFilter),
    )
  }, [items, query, statusFilter])

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
    {
      key: 'actions',
      header: '',
      align: 'right',
      width: '1%',
      cell: (o) =>
        can('network.odc.delete') ? (
          <button onClick={() => void run(() => api.del(`/api/odcs/${o.id}`))}>Hapus</button>
        ) : null,
    },
  ]

  return (
    <div className="stack">
      <div className="spread">
        <span className="muted">{items.length} ODC</span>
        {can('network.odc.create') && (
          <button className="primary" onClick={() => setDraft({ ...empty })}>
            <IconPlus size={15} /> Tambah ODC
          </button>
        )}
      </div>

      {draft && (
        <div className="card stack">
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
          <div className="row">
            <button
              className="primary"
              onClick={() =>
                void run(async () => {
                  await api.post('/api/odcs', {
                    code: draft.code,
                    name: draft.name,
                    location: { longitude: Number(draft.longitude), latitude: Number(draft.latitude) },
                    splitterRatio: draft.splitterRatio,
                    capacity: Number(draft.capacity),
                  })
                  setDraft(null)
                })
              }
            >
              Simpan
            </button>
            <button onClick={() => setDraft(null)}>Batal</button>
          </div>
        </div>
      )}

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
  const { items, loading, run } = useList<OdpView>('/api/odps')
  const { items: odcs } = useList<OdcView>('/api/odcs')
  const empty = { code: '', name: '', longitude: '', latitude: '', odcId: '', splitterRatio: '1:8', capacity: '8' }
  const [draft, setDraft] = useState<typeof empty | null>(null)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<AssetStatus | ''>('')

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter(
      (o) => matchesQuery([o.code, o.name, o.odcName], q) && (!statusFilter || o.status === statusFilter),
    )
  }, [items, query, statusFilter])

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
    {
      key: 'actions',
      header: '',
      align: 'right',
      width: '1%',
      cell: (o) =>
        can('network.odp.delete') ? (
          <button onClick={() => void run(() => api.del(`/api/odps/${o.id}`))}>Hapus</button>
        ) : null,
    },
  ]

  return (
    <div className="stack">
      <div className="spread">
        <span className="muted">{items.length} ODP</span>
        {can('network.odp.create') && (
          <button className="primary" onClick={() => setDraft({ ...empty, odcId: odcs[0]?.id ?? '' })}>
            <IconPlus size={15} /> Tambah ODP
          </button>
        )}
      </div>

      {draft && (
        <div className="card stack">
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
          <div className="row">
            <button
              className="primary"
              onClick={() =>
                void run(async () => {
                  await api.post('/api/odps', {
                    code: draft.code,
                    name: draft.name,
                    location: { longitude: Number(draft.longitude), latitude: Number(draft.latitude) },
                    odcId: draft.odcId || null,
                    splitterRatio: draft.splitterRatio,
                    capacity: Number(draft.capacity),
                  })
                  setDraft(null)
                })
              }
            >
              Simpan
            </button>
            <button onClick={() => setDraft(null)}>Batal</button>
          </div>
        </div>
      )}

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
