import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import {
  getTaxSettings,
  updateTaxSettings,
  type TaxSettingsView,
  type UpdateTaxSettingsRequest,
} from '../api/billing'
import { useCan } from '../auth/useCan'
import { Badge, Button, EmptyState, TextField } from '@/components/atoms'
import { Modal } from '@/components/molecules'
import { useToast } from '@/system'
import { PageHeader } from '@/components/molecules'
import { IconAlert, IconShield } from '@/components/atoms/icons'

/**
 * Setelan pajak tenant — dua kebijakan berbeda sifat digabung karena keduanya "pajak tenant":
 *
 *  1. **PPN** — komponen yang DITAGIHKAN ke pelanggan. Saat aktif, penerbitan tagihan menambahkan
 *     `dasar × tarif` ke atas nilai langganan. Perubahan tidak retroaktif: hanya tagihan yang
 *     terbit setelah ini yang terkena.
 *  2. **BHP/USO** — kewajiban LAPORAN tenant (PNBP), BUKAN ditagih ke pelanggan. Dihitung server
 *     dari peredaran bruto (pendapatan tertagih sebelum PPN) hanya untuk KPI/laporan.
 *
 * Pola UX meniru halaman gateway: status yang berlaku (`saved`) dipisah dari suntingan (`form`),
 * tombol simpan mati sampai ada perubahan, dan menyimpan minta konfirmasi ringkasan diff.
 * Tarif ditampilkan sebagai PERSEN (ramah operator); di server & API ia pecahan (0.11 = 11%).
 */

interface TaxForm {
  ppnEnabled: boolean
  ppnPct: string
  regulatoryEnabled: boolean
  bhpPct: string
  usoPct: string
}

/** Pecahan string server ("0.1100") → persen ramah-tampil ("11"), tanpa ekor float. */
function pctFromFraction(fraction: string): string {
  const n = Number(fraction)
  if (!Number.isFinite(n)) return '0'
  return String(parseFloat((n * 100).toFixed(4)))
}

/** Persen input ("11") → pecahan number untuk API (0.11); dibulatkan aman ke 6 desimal. */
function fractionFromPct(pct: string): number {
  return parseFloat((Number(pct) / 100).toFixed(6))
}

function toForm(v: TaxSettingsView): TaxForm {
  return {
    ppnEnabled: v.ppnEnabled,
    ppnPct: pctFromFraction(v.ppnRate),
    regulatoryEnabled: v.regulatoryEnabled,
    bhpPct: pctFromFraction(v.bhpRate),
    usoPct: pctFromFraction(v.usoRate),
  }
}

/** Persen valid: angka berhingga di [0,100). Di luar itu server juga menolak. */
function validPct(pct: string): boolean {
  const n = Number(pct)
  return pct.trim() !== '' && Number.isFinite(n) && n >= 0 && n < 100
}

interface FieldChange {
  label: string
  from: string
  to: string
}

