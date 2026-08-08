import { useEffect, useMemo, useState } from 'react'
import { ApiError } from '../api/client'
import { api } from '../api/client'
import type { Area } from '../api/types'
import { listNas, previewPppSecrets, type NasView } from '../api/bng'
import { listPlans, type PlanView } from '../api/catalog'
import {
  importPppoe,
  type ImportPppoeResult,
  type ImportRowStatus,
  type ImportSource,
} from '../api/onboarding'
import { useCan } from '../auth/useCan'
import { Badge, Button, EmptyState, SelectField, TextField, TextareaField } from '@/components/atoms'
import { Checkbox } from '@fluentui/react-components'
import { useToast } from '@/system'
import { PageHeader } from '@/components/molecules'
import { IconInbox, IconPackage } from '@/components/atoms/icons'

/** Baris PPPoE untuk pratinjau/impor. [password] hanya ada pada sumber INLINE (paste/upload). */
type PreviewRow = {
  name: string
  password: string | null
  profile: string | null
  comment: string | null
  disabled: boolean
}

/** Kunci peta profil untuk baris tanpa profil — jatuh ke paket default. */
const NO_PROFILE = ''

/**
 * Parse hasil export RouterOS (`/ppp/secret export`): baris `add name="..." password=... profile=...
 * comment="..." disabled=yes`. Token `key=value` atau `key="value"`. Baris tanpa `name=` dilewati.
 */
function parseRouterOsExport(text: string): PreviewRow[] {
  const rows: PreviewRow[] = []
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#') || !/(^|\s)name=/.test(line)) continue
    const attrs: Record<string, string> = {}
    const re = /([A-Za-z0-9_-]+)=("([^"]*)"|[^\s]+)/g
    let m: RegExpExecArray | null
    while ((m = re.exec(line)) !== null) attrs[m[1]] = m[3] !== undefined ? m[3] : m[2]
    if (!attrs.name) continue
    rows.push({
      name: attrs.name,
      password: attrs.password ?? null,
      profile: attrs.profile ?? null,
      comment: attrs.comment ?? null,
      disabled: attrs.disabled === 'yes' || attrs.disabled === 'true',
    })
  }
  return rows
}

/** Fallback CSV tanpa header: `name,password,profile,comment`. Dipakai bila bukan export RouterOS. */
function parseCsv(text: string): PreviewRow[] {
  const rows: PreviewRow[] = []
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const cols = line.split(',').map((c) => c.trim())
    if (cols.length < 1 || !cols[0] || cols[0].toLowerCase() === 'name') continue
    rows.push({
      name: cols[0],
      password: cols[1] || null,
      profile: cols[2] || null,
      comment: cols[3] || null,
      disabled: false,
    })
  }
  return rows
}

/** Dedup berdasarkan nama (RouterOS unik, tapi paste bisa dobel) — pertahankan yang pertama. */
function dedupByName(rows: PreviewRow[]): PreviewRow[] {
  const seen = new Set<string>()
  return rows.filter((r) => (seen.has(r.name) ? false : (seen.add(r.name), true)))
}

function parsePaste(text: string): PreviewRow[] {
  const ros = parseRouterOsExport(text)
  return dedupByName(ros.length > 0 ? ros : parseCsv(text))
}

/**
 * Impor PPPoE massal — migrasi akun `/ppp/secret` sebuah RouterOS menjadi pelanggan + langganan +
 * akun jaringan yang langsung AKTIF dan terprovisi ke RADIUS pusat. Untuk operator yang memindah
 * pelanggan existing dari router lama ke platform (tanpa Work Order — mereka sudah terpasang).
 *
 * Dua sumber: (1) tarik langsung dari BRAS (server yang menyentuh router; password tak lewat
 * browser), atau (2) tempel/upload hasil `/ppp/secret export`. Operator memilih baris, memetakan
 * profil RouterOS → paket, mengisi placeholder alamat/lokasi (data yang tak ada di `/ppp/secret`),
 * lalu commit. Hasilnya per-baris (dibuat/dilewati/gagal).
 */
