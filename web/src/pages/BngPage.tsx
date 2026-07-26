import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import {
  createNas,
  createPlan,
  deleteNas,
  deletePlan,
  listNas,
  listPlans,
  NAS_VENDOR_LABEL,
  NAS_VENDORS,
  updateNas,
  updatePlan,
  type NasView,
  type RateProfileView,
  type SaveNasRequest,
  type SaveRateProfileRequest,
} from '../api/bng'
import { useCan } from '../auth/useCan'
import { useToast } from '../components/ui'
import { IconPlus } from '../components/icons'

/**
 * Paket & BRAS dalam satu halaman bertab.
 *
 * Keduanya adalah konfigurasi tingkat-tenant yang mendasari akun PPPoE pelanggan:
 * paket (rate profile) menentukan kecepatan, BRAS (NAS) adalah router master yang
 * menutup sesi PPPoE. Akun PPPoE sendiri dikelola per-pelanggan di halaman detail
 * pelanggan (tab Akses). Slice fondasi: murni data, belum ada perintah ke BRAS.
 */

type Tab = 'plans' | 'nas'

const TABS: Array<{ key: Tab; label: string; permission: string }> = [
  { key: 'plans', label: 'Paket', permission: 'bng.plan.view' },
  { key: 'nas', label: 'BRAS', permission: 'bng.nas.view' },
]