export function TaxSettingsPage() {
  const { can } = useCan()
  const toast = useToast()
  const manage = can('billing.tax.manage')

  const [saved, setSaved] = useState<TaxSettingsView | null>(null)
  const [form, setForm] = useState<TaxForm | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)

  useEffect(() => {
    getTaxSettings()
      .then((s) => {
        setSaved(s)
        setForm(toForm(s))
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat setelan pajak'))
      .finally(() => setLoading(false))
  }, [toast])

  const patch = (p: Partial<TaxForm>) => setForm((f) => (f ? { ...f, ...p } : f))

  const changes = useMemo<FieldChange[]>(() => {
    if (!saved || !form) return []
    const base = toForm(saved)
    const out: FieldChange[] = []
    const onOff = (b: boolean) => (b ? 'Aktif' : 'Nonaktif')
    if (form.ppnEnabled !== base.ppnEnabled) {
      out.push({ label: 'PPN', from: onOff(base.ppnEnabled), to: onOff(form.ppnEnabled) })
    }
    if (form.ppnEnabled && form.ppnPct !== base.ppnPct) {
      out.push({ label: 'Tarif PPN', from: `${base.ppnPct}%`, to: `${form.ppnPct}%` })
    }
    if (form.regulatoryEnabled !== base.regulatoryEnabled) {
      out.push({ label: 'Pelaporan BHP/USO', from: onOff(base.regulatoryEnabled), to: onOff(form.regulatoryEnabled) })
    }
    if (form.regulatoryEnabled && form.bhpPct !== base.bhpPct) {
      out.push({ label: 'Tarif BHP', from: `${base.bhpPct}%`, to: `${form.bhpPct}%` })
    }
    if (form.regulatoryEnabled && form.usoPct !== base.usoPct) {
      out.push({ label: 'Tarif USO', from: `${base.usoPct}%`, to: `${form.usoPct}%` })
    }
    return out
  }, [saved, form])

  const dirty = changes.length > 0
  const enablingPpn = !!saved && !!form && form.ppnEnabled && !saved.ppnEnabled
  // Tombol simpan hanya aktif bila tarif fitur yang menyala valid (yang mati diabaikan).
  const valid =
    !!form && (!form.ppnEnabled || validPct(form.ppnPct)) && (!form.regulatoryEnabled || (validPct(form.bhpPct) && validPct(form.usoPct)))

  const discard = () => {
    if (saved) setForm(toForm(saved))
  }

  const doSave = async () => {
    if (!form) return
    setSaving(true)
    const body: UpdateTaxSettingsRequest = {
      ppnEnabled: form.ppnEnabled,
      ppnRate: fractionFromPct(form.ppnPct),
      regulatoryEnabled: form.regulatoryEnabled,
      bhpRate: fractionFromPct(form.bhpPct),
      usoRate: fractionFromPct(form.usoPct),
    }
    try {
      const result = await updateTaxSettings(body)
      setSaved(result)
      setForm(toForm(result))
      setConfirmOpen(false)
      toast.success('Setelan pajak disimpan')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan setelan pajak')
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <p className="muted">Memuat setelan…</p>
  if (!form || !saved) {
    return <EmptyState title="Setelan pajak tak tersedia" hint="Coba muat ulang halaman." icon={<IconAlert size={28} />} />
  }

  return (
    <div className="stack settings-page">
      <PageHeader
        title="Pajak & Kontribusi"
        subtitle="PPN yang ditagihkan ke pelanggan & kewajiban pelaporan BHP/USO. Perubahan PPN tidak berlaku surut — hanya tagihan yang terbit setelahnya."
      />

      <StatusPanel saved={saved} />

      {!manage && (
        <p className="muted" style={{ margin: 0 }}>
          Anda hanya bisa melihat setelan ini. Perlu izin “Kelola PPN &amp; kontribusi BHP/USO” untuk mengubahnya.
        </p>
      )}

      <div className="card stack" aria-disabled={!manage}>
        <SectionTitle>PPN (ditagihkan ke pelanggan)</SectionTitle>
        <FormRow
          label="Status PPN"
          hint="Saat aktif, penerbitan tagihan menambahkan PPN ke atas nilai langganan. Saat mati, tagihan tanpa PPN (perilaku lama)."
        >
          <Segmented
            value={form.ppnEnabled ? 'on' : 'off'}
            onChange={(v) => patch({ ppnEnabled: v === 'on' })}
            disabled={!manage}
            options={[
              { value: 'off', label: 'Nonaktif' },
              { value: 'on', label: 'Aktif' },
            ]}
          />
        </FormRow>
        {form.ppnEnabled && (
          <PercentField
            label="Tarif PPN"
            value={form.ppnPct}
            onChange={(ppnPct) => patch({ ppnPct })}
            disabled={!manage}
            hint="Tarif PPN barang/jasa telekomunikasi (mis. 11)."
          />
        )}

        <div className="hr" />

        <SectionTitle>BHP &amp; USO (kewajiban laporan)</SectionTitle>
        <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
          BHP Telekomunikasi &amp; Kontribusi USO adalah PNBP yang dibayar tenant dari peredaran bruto —{' '}
          <strong>tidak ditagihkan ke pelanggan</strong>. Menyalakan ini hanya menampilkan KPI &amp; kewajiban di
          halaman Tagihan (dihitung server dari tagihan lunas).
        </p>
        <FormRow label="Pelaporan BHP/USO">
          <Segmented
            value={form.regulatoryEnabled ? 'on' : 'off'}
            onChange={(v) => patch({ regulatoryEnabled: v === 'on' })}
            disabled={!manage}
            options={[
              { value: 'off', label: 'Nonaktif' },
              { value: 'on', label: 'Aktif' },
            ]}
          />
        </FormRow>
        {form.regulatoryEnabled && (
          <div className="row" style={{ gap: '1rem', flexWrap: 'wrap' }}>
            <PercentField
              label="Tarif BHP"
              value={form.bhpPct}
              onChange={(bhpPct) => patch({ bhpPct })}
              disabled={!manage}
              hint="mis. 0.5"
            />
            <PercentField
              label="Tarif USO"
              value={form.usoPct}
              onChange={(usoPct) => patch({ usoPct })}
              disabled={!manage}
              hint="mis. 1.25"
            />
          </div>
        )}

        {manage && (
          <>
            <div className="hr" />
            <div className="spread" style={{ alignItems: 'center' }}>
              <span className="muted" style={{ fontSize: '0.85rem' }}>
                {dirty ? `${changes.length} perubahan belum disimpan` : 'Tak ada perubahan'}
              </span>
              <div className="row" style={{ gap: '0.5rem' }}>
                <Button variant="subtle" onClick={discard} disabled={!dirty || saving}>
                  Batalkan
                </Button>
                <Button variant="primary" onClick={() => setConfirmOpen(true)} disabled={!dirty || !valid || saving}>
                  Tinjau &amp; simpan…
                </Button>
              </div>
            </div>
          </>
        )}
      </div>

      {confirmOpen && (
        <Modal
          title="Konfirmasi setelan pajak"
          onClose={() => !saving && setConfirmOpen(false)}
          footer={
            <>
              <Button variant="subtle" onClick={() => setConfirmOpen(false)} disabled={saving}>
                Batal
              </Button>
              <Button variant="primary" onClick={() => void doSave()} disabled={saving}>
                {saving ? 'Menyimpan…' : 'Ya, simpan'}
              </Button>
            </>
          }
        >
          <div className="stack" style={{ gap: '0.85rem' }}>
            <p style={{ margin: 0, fontSize: '0.9rem' }}>Tinjau perubahan berikut sebelum berlaku untuk tenant ini:</p>
            <div className="stack" style={{ gap: '0.4rem' }}>
              {changes.map((c) => (
                <div key={c.label} className="spread" style={{ gap: '0.75rem', fontSize: '0.88rem' }}>
                  <span className="muted">{c.label}</span>
                  <span style={{ textAlign: 'right' }}>
                    <span className="muted" style={{ textDecoration: 'line-through' }}>
                      {c.from}
                    </span>{' '}
                    → <strong>{c.to}</strong>
                  </span>
                </div>
              ))}
            </div>
            {enablingPpn && (
              <Callout>
                PPN akan <strong>AKTIF</strong> — tagihan berikutnya otomatis menambahkan PPN{' '}
                <strong>{form.ppnPct}%</strong> ke total. Tagihan yang sudah terbit tidak berubah.
              </Callout>
            )}
          </div>
        </Modal>
      )}
    </div>
  )
}

/** Kartu ringkas kebijakan pajak yang benar-benar berlaku sekarang (bukan suntingan). */
function StatusPanel({ saved }: { saved: TaxSettingsView }) {
  return (
    <div className="card stack" style={{ gap: '0.75rem' }}>
      <div className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
        <IconShield size={16} />
        <strong style={{ fontSize: '0.95rem' }}>Berlaku sekarang</strong>
      </div>
      <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap', alignItems: 'center' }}>
        <span className="muted" style={{ fontSize: '0.82rem' }}>PPN:</span>
        <Badge tone={saved.ppnEnabled ? 'good' : 'neutral'}>{saved.ppnEnabled ? 'Aktif' : 'Nonaktif'}</Badge>
        {saved.ppnEnabled && <Badge tone="accent">{pctFromFraction(saved.ppnRate)}%</Badge>}
      </div>
      <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap', alignItems: 'center' }}>
        <span className="muted" style={{ fontSize: '0.82rem' }}>BHP/USO:</span>
        <Badge tone={saved.regulatoryEnabled ? 'good' : 'neutral'}>{saved.regulatoryEnabled ? 'Aktif' : 'Nonaktif'}</Badge>
        {saved.regulatoryEnabled && (
          <>
            <Badge>BHP {pctFromFraction(saved.bhpRate)}%</Badge>
            <Badge>USO {pctFromFraction(saved.usoRate)}%</Badge>
          </>
        )}
      </div>
    </div>
  )
}

/** Input persen (0..100) dengan sufiks "%" — nilai disimpan sebagai string agar bisa dikosongkan saat mengetik. */
function PercentField({
  label,
  value,
  onChange,
  disabled,
  hint,
}: {
  label: string
  value: string
  onChange: (value: string) => void
  disabled?: boolean
  hint?: string
}) {
  const invalid = !validPct(value)
  return (
    <div style={{ minWidth: 180 }}>
      <TextField
        label={label}
        type="number"
        inputMode="decimal"
        min={0}
        max={99.9999}
        step={0.05}
        value={value}
        onChange={(_, data) => onChange(data.value)}
        disabled={disabled}
        contentAfter={<span className="muted">%</span>}
        validationState={invalid ? 'error' : 'none'}
        validationMessage={invalid ? 'Isi angka 0–99.99.' : undefined}
        hint={invalid ? undefined : hint}
        style={{ width: 160 }}
      />
    </div>
  )
}

function Segmented<T extends string>({
  value,
  options,
  onChange,
  disabled,
}: {
  value: T
  options: { value: T; label: string }[]
  onChange: (value: T) => void
  disabled?: boolean
}) {
  return (
    <div className="segment" role="group">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          className={value === o.value ? 'active' : ''}
          aria-pressed={value === o.value}
          onClick={() => onChange(o.value)}
          disabled={disabled}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

function FormRow({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="stack" style={{ gap: '0.35rem' }}>
      <span style={{ fontSize: '0.9rem', fontWeight: 600 }}>{label}</span>
      {children}
      {hint && (
        <span className="muted" style={{ fontSize: '0.82rem' }}>
          {hint}
        </span>
      )}
    </div>
  )
}

function Callout({ children }: { children: ReactNode }) {
  return (
    <div
      className="row"
      style={{
        gap: '0.5rem',
        alignItems: 'flex-start',
        padding: '0.6rem 0.75rem',
        borderRadius: 'var(--radius-sm)',
        background: 'color-mix(in srgb, var(--warning) 12%, var(--surface))',
        border: '1px solid color-mix(in srgb, var(--warning) 32%, transparent)',
        fontSize: '0.85rem',
      }}
    >
      <IconAlert size={16} />
      <span>{children}</span>
    </div>
  )
}

function SectionTitle({ children }: { children: ReactNode }) {
  return <h3 style={{ margin: '0.25rem 0 0', fontSize: '0.95rem', fontWeight: 600 }}>{children}</h3>
}
