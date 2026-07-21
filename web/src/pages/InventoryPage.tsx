import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import type { OdcView, OdpView, OltView, SiteView } from '../api/network'
import { useCan } from '../auth/useCan'
import { StatusBadge, useToast } from '../components/ui'
import { IconPlus } from '../components/icons'

/**
 * Inventory jaringan dalam satu halaman bertab.
 *
 * Dijadikan satu halaman karena keempat aset ini dikelola berurutan — site dulu,
 * lalu OLT di atasnya, lalu ODC, lalu ODP — sehingga berpindah antar tab lebih
 * masuk akal daripada berpindah antar halaman.
 */

type Tab = 'sites' | 'olts' | 'odcs' | 'odps'

const TABS: Array<{ key: Tab; label: string; permission: string }> = [
  { key: 'sites', label: 'Site / POP', permission: 'network.site.view' },
  { key: 'olts', label: 'OLT', permission: 'network.olt.view' },
  { key: 'odcs', label: 'ODC', permission: 'network.odc.view' },
  { key: 'odps', label: 'ODP', permission: 'network.odp.view' },
]

const SPLITTER_RATIOS = ['1:2', '1:4', '1:8', '1:16', '1:32', '1:64']
const VENDORS = ['ZTE', 'HUAWEI', 'FIBERHOME', 'NOKIA', 'OTHER']

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
  onChange: (field: 'longitude' | 'latitude', value: string) => void
}) {
  return (
    <>
      <label style={{ flex: 1 }}>
        <span>Longitude</span>
        <input value={longitude} onChange={(e) => onChange('longitude', e.target.value)} placeholder="106.9975" />
      </label>
      <label style={{ flex: 1 }}>
        <span>Latitude</span>
        <input value={latitude} onChange={(e) => onChange('latitude', e.target.value)} placeholder="-6.2428" />
      </label>
    </>
  )
}