export function ImportPppoePage() {
  const { can } = useCan()
  const toast = useToast()

  const [plans, setPlans] = useState<PlanView[]>([])
  const [nasList, setNasList] = useState<NasView[]>([])
  const [areas, setAreas] = useState<Area[]>([])
  const [loading, setLoading] = useState(true)

  const [nasId, setNasId] = useState('')
  const [source, setSource] = useState<ImportSource>('NAS')
  const [paste, setPaste] = useState('')
  const [rows, setRows] = useState<PreviewRow[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [profilePlan, setProfilePlan] = useState<Record<string, string>>({})
  const [defaultPlanId, setDefaultPlanId] = useState('')

  const [areaId, setAreaId] = useState('')
  const [defaultAddress, setDefaultAddress] = useState('')
  const [lat, setLat] = useState('')
  const [lng, setLng] = useState('')

  const [fetching, setFetching] = useState(false)
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState<ImportPppoeResult | null>(null)

  const canImport = can('customer.customer.create') && can('bng.nas.manage')

  useEffect(() => {
    void Promise.all([listPlans().catch(() => [] as PlanView[]), listNas().catch(() => [] as NasView[])])
      .then(([p, n]) => {
        setPlans(p.filter((x) => x.active))
        const enabled = n.filter((x) => x.enabled)
        setNasList(enabled)
        if (enabled.length > 0) setNasId(enabled[0].id)
      })
      .finally(() => setLoading(false))
    void api.get<Area[]>('/api/areas').then(setAreas).catch(() => setAreas([]))
  }, [])

  // Reset pratinjau saat BRAS/sumber berganti — data lama tak lagi relevan.
  const resetPreview = () => {
    setRows([])
    setSelected(new Set())
    setProfilePlan({})
    setResult(null)
  }

  const applyRows = (parsed: PreviewRow[]) => {
    setRows(parsed)
    // Default terpilih: semua kecuali yang disabled di router.
    setSelected(new Set(parsed.filter((r) => !r.disabled).map((r) => r.name)))
    setResult(null)
  }

  const loadFromNas = async () => {
    if (!nasId) return
    setFetching(true)
    try {
      const preview = await previewPppSecrets(nasId)
      applyRows(preview.map((p) => ({ ...p, password: null })))
      if (preview.length === 0) toast.info('Tak ada akun PPPoE di BRAS ini.')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menarik daftar dari BRAS')
    } finally {
      setFetching(false)
    }
  }

  const loadFromPaste = () => {
    const parsed = parsePaste(paste)
    if (parsed.length === 0) {
      toast.error('Tak ada baris terbaca. Tempel hasil `/ppp/secret export` atau CSV name,password,profile,comment.')
      return
    }
    applyRows(parsed)
    toast.success(`${parsed.length} akun terbaca.`)
  }

  const distinctProfiles = useMemo(() => {
    const set = new Set<string>()
    rows.forEach((r) => set.add(r.profile ?? NO_PROFILE))
    return [...set].sort()
  }, [rows])

  const selectedRows = useMemo(() => rows.filter((r) => selected.has(r.name)), [rows, selected])

  const toggle = (name: string) =>
    setSelected((s) => {
      const next = new Set(s)
      if (next.has(name)) next.delete(name)
      else next.add(name)
      return next
    })

  const toggleAll = () =>
    setSelected((s) => (s.size === rows.length ? new Set() : new Set(rows.map((r) => r.name))))

  // Baris terpilih yang paketnya tak terselesaikan (profil tak dipetakan & tak ada default) → akan di-skip server.
  const unresolved = useMemo(
    () =>
      selectedRows.filter((r) => {
        const key = r.profile ?? NO_PROFILE
        return !profilePlan[key] && !defaultPlanId
      }).length,
    [selectedRows, profilePlan, defaultPlanId],
  )

  const submit = async () => {
    if (selectedRows.length === 0) {
      toast.error('Pilih minimal satu akun untuk diimpor.')
      return
    }
    const mapping: Record<string, string> = {}
    Object.entries(profilePlan).forEach(([k, v]) => {
      if (v) mapping[k] = v
    })
    setSaving(true)
    try {
      const res = await importPppoe({
        nasId,
        source,
        rows:
          source === 'INLINE'
            ? selectedRows.map((r) => ({
                name: r.name,
                password: r.password,
                profile: r.profile,
                comment: r.comment,
                disabled: r.disabled,
              }))
            : [],
        onlyNames: source === 'NAS' ? selectedRows.map((r) => r.name) : null,
        profilePlanId: mapping,
        defaultPlanId: defaultPlanId || null,
        skipDisabled: false,
        areaId: areaId || null,
        defaultAddress: defaultAddress.trim() || null,
        defaultLocation: lat.trim() && lng.trim() ? { longitude: Number(lng), latitude: Number(lat) } : null,
      })
      setResult(res)
      toast.success(`Impor selesai: ${res.created} dibuat, ${res.skipped} dilewati, ${res.failed} gagal.`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menjalankan impor')
    } finally {
      setSaving(false)
    }
  }

  if (!canImport) {
    return (
      <div className="card">
        <EmptyState
          title="Tak berizin"
          hint="Butuh izin membuat pelanggan dan mengelola BRAS untuk impor PPPoE."
          icon={<IconInbox size={32} />}
        />
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title="Impor PPPoE"
        subtitle={
          <>
            Migrasi akun PPPoE dari RouterOS ke sistem: tiap akun jadi pelanggan + langganan + akun jaringan
            yang langsung aktif dan diprovisi ke RADIUS pusat. Tanpa Work Order — pelanggan sudah terpasang.
          </>
        }
      />

      {loading ? (
        <div className="card">Memuat…</div>
      ) : plans.length === 0 ? (
        <div className="card">
          <EmptyState
            title="Belum ada paket aktif"
            hint="Buat paket dulu di menu Paket Internet sebelum mengimpor akun."
            icon={<IconPackage size={32} />}
          />
        </div>
      ) : (
        <>
          {/* 1. Sumber & BRAS tujuan */}
          <div className="card stack" style={{ gap: '0.8rem' }}>
            <h3 style={{ margin: 0, fontSize: '0.95rem' }}>1. Sumber &amp; BRAS tujuan</h3>
            <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
              <div style={{ flex: 1, minWidth: 200 }}>
                <SelectField
                  label="BRAS tujuan *"
                  value={nasId}
                  onChange={(_, data) => {
                    setNasId(data.value)
                    resetPreview()
                  }}
                >
                  {nasList.map((n) => (
                    <option key={n.id} value={n.id}>
                      {n.name}
                    </option>
                  ))}
                </SelectField>
              </div>
              <div style={{ flex: 1, minWidth: 200 }}>
                <SelectField
                  label="Sumber daftar"
                  value={source}
                  onChange={(_, data) => {
                    setSource(data.value as ImportSource)
                    resetPreview()
                  }}
                >
                  <option value="NAS">Tarik langsung dari BRAS (RouterOS REST)</option>
                  <option value="INLINE">Tempel / upload hasil export</option>
                </SelectField>
              </div>
            </div>

            {source === 'NAS' ? (
              <div className="row" style={{ gap: '0.5rem' }}>
                <Button onClick={() => void loadFromNas()} disabled={fetching || !nasId}>
                  {fetching ? 'Menarik…' : 'Ambil daftar dari BRAS'}
                </Button>
                <span className="muted" style={{ fontSize: '0.8rem', alignSelf: 'center' }}>
                  Hanya MikroTik (RouterOS) dengan alamat &amp; kredensial kontrol REST terisi. Password
                  ditarik server saat commit — tak melewati browser.
                </span>
              </div>
            ) : (
              <div className="stack" style={{ gap: '0.4rem' }}>
                <TextareaField
                  rows={6}
                  value={paste}
                  onChange={(_, data) => setPaste(data.value)}
                  placeholder={
                    '/ppp secret\nadd name="budi" password="rahasia" profile="vip" comment="Budi"\n…\n\n' +
                    'atau CSV: name,password,profile,comment'
                  }
                  style={{ fontFamily: 'monospace', fontSize: '0.8rem' }}
                />
                <div className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
                  <input
                    id="pppoe-upload"
                    type="file"
                    accept=".rsc,.txt,.csv"
                    style={{ display: 'none' }}
                    onChange={(e) => {
                      const f = e.target.files?.[0]
                      if (!f) return
                      void f.text().then((t) => setPaste(t))
                    }}
                  />
                  <label htmlFor="pppoe-upload" className="ghost" style={{ cursor: 'pointer', padding: '0.4rem 0.7rem', borderRadius: 6, border: '1px solid var(--line, #ccc)', fontSize: '0.85rem' }}>
                    Upload file
                  </label>
                  <Button onClick={loadFromPaste} disabled={!paste.trim()}>
                    Baca daftar
                  </Button>
                </div>
              </div>
            )}
          </div>

          {/* 2. Pilih akun */}
          {rows.length > 0 && (
            <div className="card stack" style={{ gap: '0.6rem' }}>
              <div className="spread" style={{ alignItems: 'center' }}>
                <h3 style={{ margin: 0, fontSize: '0.95rem' }}>
                  2. Pilih akun <span className="muted">({selected.size}/{rows.length} terpilih)</span>
                </h3>
                <Button variant="subtle" onClick={toggleAll}>
                  {selected.size === rows.length ? 'Kosongkan' : 'Pilih semua'}
                </Button>
              </div>
              <div style={{ maxHeight: 320, overflow: 'auto' }}>
                <table className="table" style={{ fontSize: '0.85rem' }}>
                  <thead>
                    <tr>
                      <th style={{ width: 32 }}></th>
                      <th>Username</th>
                      <th>Profil</th>
                      <th>Komentar</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((r) => (
                      <tr key={r.name}>
                        <td>
                          <Checkbox checked={selected.has(r.name)} onChange={() => toggle(r.name)} />
                        </td>
                        <td>
                          <code>{r.name}</code>
                        </td>
                        <td>{r.profile ?? <span className="muted">—</span>}</td>
                        <td>{r.comment ?? <span className="muted">—</span>}</td>
                        <td>{r.disabled ? <Badge tone="neutral">disabled</Badge> : <Badge tone="good">aktif</Badge>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {/* 3. Peta profil → paket */}
          {rows.length > 0 && (
            <div className="card stack" style={{ gap: '0.6rem' }}>
              <h3 style={{ margin: 0, fontSize: '0.95rem' }}>3. Peta profil RouterOS → paket</h3>
              <div className="stack" style={{ gap: '0.4rem' }}>
                {distinctProfiles.map((prof) => (
                  <div key={prof} className="row wrap" style={{ gap: '0.6rem', alignItems: 'center' }}>
                    <span style={{ minWidth: 160 }}>
                      {prof === NO_PROFILE ? <span className="muted">(tanpa profil)</span> : <code>{prof}</code>}
                    </span>
                    <SelectField
                      style={{ minWidth: 220 }}
                      value={profilePlan[prof] ?? ''}
                      onChange={(_, data) => setProfilePlan((m) => ({ ...m, [prof]: data.value }))}
                    >
                      <option value="">— pakai paket default —</option>
                      {plans.map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.name} ({p.downMbps}/{p.upMbps} Mbps)
                        </option>
                      ))}
                    </SelectField>
                  </div>
                ))}
                <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'center' }}>
                  <span style={{ minWidth: 160 }}>Paket default</span>
                  <SelectField style={{ minWidth: 220 }} value={defaultPlanId} onChange={(_, data) => setDefaultPlanId(data.value)}>
                    <option value="">— tak ada (baris tak terpetakan dilewati) —</option>
                    {plans.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.name} ({p.downMbps}/{p.upMbps} Mbps)
                      </option>
                    ))}
                  </SelectField>
                </div>
              </div>
              {unresolved > 0 && (
                <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
                  {unresolved} akun terpilih belum ketemu paketnya — akan dilewati. Petakan profilnya atau pilih paket default.
                </p>
              )}
            </div>
          )}

          {/* 4. Placeholder data pelanggan */}
          {rows.length > 0 && (
            <div className="card stack" style={{ gap: '0.6rem' }}>
              <h3 style={{ margin: 0, fontSize: '0.95rem' }}>4. Data pelanggan (placeholder)</h3>
              <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
                `/ppp/secret` tak memuat alamat/koordinat. Isi placeholder untuk seluruh batch; lengkapi
                alamat &amp; lokasi asli tiap pelanggan belakangan.
              </p>
              <div className="row wrap" style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
                {areas.length > 0 && (
                  <div style={{ flex: 1, minWidth: 180 }}>
                    <SelectField label="Area (opsional)" value={areaId} onChange={(_, data) => setAreaId(data.value)}>
                      <option value="">— tanpa area —</option>
                      {areas.map((a) => (
                        <option key={a.id} value={a.id}>
                          {a.name}
                        </option>
                      ))}
                    </SelectField>
                  </div>
                )}
                <div style={{ flex: 2, minWidth: 200 }}>
                  <TextField
                    label="Alamat placeholder"
                    value={defaultAddress}
                    onChange={(_, data) => setDefaultAddress(data.value)}
                    placeholder="(impor PPPoE — lengkapi alamat)"
                  />
                </div>
                <div style={{ flex: 1, minWidth: 120 }}>
                  <TextField label="Longitude" value={lng} onChange={(_, data) => setLng(data.value)} placeholder="106.8" />
                </div>
                <div style={{ flex: 1, minWidth: 120 }}>
                  <TextField label="Latitude" value={lat} onChange={(_, data) => setLat(data.value)} placeholder="-6.2" />
                </div>
              </div>
            </div>
          )}

          {/* 5. Commit */}
          {rows.length > 0 && (
            <div className="row" style={{ gap: '0.5rem' }}>
              <Button variant="primary" onClick={() => void submit()} disabled={saving || selectedRows.length === 0}>
                <IconInbox size={15} /> {saving ? 'Mengimpor…' : `Impor ${selectedRows.length} akun`}
              </Button>
              <span className="muted" style={{ fontSize: '0.8rem', alignSelf: 'center' }}>
                Tiap akun langsung aktif &amp; ditulis ke RADIUS. Aktivasi memprorata tagihan dari sekarang.
              </span>
            </div>
          )}

          {result && <ResultCard result={result} onDismiss={() => setResult(null)} />}
        </>
      )}
    </div>
  )
}

