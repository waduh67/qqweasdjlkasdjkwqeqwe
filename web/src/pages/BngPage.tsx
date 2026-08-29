import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Accordion,
  AccordionHeader,
  AccordionItem,
  AccordionPanel,
  Checkbox,
  MessageBar,
  MessageBarBody,
  Text,
} from '@fluentui/react-components'
import { Pencil, Trash2 } from 'lucide-react'
import { api, ApiError } from '../api/client'
import {
  createNas,
  deleteNas,
  getRadiusEndpoint,
  listNas,
  NAS_REACHABILITY_HINT,
  NAS_REACHABILITY_LABEL,
  NAS_VENDOR_LABEL,
  NAS_VENDORS,
  updateNas,
  type NasReachability,
  type NasVendor,
  type NasView,
  type RadiusEndpointView,
  type SaveNasRequest,
} from '../api/bng'
import type { Area } from '../api/types'
import { useCan } from '../auth/useCan'
import { Blade } from '@/components/organisms'
import { DataTable, type Column } from '@/components/organisms'
import { Badge, Button, EmptyState, SelectField, StatusBadge, TextField, Toolbar } from '@/components/atoms'
import { SearchInput } from '@/components/molecules'
import { useConfirm, useToast } from '@/system'
import { PageHeader } from '@/components/molecules'
import { IconGauge, IconPlus } from '@/components/atoms/icons'
import { radiusTargetFor, selfAddressWarning, sessionControlRoute } from '@/utils/radiusTarget'
import { WALLED_GARDEN_PORT, defaultIsolirUrl, isolirScript, parseIsolirTarget } from '@/utils/isolirScript'

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
        <Text as="h3" size={400} weight="semibold" style={{ marginTop: 0 }}>Akses ditolak</Text>
        <Text as="p" className="muted" size={300}>Kamu tidak punya izin melihat registri BRAS.</Text>
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title="BRAS & RADIUS"
        subtitle={'Kelola router BRAS sebagai klien RADIUS, termasuk alamat manajemen dan shared secret.'}
      />
      <NasTab endpoint={endpoint} />
    </div>
  )
}

/**
 * Rakit skrip RouterOS v7 untuk mengarahkan Mikrotik ke FreeRADIUS pusat. [secret] kosong →
 * placeholder `<SECRET-BRAS>`; [radiusHost] kosong (platform belum set) → `<IP-RADIUS>`. Urutan
 * sama dengan panduan deploy: pool alamat pelanggan · /radius add (auth+acct) · incoming (CoA) ·
 * ppp aaa use-radius.
 *
 * Dua baris pool/profil ikut disertakan justru karena RADIUS TIDAK memberi alamat: yang kami
 * kirim adalah izin login dan kecepatan paket. Router yang profil PPP-nya belum punya
 * `remote-address` meloloskan login lalu langsung memutusnya lagi — di log terbaca
 * `logged in, 0.0.0.0` disusul `no network protocols running`, dan sepintas mirip masalah
 * RADIUS padahal autentikasinya justru sudah berhasil.
 *
 * [radiusHost] datang dari `radiusTargetFor` dan BUKAN selalu `endpoint.host`: BRAS yang masuk
 * lewat overlay VPN harus menembak alamat hub, sebab FreeRADIUS mengenali klien dari alamat asal
 * paketnya.
 */
function mikrotikScript(endpoint: RadiusEndpointView, secret: string, radiusHost: string | null): string {
  const host = radiusHost ?? '<IP-RADIUS>'
  const sec = secret || '<SECRET-BRAS>'
  return [
    `/ip pool add name=pool-pppoe ranges=10.20.0.2-10.20.255.254`,
    `/ppp profile set [find name=default] local-address=10.20.0.1 remote-address=pool-pppoe`,
    `/radius add service=ppp address=${host} secret=${sec} \\`,
    `    authentication-port=${endpoint.authPort} accounting-port=${endpoint.acctPort}`,
    `/radius incoming set accept=yes port=${endpoint.coaPort}`,
    `/ppp aaa set use-radius=yes accounting=yes interim-update=5m`,
  ].join('\n')
}

