import { useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '@/api/client'
import type { AlarmRuleView } from '@/api/monitoring'
import { useCan } from '@/auth/useCan'
import { Badge, Button, SkeletonRows, StatusBadge, TextField } from '@/components/atoms'
import { useToast } from '@/system'

/**
 * Ambang alarm per tenant — di angka berapa sistem mulai berteriak.
 *
 * Jaringan tiap ISP tak seragam: yang jalurnya panjang sampai kampung sebelah
 * wajar hidup di −25 dBm dan akan tenggelam oleh peringatan bawaan, sementara ONU
 * uji yang menumpuk dekat OLT justru kelewat terang. Ambang yang tak cocok bukan
 * sekadar berisik — operator berhenti membaca alarm, lalu gangguan sungguhan
 * lewat tanpa ada yang menengok.
 *
 * Karena itu layar ini menampilkan lebih dari kotak isian: bawaan pabrik, jumlah
 * alarm yang sedang terbuka (akibat langsung dari angka yang hendak digeser), dan
 * catatan batas fisik perangkat dari server. Perubahan berlaku seketika pada alarm
 * yang sudah terbuka, jadi hasilnya terlihat di daftar alarm begitu ditutup.
 */
export function AlarmThresholdPanel({ onChanged }: { onChanged?: () => void }) {
  const { can } = useCan()
  const toast = useToast()
  const manage = can('monitoring.threshold.manage')
  const [rules, setRules] = useState<AlarmRuleView[] | null>(null)
  const [drafts, setDrafts] = useState<Record<string, Draft>>({})
  const [busy, setBusy] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    api
      .get<AlarmRuleView[]>('/api/monitoring/alarm-rules')
      .then((list) => {
        if (!alive) return
        setRules(list)
        setDrafts(Object.fromEntries(list.map((r) => [r.kind, toDraft(r)])))
      })
      .catch((err) => {
        if (alive) toast.error(err instanceof ApiError ? err.message : 'Gagal memuat ambang alarm')
      })
    return () => {
      alive = false
    }
  }, [toast])

  /** Menaruh hasil server (satu jenis) kembali ke daftar + draft, agar keduanya sinkron. */
  const applyResult = (updated: AlarmRuleView) => {
    setRules((prev) => prev?.map((r) => (r.kind === updated.kind ? updated : r)) ?? null)
    setDrafts((prev) => ({ ...prev, [updated.kind]: toDraft(updated) }))
    onChanged?.()
  }

  const save = async (rule: AlarmRuleView, draft: Draft) => {
    setBusy(rule.kind)
    try {
      const updated = await api.put<AlarmRuleView>(`/api/monitoring/alarm-rules/${rule.kind}`, {
        enabled: draft.enabled,
        warningThreshold: parse(draft.warning),
        criticalThreshold: parse(draft.critical),
      })
      applyResult(updated)
      toast.success(`Ambang ${rule.kind} disimpan`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan ambang')
    } finally {
      setBusy(null)
    }
  }

  const reset = async (rule: AlarmRuleView) => {
    setBusy(rule.kind)
    try {
      applyResult(await api.del<AlarmRuleView>(`/api/monitoring/alarm-rules/${rule.kind}`))
      toast.success(`${rule.kind} kembali ke ambang bawaan`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengembalikan ambang bawaan')
    } finally {
      setBusy(null)
    }
  }

  if (!rules) return <SkeletonRows rows={4} />

  return (
    <div className="stack">
      {!manage && (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Hanya bisa dilihat — butuh izin “Setel ambang alarm”.
        </p>
      )}
      {rules.map((rule) => (
        <RuleCard
          key={rule.kind}
          rule={rule}
          draft={drafts[rule.kind] ?? toDraft(rule)}
          manage={manage}
          busy={busy === rule.kind}
          onDraft={(next) => setDrafts((prev) => ({ ...prev, [rule.kind]: next }))}
          onSave={() => void save(rule, drafts[rule.kind] ?? toDraft(rule))}
          onReset={() => void reset(rule)}
        />
      ))}
    </div>
  )
}

interface Draft {
  enabled: boolean
  warning: string
  critical: string
}

function RuleCard({
  rule,
  draft,
  manage,
  busy,
  onDraft,
  onSave,
  onReset,
}: {
  rule: AlarmRuleView
  draft: Draft
  manage: boolean
  busy: boolean
  onDraft: (next: Draft) => void
  onSave: () => void
  onReset: () => void
}) {
  const error = useMemo(() => validate(rule, draft), [rule, draft])
  const dirty = useMemo(() => !same(draft, toDraft(rule)), [draft, rule])
  // Arah perbandingan datang dari server, bukan ditebak dari tanda minus: −27 dan
  // −5 sama-sama negatif tapi berlawanan artinya.
  const cmp = rule.direction === 'HIGHER_IS_WORSE' ? '≥' : '≤'
  const unit = rule.unit ?? ''

  return (
    <div className="card stack" style={{ gap: '0.7rem' }}>
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-start', gap: '0.6rem' }}>
        <div>
          <div style={{ fontWeight: 600 }}>{rule.description}</div>
          <div className="row" style={{ gap: '0.35rem', marginTop: '0.2rem' }}>
            <span className="badge">{rule.kind}</span>
            <StatusBadge status={rule.defaultSeverity} />
          </div>
        </div>
        <div className="row" style={{ gap: '0.35rem', flexWrap: 'wrap', justifyContent: 'flex-end' }}>
          {rule.openAlarmCount > 0 && (
            <Badge tone={rule.enabled ? 'warning' : 'neutral'}>{rule.openAlarmCount} terbuka</Badge>
          )}
          <Badge tone={rule.customised ? 'accent' : 'neutral'}>{rule.customised ? 'disetel' : 'bawaan'}</Badge>
        </div>
      </div>

      {rule.direction ? (
        <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap', alignItems: 'flex-start' }}>
          <TextField
            label="Peringatan"
            hint={`alarm saat ${cmp} nilai ini · bawaan ${fmt(rule.defaultWarningThreshold, unit)}`}
            type="number"
            step="0.5"
            value={draft.warning}
            disabled={!manage || !draft.enabled || busy}
            onChange={(_, data) => onDraft({ ...draft, warning: data.value })}
            contentAfter={<span className="muted">{unit}</span>}
            style={{ width: 190 }}
          />
          <TextField
            label="Kritis"
            hint={`alarm saat ${cmp} nilai ini · bawaan ${fmt(rule.defaultCriticalThreshold, unit)}`}
            type="number"
            step="0.5"
            value={draft.critical}
            disabled={!manage || !draft.enabled || busy}
            onChange={(_, data) => onDraft({ ...draft, critical: data.value })}
            contentAfter={<span className="muted">{unit}</span>}
            style={{ width: 190 }}
          />
        </div>
      ) : (
        <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
          Tak berambang — hanya bisa dinyalakan atau dimatikan.
        </p>
      )}

      <p className="muted" style={{ margin: 0, fontSize: '0.82rem', lineHeight: 1.5 }}>
        {rule.guidance}
      </p>

      {error && (
        <p className="error" style={{ margin: 0, fontSize: '0.82rem' }} role="alert">
          {error}
        </p>
      )}

      <div className="row" style={{ justifyContent: 'space-between', flexWrap: 'wrap', gap: '0.5rem' }}>
        <Button
          variant={draft.enabled ? 'primary' : 'subtle'}
          size="small"
          disabled={!manage || busy}
          onClick={() => onDraft({ ...draft, enabled: !draft.enabled })}
          title={draft.enabled ? 'Matikan — alarm yang terbuka ikut ditutup' : 'Nyalakan pemantauan jenis ini'}
        >
          {draft.enabled ? 'Aktif' : 'Nonaktif'}
        </Button>
        <div className="row" style={{ gap: '0.4rem', flexWrap: 'wrap' }}>
          {rule.customised && (
            <Button variant="subtle" size="small" disabled={!manage || busy} onClick={onReset}>
              Kembalikan bawaan
            </Button>
          )}
          {dirty && (
            <Button variant="subtle" size="small" disabled={busy} onClick={() => onDraft(toDraft(rule))}>
              Batalkan
            </Button>
          )}
          <Button variant="primary" size="small" disabled={!manage || !dirty || !!error || busy} onClick={onSave}>
            Simpan
          </Button>
        </div>
      </div>
    </div>
  )
}

function toDraft(rule: AlarmRuleView): Draft {
  return {
    enabled: rule.enabled,
    warning: rule.warningThreshold == null ? '' : String(rule.warningThreshold),
    critical: rule.criticalThreshold == null ? '' : String(rule.criticalThreshold),
  }
}

function same(a: Draft, b: Draft): boolean {
  return a.enabled === b.enabled && parse(a.warning) === parse(b.warning) && parse(a.critical) === parse(b.critical)
}

function parse(value: string): number | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  const n = Number(trimmed)
  return Number.isFinite(n) ? n : null
}

function fmt(value: number | null, unit: string): string {
  return value == null ? '—' : `${value}${unit ? ` ${unit}` : ''}`
}

/**
 * Menjegal setelan yang diam-diam mematikan alarm — aturan yang sama dengan
 * server, dikerjakan di sini supaya orang tahu sebelum menekan Simpan.
 */
function validate(rule: AlarmRuleView, draft: Draft): string | null {
  if (!rule.direction || !draft.enabled) return null
  const warning = parse(draft.warning)
  const critical = parse(draft.critical)
  if (draft.warning.trim() && warning == null) return 'Ambang peringatan bukan angka.'
  if (draft.critical.trim() && critical == null) return 'Ambang kritis bukan angka.'
  if (warning == null && critical == null) return 'Isi minimal satu ambang, atau matikan jenis ini.'
  if (warning == null || critical == null) return null
  if (rule.direction === 'LOWER_IS_WORSE' && critical > warning) {
    return 'Kritis harus lebih rendah dari peringatan — kalau tidak, tak akan pernah terpicu.'
  }
  if (rule.direction === 'HIGHER_IS_WORSE' && critical < warning) {
    return 'Kritis harus lebih tinggi dari peringatan — kalau tidak, tak akan pernah terpicu.'
  }
  return null
}