const STATUS_TONE: Record<ImportRowStatus, 'good' | 'neutral' | 'critical'> = {
  CREATED: 'good',
  SKIPPED: 'neutral',
  FAILED: 'critical',
}

/** Rekap hasil impor + rincian per-baris (username, status, pesan). */
function ResultCard({ result, onDismiss }: { result: ImportPppoeResult; onDismiss: () => void }) {
  return (
    <div className="card stack" style={{ gap: '0.6rem', borderLeft: '3px solid var(--good, #34c759)' }}>
      <div className="spread" style={{ alignItems: 'center' }}>
        <h3 style={{ margin: 0, fontSize: '0.95rem' }}>
          Hasil impor — <Badge tone="good">{result.created} dibuat</Badge>{' '}
          <Badge tone="neutral">{result.skipped} dilewati</Badge>{' '}
          {result.failed > 0 && <Badge tone="critical">{result.failed} gagal</Badge>}
        </h3>
        <Button variant="subtle" onClick={onDismiss}>
          Tutup
        </Button>
      </div>
      <div style={{ maxHeight: 320, overflow: 'auto' }}>
        <table className="table" style={{ fontSize: '0.85rem' }}>
          <thead>
            <tr>
              <th>Username</th>
              <th>Status</th>
              <th>Keterangan</th>
            </tr>
          </thead>
          <tbody>
            {result.rows.map((r) => (
              <tr key={r.username}>
                <td>
                  <code>{r.username}</code>
                </td>
                <td>
                  <Badge tone={STATUS_TONE[r.status]}>{r.status}</Badge>
                </td>
                <td>{r.message ?? <span className="muted">—</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
