import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Text, typographyStyles } from '@fluentui/react-components'
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
import { Checkbox } from '@fluentui/react-components'
import { useCan } from '../auth/useCan'
import { DataTable, type Column, type RowAction } from '@/components/organisms'
import { CommandBar, type CommandAction } from '@/components/molecules'
import { Blade } from '@/components/organisms'
import { PageHeader } from '@/components/molecules'
import { Badge, Button, EmptyState, SelectField, StatusBadge, TextField, Toolbar } from '@/components/atoms'
import { SearchInput } from '@/components/molecules'
import { useToast } from '@/system'
import { IconPackage } from '@/components/atoms/icons'

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
      header: 'Nama',
      sortValue: (p) => p.name,
      cell: (p) => p.name,
      onCellClick: canManage ? edit : undefined,
      inlineActions: canManage ? inlineActions : undefined,
    },
    {
      key: 'description',
      header: 'Deskripsi',
      sortValue: (p) => p.description,
      cell: (p) => p.description || <span className="muted">—</span>,
    },
    {
      key: 'price',
      header: 'Harga / bln',
      align: 'right',
      sortValue: (p) => p.price,
      cell: (p) => fmtRupiah(p.price),
    },
    {
      key: 'downloadSpeed',
      header: 'Unduh',
      align: 'right',
      sortValue: (p) => p.downMbps,
      cell: (p) => `${p.downMbps} Mbps`,
    },
    {
      key: 'uploadSpeed',
      header: 'Unggah',
      align: 'right',
      sortValue: (p) => p.upMbps,
      cell: (p) => `${p.upMbps} Mbps`,
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

  function inlineActions(p: PlanView): RowAction[] {
    return [{ key: 'edit', label: 'Ubah', icon: <Pencil size={16} />, onClick: () => edit(p) }]
  }

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
            <Button variant="primary" onClick={() => void save()} disabled={saving}>
              {saving ? 'Menyimpan…' : 'Simpan'}
            </Button>
            <Button onClick={closeDraft} disabled={saving}>Batal</Button>
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
            <Text as="span" className="muted" size={200} style={{ textTransform: 'uppercase', letterSpacing: '0.04em' }}>
              Preview atribut RADIUS (Mikrotik-Rate-Limit)
            </Text>
            <code style={{ ...typographyStyles.body1Strong }}>{preview || '— isi kecepatan dulu —'}</code>
            {fupPreview && (
              <code className="muted" style={{ ...typographyStyles.body2 }}>
                FUP → {fupPreview}
              </code>
            )}
          </div>

          {/* ---- Informasi Dasar ---- */}
          <SectionTitle>Informasi dasar</SectionTitle>
          <div className="row">
            <div style={{ flex: 2 }}>
              <TextField
                label="Nama paket"
                value={draft.name}
                onChange={(_, data) => setDraft({ ...draft, name: data.value })}
                placeholder="Home 50 Mbps"
              />
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label="Harga / bulan (Rp)"
                value={draft.price}
                onChange={(_, data) => setDraft({ ...draft, price: data.value })}
                placeholder="150000"
              />
            </div>
          </div>
          <TextField
            label="Deskripsi"
            value={draft.description}
            onChange={(_, data) => setDraft({ ...draft, description: data.value })}
            placeholder="opsional"
          />
          <div className="row">
            <div style={{ flex: 1 }}>
              <TextField
                label="Unduh (Mbps)"
                value={draft.downMbps}
                onChange={(_, data) => setDraft({ ...draft, downMbps: data.value })}
                placeholder="50"
              />
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label="Unggah (Mbps)"
                value={draft.upMbps}
                onChange={(_, data) => setDraft({ ...draft, upMbps: data.value })}
                placeholder="25"
              />
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label="Prioritas (1=tinggi … 8)"
                value={draft.priority}
                onChange={(_, data) => setDraft({ ...draft, priority: data.value })}
                placeholder="8"
              />
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label="Batas koneksi"
                value={draft.connectionLimit}
                onChange={(_, data) => setDraft({ ...draft, connectionLimit: data.value })}
                placeholder="opsional"
              />
            </div>
          </div>

          {/* ---- Burst & Limit-at ---- */}
          <SectionTitle>Burst &amp; jaminan minimum (lanjutan)</SectionTitle>
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
            Burst mengizinkan lonjakan sesaat di atas rate. Isi berpasangan (unduh &amp; unggah);
            threshold &amp; waktu hanya berlaku bila burst diisi.
          </Text>
          <div className="row">
            <div style={{ flex: 1 }}>
              <TextField
                label="Burst unduh (Mbps)"
                value={draft.downBurstMbps}
                onChange={(_, data) => setDraft({ ...draft, downBurstMbps: data.value })}
                placeholder="100"
              />
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label="Burst unggah (Mbps)"
                value={draft.upBurstMbps}
                onChange={(_, data) => setDraft({ ...draft, upBurstMbps: data.value })}
                placeholder="50"
              />
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label="Threshold unduh"
                value={draft.downThresholdMbps}
                onChange={(_, data) => setDraft({ ...draft, downThresholdMbps: data.value })}
                placeholder="75"
              />
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label="Threshold unggah"
                value={draft.upThresholdMbps}
                onChange={(_, data) => setDraft({ ...draft, upThresholdMbps: data.value })}
                placeholder="35"
              />
            </div>
          </div>
          <div className="row">
            <div style={{ flex: 1 }}>
              <TextField
                label="Waktu burst (detik)"
                value={draft.burstTimeSec}
                onChange={(_, data) => setDraft({ ...draft, burstTimeSec: data.value })}
                placeholder="8"
              />
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label="Limit-at unduh (Mbps)"
                value={draft.downMinMbps}
                onChange={(_, data) => setDraft({ ...draft, downMinMbps: data.value })}
                placeholder="opsional"
              />
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label="Limit-at unggah (Mbps)"
                value={draft.upMinMbps}
                onChange={(_, data) => setDraft({ ...draft, upMinMbps: data.value })}
                placeholder="opsional"
              />
            </div>
          </div>

          {/* ---- FUP ---- */}
          <SectionTitle>Fair Usage Policy (FUP)</SectionTitle>
          <Checkbox
            label="Aktifkan FUP (turunkan kecepatan setelah kuota terlampaui)"
            checked={draft.fupEnabled}
            onChange={(e) => setDraft({ ...draft, fupEnabled: e.target.checked })}
          />
          {draft.fupEnabled && (
            <div className="row">
              <div style={{ flex: 1 }}>
                <TextField
                  label={<>Kuota (MB){num(draft.fupQuotaMb) ? ` ≈ ${(num(draft.fupQuotaMb)! / 1024).toFixed(0)} GB` : ''}</>}
                  value={draft.fupQuotaMb}
                  onChange={(_, data) => setDraft({ ...draft, fupQuotaMb: data.value })}
                  placeholder="500000"
                />
              </div>
              <div style={{ flex: 1 }}>
                <TextField
                  label="Throttle unduh (Mbps)"
                  value={draft.fupDownMbps}
                  onChange={(_, data) => setDraft({ ...draft, fupDownMbps: data.value })}
                  placeholder="10"
                />
              </div>
              <div style={{ flex: 1 }}>
                <TextField
                  label="Throttle unggah (Mbps)"
                  value={draft.fupUpMbps}
                  onChange={(_, data) => setDraft({ ...draft, fupUpMbps: data.value })}
                  placeholder="4"
                />
              </div>
            </div>
          )}

          {/* ---- Ketersediaan ---- */}
          <SectionTitle>Ketersediaan</SectionTitle>
          <div className="row" style={{ gap: '1rem', flexWrap: 'wrap' }}>
            {SERVICE_TYPES.map((svc) => (
              <Checkbox
                key={svc}
                label={SERVICE_TYPE_LABEL[svc]}
                checked={draft.serviceTypes.includes(svc)}
                onChange={() => toggleService(svc)}
              />
            ))}
          </div>
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
            Baru PPPoE yang benar-benar diprovisi ke RADIUS; tipe lain masih metadata.
          </Text>

          {/* ---- Siklus Billing (override) ---- */}
          <SectionTitle>Siklus billing (override — kosong = ikut kebijakan global)</SectionTitle>
          <div className="row">
            <div style={{ flex: 1 }}>
              <SelectField
                label="Prorate saat aktivasi"
                value={draft.prorateOnActivation}
                onChange={(_, data) => setDraft({ ...draft, prorateOnActivation: data.value as TriState })}
              >
                <option value="">Ikut global</option>
                <option value="true">Ya</option>
                <option value="false">Tidak</option>
              </SelectField>
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label="Tanggal tagih (1–31)"
                value={draft.billingDayOfMonth}
                onChange={(_, data) => setDraft({ ...draft, billingDayOfMonth: data.value })}
                placeholder="global"
              />
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label="Jatuh tempo (hari)"
                value={draft.dueDays}
                onChange={(_, data) => setDraft({ ...draft, dueDays: data.value })}
                placeholder="global"
              />
            </div>
            <div style={{ flex: 1 }}>
              <TextField
                label="Grace (hari)"
                value={draft.graceDays}
                onChange={(_, data) => setDraft({ ...draft, graceDays: data.value })}
                placeholder="global"
              />
            </div>
            <div style={{ flex: 1 }}>
              <SelectField
                label="Auto-isolir"
                value={draft.autoIsolir}
                onChange={(_, data) => setDraft({ ...draft, autoIsolir: data.value as TriState })}
              >
                <option value="">Ikut global</option>
                <option value="true">Ya</option>
                <option value="false">Tidak</option>
              </SelectField>
            </div>
          </div>

          {/* ---- Status ---- */}
          <Checkbox
            label="Status aktif (paket nonaktif tak bisa dipilih untuk langganan baru)"
            checked={draft.active}
            onChange={(e) => setDraft({ ...draft, active: e.target.checked })}
          />
          </div>
        )}
      </Blade>

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari nama paket…" />
        <SelectField
          value={serviceFilter}
          onChange={(_, data) => setServiceFilter(data.value as ServiceType | '')}
        >
          <option value="">Semua layanan</option>
          {SERVICE_TYPES.map((s) => (
            <option key={s} value={s}>
              {SERVICE_TYPE_LABEL[s]}
            </option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(p) => p.id}
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
        ...typographyStyles.body1Strong,
        marginTop: '0.5rem',
        borderBottom: '1px solid var(--border, rgba(127,127,127,0.2))',
        paddingBottom: '0.35rem',
      }}
    >
      {children}
    </div>
  )
}
