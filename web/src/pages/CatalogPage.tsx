import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import {
  buildFupRateLimit,
  buildRateLimit,
  createPlan,
  DEFAULT_PRIORITY,
  listPlans,
  SERVICE_TYPES,
  SERVICE_TYPE_LABEL,
  updatePlan,
  type PlanView,
  type SavePlanRequest,
  type ServiceType,
} from '../api/catalog'
import { Pencil, Plus, RefreshCw } from 'lucide-react'
import { useCan } from '../auth/useCan'
import { DataTable, type Column, type RowAction } from '../components/DataTable'
import { CommandBar, type CommandAction } from '../components/CommandBar'
import { Blade } from '../components/Blade'
import { PageHeader } from '../components/PageHeader'
import { Badge, EmptyState, SearchInput, StatusBadge, Toolbar, useToast } from '../components/ui'
import { IconPackage } from '../components/icons'

/**
 * Paket internet — SUMBER TUNGGAL harga + kecepatan + QoS + FUP + siklus billing.
 *
 * Form ini mengganti pengetikan manual "nama profil RADIUS": operator mengisi
 * field terstruktur (rate, burst, limit-at, prioritas, FUP) dan melihat PREVIEW
 * string Mikrotik-Rate-Limit yang persis akan ditulis ke RADIUS. Paket tak dihapus
 * keras (jaga integritas snapshot invoice & grup RADIUS) — dinonaktifkan lewat
 * toggle "Status aktif".
 */

type TriState = '' | 'true' | 'false'

type Draft = {
  id: string | null
  name: string
  description: string
  price: string
  downMbps: string
  upMbps: string
  priority: string
  connectionLimit: string
  downBurstMbps: string
  upBurstMbps: string
  downThresholdMbps: string
  upThresholdMbps: string
  burstTimeSec: string
  downMinMbps: string
  upMinMbps: string
  fupEnabled: boolean
  fupQuotaMb: string
  fupDownMbps: string
  fupUpMbps: string
  serviceTypes: ServiceType[]
  prorateOnActivation: TriState
  billingDayOfMonth: string
  dueDays: string
  graceDays: string
  autoIsolir: TriState
  active: boolean
}

const EMPTY_DRAFT: Draft = {
  id: null,
  name: '',
  description: '',
  price: '',
  downMbps: '',
  upMbps: '',
  priority: String(DEFAULT_PRIORITY),
  connectionLimit: '',
  downBurstMbps: '',
  upBurstMbps: '',
  downThresholdMbps: '',
  upThresholdMbps: '',
  burstTimeSec: '',
  downMinMbps: '',
  upMinMbps: '',
  fupEnabled: false,
  fupQuotaMb: '',
  fupDownMbps: '',
  fupUpMbps: '',
  serviceTypes: ['PPPOE'],
  prorateOnActivation: '',
  billingDayOfMonth: '',
  dueDays: '',
  graceDays: '',
  autoIsolir: '',
  active: true,
}

/** String angka → number (undefined bila kosong/bukan angka) untuk preview. */
const num = (s: string): number | undefined => {
  const t = s.trim()
  if (!t) return undefined
  const v = Number(t)
  return Number.isFinite(v) ? v : undefined
}

/** String angka → number | null untuk body request (field opsional server). */
const optNum = (s: string): number | null => {
  const v = num(s)
  return v ?? null
}

/** Select tri-state → boolean | null ('' = ikut kebijakan global). */
const tri = (s: TriState): boolean | null => (s === '' ? null : s === 'true')

const fmtRupiah = (n: number): string => `Rp ${n.toLocaleString('id-ID')}`

