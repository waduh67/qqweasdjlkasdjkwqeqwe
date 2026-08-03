import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import {
  getPaymentGatewaySettings,
  GATEWAY_MODE_LABEL,
  PAYMENT_PROVIDER_LABEL,
  SUPPORTED_PROVIDERS,
  updatePaymentGatewaySettings,
  type GatewayMode,
  type PaymentGatewaySettingsView,
  type PaymentProvider,
  type UpdatePaymentGatewaySettingsRequest,
} from '../api/payment'
import { useCan } from '../auth/useCan'
import { Badge, EmptyState, Modal, useToast } from '../components/ui'
import { IconAlert, IconShield } from '../components/icons'

/**
 * Pengaturan Payment Gateway tenant — halaman kritis: salah setel = tagihan tak dapat tautan
 * bayar atau charge lewat gateway yang keliru. Karena itu UX-nya dijaga:
 *
 *  1. **Status live dipisah dari form edit** — kartu atas menampilkan konfigurasi yang BENAR-BENAR
 *     berlaku sekarang (`saved`); form di bawah menampung suntingan yang belum disimpan (`form`).
 *  2. **Lacak perubahan (dirty)** — tombol simpan mati sampai ada yang berubah; "Batalkan" mengembalikan.
 *  3. **Wajib konfirmasi** — menyimpan memunculkan ringkasan diff (penyedia/mode/status/kredensial)
 *     yang harus dikonfirmasi, supaya tak ada perubahan tak sengaja.
 *
 * Kredensial write-only: dikirim saat menyimpan, tak pernah ditarik kembali — server hanya
 * menandai sudah terisi. Mode PLATFORM disiapkan admin platform (sub-account di-provisi).
 */

const PROVIDERS: PaymentProvider[] = ['XENDIT', 'PIVOT', 'PAYWUZ', 'MANUAL']

type CredKey = 'apiKey' | 'secretKey' | 'webhookToken'
interface CredField {
  key: CredKey
  label: string
  placeholder: string
}

/** Kredensial yang relevan per penyedia — satu sumber untuk input, status, & ringkasan konfirmasi. */
function credFields(provider: PaymentProvider): CredField[] {
  switch (provider) {
    case 'XENDIT':
      return [
        { key: 'secretKey', label: 'Secret key', placeholder: 'xnd_production_… / xnd_development_…' },
        { key: 'webhookToken', label: 'Webhook token', placeholder: 'x-callback-token dari dashboard Xendit' },
      ]
    case 'PIVOT':
      return [
        { key: 'apiKey', label: 'Merchant ID', placeholder: 'X-MERCHANT-ID dari dashboard Pivot' },
        { key: 'secretKey', label: 'Merchant Secret', placeholder: 'X-MERCHANT-SECRET dari dashboard Pivot' },
        { key: 'webhookToken', label: 'Callback API Key', placeholder: 'X-API-Key untuk verifikasi callback' },
      ]
    case 'PAYWUZ':
      // Satu kredensial: API key proyek — Bearer auth SEKALIGUS secret HMAC verifikasi webhook.
      return [{ key: 'apiKey', label: 'API key', placeholder: 'pk_live_… / pk_sand_…' }]
    case 'MANUAL':
      return []
  }
}

const isCredSet = (v: PaymentGatewaySettingsView, key: CredKey): boolean =>
  key === 'apiKey' ? v.apiKeySet : key === 'secretKey' ? v.secretKeySet : v.webhookTokenSet

interface FieldChange {
  label: string
  from: string
  to: string
}

