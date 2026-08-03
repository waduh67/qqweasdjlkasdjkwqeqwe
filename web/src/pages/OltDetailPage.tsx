import { useCallback, useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { AssetStatus, OltView, PonPortView, SiteView, SnmpVersion, WebProtocol } from '../api/network'
import type { PageResponse } from '../api/types'
import { useCan } from '../auth/useCan'
import { LocationPicker } from '../components/LocationPicker'
import { Badge, EmptyState, Modal, Spinner, StatusBadge, useToast } from '../components/ui'
import { IconInventory, IconPlus } from '../components/icons'

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

type Tab = 'ringkasan' | 'pon'

export function OltDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const back = (location.state as { backTo?: string; backLabel?: string } | null) ?? null
  const backTo = back?.backTo ?? '/inventory'
  const backLabel = back?.backLabel ?? 'Inventaris'
  const toast = useToast()
  const { can } = useCan()
  const canUpdate = can('network.olt.update')
  const canDelete = can('network.olt.delete')

  const [olt, setOlt] = useState<OltView | null>(null)
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [tab, setTab] = useState<Tab>('ringkasan')
  const [editing, setEditing] = useState(false)

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
      navigate(backTo)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus OLT')
    }
  }

  if (loading) {
    return (
      <div className="stack" style={{ gap: '1.25rem' }}>
        <BackLink label={backLabel} onClick={() => navigate(backTo)} />
        <div className="card" style={{ display: 'grid', placeItems: 'center', padding: '3rem' }}>
          <Spinner />
        </div>
      </div>
    )
  }

  if (notFound || !olt) {
    return (
      <div className="stack" style={{ gap: '1rem' }}>
        <BackLink label={backLabel} onClick={() => navigate(backTo)} />
        <div className="card">
          <EmptyState
            title="OLT tidak ditemukan"
            hint="Mungkin sudah dihapus atau kamu tak berizin melihatnya."
            icon={<IconInventory size={32} />}
          />
        </div>
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <BackLink label={backLabel} onClick={() => navigate(backTo)} />

      <div className="spread" style={{ gap: '0.75rem', alignItems: 'flex-start' }}>
        <div className="stack" style={{ gap: '0.35rem' }}>
          <div className="row wrap" style={{ gap: '0.5rem', alignItems: 'center' }}>
            <h1 className="page-title" style={{ margin: 0 }}>{olt.code}</h1>
            <StatusBadge status={olt.status} />
            <Badge>{olt.vendor}</Badge>
            {olt.pollable ? <Badge tone="good">SNMP siap</Badge> : <Badge tone="neutral">SNMP belum lengkap</Badge>}
          </div>
          <p className="page-sub" style={{ margin: 0 }}>
            {olt.name}
            {olt.siteName ? ` · Site ${olt.siteName}` : ''}
          </p>
        </div>
        <div className="row" style={{ gap: '0.5rem', flexShrink: 0 }}>
          {canUpdate && (
            <button className="ghost" onClick={() => setEditing(true)}>
              Edit
            </button>
          )}
          {canDelete && (
            <button className="ghost danger" onClick={() => void remove()}>
              Hapus
            </button>
          )}
        </div>
      </div>

      <div className="segment" style={{ alignSelf: 'flex-start' }}>
        <button className={tab === 'ringkasan' ? 'active' : ''} onClick={() => setTab('ringkasan')}>
          Ringkasan
        </button>
        <button className={tab === 'pon' ? 'active' : ''} onClick={() => setTab('pon')}>
          PON Port{olt.ponPortCount ? ` (${olt.ponPortCount})` : ''}
        </button>
      </div>

      {tab === 'ringkasan' && <RingkasanTab olt={olt} canUpdate={canUpdate} onSaved={load} />}
      {tab === 'pon' && <PonPortTab oltId={olt.id} canUpdate={canUpdate} onChanged={load} />}

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
                <button className="primary" disabled={saving} onClick={() => void saveLocation()}>
                  Simpan lokasi
                </button>
                <button
                  className="ghost"
                  disabled={saving}
                  onClick={() => {
                    setLon(String(olt.location.longitude))
                    setLat(String(olt.location.latitude))
                  }}
                >
                  Batal
                </button>
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
function PonPortTab({ oltId, canUpdate, onChanged }: { oltId: string; canUpdate: boolean; onChanged: () => void }) {
  const toast = useToast()
  const [ports, setPorts] = useState<PonPortView[] | null>(null)
  const [label, setLabel] = useState('')
  const [busy, setBusy] = useState(false)

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
            <input style={{ width: '7rem' }} placeholder="1/2/3" value={label} onChange={(e) => setLabel(e.target.value)} />
            <button className="primary" disabled={busy || !label.trim()} onClick={() => void add()}>
              <IconPlus size={14} /> Tambah
            </button>
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
          {ports.map((p) => (
            <div
              key={p.id}
              className="spread"
              style={{ gap: '0.5rem', alignItems: 'center', padding: '0.55rem 0', borderTop: '1px solid var(--border)' }}
            >
              <div className="row" style={{ gap: '0.5rem', alignItems: 'center', minWidth: 0 }}>
                <strong className="tnum">{p.label}</strong>
                <StatusBadge status={p.status} />
                {p.description && (
                  <span className="muted" style={{ fontSize: '0.85rem' }}>
                    {p.description}
                  </span>
                )}
              </div>
              <div className="row" style={{ gap: '0.5rem', alignItems: 'center', flexShrink: 0 }}>
                <span className="badge">{p.odcCount} ODC</span>
                {canUpdate && (
                  <button className="ghost danger" disabled={p.odcCount > 0} onClick={() => void remove(p)}>
                    Hapus
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
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
    <Modal
      title={`Edit ${olt.code}`}
      onClose={onClose}
      wide
      footer={
        <>
          <button className="ghost" onClick={onClose} disabled={saving}>
            Batal
          </button>
          <button className="primary" onClick={() => void save()} disabled={saving || !name.trim() || !siteId}>
            Simpan
          </button>
        </>
      }
    >
      <div className="stack" style={{ gap: '0.75rem' }}>
        <div className="row" style={{ gap: '0.5rem' }}>
          <label style={{ flex: 1 }}>
            <span>Site</span>
            <select value={siteId} onChange={(e) => setSiteId(e.target.value)}>
              {sites.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} — {s.name}
                </option>
              ))}
            </select>
          </label>
          <label style={{ flex: 1 }}>
            <span>Nama</span>
            <input value={name} onChange={(e) => setName(e.target.value)} />
          </label>
        </div>
        <div className="row" style={{ gap: '0.5rem' }}>
          <label style={{ flex: 1 }}>
            <span>Vendor</span>
            <select value={vendor} onChange={(e) => setVendor(e.target.value)}>
              {VENDORS.map((v) => (
                <option key={v} value={v}>
                  {v}
                </option>
              ))}
              {!VENDORS.includes(vendor) && <option value={vendor}>{vendor}</option>}
            </select>
          </label>
          <label style={{ flex: 1 }}>
            <span>
              Model <span className="muted">(opsional)</span>
            </span>
            <input value={model} onChange={(e) => setModel(e.target.value)} placeholder="C320" />
          </label>
          <label style={{ flex: 1 }}>
            <span>Status</span>
            <select value={status} onChange={(e) => setStatus(e.target.value as AssetStatus)}>
              {STATUS_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        </div>
        <div className="row" style={{ gap: '0.5rem' }}>
          <label style={{ flex: 1 }}>
            <span>
              IP manajemen <span className="muted">(opsional)</span>
            </span>
            <input value={managementIp} onChange={(e) => setManagementIp(e.target.value)} placeholder="10.10.1.2" />
          </label>
          <label style={{ flex: 2 }}>
            <span>
              Deskripsi <span className="muted">(opsional)</span>
            </span>
            <input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Lokasi rak, kontak vendor, atau ID kontrak…"
            />
          </label>
        </div>

        {/* Kanal SNMP */}
        <div className="stack" style={{ gap: '0.6rem', borderTop: '1px solid var(--border)', paddingTop: '0.85rem' }}>
          <label className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
            <input
              type="checkbox"
              checked={snmpEnabled}
              onChange={(e) => setSnmpEnabled(e.target.checked)}
              style={{ width: 'auto' }}
            />
            <span style={{ fontWeight: 600 }}>Aktifkan SNMP untuk OLT ini</span>
          </label>
          {snmpEnabled && (
            <div className="row" style={{ gap: '0.5rem' }}>
              <label style={{ flex: 1 }}>
                <span>
                  Community string <span className="muted">(RO/RW)</span>
                </span>
                <input
                  type="password"
                  value={snmpCommunity}
                  onChange={(e) => setSnmpCommunity(e.target.value)}
                  placeholder={olt.snmpConfigured ? '(tersimpan)' : 'public'}
                />
              </label>
              <label style={{ width: 120 }}>
                <span>Versi</span>
                <select value={snmpVersion} onChange={(e) => setSnmpVersion(e.target.value as SnmpVersion)}>
                  {SNMP_VERSIONS.map((v) => (
                    <option key={v.value} value={v.value}>
                      {v.label}
                    </option>
                  ))}
                </select>
              </label>
              <label style={{ width: 110 }}>
                <span>Port SNMP</span>
                <input type="number" min={1} max={65535} value={snmpPort} onChange={(e) => setSnmpPort(e.target.value)} />
              </label>
            </div>
          )}
        </div>

        {/* Kanal Web UI / HTTP */}
        <div className="stack" style={{ gap: '0.6rem', borderTop: '1px solid var(--border)', paddingTop: '0.85rem' }}>
          <label className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
            <input
              type="checkbox"
              checked={webEnabled}
              onChange={(e) => setWebEnabled(e.target.checked)}
              style={{ width: 'auto' }}
            />
            <span style={{ fontWeight: 600 }}>Aktifkan Web UI / HTTP</span>
          </label>
          {webEnabled && (
            <div className="row" style={{ gap: '0.5rem' }}>
              <label style={{ width: 120 }}>
                <span>Protokol</span>
                <select value={webProtocol} onChange={(e) => setWebProtocol(e.target.value as WebProtocol)}>
                  <option value="HTTP">HTTP</option>
                  <option value="HTTPS">HTTPS</option>
                </select>
              </label>
              <label style={{ width: 120 }}>
                <span>Port Web</span>
                <input
                  type="number"
                  min={1}
                  max={65535}
                  value={webPort}
                  onChange={(e) => setWebPort(e.target.value)}
                  placeholder="80"
                />
              </label>
              <label style={{ flex: 1 }}>
                <span>
                  Web Username <span className="muted">(opsional)</span>
                </span>
                <input value={webUsername} onChange={(e) => setWebUsername(e.target.value)} placeholder="admin" />
              </label>
              <label style={{ flex: 1 }}>
                <span>Web Password</span>
                <input
                  type="password"
                  value={webPassword}
                  onChange={(e) => setWebPassword(e.target.value)}
                  placeholder={olt.webPasswordConfigured ? '(tersimpan)' : 'password Web UI'}
                />
              </label>
            </div>
          )}
        </div>

        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Community string &amp; password Web disimpan terenkripsi dan tak pernah ditampilkan. Kosongkan untuk
          mempertahankan yang tersimpan. Kode OLT tak bisa diubah.
        </p>
      </div>
    </Modal>
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
    <button className="ghost" onClick={onClick} style={{ alignSelf: 'flex-start', gap: '0.35rem' }}>
      <span aria-hidden>←</span> {label}
    </button>
  )
}
