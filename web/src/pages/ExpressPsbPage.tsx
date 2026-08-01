import { useEffect, useMemo, useState } from 'react'
import { ApiError } from '../api/client'
import { api } from '../api/client'
import type { Area, PageResponse, Role, User } from '../api/types'
import { listNas, type NasView } from '../api/bng'
import { listPlans, SERVICE_TYPE_LABEL, type PlanView, type ServiceType } from '../api/catalog'
import { onboardPsb, type ExpressPsbResult } from '../api/onboarding'
import { useCan } from '../auth/useCan'
import { Badge, EmptyState, useToast } from '../components/ui'
import { LocationPicker } from '../components/LocationPicker'
import { IconPackage, IconPlus } from '../components/icons'

/** Alfabet secret (tanpa 0/O/1/l/I) — cermin konvensi generator server agar mudah dibaca operator. */
const SECRET_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'

/** Password acak 12 karakter untuk PPPoE/Hotspot; operator boleh mengubahnya. */
function randomSecret(len = 12): string {
  const bytes = new Uint32Array(len)
  crypto.getRandomValues(bytes)
  let out = ''
  for (let i = 0; i < len; i++) out += SECRET_ALPHABET[bytes[i] % SECRET_ALPHABET.length]
  return out
}

const EMPTY = {
  code: '',
  name: '',
  phone: '',
  email: '',
  address: '',
  areaId: '',
  longitude: '',
  latitude: '',
  monthlyFeeOverride: '',
  username: '',
  title: '',
  description: '',
  scheduledAt: '',
  assignedTo: '',
  framedIp: '',
}

type Draft = typeof EMPTY

const toInstant = (local: string): string | null => (local ? new Date(local).toISOString() : null)

/**
 * PSB Ekspres — onboarding pelanggan baru dalam satu formulir: data pelanggan + paket +
 * akun jaringan + jadwal pemasangan sekaligus. Server merangkainya dalam SATU transaksi
 * (all-or-nothing) lalu membuka Work Order PSB sebagai tulang punggung akuntabilitas.
 * Langganan & akun lahir PENDING — pelanggan baru benar-benar online saat teknisi
 * menuntaskan WO PSB (yang mengaktifkan langganan lalu memprovisi akun ke RADIUS).
 *
 * Alur cepat untuk ISP menengah ke atas: alih-alih membuat pelanggan → langganan → akun →
 * WO di empat layar terpisah, semuanya di sini. Kredensial di-generate otomatis (operator
 * bisa menimpanya); password tak pernah dibalikkan server, jadi disalin dari sini.
 */
