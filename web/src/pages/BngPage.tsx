import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import {
  createNas,
  deleteNas,
  getRadiusEndpoint,
  listNas,
  NAS_VENDOR_LABEL,
  NAS_VENDORS,
  updateNas,
  type NasView,
  type RadiusEndpointView,
  type SaveNasRequest,
} from '../api/bng'
import { useCan } from '../auth/useCan'
import { useToast } from '../components/ui'
import { IconPlus } from '../components/icons'

/**
 * Registri BRAS/RADIUS tenant.
 *
 * BRAS (NAS) adalah router master yang menutup sesi PPPoE dan menjadi klien RADIUS.
 * Katalog paket (kecepatan/harga/QoS) kini di halaman Paket Internet (modul catalog);
 * akun PPPoE sendiri dikelola per-pelanggan di halaman detail pelanggan (tab Akses).
 */

export function BngPage() {
  const { can } = useCan()
  const allowed = can('bng.nas.view')
  const [endpoint, setEndpoint] = useState<RadiusEndpointView | null>(null)

  useEffect(() => {
    if (!allowed) return
    void getRadiusEndpoint()
      .then(setEndpoint)
      .catch(() => setEndpoint(null))
  }, [allowed])

  if (!allowed) {
    return (
      <div className="card">
        <h3 style={{ marginTop: 0 }}>Akses ditolak</h3>
        <p className="muted">Kamu tidak punya izin melihat registri BRAS.</p>
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div>
        <h1 className="page-title">BRAS &amp; RADIUS</h1>
        <p className="page-sub">
          Daftarkan router master (BRAS) sebagai klien RADIUS — alamat + shared secret. FreeRADIUS
          pusat memuatnya otomatis; tak perlu setup server RADIUS sendiri. Arahkan router ke alamat
          server di bawah.
        </p>
      </div>
      {endpoint && <RadiusServerCard endpoint={endpoint} />}
      <NasTab endpoint={endpoint} />
    </div>
  )
}

/**
 * Rakit skrip RouterOS v7 untuk mengarahkan Mikrotik ke FreeRADIUS pusat. [secret] kosong →
 * placeholder `<SECRET-BRAS>`; host kosong (platform belum set) → `<IP-RADIUS>`. Urutan sama
 * dengan panduan deploy: /radius add (auth+acct) · incoming (CoA) · ppp aaa use-radius.
 */
function mikrotikScript(endpoint: RadiusEndpointView, secret: string): string {
  const host = endpoint.host ?? '<IP-RADIUS>'
  const sec = secret || '<SECRET-BRAS>'
  return [
    `/radius add service=ppp address=${host} secret=${sec} \\`,
    `    authentication-port=${endpoint.authPort} accounting-port=${endpoint.acctPort}`,
    `/radius incoming set accept=yes port=${endpoint.coaPort}`,
    `/ppp aaa set use-radius=yes accounting=yes interim-update=5m`,
  ].join('\n')
}

/** Blok skrip RouterOS siap-salin (monospace multi-baris + tombol Salin). */
function MikrotikSnippet({ script }: { script: string }) {
  const toast = useToast()
  return (
    <div className="stack" style={{ gap: '0.4rem' }}>
      <code
        style={{
          display: 'block',
          whiteSpace: 'pre',
          overflowX: 'auto',
          padding: '0.6rem 0.75rem',
          fontSize: '0.8rem',
          lineHeight: 1.5,
        }}
      >
        {script}
      </code>
      <div>
        <button
          type="button"
          className="small"
          onClick={() =>
            void navigator.clipboard?.writeText(script).then(() => toast.success('Config Mikrotik disalin'))
          }
        >
          Salin config Mikrotik
        </button>
      </div>
    </div>
  )
}

/**
 * Kartu info koneksi server RADIUS pusat — ke mana tenant mengarahkan router-nya. Host/port
 * dari platform (sama untuk semua tenant). Bila platform belum mengisi host, tampilkan catatan
 * alih-alih menebak. Secret di skrip = placeholder (diisi nyata di form saat Generate).
 */
function RadiusServerCard({ endpoint }: { endpoint: RadiusEndpointView }) {
  return (
    <div className="card stack" style={{ gap: '0.75rem' }}>
      <div>
        <h3 style={{ margin: 0 }}>Arahkan router ke RADIUS ini</h3>
        <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.85rem' }}>
          Setiap BRAS mengarah ke server RADIUS pusat kami. Auth &amp; accounting jalan tanpa VPN
          (router menembak keluar) — cukup router bisa keluar ke internet.
        </p>
      </div>
      {endpoint.configured ? (
        <>
          <div className="row" style={{ gap: '1.5rem', flexWrap: 'wrap' }}>
            <span>
              <span className="muted" style={{ fontSize: '0.8rem' }}>
                Host
              </span>
              <br />
              <code>{endpoint.host}</code>
            </span>
            <span>
              <span className="muted" style={{ fontSize: '0.8rem' }}>
                Auth
              </span>
              <br />
              <code>{endpoint.authPort}/udp</code>
            </span>
            <span>
              <span className="muted" style={{ fontSize: '0.8rem' }}>
                Accounting
              </span>
              <br />
              <code>{endpoint.acctPort}/udp</code>
            </span>
            <span>
              <span className="muted" style={{ fontSize: '0.8rem' }}>
                CoA (server → BRAS)
              </span>
              <br />
              <code>{endpoint.coaPort}/udp</code>
            </span>
          </div>
          <MikrotikSnippet script={mikrotikScript(endpoint, '')} />
          <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
            Ganti <code>&lt;SECRET-BRAS&gt;</code> dengan shared secret BRAS ini (hasil Generate di
            form). Buka <code>{endpoint.coaPort}/udp</code> masuk di router agar CoA/Disconnect dari
            server bisa memutus/mengubah sesi.
          </p>
        </>
      ) : (
        <p className="muted" style={{ margin: 0 }}>
          Host server RADIUS belum dikonfigurasi platform. Hubungi admin untuk mengaktifkan{' '}
          <code>FTTH_RADIUS_PUBLIC_HOST</code> sebelum mengarahkan router.
        </p>
      )}
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

/* ---------- BRAS (NAS) ---------- */

/**
 * Shared secret RADIUS acak (base64url ~24 char). Dipakai tombol "Generate" agar
 * operator tak perlu mengarang secret sendiri — nilai sama dipakai Mikrotik (auth ke
 * FreeRADIUS pusat) dan server (CoA/Disconnect RFC 5176).
 */
function randomSecret(): string {
  const bytes = new Uint8Array(18)
  crypto.getRandomValues(bytes)
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

type NasDraft = {
  id: string | null
  name: string
  vendor: NasView['vendor']
  address: string
  nasIdentifier: string
  coaSecret: string
  enabled: boolean
  /** Apakah entri yang diedit sudah punya shared secret (untuk teks bantu). */
  hasCoaSecret: boolean
  /** Kredensial kontrol REST RouterOS (vendor MIKROTIK). */
  apiUsername: string
  apiSecret: string
  apiPort: string
  apiUseTls: boolean
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
  hasApiSecret: false,
}

function NasTab({ endpoint }: { endpoint: RadiusEndpointView | null }) {
  const { can } = useCan()
  const { items, run } = useResource(listNas)
  const canManage = can('bng.nas.manage')
  const [draft, setDraft] = useState<NasDraft | null>(null)
  /** Buka draft baru/edit dengan secret tersembunyi (baru terungkap saat "Generate"). */
  const [revealSecret, setRevealSecret] = useState(false)
  const open = (next: NasDraft | null) => {
    setRevealSecret(false)
    setDraft(next)
  }

  const edit = (nas: NasView) =>
    open({
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
    }
    void run(
      async () => {
        await (draft.id ? updateNas(draft.id, body) : createNas(body))
        open(null)
      },
      draft.id ? 'BRAS diperbarui' : 'BRAS didaftarkan',
    )
  }

  return (
    <div className="stack">
      <div className="spread">
        <span className="muted">{items.length} BRAS</span>
        {canManage && (
          <button className="primary" onClick={() => open({ ...EMPTY_NAS })}>
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
                placeholder="IP publik / overlay VPN, mis. 10.20.0.1"
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
          </div>
          <div className="row" style={{ alignItems: 'flex-end' }}>
            <label style={{ flex: 2 }}>
              <span>Shared Secret RADIUS</span>
              <input
                type={revealSecret ? 'text' : 'password'}
                value={draft.coaSecret}
                onChange={(e) => setDraft({ ...draft, coaSecret: e.target.value })}
                placeholder={draft.hasCoaSecret ? 'terisi — isi untuk mengganti' : 'ketik atau Generate'}
              />
            </label>
            <button
              type="button"
              onClick={() => {
                setDraft({ ...draft, coaSecret: randomSecret() })
                setRevealSecret(true)
              }}
            >
              Generate
            </button>
            <button type="button" onClick={() => setRevealSecret((v) => !v)}>
              {revealSecret ? 'Sembunyikan' : 'Lihat'}
            </button>
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
            Shared secret ini dipakai dua arah: Mikrotik memakainya untuk autentikasi ke FreeRADIUS
            pusat, dan server memakainya untuk CoA/Disconnect (RFC 5176) ke BRAS. Isi nilai yang
            <strong> sama persis</strong> di konfigurasi RADIUS Mikrotik. Disimpan terenkripsi dan tak
            pernah ditampilkan kembali — salin dulu hasil Generate sebelum menyimpan.
          </p>

          {endpoint?.configured && (
            <div className="stack" style={{ gap: '0.35rem' }}>
              <span className="muted" style={{ fontSize: '0.8rem', fontWeight: 600 }}>
                Config Mikrotik untuk BRAS ini
              </span>
              <MikrotikSnippet script={mikrotikScript(endpoint, draft.coaSecret)} />
              {!draft.coaSecret && (
                <span className="muted" style={{ fontSize: '0.78rem' }}>
                  Isi / Generate shared secret di atas agar tersalin utuh ke skrip.
                </span>
              )}
            </div>
          )}

          <div className="row">
            <button className="primary" onClick={save}>
              Simpan
            </button>
            <button onClick={() => open(null)}>Batal</button>
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
              <th>Secret</th>
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