export function CatalogPage() {
  const { can } = useCan()
  const toast = useToast()
  const canManage = can('catalog.plan.manage')

  const [items, setItems] = useState<PlanView[]>([])
  const [loading, setLoading] = useState(true)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [initialDraft, setInitialDraft] = useState<Draft | null>(null)
  const [saving, setSaving] = useState(false)
  const [query, setQuery] = useState('')
  const [serviceFilter, setServiceFilter] = useState<ServiceType | ''>('')

  // Buka/tutup Blade + snapshot untuk deteksi "dirty" (ada perubahan belum disimpan).
  const openDraft = (d: Draft) => {
    setDraft(d)
    setInitialDraft(d)
  }
  const closeDraft = () => {
    setDraft(null)
    setInitialDraft(null)
  }
  const dirty = draft != null && JSON.stringify(draft) !== JSON.stringify(initialDraft)

  const reload = useCallback(async () => {
    try {
      setItems(await listPlans())
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat paket')
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    void reload()
  }, [reload])

  const edit = (plan: PlanView) =>
    openDraft({
      id: plan.id,
      name: plan.name,
      description: plan.description ?? '',
      price: String(plan.price),
      downMbps: String(plan.downMbps),
      upMbps: String(plan.upMbps),
      priority: String(plan.priority),
      connectionLimit: plan.connectionLimit != null ? String(plan.connectionLimit) : '',
      downBurstMbps: plan.downBurstMbps != null ? String(plan.downBurstMbps) : '',
      upBurstMbps: plan.upBurstMbps != null ? String(plan.upBurstMbps) : '',
      downThresholdMbps: plan.downThresholdMbps != null ? String(plan.downThresholdMbps) : '',
      upThresholdMbps: plan.upThresholdMbps != null ? String(plan.upThresholdMbps) : '',
      burstTimeSec: plan.burstTimeSec != null ? String(plan.burstTimeSec) : '',
      downMinMbps: plan.downMinMbps != null ? String(plan.downMinMbps) : '',
      upMinMbps: plan.upMinMbps != null ? String(plan.upMinMbps) : '',
      fupEnabled: plan.fupEnabled,
      fupQuotaMb: plan.fupQuotaMb != null ? String(plan.fupQuotaMb) : '',
      fupDownMbps: plan.fupDownMbps != null ? String(plan.fupDownMbps) : '',
      fupUpMbps: plan.fupUpMbps != null ? String(plan.fupUpMbps) : '',
      serviceTypes: plan.serviceTypes.length ? plan.serviceTypes : ['PPPOE'],
      prorateOnActivation: plan.prorateOnActivation == null ? '' : String(plan.prorateOnActivation) as TriState,
      billingDayOfMonth: plan.billingDayOfMonth != null ? String(plan.billingDayOfMonth) : '',
      dueDays: plan.dueDays != null ? String(plan.dueDays) : '',
      graceDays: plan.graceDays != null ? String(plan.graceDays) : '',
      autoIsolir: plan.autoIsolir == null ? '' : String(plan.autoIsolir) as TriState,
      active: plan.active,
    })

  const toggleService = (svc: ServiceType) => {
    if (!draft) return
    const on = draft.serviceTypes.includes(svc)
    setDraft({
      ...draft,
      serviceTypes: on ? draft.serviceTypes.filter((s) => s !== svc) : [...draft.serviceTypes, svc],
    })
  }

  const save = async () => {
    if (!draft) return
    const body: SavePlanRequest = {
      name: draft.name.trim(),
      description: draft.description.trim() || null,
      price: Number(draft.price) || 0,
      downMbps: Number(draft.downMbps) || 0,
      upMbps: Number(draft.upMbps) || 0,
      downBurstMbps: optNum(draft.downBurstMbps),
      upBurstMbps: optNum(draft.upBurstMbps),
      downThresholdMbps: optNum(draft.downThresholdMbps),
      upThresholdMbps: optNum(draft.upThresholdMbps),
      burstTimeSec: optNum(draft.burstTimeSec),
      downMinMbps: optNum(draft.downMinMbps),
      upMinMbps: optNum(draft.upMinMbps),
      priority: Number(draft.priority) || DEFAULT_PRIORITY,
      connectionLimit: optNum(draft.connectionLimit),
      fupEnabled: draft.fupEnabled,
      fupQuotaMb: draft.fupEnabled ? optNum(draft.fupQuotaMb) : null,
      fupDownMbps: draft.fupEnabled ? optNum(draft.fupDownMbps) : null,
      fupUpMbps: draft.fupEnabled ? optNum(draft.fupUpMbps) : null,
      serviceTypes: draft.serviceTypes,
      prorateOnActivation: tri(draft.prorateOnActivation),
      billingDayOfMonth: optNum(draft.billingDayOfMonth),
      dueDays: optNum(draft.dueDays),
      graceDays: optNum(draft.graceDays),
      autoIsolir: tri(draft.autoIsolir),
      active: draft.active,
    }
    setSaving(true)
    try {
      await (draft.id ? updatePlan(draft.id, body) : createPlan(body))
      closeDraft()
      await reload()
      toast.success(draft.id ? 'Paket diperbarui' : 'Paket dibuat')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan paket')
    } finally {
      setSaving(false)
    }
  }

  const preview = draft
    ? buildRateLimit({
        downMbps: num(draft.downMbps),
        upMbps: num(draft.upMbps),
        downBurstMbps: num(draft.downBurstMbps),
        upBurstMbps: num(draft.upBurstMbps),
        downThresholdMbps: num(draft.downThresholdMbps),
        upThresholdMbps: num(draft.upThresholdMbps),
        burstTimeSec: num(draft.burstTimeSec),
        downMinMbps: num(draft.downMinMbps),
        upMinMbps: num(draft.upMinMbps),
        priority: num(draft.priority),
      })
    : ''
  const fupPreview = draft
    ? buildFupRateLimit({
        fupEnabled: draft.fupEnabled,
        fupDownMbps: num(draft.fupDownMbps),
        fupUpMbps: num(draft.fupUpMbps),
      })
    : ''

  // Saring paket di sisi klien: cocokkan nama + (opsional) tipe layanan.
  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter((p) => {
      if (q && !p.name.toLowerCase().includes(q)) return false
      if (serviceFilter && !p.serviceTypes.includes(serviceFilter)) return false
      return true
    })
  }, [items, query, serviceFilter])

  const columns: Column<PlanView>[] = [
    {
      key: 'name',
      header: 'Nama paket',
      sortValue: (p) => p.name,
      cell: (p) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <strong>{p.name}</strong>
          {p.description && (
            <span className="muted" style={{ fontSize: '0.8rem' }}>{p.description}</span>
          )}
        </div>
      ),
    },
    {
      key: 'price',
      header: 'Harga / bln',
      align: 'right',
      sortValue: (p) => p.price,
      cell: (p) => fmtRupiah(p.price),
    },
    {
      key: 'speed',
      header: 'Kecepatan',
      align: 'right',
      sortValue: (p) => p.downMbps,
      cell: (p) => `${p.downMbps} / ${p.upMbps} Mbps`,
    },
    {
      key: 'fup',
      header: 'FUP',
      sortValue: (p) => (p.fupEnabled ? 1 : 0),
      cell: (p) =>
        p.fupEnabled ? <Badge tone="accent">FUP</Badge> : <span className="muted">—</span>,
    },
    {
      key: 'services',
      header: 'Layanan',
      sortValue: (p) => p.serviceTypes.map((s) => SERVICE_TYPE_LABEL[s]).join(', '),
      cell: (p) => (
        <div className="row" style={{ gap: '0.3rem', flexWrap: 'wrap' }}>
          {p.serviceTypes.length === 0 ? (
            <span className="muted">—</span>
          ) : (
            p.serviceTypes.map((s) => <Badge key={s}>{SERVICE_TYPE_LABEL[s]}</Badge>)
          )}
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (p) => (p.active ? 1 : 0),
      cell: (p) =>
        p.active ? (
          <StatusBadge status="ACTIVE" label="Aktif" />
        ) : (
          <StatusBadge status="INACTIVE" label="Nonaktif" />
        ),
    },
  ]

  // Aksi per-baris di menu `…` — paket tak dihapus keras (dinonaktifkan via form), jadi hanya "Ubah".
  const rowActions = (p: PlanView): RowAction[] => [
    { key: 'edit', label: 'Ubah', icon: <Pencil size={16} />, onClick: () => edit(p) },
  ]

  const primary: CommandAction | undefined = canManage
    ? {
        key: 'create',
        label: 'Tambah paket',
        icon: <Plus size={16} />,
        onClick: () => openDraft({ ...EMPTY_DRAFT }),
        disabled: draft != null,
      }
    : undefined

  const actions: CommandAction[] = [
    { key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, onClick: () => void reload() },
  ]

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <PageHeader
        title="Paket Internet"
        subtitle="Sumber tunggal harga, kecepatan, QoS & FUP. Kecepatan dirakit jadi atribut RADIUS otomatis — tak perlu ketik profil manual."
      />

      <CommandBar primary={primary} actions={actions} />

      <Blade
        open={draft != null}
        title={draft?.id ? 'Edit paket' : 'Tambah paket'}
        subtitle="Harga, kecepatan, QoS & FUP — dirakit jadi atribut RADIUS otomatis."
        size="lg"
        dirty={dirty}
        onClose={closeDraft}
        footer={
          <>
            <button className="primary" onClick={() => void save()} disabled={saving}>
              {saving ? 'Menyimpan…' : 'Simpan'}
            </button>
            <button onClick={closeDraft} disabled={saving}>Batal</button>
          </>
        }
      >
        {draft && (
          <div className="stack">
            {/* Preview rate-limit — persis yang ditulis ke RADIUS */}
          <div
            className="stack"
            style={{
              gap: '0.35rem',
              padding: '0.75rem 1rem',
              borderRadius: 8,
              background: 'var(--surface-2, rgba(127,127,127,0.08))',
            }}
          >
            <span className="muted" style={{ fontSize: '0.75rem', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
              Preview atribut RADIUS (Mikrotik-Rate-Limit)
            </span>
            <code style={{ fontSize: '0.95rem', fontWeight: 600 }}>{preview || '— isi kecepatan dulu —'}</code>
            {fupPreview && (
              <code className="muted" style={{ fontSize: '0.85rem' }}>
                FUP → {fupPreview}
              </code>
            )}
          </div>

          {/* ---- Informasi Dasar ---- */}
          <SectionTitle>Informasi dasar</SectionTitle>
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
              <span>Harga / bulan (Rp)</span>
              <input
                value={draft.price}
                onChange={(e) => setDraft({ ...draft, price: e.target.value })}
                placeholder="150000"
              />
            </label>
          </div>
          <label>
            <span>Deskripsi</span>
            <input
              value={draft.description}
              onChange={(e) => setDraft({ ...draft, description: e.target.value })}
              placeholder="opsional"
            />
          </label>
          <div className="row">
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
            <label style={{ flex: 1 }}>
              <span>Prioritas (1=tinggi … 8)</span>
              <input
                value={draft.priority}
                onChange={(e) => setDraft({ ...draft, priority: e.target.value })}
                placeholder="8"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Batas koneksi</span>
              <input
                value={draft.connectionLimit}
                onChange={(e) => setDraft({ ...draft, connectionLimit: e.target.value })}
                placeholder="opsional"
              />
            </label>
          </div>

          {/* ---- Burst & Limit-at ---- */}
          <SectionTitle>Burst &amp; jaminan minimum (lanjutan)</SectionTitle>
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Burst mengizinkan lonjakan sesaat di atas rate. Isi berpasangan (unduh &amp; unggah);
            threshold &amp; waktu hanya berlaku bila burst diisi.
          </p>
          <div className="row">
            <label style={{ flex: 1 }}>
              <span>Burst unduh (Mbps)</span>
              <input
                value={draft.downBurstMbps}
                onChange={(e) => setDraft({ ...draft, downBurstMbps: e.target.value })}
                placeholder="100"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Burst unggah (Mbps)</span>
              <input
                value={draft.upBurstMbps}
                onChange={(e) => setDraft({ ...draft, upBurstMbps: e.target.value })}
                placeholder="50"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Threshold unduh</span>
              <input
                value={draft.downThresholdMbps}
                onChange={(e) => setDraft({ ...draft, downThresholdMbps: e.target.value })}
                placeholder="75"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Threshold unggah</span>
              <input
                value={draft.upThresholdMbps}
                onChange={(e) => setDraft({ ...draft, upThresholdMbps: e.target.value })}
                placeholder="35"
              />
            </label>
          </div>
          <div className="row">
            <label style={{ flex: 1 }}>
              <span>Waktu burst (detik)</span>
              <input
                value={draft.burstTimeSec}
                onChange={(e) => setDraft({ ...draft, burstTimeSec: e.target.value })}
                placeholder="8"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Limit-at unduh (Mbps)</span>
              <input
                value={draft.downMinMbps}
                onChange={(e) => setDraft({ ...draft, downMinMbps: e.target.value })}
                placeholder="opsional"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Limit-at unggah (Mbps)</span>
              <input
                value={draft.upMinMbps}
                onChange={(e) => setDraft({ ...draft, upMinMbps: e.target.value })}
                placeholder="opsional"
              />
            </label>
          </div>

          {/* ---- FUP ---- */}
          <SectionTitle>Fair Usage Policy (FUP)</SectionTitle>
          <label className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
            <input
              type="checkbox"
              checked={draft.fupEnabled}
              onChange={(e) => setDraft({ ...draft, fupEnabled: e.target.checked })}
              style={{ width: 'auto' }}
            />
            <span>Aktifkan FUP (turunkan kecepatan setelah kuota terlampaui)</span>
          </label>
          {draft.fupEnabled && (
            <div className="row">
              <label style={{ flex: 1 }}>
                <span>Kuota (MB){num(draft.fupQuotaMb) ? ` ≈ ${(num(draft.fupQuotaMb)! / 1024).toFixed(0)} GB` : ''}</span>
                <input
                  value={draft.fupQuotaMb}
                  onChange={(e) => setDraft({ ...draft, fupQuotaMb: e.target.value })}
                  placeholder="500000"
                />
              </label>
              <label style={{ flex: 1 }}>
                <span>Throttle unduh (Mbps)</span>
                <input
                  value={draft.fupDownMbps}
                  onChange={(e) => setDraft({ ...draft, fupDownMbps: e.target.value })}
                  placeholder="10"
                />
              </label>
              <label style={{ flex: 1 }}>
                <span>Throttle unggah (Mbps)</span>
                <input
                  value={draft.fupUpMbps}
                  onChange={(e) => setDraft({ ...draft, fupUpMbps: e.target.value })}
                  placeholder="4"
                />
              </label>
            </div>
          )}

          {/* ---- Ketersediaan ---- */}
          <SectionTitle>Ketersediaan</SectionTitle>
          <div className="row" style={{ gap: '1rem', flexWrap: 'wrap' }}>
            {SERVICE_TYPES.map((svc) => (
              <label key={svc} className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
                <input
                  type="checkbox"
                  checked={draft.serviceTypes.includes(svc)}
                  onChange={() => toggleService(svc)}
                  style={{ width: 'auto' }}
                />
                <span>{SERVICE_TYPE_LABEL[svc]}</span>
              </label>
            ))}
          </div>
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Baru PPPoE yang benar-benar diprovisi ke RADIUS; tipe lain masih metadata.
          </p>

          {/* ---- Siklus Billing (override) ---- */}
          <SectionTitle>Siklus billing (override — kosong = ikut kebijakan global)</SectionTitle>
          <div className="row">
            <label style={{ flex: 1 }}>
              <span>Prorate saat aktivasi</span>
              <select
                value={draft.prorateOnActivation}
                onChange={(e) => setDraft({ ...draft, prorateOnActivation: e.target.value as TriState })}
              >
                <option value="">Ikut global</option>
                <option value="true">Ya</option>
                <option value="false">Tidak</option>
              </select>
            </label>
            <label style={{ flex: 1 }}>
              <span>Tanggal tagih (1–31)</span>
              <input
                value={draft.billingDayOfMonth}
                onChange={(e) => setDraft({ ...draft, billingDayOfMonth: e.target.value })}
                placeholder="global"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Jatuh tempo (hari)</span>
              <input
                value={draft.dueDays}
                onChange={(e) => setDraft({ ...draft, dueDays: e.target.value })}
                placeholder="global"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Grace (hari)</span>
              <input
                value={draft.graceDays}
                onChange={(e) => setDraft({ ...draft, graceDays: e.target.value })}
                placeholder="global"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Auto-isolir</span>
              <select
                value={draft.autoIsolir}
                onChange={(e) => setDraft({ ...draft, autoIsolir: e.target.value as TriState })}
              >
                <option value="">Ikut global</option>
                <option value="true">Ya</option>
                <option value="false">Tidak</option>
              </select>
            </label>
          </div>

          {/* ---- Status ---- */}
          <label className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
            <input
              type="checkbox"
              checked={draft.active}
              onChange={(e) => setDraft({ ...draft, active: e.target.checked })}
              style={{ width: 'auto' }}
            />
            <span>Status aktif (paket nonaktif tak bisa dipilih untuk langganan baru)</span>
          </label>
          </div>
        )}
      </Blade>

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari nama paket…" />
        <select
          value={serviceFilter}
          onChange={(e) => setServiceFilter(e.target.value as ServiceType | '')}
        >
          <option value="">Semua layanan</option>
          {SERVICE_TYPES.map((s) => (
            <option key={s} value={s}>
              {SERVICE_TYPE_LABEL[s]}
            </option>
          ))}
        </select>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(p) => p.id}
        onRowClick={canManage ? edit : undefined}
        rowActions={canManage ? rowActions : undefined}
        loading={loading}
        initialSort={{ key: 'name', dir: 'asc' }}
        empty={
          <EmptyState
            title={query || serviceFilter ? 'Tidak ada paket yang cocok' : 'Belum ada paket'}
            hint={
              query || serviceFilter
                ? 'Coba ubah kata kunci atau filter.'
                : canManage
                  ? 'Klik “Tambah paket” untuk membuat paket pertama.'
                  : undefined
            }
            icon={<IconPackage size={32} />}
          />
        }
      />
    </div>
  )
}

function SectionTitle({ children }: { children: ReactNode }) {
  return (
    <div
      style={{
        marginTop: '0.5rem',
        fontWeight: 600,
        fontSize: '0.9rem',
        borderBottom: '1px solid var(--border, rgba(127,127,127,0.2))',
        paddingBottom: '0.35rem',
      }}
    >
      {children}
    </div>
  )
}