export function BngPage() {
  const { can } = useCan()
  const visible = TABS.filter((tab) => can(tab.permission))
  const [tab, setTab] = useState<Tab>(visible[0]?.key ?? 'plans')

  if (visible.length === 0) {
    return (
      <div className="card">
        <h3 style={{ marginTop: 0 }}>Akses ditolak</h3>
        <p className="muted">Kamu tidak punya izin melihat paket maupun BRAS.</p>
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div>
        <h1 className="page-title">Paket &amp; BRAS</h1>
        <p className="page-sub">
          Kelola paket layanan (kecepatan) dan registri BRAS/RADIUS — fondasi akun PPPoE pelanggan.
        </p>
      </div>
      <div className="segment" style={{ alignSelf: 'flex-start' }}>
        {visible.map((item) => (
          <button key={item.key} className={tab === item.key ? 'active' : ''} onClick={() => setTab(item.key)}>
            {item.label}
          </button>
        ))}
      </div>
      {tab === 'plans' && <PlansTab />}
      {tab === 'nas' && <NasTab />}
    </div>
  )
}

/** Hook pemuat daftar bersama untuk endpoint yang mengembalikan array polos. */
function useResource<T>(fetcher: () => Promise<T[]>) {
  const toast = useToast()
  const [items, setItems] = useState<T[]>([])
  const [loading, setLoading] = useState(true)

  const reload = useCallback(async () => {
    try {
      setItems(await fetcher())
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat data')
    } finally {
      setLoading(false)
    }
  }, [fetcher, toast])

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

/* ---------- Tab: Paket (rate profile) ---------- */

type PlanDraft = {
  id: string | null
  name: string
  description: string
  downMbps: string
  upMbps: string
  radiusProfileName: string
}

const EMPTY_PLAN: PlanDraft = { id: null, name: '', description: '', downMbps: '', upMbps: '', radiusProfileName: '' }

function PlansTab() {
  const { can } = useCan()
  const { items, run } = useResource(listPlans)
  const canManage = can('bng.plan.manage')
  const [draft, setDraft] = useState<PlanDraft | null>(null)

  const edit = (plan: RateProfileView) =>
    setDraft({
      id: plan.id,
      name: plan.name,
      description: plan.description ?? '',
      downMbps: String(plan.downMbps),
      upMbps: String(plan.upMbps),
      radiusProfileName: plan.radiusProfileName ?? '',
    })

  const save = () => {
    if (!draft) return
    const body: SaveRateProfileRequest = {
      name: draft.name,
      description: draft.description || null,
      downMbps: Number(draft.downMbps),
      upMbps: Number(draft.upMbps),
      radiusProfileName: draft.radiusProfileName || null,
    }
    void run(
      async () => {
        await (draft.id ? updatePlan(draft.id, body) : createPlan(body))
        setDraft(null)
      },
      draft.id ? 'Paket diperbarui' : 'Paket dibuat',
    )
  }

  return (
    <div className="stack">
      <div className="spread">
        <span className="muted">{items.length} paket</span>
        {canManage && (
          <button className="primary" onClick={() => setDraft({ ...EMPTY_PLAN })}>
            <IconPlus size={15} /> Tambah paket
          </button>
        )}
      </div>

      {draft && (
        <div className="card stack">
          <div className="row">
            <label style={{ flex: 2 }}>
              <span>Nama paket</span>
              <input
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                placeholder="Home 50 Mbps"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Unduh (Mbps)</span>
              <input
                value={draft.downMbps}
                onChange={(e) => setDraft({ ...draft, downMbps: e.target.value })}
                placeholder="50"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Unggah (Mbps)</span>
              <input
                value={draft.upMbps}
                onChange={(e) => setDraft({ ...draft, upMbps: e.target.value })}
                placeholder="25"
              />
            </label>
          </div>
          <div className="row">
            <label style={{ flex: 2 }}>
              <span>Deskripsi</span>
              <input
                value={draft.description}
                onChange={(e) => setDraft({ ...draft, description: e.target.value })}
                placeholder="opsional"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Profil RADIUS</span>
              <input
                value={draft.radiusProfileName}
                onChange={(e) => setDraft({ ...draft, radiusProfileName: e.target.value })}
                placeholder="pppoe-50m"
              />
            </label>
          </div>
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Profil RADIUS = nama profil yang dikenal BRAS (profil PPP Mikrotik atau grup FreeRADIUS) —
            dipakai saat menerapkan kecepatan ke sesi nanti.
          </p>
          <div className="row">
            <button className="primary" onClick={save}>
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
              <th>Nama</th>
              <th>Kecepatan</th>
              <th>Profil RADIUS</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {items.map((plan) => (
              <tr key={plan.id}>
                <td>
                  {plan.name}
                  {plan.description && (
                    <>
                      <br />
                      <span className="muted" style={{ fontSize: '0.8rem' }}>
                        {plan.description}
                      </span>
                    </>
                  )}
                </td>
                <td className="tnum">
                  {plan.downMbps} / {plan.upMbps} Mbps
                </td>
                <td className="muted">{plan.radiusProfileName ?? '—'}</td>
                <td>
                  {canManage && (
                    <div className="row">
                      <button onClick={() => edit(plan)}>Ubah</button>
                      <button
                        onClick={() => {
                          if (window.confirm(`Hapus paket ${plan.name}?`)) {
                            void run(() => deletePlan(plan.id), 'Paket dihapus')
                          }
                        }}
                      >
                        Hapus
                      </button>
                    </div>
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

/* ---------- Tab: BRAS (NAS) ---------- */

type NasDraft = {
  id: string | null
  name: string
  vendor: NasView['vendor']
  address: string
  nasIdentifier: string
  coaSecret: string
  enabled: boolean
  /** Apakah entri yang diedit sudah punya secret CoA (untuk teks bantu). */
  hasCoaSecret: boolean
  /** Kredensial kontrol adapter nyata (REST RouterOS / SQL FreeRADIUS). */
  apiUsername: string
  apiSecret: string
  apiPort: string
  apiUseTls: boolean
  apiDatabase: string
  hasApiSecret: boolean
}

const EMPTY_NAS: NasDraft = {
  id: null,
  name: '',
  vendor: 'MIKROTIK',
  address: '',
  nasIdentifier: '',
  coaSecret: '',
  enabled: true,
  hasCoaSecret: false,
  apiUsername: '',
  apiSecret: '',
  apiPort: '',
  apiUseTls: true,
  apiDatabase: '',
  hasApiSecret: false,
}

function NasTab() {
  const { can } = useCan()
  const { items, run } = useResource(listNas)
  const canManage = can('bng.nas.manage')
  const [draft, setDraft] = useState<NasDraft | null>(null)

  const edit = (nas: NasView) =>
    setDraft({
      id: nas.id,
      name: nas.name,
      vendor: nas.vendor,
      address: nas.address ?? '',
      nasIdentifier: nas.nasIdentifier ?? '',
      coaSecret: '',
      enabled: nas.enabled,
      hasCoaSecret: nas.hasCoaSecret,
      apiUsername: nas.apiUsername ?? '',
      apiSecret: '',
      apiPort: nas.apiPort != null ? String(nas.apiPort) : '',
      apiUseTls: nas.apiUseTls,
      apiDatabase: nas.apiDatabase ?? '',
      hasApiSecret: nas.hasApiSecret,
    })

  const save = () => {
    if (!draft) return
    const body: SaveNasRequest = {
      name: draft.name,
      vendor: draft.vendor,
      address: draft.address || null,
      nasIdentifier: draft.nasIdentifier || null,
      coaSecret: draft.coaSecret || null,
      enabled: draft.enabled,
      apiUsername: draft.apiUsername || null,
      apiSecret: draft.apiSecret || null,
      apiPort: draft.apiPort ? Number(draft.apiPort) : null,
      apiUseTls: draft.apiUseTls,
      apiDatabase: draft.apiDatabase || null,
    }
    void run(
      async () => {
        await (draft.id ? updateNas(draft.id, body) : createNas(body))
        setDraft(null)
      },
      draft.id ? 'BRAS diperbarui' : 'BRAS didaftarkan',
    )
  }

  return (
    <div className="stack">
      <div className="spread">
        <span className="muted">{items.length} BRAS</span>
        {canManage && (
          <button className="primary" onClick={() => setDraft({ ...EMPTY_NAS })}>
            <IconPlus size={15} /> Tambah BRAS
          </button>
        )}
      </div>

      {draft && (
        <div className="card stack">
          <div className="row">
            <label style={{ flex: 2 }}>
              <span>Nama</span>
              <input
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                placeholder="BRAS-BKS-01"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Vendor</span>
              <select
                value={draft.vendor}
                onChange={(e) => setDraft({ ...draft, vendor: e.target.value as NasView['vendor'] })}
              >
                {NAS_VENDORS.map((vendor) => (
                  <option key={vendor} value={vendor}>
                    {NAS_VENDOR_LABEL[vendor]}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <div className="row">
            <label style={{ flex: 1 }}>
              <span>Alamat manajemen</span>
              <input
                value={draft.address}
                onChange={(e) => setDraft({ ...draft, address: e.target.value })}
                placeholder="10.20.0.1"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>NAS-Identifier</span>
              <input
                value={draft.nasIdentifier}
                onChange={(e) => setDraft({ ...draft, nasIdentifier: e.target.value })}
                placeholder="opsional"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Secret CoA</span>
              <input
                type="password"
                value={draft.coaSecret}
                onChange={(e) => setDraft({ ...draft, coaSecret: e.target.value })}
                placeholder={draft.hasCoaSecret ? 'terisi — isi untuk mengganti' : 'opsional'}
              />
            </label>
          </div>

          {draft.vendor === 'MIKROTIK' && (
            <>
              <p className="muted" style={{ margin: '0.25rem 0 0', fontWeight: 600 }}>
                Kredensial REST API (RouterOS v7)
              </p>
              <div className="row">
                <label style={{ flex: 1 }}>
                  <span>User API</span>
                  <input
                    value={draft.apiUsername}
                    onChange={(e) => setDraft({ ...draft, apiUsername: e.target.value })}
                    placeholder="mis. ftth-api"
                  />
                </label>
                <label style={{ flex: 1 }}>
                  <span>Password API</span>
                  <input
                    type="password"
                    value={draft.apiSecret}
                    onChange={(e) => setDraft({ ...draft, apiSecret: e.target.value })}
                    placeholder={draft.hasApiSecret ? 'terisi — isi untuk mengganti' : 'password user API'}
                  />
                </label>
                <label style={{ flex: 1 }}>
                  <span>Port</span>
                  <input
                    value={draft.apiPort}
                    onChange={(e) => setDraft({ ...draft, apiPort: e.target.value })}
                    placeholder={draft.apiUseTls ? '443' : '80'}
                  />
                </label>
              </div>
              <label className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
                <input
                  type="checkbox"
                  checked={draft.apiUseTls}
                  onChange={(e) => setDraft({ ...draft, apiUseTls: e.target.checked })}
                  style={{ width: 'auto' }}
                />
                <span>Pakai HTTPS (www-ssl)</span>
              </label>
            </>
          )}

          {draft.vendor === 'FREERADIUS' && (
            <>
              <p className="muted" style={{ margin: '0.25rem 0 0', fontWeight: 600 }}>
                Kredensial basis data RADIUS (FreeRADIUS)
              </p>
              <div className="row">
                <label style={{ flex: 2 }}>
                  <span>URL JDBC</span>
                  <input
                    value={draft.apiDatabase}
                    onChange={(e) => setDraft({ ...draft, apiDatabase: e.target.value })}
                    placeholder="jdbc:postgresql://10.0.0.9:5432/radius"
                  />
                </label>
              </div>
              <div className="row">
                <label style={{ flex: 1 }}>
                  <span>User DB</span>
                  <input
                    value={draft.apiUsername}
                    onChange={(e) => setDraft({ ...draft, apiUsername: e.target.value })}
                    placeholder="mis. radius"
                  />
                </label>
                <label style={{ flex: 1 }}>
                  <span>Password DB</span>
                  <input
                    type="password"
                    value={draft.apiSecret}
                    onChange={(e) => setDraft({ ...draft, apiSecret: e.target.value })}
                    placeholder={draft.hasApiSecret ? 'terisi — isi untuk mengganti' : 'password basis data'}
                  />
                </label>
              </div>
              <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
                Sesi dibaca dari tabel <code>radacct</code>; Disconnect/CoA tetap lewat Secret CoA di atas
                (paket RFC 5176 ke alamat manajemen, port 3799).
              </p>
            </>
          )}

          <label className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
            <input
              type="checkbox"
              checked={draft.enabled}
              onChange={(e) => setDraft({ ...draft, enabled: e.target.checked })}
              style={{ width: 'auto' }}
            />
            <span>Aktif</span>
          </label>
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Secret CoA (Change-of-Authorization) disimpan terenkripsi dan tidak pernah ditampilkan kembali.
            Dipakai untuk mengubah/memutus sesi PPPoE dari jarak jauh nanti.
          </p>
          <div className="row">
            <button className="primary" onClick={save}>
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
              <th>Nama</th>
              <th>Vendor</th>
              <th>Alamat</th>
              <th>CoA</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {items.map((nas) => (
              <tr key={nas.id}>
                <td>
                  {nas.name}
                  {nas.nasIdentifier && (
                    <>
                      <br />
                      <span className="muted" style={{ fontSize: '0.8rem' }}>
                        {nas.nasIdentifier}
                      </span>
                    </>
                  )}
                </td>
                <td>{NAS_VENDOR_LABEL[nas.vendor]}</td>
                <td className="muted">{nas.address ?? '—'}</td>
                <td>
                  <span
                    className="badge"
                    style={{ color: nas.hasCoaSecret ? 'var(--good-ink)' : 'var(--muted)', fontWeight: 600 }}
                  >
                    {nas.hasCoaSecret ? 'secret terpasang' : 'belum diisi'}
                  </span>
                </td>
                <td>
                  <span
                    className="badge"
                    style={{ color: nas.enabled ? 'var(--good-ink)' : 'var(--muted)', fontWeight: 600 }}
                  >
                    {nas.enabled ? 'aktif' : 'nonaktif'}
                  </span>
                </td>
                <td>
                  {canManage && (
                    <div className="row">
                      <button onClick={() => edit(nas)}>Ubah</button>
                      <button
                        onClick={() => {
                          if (window.confirm(`Hapus BRAS ${nas.name}?`)) {
                            void run(() => deleteNas(nas.id), 'BRAS dihapus')
                          }
                        }}
                      >
                        Hapus
                      </button>
                    </div>
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