/** Blok skrip RouterOS siap-salin (monospace multi-baris + tombol Salin). */
function MikrotikSnippet({
  script,
  label = 'Salin config Mikrotik',
  copied = 'Config Mikrotik disalin',
}: {
  script: string
  label?: string
  copied?: string
}) {
  const toast = useToast()
  return (
    <div className="stack" style={{ gap: '0.4rem' }}>
      <code
        style={{
          display: 'block',
          whiteSpace: 'pre',
          overflowX: 'auto',
          padding: '0.6rem 0.75rem',
          
        }}
      >
        {script}
      </code>
      <div>
        <Button
          type="button"
          size="small"
          onClick={() => void navigator.clipboard?.writeText(script).then(() => toast.success(copied))}
        >
          {label}
        </Button>
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
  /**
   * Collector on-prem yang menjangkau BRAS ini. Tak ada kontrolnya di form ini (penugasan
   * collector dilakukan di tempat lain), tapi TETAP dibawa dalam draft: simpan yang tak
   * menyertakannya akan melepas tautan collector-nya diam-diam — dan sejak rute kendali
   * sesi disimpulkan dari sini, ikut membelokkan jalur isolir & Reset Login BRAS itu.
   */
  collectorId: string | null
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
  collectorId: null,
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
      collectorId: nas.collectorId,
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
      collectorId: draft.collectorId,
    }
    void run(
      async () => {
        await (draft.id ? updateNas(draft.id, body) : createNas(body))
        closeDraft()
      },
      draft.id ? 'BRAS diperbarui' : 'BRAS didaftarkan',
    )
  }

  /**
   * Alamat RADIUS yang benar untuk BRAS yang sedang disunting — ikut alamat yang barusan
   * diketik, sebab jalur masuk router (tunnel VPN vs internet biasa) yang menentukannya.
   * Dihitung hidup-hidup supaya skrip yang disodorkan berubah begitu alamatnya diketik,
   * bukan setelah gagal di lapangan.
   */
  const draftAddress = draft?.address ?? ''
  const radiusTarget = useMemo(
    () => (endpoint ? radiusTargetFor(endpoint, draftAddress) : null),
    [endpoint, draftAddress],
  )
  const addressWarning = selfAddressWarning(endpoint, draftAddress)
  /**
   * Alamat halaman tagihan untuk aturan walled-garden. Terisi sendiri dengan portal
   * pelanggan pada deployment yang sedang dibuka — hampir selalu benar — tapi tetap boleh
   * diganti: sebagian ISP memasang portalnya di domain sendiri.
   */
  const [isolirUrl, setIsolirUrl] = useState(() => defaultIsolirUrl(window.location.origin))
  const isolirTarget = useMemo(() => parseIsolirTarget(isolirUrl), [isolirUrl])
  /**
   * Rute isolir & Reset Login yang akan berlaku bila draft ini disimpan. Server yang
   * memutuskannya; ini pratinjaunya, supaya "tak terjangkau" ketahuan di sini — bukan
   * nanti, dari pelanggan yang mestinya terputus tapi masih online.
   */
  const draftRoute: NasReachability | null = draft
    ? sessionControlRoute(endpoint, draftAddress, draft.collectorId != null)
    : null

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

  const remove = (nas: NasView) => {
    void (async () => {
      if (await confirm({ title: 'Hapus BRAS', message: `Hapus BRAS ${nas.name}?`, confirmLabel: 'Hapus', danger: true })) {
        void run(() => deleteNas(nas.id), 'BRAS dihapus')
      }
    })()
  }
  const columns: Column<NasView>[] = [
    {
      key: 'name',
       header: 'Nama',
       sortValue: (nas) => nas.name,
       cell: (nas) => nas.name,
       onCellClick: edit,
       inlineActions: canManage
        ? (nas) => [
            { key: 'edit', label: 'Ubah', icon: <Pencil size={16} />, onClick: () => edit(nas) },
            { key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => remove(nas) },
          ]
        : undefined,
    },
    {
      key: 'identifier',
      header: 'NAS-Identifier',
      sortValue: (nas) => nas.nasIdentifier ?? '',
      cell: (nas) => nas.nasIdentifier ?? <span className="muted">—</span>,
    },
    {
      key: 'vendor',
      header: 'Vendor',
      sortValue: (nas) => NAS_VENDOR_LABEL[nas.vendor],
      cell: (nas) => NAS_VENDOR_LABEL[nas.vendor],
    },
    {
      key: 'address',
      header: 'Alamat manajemen',
      sortValue: (nas) => nas.address ?? '',
      cell: (nas) => nas.address ?? <span className="muted">—</span>,
    },
    {
      key: 'reachability',
      header: 'Kendali sesi',
      sortValue: (nas) => NAS_REACHABILITY_LABEL[nas.reachability],
      cell: (nas) => (
        <span title={NAS_REACHABILITY_HINT[nas.reachability]}>
          <Badge tone={nas.reachability === 'NONE' ? 'serious' : 'good'}>
            {NAS_REACHABILITY_LABEL[nas.reachability]}
          </Badge>
        </span>
      ),
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
  ]

  return (
    <div className="stack">
      <div className="spread">
        <span className="muted">{items.length} BRAS</span>
        {canManage && (
          <Button variant="primary" onClick={() => openDraft({ ...EMPTY_NAS })}>
            <IconPlus size={15} /> Daftarkan router
          </Button>
        )}
      </div>

      <Blade
        open={draft != null}
        title={draft?.id ? 'Ubah router BRAS' : 'Daftarkan router BRAS'}
        subtitle="Identitas dan akses RADIUS router."
        size="lg"
        dirty={dirty}
        onClose={closeDraft}
        footer={
          <>
            <Button variant="primary" onClick={save}>
              Simpan
            </Button>
            <Button onClick={closeDraft}>Batal</Button>
          </>
        }
      >
        {draft && (
          <div className="stack">
            <div className="row wrap">
              <TextField
                label="Nama BRAS"
                value={draft.name}
                onChange={(_, data) => setDraft({ ...draft, name: data.value })}
                placeholder="BRAS-BKS-01"
                style={{ flex: 2 }}
              />
              <SelectField
                label="Vendor"
                value={draft.vendor}
                onChange={(_, data) => setDraft({ ...draft, vendor: data.value as NasView['vendor'] })}
                style={{ flex: 1 }}
              >
                {NAS_VENDORS.map((vendor) => (
                  <option key={vendor} value={vendor}>{NAS_VENDOR_LABEL[vendor]}</option>
                ))}
              </SelectField>
            </div>

            <div className="row wrap">
              <TextField
                label="Alamat manajemen"
                value={draft.address}
                onChange={(_, data) => setDraft({ ...draft, address: data.value })}
                placeholder="Contoh: 10.8.0.3 atau 103.10.20.30"
                hint="Gunakan alamat tunnel untuk router melalui VPN."
                validationState={addressWarning ? 'warning' : undefined}
                validationMessage={addressWarning ?? undefined}
                style={{ flex: 1 }}
              />
              <TextField
                label="NAS-Identifier"
                value={draft.nasIdentifier}
                onChange={(_, data) => setDraft({ ...draft, nasIdentifier: data.value })}
                placeholder="Opsional"
                style={{ flex: 1 }}
              />
            </div>

            {draftRoute && (
              <div className="row wrap">
                <Text as="span" size={300} className="muted">Kendali sesi:</Text>
                <Badge tone={draftRoute === 'NONE' ? 'serious' : 'good'}>
                  {NAS_REACHABILITY_LABEL[draftRoute]}
                </Badge>
                <Text as="span" size={300} className="muted">{NAS_REACHABILITY_HINT[draftRoute]}</Text>
              </div>
            )}

            <div className="row wrap" style={{ alignItems: 'flex-end' }}>
              <TextField
                label="Shared secret RADIUS"
                type={revealSecret ? 'text' : 'password'}
                value={draft.coaSecret}
                onChange={(_, data) => setDraft({ ...draft, coaSecret: data.value })}
                placeholder={draft.hasCoaSecret ? 'terisi — isi untuk mengganti' : 'ketik atau Generate'}
                style={{ flex: 2 }}
              />
              <Button type="button" onClick={() => {
                setDraft({ ...draft, coaSecret: randomSecret() })
                setRevealSecret(true)
              }}>
                Generate
              </Button>
              <Button type="button" onClick={() => setRevealSecret((value) => !value)}>
                {revealSecret ? 'Sembunyikan' : 'Lihat'}
              </Button>
            </div>
            <MessageBar intent="warning">
              <MessageBarBody>Secret disimpan terenkripsi dan tidak dapat dibaca kembali.</MessageBarBody>
            </MessageBar>

            <Checkbox
              label="Aktif"
              checked={draft.enabled}
              onChange={(_, data) => setDraft({ ...draft, enabled: !!data.checked })}
            />

            <div className="stack" style={{ gap: '0.35rem' }}>
              <Text as="span" size={300} className="muted">Cakupan area</Text>
              {!canViewAreas ? (
                <Text as="span" size={300} className="muted">Anda memerlukan izin melihat area untuk mengatur cakupan.</Text>
              ) : areas.length === 0 ? (
                <Text as="span" size={300} className="muted">Belum ada area untuk ditetapkan.</Text>
              ) : (
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.4rem 1rem', maxHeight: 160, overflowY: 'auto', border: '1px solid var(--line)', borderRadius: 6, padding: '0.5rem 0.65rem' }}>
                  {areas.map((area) => (
                    <Checkbox
                      key={area.id}
                      label={area.name}
                      checked={draft.areaIds.includes(area.id)}
                      onChange={(_, data) => setDraft({
                        ...draft,
                        areaIds: data.checked ? [...draft.areaIds, area.id] : draft.areaIds.filter((id) => id !== area.id),
                      })}
                    />
                  ))}
                </div>
              )}
              <Text as="span" size={300} className="muted">Setiap area hanya dapat ditetapkan ke satu router.</Text>
            </div>

            <Accordion collapsible>
              {draft.vendor === 'MIKROTIK' && (
                <AccordionItem value="api-routeros">
                  <AccordionHeader>Akses API RouterOS</AccordionHeader>
                  <AccordionPanel>
                    <div className="stack">
                      <div className="row wrap">
                        <TextField label="Pengguna REST API" value={draft.apiUsername} onChange={(_, data) => setDraft({ ...draft, apiUsername: data.value })} placeholder="mis. ftth-api" style={{ flex: 1 }} />
                        <TextField label="Kata sandi REST API" type="password" value={draft.apiSecret} onChange={(_, data) => setDraft({ ...draft, apiSecret: data.value })} placeholder={draft.hasApiSecret ? 'terisi — isi untuk mengganti' : 'password user API'} style={{ flex: 1 }} />
                        <TextField label="Port" value={draft.apiPort} onChange={(_, data) => setDraft({ ...draft, apiPort: data.value })} placeholder={draft.apiUseTls ? '443' : '80'} style={{ flex: 1 }} />
                      </div>
                      <Checkbox label="Pakai HTTPS (www-ssl)" checked={draft.apiUseTls} onChange={(_, data) => setDraft({ ...draft, apiUseTls: !!data.checked })} />
                    </div>
                  </AccordionPanel>
                </AccordionItem>
              )}

              <AccordionItem value="panduan-konfigurasi">
                <AccordionHeader>Panduan konfigurasi</AccordionHeader>
                <AccordionPanel>
                  <div className="stack">
                    {endpoint && radiusTarget?.host && (
                      <>
                        <Text as="span" size={300} className="muted">Konfigurasi MikroTik</Text>
                        <MikrotikSnippet script={mikrotikScript(endpoint, draft.coaSecret, radiusTarget.host)} />
                        <Text as="span" size={300} className="muted">
                          Dua baris pertama memberi <strong>alamat</strong> ke pelanggan — RADIUS mengirim izin login dan kecepatan, bukan IP. Profil PPP tanpa <code>remote-address</code> membuat pelanggan lolos login lalu putus lagi seketika: di log router terbaca <code>logged in, 0.0.0.0</code> disusul <code>no network protocols running</code>. Ganti rentangnya dengan blok milikmu, dan bila server PPPoE memakai profil selain <code>default</code>, setel profil itu.
                        </Text>
                        {radiusTarget.viaVpn && (
                          <Text as="span" size={300} className="muted">
                            Alamat BRAS ini ada di blok VPN, jadi skrip menembak alamat hub <strong>{radiusTarget.host}</strong> — bukan IP publik. Router yang sudah ber-tunnel tetapi diarahkan ke IP publik akan mengirim paket dengan alamat asal yang tak terdaftar; RADIUS mengabaikan klien tak dikenal dan router hanya melihat timeout.
                          </Text>
                        )}
                        {!draft.coaSecret && (
                          <Text as="span" size={300} className="muted">Isi atau generate shared secret agar tersalin utuh ke skrip.</Text>
                        )}
                      </>
                    )}
                    {endpoint && (
                      <>
                        <Text as="span" size={300} className="muted">Halaman isolir (walled garden)</Text>
                        <TextField label="Alamat halaman tagihan" value={isolirUrl} onChange={(_, data) => setIsolirUrl(data.value)} placeholder="https://portal.isp-kamu.id/portal" />
                        <MikrotikSnippet script={isolirScript(endpoint.isolirAddressList, isolirTarget)} label="Salin aturan isolir" copied="Aturan isolir disalin" />
                        <Text as="span" size={300} className="muted">
                          Saat pelanggan diisolir, RADIUS memasukkan IP sesinya ke address-list <code>{endpoint.isolirAddressList}</code> dan menurunkan kecepatannya — <strong>router yang menentukan arti daftar itu</strong>. Tanpa aturan ini, pelanggan tetap dapat browsing. Pasang sekali per BRAS.
                        </Text>
                        <Text as="span" size={300} className="muted">
                          Login tetap diterima agar pelanggan dapat membuka tagihan. HTTPS ditolak cepat untuk menghindari peringatan sertifikat; captive portal memakai HTTP.
                        </Text>
                        <Text as="span" size={300} className="muted">
                          Baris NAT menembak port <code>{WALLED_GARDEN_PORT}</code>, bukan port halaman tagihan. Pastikan port ini terbuka di server konsol.
                        </Text>
                        {isolirTarget && !isolirTarget.ip && (
                          <MessageBar intent="warning"><MessageBarBody>Alamat memakai nama host. Ganti <code>&lt;IP-HALAMAN-TAGIHAN&gt;</code> dengan IP server {isolirTarget.host} pada aturan NAT.</MessageBarBody></MessageBar>
                        )}
                      </>
                    )}
                  </div>
                </AccordionPanel>
              </AccordionItem>
            </Accordion>
          </div>
        )}
      </Blade>

      <Toolbar>
        <SearchInput
          value={query}
          onChange={setQuery}
          placeholder="Cari nama, alamat, NAS-Identifier, atau vendor…"
        />
        <SelectField value={vendorFilter} onChange={(_, data) => setVendorFilter(data.value as NasVendor | '')}>
          <option value="">Semua vendor</option>
          {NAS_VENDORS.map((vendor) => (
            <option key={vendor} value={vendor}>
              {NAS_VENDOR_LABEL[vendor]}
            </option>
          ))}
        </SelectField>
        <SelectField
          value={statusFilter}
          onChange={(_, data) => setStatusFilter(data.value as '' | 'enabled' | 'disabled')}
        >
          <option value="">Semua status</option>
          <option value="enabled">Aktif</option>
          <option value="disabled">Nonaktif</option>
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(nas) => nas.id}
        loading={loading}
        initialSort={{ key: 'name', dir: 'asc' }}
        empty={
          <EmptyState
               title={query || vendorFilter || statusFilter ? 'Router BRAS tidak ditemukan' : 'Belum ada router BRAS'}
             hint={
               query || vendorFilter || statusFilter
                 ? 'Ubah kata kunci atau filter.'
                 : 'Daftarkan router BRAS pertama sebagai klien RADIUS.'
             }
            icon={<IconGauge size={32} />}
          />
        }
      />
    </div>
  )
}