export function ExpressPsbPage() {
  const { can } = useCan()
  const toast = useToast()

  const [plans, setPlans] = useState<PlanView[]>([])
  const [nasList, setNasList] = useState<NasView[]>([])
  const [areas, setAreas] = useState<Area[]>([])
  const [technicians, setTechnicians] = useState<User[]>([])
  const [loading, setLoading] = useState(true)

  const [draft, setDraft] = useState<Draft>({ ...EMPTY })
  // Password dipisah dari draft agar tombol Generate/Lihat sederhana.
  const [secret, setSecret] = useState(randomSecret())
  const [showSecret, setShowSecret] = useState(false)
  const [planId, setPlanId] = useState('')
  const [nasId, setNasId] = useState('')
  const [authType, setAuthType] = useState<ServiceType>('PPPOE')
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState<(ExpressPsbResult & { secretUsed: string }) | null>(null)

  const canManage = can('customer.customer.create')

  useEffect(() => {
    void Promise.all([
      listPlans().catch(() => [] as PlanView[]),
      listNas().catch(() => [] as NasView[]),
    ])
      .then(([p, n]) => {
        const active = p.filter((x) => x.active)
        setPlans(active)
        setNasList(n.filter((x) => x.enabled))
        if (active.length > 0) {
          setPlanId(active[0].id)
          setAuthType(active[0].serviceTypes[0] ?? 'PPPOE')
        }
      })
      .finally(() => setLoading(false))

    // Pemilih teknisi best-effort (disaring role "Teknisi"); form tetap jalan tanpa izin lihat user.
    void Promise.all([
      api.get<PageResponse<User>>('/api/users?size=200').catch(() => ({ content: [] as User[] }) as PageResponse<User>),
      api.get<Role[]>('/api/roles').catch(() => [] as Role[]),
    ]).then(([users, roles]) => {
      const active = users.content.filter((u) => u.status === 'ACTIVE')
      const techRole = roles.find((r) => r.name === 'Teknisi')
      setTechnicians(techRole ? active.filter((u) => u.roleIds.includes(techRole.id)) : active)
    })

    // Area best-effort — dipakai memilih area pelanggan sekaligus auto-pilih BRAS dari cakupannya.
    void api
      .get<Area[]>('/api/areas')
      .then(setAreas)
      .catch(() => setAreas([]))
  }, [])

  // Peta area → BRAS (dari cakupan tiap BRAS). Dasar auto-pilih BRAS saat area dipilih.
  const nasByArea = useMemo(() => {
    const map = new Map<string, string>()
    nasList.forEach((n) => n.areaIds.forEach((areaId) => map.set(areaId, n.id)))
    return map
  }, [nasList])

  const selectedPlan = useMemo(() => plans.find((p) => p.id === planId), [plans, planId])
  const availableTypes: ServiceType[] = selectedPlan?.serviceTypes ?? []
  const macBased = authType === 'DHCP' || authType === 'STATIC'

  const set = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }))

  const changePlan = (id: string) => {
    setPlanId(id)
    const p = plans.find((x) => x.id === id)
    if (p && !p.serviceTypes.includes(authType)) setAuthType(p.serviceTypes[0] ?? 'PPPOE')
  }

  // Pilih area → auto-isi BRAS dari cakupan area itu (operator tetap boleh menimpanya di bawah).
  const changeArea = (areaId: string) => {
    set({ areaId })
    const auto = nasByArea.get(areaId)
    if (auto) setNasId(auto)
  }

  const invalid =
    !draft.name.trim() ||
    !draft.address.trim() ||
    !draft.longitude.trim() ||
    !draft.latitude.trim() ||
    Number.isNaN(Number(draft.longitude)) ||
    Number.isNaN(Number(draft.latitude)) ||
    !planId ||
    (macBased && !draft.username.trim()) ||
    (authType === 'STATIC' && !draft.framedIp.trim()) ||
    (!macBased && !secret.trim())

  const submit = async () => {
    if (invalid) {
      toast.error('Lengkapi dulu kolom wajib (nama, alamat, koordinat, paket, kredensial).')
      return
    }
    setSaving(true)
    try {
      const res = await onboardPsb({
        code: draft.code.trim() || undefined,
        name: draft.name.trim(),
        phone: draft.phone.trim() || null,
        email: draft.email.trim() || null,
        address: draft.address.trim(),
        areaId: draft.areaId || null,
        location: { longitude: Number(draft.longitude), latitude: Number(draft.latitude) },
        planId,
        monthlyFeeOverride: draft.monthlyFeeOverride.trim() ? Number(draft.monthlyFeeOverride) : null,
        // MAC-based → username = MAC, tanpa secret; PPPoE/Hotspot → username opsional (server generate) + secret.
        username: macBased ? draft.username.trim() : draft.username.trim() || null,
        secret: macBased ? null : secret,
        serviceType: authType,
        nasId: nasId || null,
        framedIp: macBased ? draft.framedIp.trim() || null : null,
        title: draft.title.trim() || null,
        description: draft.description.trim() || null,
        scheduledAt: toInstant(draft.scheduledAt),
        assignedTo: draft.assignedTo || null,
      })
      setResult({ ...res, secretUsed: macBased ? res.username : secret })
      toast.success(`PSB ${res.workOrderCode} dibuat untuk ${draft.name.trim()}`)
      // Reset untuk entri berikutnya; paket/BRAS/teknisi dipertahankan agar batch cepat.
      setDraft({ ...EMPTY } as Draft)
      setSecret(randomSecret())
      setShowSecret(false)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal membuat PSB ekspres')
    } finally {
      setSaving(false)
    }
  }

  if (!canManage) {
    return (
      <div className="card">
        <EmptyState title="Tak berizin" hint="Butuh izin membuat pelanggan untuk PSB ekspres." icon={<IconPlus size={32} />} />
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div>
        <h1 className="page-title">PSB Ekspres</h1>
        <p className="page-sub">
          Onboarding pelanggan baru sekali jalan: data pelanggan + paket + akun jaringan + jadwal pemasangan.
          Semua dibuat dalam satu transaksi; layanan aktif saat Work Order PSB dituntaskan teknisi.
        </p>
      </div>

      {result && <ResultCard result={result} onDismiss={() => setResult(null)} />}

      {loading ? (
        <div className="card">Memuat paket…</div>
      ) : plans.length === 0 ? (
        <div className="card">
          <EmptyState
            title="Belum ada paket aktif"
            hint="Buat paket dulu di menu Paket Internet sebelum melakukan PSB ekspres."
            icon={<IconPackage size={32} />}
          />
        </div>
      ) : (
        <div className="card stack" style={{ gap: '1rem' }}>
          {/* Pelanggan */}
          <section className="stack" style={{ gap: '0.5rem' }}>
            <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Pelanggan</h3>
            <div className="row wrap" style={{ gap: '0.6rem' }}>
              <label style={{ flex: 1, minWidth: 140 }}>
                <span>Kode</span>
                <input value={draft.code} onChange={(e) => set({ code: e.target.value })} placeholder="Otomatis: CUST-000001" />
              </label>
              <label style={{ flex: 2, minWidth: 180 }}>
                <span>Nama *</span>
                <input value={draft.name} onChange={(e) => set({ name: e.target.value })} placeholder="Budi Santoso" />
              </label>
              <label style={{ flex: 1, minWidth: 140 }}>
                <span>Telepon</span>
                <input value={draft.phone} onChange={(e) => set({ phone: e.target.value })} placeholder="08123456789" />
              </label>
              <label style={{ flex: 1, minWidth: 140 }}>
                <span>Email</span>
                <input value={draft.email} onChange={(e) => set({ email: e.target.value })} placeholder="budi@email.com" />
              </label>
            </div>
            <label>
              <span>Alamat *</span>
              <input value={draft.address} onChange={(e) => set({ address: e.target.value })} placeholder="Jl. Merdeka No. 10" />
            </label>
            <label>
              <span>Lokasi *</span>
              <LocationPicker
                longitude={draft.longitude}
                latitude={draft.latitude}
                onChange={(longitude, latitude) => set({ longitude, latitude })}
                onAddress={(address) => set(draft.address.trim() ? {} : { address })}
              />
            </label>
            {areas.length > 0 && (
              <div className="row wrap" style={{ gap: '0.6rem' }}>
                <label style={{ flex: 1, minWidth: 200 }}>
                  <span>Area {nasByArea.has(draft.areaId) && <span className="muted">· BRAS otomatis terpilih</span>}</span>
                  <select value={draft.areaId} onChange={(e) => changeArea(e.target.value)}>
                    <option value="">— pilih area —</option>
                    {areas.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.name}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
            )}
          </section>

          {/* Paket & akun jaringan */}
          <section className="stack" style={{ gap: '0.5rem' }}>
            <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Paket &amp; akun jaringan</h3>
            <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
              <label style={{ flex: 2, minWidth: 180 }}>
                <span>Paket *</span>
                <select value={planId} onChange={(e) => changePlan(e.target.value)}>
                  {plans.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name} ({p.downMbps}/{p.upMbps} Mbps)
                    </option>
                  ))}
                </select>
              </label>
              <label style={{ flex: 1, minWidth: 140 }}>
                <span>Tipe layanan</span>
                <select value={authType} onChange={(e) => setAuthType(e.target.value as ServiceType)} disabled={availableTypes.length <= 1}>
                  {availableTypes.map((t) => (
                    <option key={t} value={t}>
                      {SERVICE_TYPE_LABEL[t]}
                    </option>
                  ))}
                </select>
              </label>
              <label style={{ flex: 1, minWidth: 150 }}>
                <span>Harga khusus (opsional)</span>
                <input
                  type="number"
                  min={0}
                  value={draft.monthlyFeeOverride}
                  onChange={(e) => set({ monthlyFeeOverride: e.target.value })}
                  placeholder="ikuti harga paket"
                />
              </label>
            </div>

            {macBased ? (
              <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
                <label style={{ flex: 2, minWidth: 180 }}>
                  <span>MAC Address *</span>
                  <input value={draft.username} onChange={(e) => set({ username: e.target.value })} placeholder="AA:BB:CC:DD:EE:FF" />
                </label>
                <label style={{ flex: 2, minWidth: 160 }}>
                  <span>Reserved IP{authType === 'STATIC' ? ' *' : ' (opsional)'}</span>
                  <input value={draft.framedIp} onChange={(e) => set({ framedIp: e.target.value })} placeholder="100.64.0.10" />
                </label>
              </div>
            ) : (
              <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
                <label style={{ flex: 2, minWidth: 160 }}>
                  <span>Username (opsional)</span>
                  <input value={draft.username} onChange={(e) => set({ username: e.target.value })} placeholder="otomatis dari kode pelanggan" />
                </label>
                <label style={{ flex: 2, minWidth: 160 }}>
                  <span>Password *</span>
                  <input type={showSecret ? 'text' : 'password'} value={secret} onChange={(e) => setSecret(e.target.value)} />
                </label>
                <button type="button" onClick={() => setShowSecret((v) => !v)}>{showSecret ? 'Sembunyikan' : 'Lihat'}</button>
                <button type="button" onClick={() => { setSecret(randomSecret()); setShowSecret(true) }}>Generate</button>
              </div>
            )}

            <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
              <label style={{ flex: 1, minWidth: 160 }}>
                <span>BRAS</span>
                <select value={nasId} onChange={(e) => setNasId(e.target.value)}>
                  <option value="">— tanpa BRAS —</option>
                  {nasList.map((n) => (
                    <option key={n.id} value={n.id}>
                      {n.name}
                    </option>
                  ))}
                </select>
              </label>
              {draft.areaId !== '' && !nasByArea.has(draft.areaId) && (
                <span className="muted" style={{ fontSize: '0.8rem', alignSelf: 'center' }}>
                  Area ini belum dipetakan ke BRAS — pilih manual bila perlu.
                </span>
              )}
            </div>
            <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
              {macBased
                ? 'DHCP/Static memakai MAC sebagai identitas (konvensi use-radius). Static butuh IP yang direservasi.'
                : 'Password tak pernah dibalikkan server — salin dari sini. Username kosong = di-generate dari kode pelanggan.'}
              {' '}Akun lahir PENDING dan belum ditulis ke RADIUS sampai WO PSB selesai.
            </p>
          </section>

          {/* Work order pemasangan */}
          <section className="stack" style={{ gap: '0.5rem' }}>
            <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Pemasangan (Work Order PSB)</h3>
            <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
              <label style={{ flex: 2, minWidth: 200 }}>
                <span>Judul WO (opsional)</span>
                <input value={draft.title} onChange={(e) => set({ title: e.target.value })} placeholder={`PSB ${draft.name.trim() || 'pelanggan'}`} />
              </label>
              <label style={{ flex: 1, minWidth: 180 }}>
                <span>Teknisi (opsional)</span>
                <select value={draft.assignedTo} onChange={(e) => set({ assignedTo: e.target.value })}>
                  <option value="">— belum ditugaskan —</option>
                  {technicians.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name}
                    </option>
                  ))}
                </select>
              </label>
              <label style={{ flex: 1, minWidth: 180 }}>
                <span>Jadwal (opsional)</span>
                <input type="datetime-local" value={draft.scheduledAt} onChange={(e) => set({ scheduledAt: e.target.value })} />
              </label>
            </div>
            <label className="stack" style={{ gap: '0.25rem' }}>
              <span>Catatan pemasangan (opsional)</span>
              <textarea rows={2} maxLength={2000} value={draft.description} onChange={(e) => set({ description: e.target.value })} />
            </label>
          </section>

          <div className="row" style={{ gap: '0.5rem' }}>
            <button className="primary" onClick={() => void submit()} disabled={saving || invalid}>
              <IconPlus size={15} /> {saving ? 'Memproses…' : 'Buat PSB'}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

