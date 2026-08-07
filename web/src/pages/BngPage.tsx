import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../api/client'
import {
  createNas,
  deleteNas,
  getRadiusEndpoint,
  listNas,
  NAS_VENDOR_LABEL,
  NAS_VENDORS,
  updateNas,
  type NasVendor,
  type NasView,
  type RadiusEndpointView,
  type SaveNasRequest,
} from '../api/bng'
import type { Area } from '../api/types'
import { useCan } from '../auth/useCan'
import { Blade } from '../components/Blade'
import { DataTable, type Column } from '../components/DataTable'
import { Badge, EmptyState, SearchInput, StatusBadge, Toolbar, useConfirm, useToast } from '../components/ui'
import { IconGauge, IconPlus } from '../components/icons'

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
          pusat memuatnya otomatis; tak perlu setup server RADIUS sendiri. Config Mikrotik siap-salin
          muncul di form tiap BRAS.
        </p>
      </div>
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
  /** Area yang dinaungi BRAS ini — dasar auto-pilih BRAS dari area pelanggan saat PSB. */
  areaIds: string[]
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
  areaIds: [],
}

function NasTab({ endpoint }: { endpoint: RadiusEndpointView | null }) {
  const { can } = useCan()
  const confirm = useConfirm()
  const { items, loading, run } = useResource(listNas)
  const canManage = can('bng.nas.manage')
  const canViewAreas = can('iam.area.view')
  const [areas, setAreas] = useState<Area[]>([])
  const [draft, setDraft] = useState<NasDraft | null>(null)
  const [query, setQuery] = useState('')
  const [vendorFilter, setVendorFilter] = useState<NasVendor | ''>('')
  const [statusFilter, setStatusFilter] = useState<'' | 'enabled' | 'disabled'>('')

  useEffect(() => {
    if (!canViewAreas) return
    void api
      .get<Area[]>('/api/areas')
      .then(setAreas)
      .catch(() => setAreas([]))
  }, [canViewAreas])
  /** Buka draft baru/edit dengan secret tersembunyi (baru terungkap saat "Generate"). */
  const [revealSecret, setRevealSecret] = useState(false)
  /** Snapshot draft awal untuk deteksi perubahan (konfirmasi sebelum tutup Blade). */
  const [initialDraft, setInitialDraft] = useState<NasDraft | null>(null)
  const openDraft = (next: NasDraft) => {
    setRevealSecret(false)
    setDraft(next)
    setInitialDraft(next)
  }
  const closeDraft = () => {
    setRevealSecret(false)
    setDraft(null)
    setInitialDraft(null)
  }
  const dirty = draft != null && JSON.stringify(draft) !== JSON.stringify(initialDraft)

  const edit = (nas: NasView) =>
    openDraft({
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
      areaIds: nas.areaIds,
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
      areaIds: draft.areaIds,
    }
    void run(
      async () => {
        await (draft.id ? updateNas(draft.id, body) : createNas(body))
        closeDraft()
      },
      draft.id ? 'BRAS diperbarui' : 'BRAS didaftarkan',
    )
  }

  /** Peta id→nama area untuk menampilkan cakupan tiap BRAS tanpa memanggil balik. */
  const areaNames = useMemo(() => new Map(areas.map((a) => [a.id, a.name])), [areas])

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter((nas) => {
      if (vendorFilter && nas.vendor !== vendorFilter) return false
      if (statusFilter === 'enabled' && !nas.enabled) return false
      if (statusFilter === 'disabled' && nas.enabled) return false
      if (!q) return true
      return [nas.name, nas.address ?? '', nas.nasIdentifier ?? '', NAS_VENDOR_LABEL[nas.vendor]].some((v) =>
        v.toLowerCase().includes(q),
      )
    })
  }, [items, query, vendorFilter, statusFilter])

  const columns: Column<NasView>[] = [
    {
      key: 'name',
      header: 'Nama',
      sortValue: (nas) => nas.name,
      cell: (nas) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <strong>{nas.name}</strong>
          {nas.nasIdentifier && (
            <span className="muted" style={{ fontSize: '0.8rem' }}>
              {nas.nasIdentifier}
            </span>
          )}
        </div>
      ),
    },
    {
      key: 'vendor',
      header: 'Vendor',
      sortValue: (nas) => NAS_VENDOR_LABEL[nas.vendor],
      cell: (nas) => NAS_VENDOR_LABEL[nas.vendor],
    },
    {
      key: 'address',
      header: 'Alamat',
      sortValue: (nas) => nas.address ?? '',
      cell: (nas) => nas.address ?? <span className="muted">—</span>,
    },
    {
      key: 'areas',
      header: 'Cakupan area',
      sortValue: (nas) => nas.areaIds.length,
      cell: (nas) => {
        if (nas.areaIds.length === 0) return <span className="muted">—</span>
        const names = nas.areaIds.map((id) => areaNames.get(id)).filter((n): n is string => !!n)
        if (names.length === 0) return <Badge>{nas.areaIds.length} area</Badge>
        return (
          <div className="row" style={{ gap: '0.3rem', flexWrap: 'wrap' }}>
            {names.map((n) => (
              <Badge key={n}>{n}</Badge>
            ))}
          </div>
        )
      },
    },
    {
      key: 'secret',
      header: 'Secret',
      sortValue: (nas) => (nas.hasCoaSecret ? 1 : 0),
      cell: (nas) => (
        <Badge tone={nas.hasCoaSecret ? 'good' : 'neutral'}>
          {nas.hasCoaSecret ? 'secret terpasang' : 'belum diisi'}
        </Badge>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (nas) => (nas.enabled ? 1 : 0),
      cell: (nas) => (
        <StatusBadge status={nas.enabled ? 'ACTIVE' : 'INACTIVE'} label={nas.enabled ? 'aktif' : 'nonaktif'} />
      ),
    },
    ...(canManage
      ? [
          {
            key: 'actions',
            header: '',
            align: 'right',
            width: '1%',
            cell: (nas: NasView) => (
              <div className="row" style={{ justifyContent: 'flex-end' }}>
                <button onClick={() => edit(nas)}>Ubah</button>
                <button
                  onClick={() => {
                    void (async () => {
                      if (await confirm({ title: 'Hapus BRAS', message: `Hapus BRAS ${nas.name}?`, confirmLabel: 'Hapus', danger: true })) {
                        void run(() => deleteNas(nas.id), 'BRAS dihapus')
                      }
                    })()
                  }}
                >
                  Hapus
                </button>
              </div>
            ),
          } satisfies Column<NasView>,
        ]
      : []),
  ]

  return (
    <div className="stack">
      <div className="spread">
        <span className="muted">{items.length} BRAS</span>
        {canManage && (
          <button className="primary" onClick={() => openDraft({ ...EMPTY_NAS })}>
            <IconPlus size={15} /> Tambah BRAS
          </button>
        )}
      </div>

      <Blade
        open={draft != null}
        title={draft?.id ? 'Ubah BRAS' : 'Tambah BRAS'}
        subtitle="Kredensial RADIUS/REST API BRAS & cakupan area PSB Ekspres."
        size="lg"
        dirty={dirty}
        onClose={closeDraft}
        footer={
          <>
            <button className="primary" onClick={save}>
              Simpan
            </button>
            <button onClick={closeDraft}>Batal</button>
          </>
        }
      >
        {draft && (
          <div className="stack">
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

          <div className="stack" style={{ gap: '0.35rem' }}>
            <span className="muted" style={{ fontSize: '0.8rem', fontWeight: 600 }}>
              Area yang dinaungi BRAS ini
            </span>
            {!canViewAreas ? (
              <span className="muted" style={{ fontSize: '0.82rem' }}>
                Butuh izin lihat area (iam.area.view) untuk menyetel cakupan.
              </span>
            ) : areas.length === 0 ? (
              <span className="muted" style={{ fontSize: '0.82rem' }}>
                Belum ada area. Buat dulu di menu Area.
              </span>
            ) : (
              <div
                style={{
                  display: 'flex',
                  flexWrap: 'wrap',
                  gap: '0.4rem 1rem',
                  maxHeight: 160,
                  overflowY: 'auto',
                  border: '1px solid var(--line)',
                  borderRadius: 6,
                  padding: '0.5rem 0.65rem',
                }}
              >
                {areas.map((area) => (
                  <label key={area.id} className="row" style={{ gap: '0.4rem', alignItems: 'center', width: 'auto' }}>
                    <input
                      type="checkbox"
                      checked={draft.areaIds.includes(area.id)}
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          areaIds: e.target.checked
                            ? [...draft.areaIds, area.id]
                            : draft.areaIds.filter((id) => id !== area.id),
                        })
                      }
                      style={{ width: 'auto' }}
                    />
                    <span style={{ fontSize: '0.85rem' }}>{area.name}</span>
                  </label>
                ))}
              </div>
            )}
            <span className="muted" style={{ fontSize: '0.78rem' }}>
              PSB Ekspres memakai peta ini untuk otomatis memilih BRAS dari area pelanggan. Tiap area
              cuma boleh dinaungi satu BRAS.
            </span>
          </div>

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

          </div>
        )}
      </Blade>

      <Toolbar>
        <SearchInput
          value={query}
          onChange={setQuery}
          placeholder="Cari nama, alamat, NAS-Identifier, atau vendor…"
        />
        <select value={vendorFilter} onChange={(e) => setVendorFilter(e.target.value as NasVendor | '')}>
          <option value="">Semua vendor</option>
          {NAS_VENDORS.map((vendor) => (
            <option key={vendor} value={vendor}>
              {NAS_VENDOR_LABEL[vendor]}
            </option>
          ))}
        </select>
        <select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value as '' | 'enabled' | 'disabled')}
        >
          <option value="">Semua status</option>
          <option value="enabled">Aktif</option>
          <option value="disabled">Nonaktif</option>
        </select>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(nas) => nas.id}
        loading={loading}
        initialSort={{ key: 'name', dir: 'asc' }}
        empty={
          <EmptyState
            title={query || vendorFilter || statusFilter ? 'Tidak ada BRAS yang cocok' : 'Belum ada BRAS'}
            hint={
              query || vendorFilter || statusFilter
                ? 'Coba ubah kata kunci atau filter.'
                : 'Daftarkan router master pertama sebagai klien RADIUS.'
            }
            icon={<IconGauge size={32} />}
          />
        }
      />
    </div>
  )
}