export function PaymentGatewaySettingsPage() {
  const { can } = useCan()
  const toast = useToast()
  const manage = can('billing.gateway.manage')

  // `saved` = konfigurasi yang berlaku (dari server); `form` = suntingan yang belum disimpan.
  const [saved, setSaved] = useState<PaymentGatewaySettingsView | null>(null)
  const [form, setForm] = useState<PaymentGatewaySettingsView | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  // Input kredensial write-only, terpisah dari view: kosong = pertahankan yang tersimpan.
  const [creds, setCreds] = useState<Record<CredKey, string>>({ apiKey: '', secretKey: '', webhookToken: '' })

  useEffect(() => {
    getPaymentGatewaySettings()
      .then((s) => {
        setSaved(s)
        setForm(s)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat setelan gateway'))
      .finally(() => setLoading(false))
  }, [toast])

  const setCred = (key: CredKey, value: string) => setCreds((c) => ({ ...c, [key]: value }))
  const clearCreds = () => setCreds({ apiKey: '', secretKey: '', webhookToken: '' })

  // Ringkasan perubahan (dipakai untuk dirty-state + konfirmasi). Suntingan kredensial hanya
  // dihitung untuk field yang relevan dengan penyedia terpilih.
  const changes = useMemo<FieldChange[]>(() => {
    if (!saved || !form) return []
    const out: FieldChange[] = []
    if (form.provider !== saved.provider) {
      out.push({ label: 'Penyedia', from: PAYMENT_PROVIDER_LABEL[saved.provider], to: PAYMENT_PROVIDER_LABEL[form.provider] })
    }
    if (form.mode !== saved.mode) {
      out.push({ label: 'Mode', from: GATEWAY_MODE_LABEL[saved.mode], to: GATEWAY_MODE_LABEL[form.mode] })
    }
    if (form.enabled !== saved.enabled) {
      out.push({ label: 'Status', from: saved.enabled ? 'Aktif' : 'Nonaktif', to: form.enabled ? 'Aktif' : 'Nonaktif' })
    }
    for (const f of credFields(form.provider)) {
      if (creds[f.key].trim() !== '') {
        out.push({ label: f.label, from: isCredSet(saved, f.key) ? 'tersimpan' : 'kosong', to: 'diganti ke nilai baru' })
      }
    }
    return out
  }, [saved, form, creds])

  const dirty = changes.length > 0
  const enabling = !!saved && !!form && form.enabled && !saved.enabled

  const onProvider = (provider: PaymentProvider) => {
    // Ganti penyedia mengosongkan input kredensial (spesifik penyedia) & memaksa BYO bila
    // penyedia tak mendukung PLATFORM (hanya Xendit/xenPlatform yang punya mode agregator).
    clearCreds()
    setForm((f) => (f ? { ...f, provider, mode: provider === 'XENDIT' ? f.mode : 'BYO' } : f))
  }

  const discard = () => {
    if (saved) setForm(saved)
    clearCreds()
  }

  const doSave = async () => {
    if (!form) return
    setSaving(true)
    const body: UpdatePaymentGatewaySettingsRequest = {
      provider: form.provider,
      mode: form.mode,
      enabled: form.enabled,
      apiKey: creds.apiKey.trim() || null,
      secretKey: creds.secretKey.trim() || null,
      webhookToken: creds.webhookToken.trim() || null,
    }
    try {
      const result = await updatePaymentGatewaySettings(body)
      setSaved(result)
      setForm(result)
      clearCreds()
      setConfirmOpen(false)
      toast.success('Setelan payment gateway disimpan')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan setelan')
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <p className="muted">Memuat setelan…</p>
  if (!form || !saved) {
    return <EmptyState title="Setelan gateway tak tersedia" hint="Coba muat ulang halaman." icon={<IconAlert size={28} />} />
  }

  const supported = SUPPORTED_PROVIDERS.includes(form.provider)
  const fields = credFields(form.provider)

  return (
    <div className="stack" style={{ maxWidth: 720 }}>
      <div>
        <h2 style={{ margin: 0 }}>Payment Gateway</h2>
        <p className="muted" style={{ margin: '0.25rem 0 0' }}>
          Penyedia pembayaran &amp; kredensial untuk menagih pelanggan otomatis. Perubahan minta konfirmasi
          sebelum berlaku.
        </p>
      </div>

      {/* ---- Status yang berlaku sekarang (dari server, bukan suntingan) ---- */}
      <StatusPanel saved={saved} />

      {!manage && (
        <p className="muted" style={{ margin: 0 }}>
          Anda hanya bisa melihat setelan ini. Perlu izin “Kelola payment gateway” untuk mengubahnya.
        </p>
      )}

      {/* ---- Form suntingan ---- */}
      <div className="card stack" aria-disabled={!manage}>
        <SectionTitle>Ubah setelan</SectionTitle>

        <FormRow label="Status gateway" hint="Saat aktif, penerbitan tagihan otomatis membuat tautan bayar lewat penyedia. Saat mati, tagihan terbit tanpa tautan — pelunasan dicatat manual.">
          <Segmented
            value={form.enabled ? 'on' : 'off'}
            onChange={(v) => setForm({ ...form, enabled: v === 'on' })}
            disabled={!manage}
            options={[
              { value: 'off', label: 'Nonaktif' },
              { value: 'on', label: 'Aktif' },
            ]}
          />
        </FormRow>

        <FormRow label="Penyedia">
          <select value={form.provider} onChange={(e) => onProvider(e.target.value as PaymentProvider)} disabled={!manage}>
            {PROVIDERS.map((p) => (
              <option key={p} value={p}>
                {PAYMENT_PROVIDER_LABEL[p]}
              </option>
            ))}
          </select>
        </FormRow>

        {!supported && (
          <Callout>
            Penyedia ini <strong>belum didukung</strong> — dokumentasi API-nya belum tersedia. Setelan bisa disimpan,
            tapi penerbitan tagihan otomatis akan gagal untuk tenant ini sampai integrasinya rampung.
          </Callout>
        )}

        {form.provider === 'XENDIT' && (
          <FormRow label="Mode" hint="BYO memakai akun Xendit tenant sendiri; PLATFORM menagih lewat sub-account agregator platform.">
            <Segmented
              value={form.mode}
              onChange={(v) => setForm({ ...form, mode: v as GatewayMode })}
              disabled={!manage}
              options={[
                { value: 'BYO', label: GATEWAY_MODE_LABEL.BYO },
                { value: 'PLATFORM', label: GATEWAY_MODE_LABEL.PLATFORM },
              ]}
            />
          </FormRow>
        )}

        <div className="hr" />

        {form.mode === 'PLATFORM' ? (
          <PlatformSection subAccountId={form.subAccountId} />
        ) : (
          <div className="stack" style={{ gap: '0.85rem' }}>
            <SectionTitle>Kredensial (akun sendiri)</SectionTitle>

            {form.provider === 'MANUAL' && (
              <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
                Mode manual tak butuh kredensial penyedia — pembayaran dicatat operator atau lewat webhook bersecret
                bersama. Bagian ini kosong dengan sengaja.
              </p>
            )}

            {fields.map((f) => (
              <label key={f.key}>
                <span>
                  {f.label} {isCredSet(saved, f.key) && <span className="muted">· tersimpan</span>}
                </span>
                <input
                  type="password"
                  autoComplete="new-password"
                  value={creds[f.key]}
                  onChange={(e) => setCred(f.key, e.target.value)}
                  placeholder={isCredSet(saved, f.key) ? 'Biarkan kosong untuk mempertahankan' : f.placeholder}
                  disabled={!manage || !supported}
                />
              </label>
            ))}

            {form.provider === 'XENDIT' && (
              <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
                Secret key dari dashboard Xendit (Settings → API Keys). Webhook token = <code>x-callback-token</code>;
                arahkan URL callback Xendit ke <code>/api/billing/webhooks/&lt;tenant&gt;/xendit</code>.
              </p>
            )}
            {form.provider === 'PIVOT' && (
              <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
                Merchant ID &amp; Secret dari dashboard Pivot (Settings → API Keys). Callback API Key dari halaman
                Callbacks; arahkan URL callback Pivot ke <code>/api/billing/webhooks/&lt;tenant&gt;/pivot</code>.
                Server juga wajib mengisi <code>FTTH_BILLING_PIVOT_REDIRECT_BASE_URL</code>.
              </p>
            )}
            {form.provider === 'PAYWUZ' && (
              <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
                API key proyek dari dashboard Paywuz (<code>pk_live_…</code> / <code>pk_sand_…</code>) — dipakai untuk
                menagih sekaligus memverifikasi webhook, jadi tak ada token terpisah. Arahkan URL callback Paywuz ke{' '}
                <code>/api/billing/webhooks/&lt;tenant&gt;/paywuz</code>. Kode metode bayar (mis. QRIS) diatur di server
                (<code>FTTH_BILLING_PAYWUZ_PAYMENT_METHOD</code>).
              </p>
            )}
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
                <button className="ghost" onClick={discard} disabled={!dirty || saving}>
                  Batalkan
                </button>
                <button className="primary" onClick={() => setConfirmOpen(true)} disabled={!dirty || saving}>
                  Tinjau &amp; simpan…
                </button>
              </div>
            </div>
          </>
        )}
      </div>

      {confirmOpen && (
        <Modal
          title="Konfirmasi perubahan gateway"
          onClose={() => !saving && setConfirmOpen(false)}
          footer={
            <>
              <button className="ghost" onClick={() => setConfirmOpen(false)} disabled={saving}>
                Batal
              </button>
              <button className="primary" onClick={() => void doSave()} disabled={saving}>
                {saving ? 'Menyimpan…' : 'Ya, simpan'}
              </button>
            </>
          }
        >
          <div className="stack" style={{ gap: '0.85rem' }}>
            <p style={{ margin: 0, fontSize: '0.9rem' }}>
              Tinjau perubahan berikut sebelum berlaku untuk penagihan tenant ini:
            </p>

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

            {enabling && (
              <Callout>
                Gateway akan <strong>AKTIF</strong> — tagihan berikutnya otomatis dibuatkan tautan bayar lewat{' '}
                <strong>{PAYMENT_PROVIDER_LABEL[form.provider]}</strong>. Pastikan kredensial di atas benar.
              </Callout>
            )}
            {saved.enabled && !form.enabled && (
              <Callout>
                Gateway akan <strong>DINONAKTIFKAN</strong> — tagihan tetap terbit tapi tanpa tautan bayar; pelunasan
                harus dicatat manual.
              </Callout>
            )}
          </div>
        </Modal>
      )}
    </div>
  )
}

/** Kartu ringkas konfigurasi yang benar-benar berlaku sekarang (bukan suntingan). */
function StatusPanel({ saved }: { saved: PaymentGatewaySettingsView }) {
  const fields = credFields(saved.provider)
  const supported = SUPPORTED_PROVIDERS.includes(saved.provider)
  return (
    <div className="card stack" style={{ gap: '0.75rem' }}>
      <div className="spread" style={{ alignItems: 'center' }}>
        <div className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
          <IconShield size={16} />
          <strong style={{ fontSize: '0.95rem' }}>Berlaku sekarang</strong>
        </div>
        <Badge tone={saved.enabled ? 'good' : 'neutral'}>{saved.enabled ? 'Aktif' : 'Nonaktif'}</Badge>
      </div>

      <div className="row" style={{ gap: '0.4rem', flexWrap: 'wrap' }}>
        <Badge tone="accent">{PAYMENT_PROVIDER_LABEL[saved.provider]}</Badge>
        <Badge>{GATEWAY_MODE_LABEL[saved.mode]}</Badge>
        {!supported && <Badge tone="warning">belum didukung</Badge>}
        {saved.mode === 'PLATFORM' && (
          <Badge tone={saved.subAccountId ? 'good' : 'warning'}>
            {saved.subAccountId ? `sub-account ${saved.subAccountId}` : 'sub-account belum diprovisi'}
          </Badge>
        )}
      </div>

      {saved.provider === 'MANUAL' ? (
        <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>Tanpa kredensial penyedia (pelunasan manual).</p>
      ) : saved.mode === 'BYO' ? (
        <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap', fontSize: '0.82rem' }}>
          {fields.map((f) => (
            <span key={f.key} className="row" style={{ gap: '0.3rem', alignItems: 'center' }}>
              <span aria-hidden style={{ color: isCredSet(saved, f.key) ? 'var(--good-ink, green)' : 'var(--text-3)' }}>
                {isCredSet(saved, f.key) ? '●' : '○'}
              </span>
              <span className="muted">
                {f.label}: {isCredSet(saved, f.key) ? 'terisi' : 'belum'}
              </span>
            </span>
          ))}
        </div>
      ) : null}
    </div>
  )
}

function PlatformSection({ subAccountId }: { subAccountId: string | null }) {
  return (
    <div className="stack" style={{ gap: '0.75rem' }}>
      <SectionTitle>Akun platform (agregator)</SectionTitle>
      <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
        Tagihan dibuat atas nama sub-account platform; platform menampung &amp; meneruskan dana lalu memotong komisi.
        Sub-account disiapkan oleh admin platform — tak diisi di sini.
      </p>
      <label>
        <span>Sub-account ID</span>
        <input value={subAccountId ?? ''} placeholder="belum diprovisi — hubungi admin platform" disabled readOnly />
      </label>
      {!subAccountId && (
        <Callout>
          Belum ada sub-account. Mode PLATFORM tak akan aktif sampai admin platform memprovisi sub-account Xendit untuk
          tenant ini.
        </Callout>
      )}
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

/** Kotak peringatan bernada amber — konsisten dengan tona `warning` desain sistem. */
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
  return <h3 style={{ margin: '0.25rem 0 0', fontSize: '0.95rem', fontWeight: 650 }}>{children}</h3>
}