/** Ringkasan hasil onboarding — kode WO + kredensial untuk disalin (password hanya tampil sekali). */
function ResultCard({ result, onDismiss }: { result: ExpressPsbResult & { secretUsed: string }; onDismiss: () => void }) {
  return (
    <div className="card stack" style={{ gap: '0.6rem', borderLeft: '3px solid var(--good, #34c759)' }}>
      <div className="spread" style={{ alignItems: 'center' }}>
        <h3 style={{ margin: 0, fontSize: '0.95rem' }}>
          <Badge tone="good">PSB dibuat</Badge> {result.workOrderCode}
        </h3>
        <button className="ghost" onClick={onDismiss}>Tutup</button>
      </div>
      <dl className="kv" style={{ margin: 0, display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '0.3rem 0.8rem', fontSize: '0.85rem' }}>
        <dt className="muted">Username</dt>
        <dd style={{ margin: 0 }}><code>{result.username}</code></dd>
        <dt className="muted">Password</dt>
        <dd style={{ margin: 0 }}><code>{result.secretUsed}</code> <span className="muted">(catat sekarang — tak bisa dilihat lagi)</span></dd>
        <dt className="muted">Work Order</dt>
        <dd style={{ margin: 0 }}>{result.workOrderCode} · menunggu instalasi</dd>
      </dl>
      <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
        Langganan &amp; akun berstatus PENDING. Pelanggan online setelah teknisi menuntaskan WO PSB.
      </p>
    </div>
  )
}