function SitesTab() {
  const { can } = useCan()
  const { items, run } = useList<SiteView>('/api/sites')
  const empty = { code: '', name: '', address: '', longitude: '', latitude: '' }
  const [draft, setDraft] = useState<typeof empty | null>(null)

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
          <div className="row">
            <label style={{ flex: 2 }}>
              <span>Alamat</span>
              <input value={draft.address} onChange={(e) => setDraft({ ...draft, address: e.target.value })} />
            </label>
            <LocationFields
              longitude={draft.longitude}
              latitude={draft.latitude}
              onChange={(field, value) => setDraft({ ...draft, [field]: value })}
            />
          </div>
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

      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Kode</th>
              <th>Nama</th>
              <th>OLT</th>
              <th>Koordinat</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {items.map((site) => (
              <tr key={site.id}>
                <td>{site.code}</td>
                <td>
                  {site.name}
                  <br />
                  <span className="muted" style={{ fontSize: '0.8rem' }}>
                    {site.address}
                  </span>
                </td>
                <td>{site.oltCount}</td>
                <td className="muted" style={{ fontSize: '0.8rem' }}>
                  {site.location.latitude.toFixed(5)}, {site.location.longitude.toFixed(5)}
                </td>
                <td>
                  {can('network.site.delete') && (
                    <button onClick={() => void run(() => api.del(`/api/sites/${site.id}`))}>Hapus</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function OltsTab() {
  const { can } = useCan()
  const { items, run } = useList<OltView>('/api/olts')
  const { items: sites } = useList<SiteView>('/api/sites')
  const empty = { siteId: '', code: '', name: '', vendor: 'ZTE', model: '', managementIp: '', snmpCommunity: '' }
  const [draft, setDraft] = useState<typeof empty | null>(null)
  const [ports, setPorts] = useState<Record<string, string>>({})

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

      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Kode</th>
              <th>Site</th>
              <th>Vendor</th>
              <th>Monitoring</th>
              <th>PON port</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {items.map((olt) => (
              <tr key={olt.id}>
                <td>
                  {olt.code}
                  <br />
                  <span className="muted" style={{ fontSize: '0.8rem' }}>
                    {olt.name}
                  </span>
                </td>
                <td className="muted">{olt.siteName}</td>
                <td>
                  {olt.vendor}
                  <br />
                  <span className="muted" style={{ fontSize: '0.8rem' }}>
                    {olt.managementIp ?? '—'}
                  </span>
                </td>
                <td>
                  <span className="badge">{olt.pollable ? 'siap dipolling' : 'belum lengkap'}</span>
                </td>
                <td>
                  {olt.ponPortCount}
                  {can('network.olt.update') && (
                    <div className="row" style={{ marginTop: '0.35rem' }}>
                      <input
                        style={{ width: '5.5rem' }}
                        placeholder="1/2/3"
                        value={ports[olt.id] ?? ''}
                        onChange={(e) => setPorts({ ...ports, [olt.id]: e.target.value })}
                      />
                      <button
                        onClick={() =>
                          void run(async () => {
                            await api.post(`/api/olts/${olt.id}/pon-ports`, { label: ports[olt.id] })
                            setPorts({ ...ports, [olt.id]: '' })
                          })
                        }
                      >
                        + port
                      </button>
                    </div>
                  )}
                </td>
                <td>
                  {can('network.olt.delete') && (
                    <button onClick={() => void run(() => api.del(`/api/olts/${olt.id}`))}>Hapus</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function OdcsTab() {
  const { can } = useCan()
  const { items, run } = useList<OdcView>('/api/odcs')
  const empty = { code: '', name: '', longitude: '', latitude: '', splitterRatio: '1:8', capacity: '64' }
  const [draft, setDraft] = useState<typeof empty | null>(null)

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
            <LocationFields
              longitude={draft.longitude}
              latitude={draft.latitude}
              onChange={(field, value) => setDraft({ ...draft, [field]: value })}
            />
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

      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Kode</th>
              <th>Hulu</th>
              <th>Splitter</th>
              <th>ODP</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {items.map((odc) => (
              <tr key={odc.id}>
                <td>
                  {odc.code}
                  <br />
                  <span className="muted" style={{ fontSize: '0.8rem' }}>
                    {odc.name}
                  </span>
                </td>
                <td className="muted" style={{ fontSize: '0.85rem' }}>
                  {odc.oltName ? `${odc.oltName} · ${odc.ponPortLabel}` : 'belum di-uplink'}
                </td>
                <td>{odc.splitterRatio}</td>
                <td>{odc.odpCount}</td>
                <td>
                  {odc.energized ? <StatusBadge status="ACTIVE" label="teraliri" /> : <StatusBadge status={odc.status} />}
                </td>
                <td>
                  {can('network.odc.delete') && (
                    <button onClick={() => void run(() => api.del(`/api/odcs/${odc.id}`))}>Hapus</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function OdpsTab() {
  const { can } = useCan()
  const { items, run } = useList<OdpView>('/api/odps')
  const { items: odcs } = useList<OdcView>('/api/odcs')
  const empty = { code: '', name: '', longitude: '', latitude: '', odcId: '', splitterRatio: '1:8', capacity: '8' }
  const [draft, setDraft] = useState<typeof empty | null>(null)

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
            <LocationFields
              longitude={draft.longitude}
              latitude={draft.latitude}
              onChange={(field, value) => setDraft({ ...draft, [field]: value })}
            />
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

      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Kode</th>
              <th>ODC induk</th>
              <th>Splitter</th>
              <th>Port</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {items.map((odp) => (
              <tr key={odp.id}>
                <td>
                  {odp.code}
                  <br />
                  <span className="muted" style={{ fontSize: '0.8rem' }}>
                    {odp.name}
                  </span>
                </td>
                <td className="muted">{odp.odcName ?? '—'}</td>
                <td>{odp.splitterRatio}</td>
                <td>{odp.capacity}</td>
                <td>
                  <StatusBadge status={odp.status} />
                </td>
                <td>
                  {can('network.odp.delete') && (
                    <button onClick={() => void run(() => api.del(`/api/odps/${odp.id}`))}>Hapus</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
