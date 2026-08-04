import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import {
  getPlatformBillingSettings,
  PLATFORM_PROVIDER_LABEL,
  PLATFORM_PROVIDERS,
  updatePlatformGateway,
  updatePlatformSettings,
  type PlatformBillingSettingsView,
  type PlatformGatewayView,
  type PlatformProvider,
} from '../api/platformBilling'
import { useCan } from '../auth/useCan'
import { Badge, EmptyState, useToast } from '../components/ui'
import { IconAlert, IconShield } from '../components/icons'

/**
 * Setelan billing langganan SaaS (level platform) — super-admin memilih gateway mana yang menagih
 * tenant untuk memakai aplikasi, plus kredensial tiap penyedia. Beda dari `PaymentGatewaySettingsPage`
 * (yang per-tenant menagih pelanggan tenant): ini GLOBAL, satu baris per penyedia untuk seluruh platform.
 *
 * Kredensial write-only: dikirim saat menyimpan, tak pernah ditarik balik — server hanya menandai
 * sudah terisi (`*Set`). Rahasia null/kosong saat simpan = pertahankan yang tersimpan.
 */

type CredKey = 'apiKey' | 'secretKey' | 'webhookToken'
interface CredField {
  key: CredKey
  label: string
  placeholder: string
}

/** Kredensial relevan per penyedia — cermin `PlatformPaymentGateway.resolve()` di server. */
function credFields(provider: PlatformProvider): CredField[] {
  switch (provider) {
    case 'PAYWUZ':
      // Satu API key: Bearer auth SEKALIGUS secret HMAC verifikasi webhook.
      return [{ key: 'apiKey', label: 'API key', placeholder: 'pk_live_… / pk_sand_…' }]
    case 'XENDIT':
      return [
        { key: 'secretKey', label: 'Secret key', placeholder: 'xnd_production_… / xnd_development_…' },
        { key: 'webhookToken', label: 'Webhook token', placeholder: 'x-callback-token dari dashboard Xendit' },
      ]
    case 'MIDTRANS':
      return [
        { key: 'secretKey', label: 'Server Key', placeholder: 'Mid-server-… (produksi) / SB-Mid-server-… (sandbox)' },
      ]
  }
}

const isCredSet = (v: PlatformGatewayView, key: CredKey): boolean =>
  key === 'apiKey' ? v.apiKeySet : key === 'secretKey' ? v.secretKeySet : v.webhookTokenSet

export function PlatformBillingSettingsPage() {
  const { can } = useCan()
  const toast = useToast()
  const manage = can('platform.billing.manage')

  const [settings, setSettings] = useState<PlatformBillingSettingsView | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    getPlatformBillingSettings()
      .then(setSettings)
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat setelan billing'))
      .finally(() => setLoading(false))
  }, [toast])

  if (loading) return <p className="muted">Memuat setelan…</p>
  if (!settings) {
    return <EmptyState title="Setelan billing tak tersedia" hint="Coba muat ulang halaman." icon={<IconAlert size={28} />} />
  }

  return (
    <div className="stack" style={{ maxWidth: 760, gap: '1.25rem' }}>
      <div>
        <h1 className="page-title">Billing Langganan Platform</h1>
        <p className="page-sub">
          Gateway &amp; kredensial untuk menagih tenant biaya bulanan memakai aplikasi ini. Setelan berlaku
          global — satu penyedia aktif menagih semua tenant.
        </p>
      </div>

      <GlobalPanel settings={settings} manage={manage} onSaved={setSettings} />

      <div className="stack" style={{ gap: '0.85rem' }}>
        <h2 style={{ margin: 0, fontSize: '1.05rem' }}>Kredensial penyedia</h2>
        {!manage && (
          <p className="muted" style={{ margin: 0 }}>
            Anda hanya bisa melihat. Perlu izin “Kelola gateway billing platform” untuk mengubah.
          </p>
        )}
        {PLATFORM_PROVIDERS.map((provider) => {
          const gw = settings.gateways.find((g) => g.provider === provider)
          if (!gw) return null
          return (
            <GatewayCard
              key={provider}
              gateway={gw}
              active={settings.activeProvider === provider}
              manage={manage}
              onSaved={setSettings}
            />
          )
        })}
      </div>
    </div>
  )
}

/** Setelan global: gateway aktif + default grace/jatuh-tempo/tanggal-tagih/mata-uang. */
function GlobalPanel({
  settings,
  manage,
  onSaved,
}: {
  settings: PlatformBillingSettingsView
  manage: boolean
  onSaved: (s: PlatformBillingSettingsView) => void
}) {
  const toast = useToast()
  const [activeProvider, setActiveProvider] = useState(settings.activeProvider)
  const [graceDays, setGraceDays] = useState(String(settings.defaultGraceDays))
  const [dueDays, setDueDays] = useState(String(settings.defaultDueDays))
  const [billingDay, setBillingDay] = useState(String(settings.defaultBillingDay))
  const [monthlyFee, setMonthlyFee] = useState(String(settings.defaultMonthlyFee))
  const [currency, setCurrency] = useState(settings.currency)
  const [saving, setSaving] = useState(false)

  const dirty =
    activeProvider !== settings.activeProvider ||
    Number(graceDays) !== settings.defaultGraceDays ||
    Number(dueDays) !== settings.defaultDueDays ||
    Number(billingDay) !== settings.defaultBillingDay ||
    Number(monthlyFee) !== settings.defaultMonthlyFee ||
    currency.trim().toUpperCase() !== settings.currency

  const activeGateway = settings.gateways.find((g) => g.provider === activeProvider)
  const activeReady = activeGateway?.enabled && activeGateway?.credentialsSet

  const save = async () => {
    setSaving(true)
    try {
      const result = await updatePlatformSettings({
        activeProvider,
        defaultGraceDays: Number(graceDays),
        defaultDueDays: Number(dueDays),
        defaultBillingDay: Number(billingDay),
        defaultMonthlyFee: Number(monthlyFee),
        currency: currency.trim().toUpperCase(),
      })
      onSaved(result)
      toast.success('Setelan billing global disimpan')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan setelan')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="card stack" style={{ gap: '0.85rem' }}>
      <div className="spread" style={{ alignItems: 'center' }}>
        <div className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
          <IconShield size={16} />
          <strong style={{ fontSize: '0.95rem' }}>Gateway aktif &amp; default global</strong>
        </div>
        <Badge tone={activeReady ? 'good' : 'warning'}>
          {activeReady ? 'siap menagih' : 'belum siap'}
        </Badge>
      </div>

      <FormRow
        label="Gateway aktif"
        hint="Penyedia yang dipakai menagih langganan tenant. Pastikan kredensialnya terisi & aktif di bawah."
      >
        <select
          value={activeProvider}
          onChange={(e) => setActiveProvider(e.target.value as PlatformProvider)}
          disabled={!manage}
        >
          {PLATFORM_PROVIDERS.map((p) => (
            <option key={p} value={p}>
              {PLATFORM_PROVIDER_LABEL[p]}
            </option>
          ))}
        </select>
      </FormRow>

      {!activeReady && (
        <Callout>
          <strong>{PLATFORM_PROVIDER_LABEL[activeProvider]}</strong> belum siap — aktifkan &amp; isi kredensialnya di
          kartu penyedia di bawah, kalau tidak penerbitan tagihan langganan akan gagal membuat tautan bayar.
        </Callout>
      )}

      <div className="hr" />

      <FormRow
        label="Harga bulanan default (Rp)"
        hint="Biaya langganan bulanan yang sama untuk semua tenant. Saat onboarding tenant baru, super-admin bisa menimpanya jadi harga khusus."
      >
        <input
          type="number"
          min={0}
          step={1000}
          value={monthlyFee}
          onChange={(e) => setMonthlyFee(e.target.value)}
          disabled={!manage}
        />
      </FormRow>

      <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
        <label style={{ flex: 1, minWidth: 140 }}>
          <span>Jatuh tempo (hari)</span>
          <input
            type="number"
            min={0}
            max={90}
            value={dueDays}
            onChange={(e) => setDueDays(e.target.value)}
            disabled={!manage}
          />
        </label>
        <label style={{ flex: 1, minWidth: 140 }}>
          <span>Masa tenggang (hari)</span>
          <input
            type="number"
            min={0}
            max={90}
            value={graceDays}
            onChange={(e) => setGraceDays(e.target.value)}
            disabled={!manage}
          />
        </label>
        <label style={{ flex: 1, minWidth: 120 }}>
          <span>Tanggal tagih</span>
          <input
            type="number"
            min={1}
            max={28}
            value={billingDay}
            onChange={(e) => setBillingDay(e.target.value)}
            disabled={!manage}
          />
        </label>
        <label style={{ flex: 1, minWidth: 100 }}>
          <span>Mata uang</span>
          <input
            value={currency}
            onChange={(e) => setCurrency(e.target.value)}
            maxLength={3}
            disabled={!manage}
          />
        </label>
      </div>
      <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
        Jatuh tempo = umur tagihan sejak terbit; masa tenggang = jeda setelah jatuh tempo sebelum tenant
        di-suspend otomatis. Tanggal tagih = hari penerbitan tagihan tiap bulan.
      </p>

      {manage && (
        <>
          <div className="hr" />
          <div className="row" style={{ justifyContent: 'flex-end' }}>
            <button className="primary" onClick={() => void save()} disabled={!dirty || saving}>
              {saving ? 'Menyimpan…' : 'Simpan setelan global'}
            </button>
          </div>
        </>
      )}
    </div>
  )
}

/** Kartu satu penyedia: saklar aktif + kredensial + URL webhook untuk disalin ke dashboard. */
function GatewayCard({
  gateway,
  active,
  manage,
  onSaved,
}: {
  gateway: PlatformGatewayView
  active: boolean
  manage: boolean
  onSaved: (s: PlatformBillingSettingsView) => void
}) {
  const toast = useToast()
  const fields = useMemo(() => credFields(gateway.provider), [gateway.provider])
  const [enabled, setEnabled] = useState(gateway.enabled)
  const [paymentMethod, setPaymentMethod] = useState(gateway.paymentMethod ?? '')
  const [creds, setCreds] = useState<Record<CredKey, string>>({ apiKey: '', secretKey: '', webhookToken: '' })
  const [saving, setSaving] = useState(false)

  // Sinkron ulang saat baris gateway diperbarui dari server (mis. penyedia lain disimpan).
  useEffect(() => {
    setEnabled(gateway.enabled)
    setPaymentMethod(gateway.paymentMethod ?? '')
    setCreds({ apiKey: '', secretKey: '', webhookToken: '' })
  }, [gateway])

  const credDirty = fields.some((f) => creds[f.key].trim() !== '')
  const dirty =
    enabled !== gateway.enabled ||
    (gateway.provider === 'PAYWUZ' && paymentMethod.trim() !== (gateway.paymentMethod ?? '')) ||
    credDirty

  const webhookUrl = `${window.location.origin}/api/platform/billing/webhooks/${gateway.provider.toLowerCase()}`

  const copyWebhook = async () => {
    try {
      await navigator.clipboard.writeText(webhookUrl)
      toast.success('URL webhook disalin')
    } catch {
      toast.error('Gagal menyalin — salin manual dari kolomnya')
    }
  }

  const save = async () => {
    setSaving(true)
    try {
      const result = await updatePlatformGateway(gateway.provider, {
        enabled,
        apiKey: creds.apiKey.trim() || null,
        secretKey: creds.secretKey.trim() || null,
        webhookToken: creds.webhookToken.trim() || null,
        paymentMethod: gateway.provider === 'PAYWUZ' ? paymentMethod.trim() || null : null,
      })
      onSaved(result)
      toast.success(`Kredensial ${PLATFORM_PROVIDER_LABEL[gateway.provider]} disimpan`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan kredensial')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="card stack" style={{ gap: '0.85rem' }} aria-disabled={!manage}>
      <div className="spread" style={{ alignItems: 'center' }}>
        <div className="row" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <strong style={{ fontSize: '0.95rem' }}>{PLATFORM_PROVIDER_LABEL[gateway.provider]}</strong>
          {active && <Badge tone="accent">aktif</Badge>}
          <Badge tone={gateway.credentialsSet ? 'good' : 'neutral'}>
            {gateway.credentialsSet ? 'kredensial terisi' : 'kredensial kosong'}
          </Badge>
        </div>
        <Segmented
          value={enabled ? 'on' : 'off'}
          onChange={(v) => setEnabled(v === 'on')}
          disabled={!manage}
          options={[
            { value: 'off', label: 'Nonaktif' },
            { value: 'on', label: 'Aktif' },
          ]}
        />
      </div>

      {fields.map((f) => (
        <label key={f.key}>
          <span>
            {f.label} {isCredSet(gateway, f.key) && <span className="muted">· tersimpan</span>}
          </span>
          <input
            type="password"
            autoComplete="new-password"
            value={creds[f.key]}
            onChange={(e) => setCreds((c) => ({ ...c, [f.key]: e.target.value }))}
            placeholder={isCredSet(gateway, f.key) ? 'Biarkan kosong untuk mempertahankan' : f.placeholder}
            disabled={!manage}
          />
        </label>
      ))}

      {gateway.provider === 'PAYWUZ' && (
        <label>
          <span>Metode pembayaran</span>
          <select value={paymentMethod} onChange={(e) => setPaymentMethod(e.target.value)} disabled={!manage}>
            <option value="">Default server (QRIS)</option>
            <option value="QRIS">QRIS</option>
            <option value="VA">Virtual Account (Pilih Bank)</option>
          </select>
        </label>
      )}

      <WebhookField url={webhookUrl} onCopy={() => void copyWebhook()} provider={gateway.provider} />

      {manage && (
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          <button className="primary" onClick={() => void save()} disabled={!dirty || saving}>
            {saving ? 'Menyimpan…' : 'Simpan'}
          </button>
        </div>
      )}
    </div>
  )
}

function WebhookField({ url, onCopy, provider }: { url: string; onCopy: () => void; provider: PlatformProvider }) {
  return (
    <div className="stack" style={{ gap: '0.4rem' }}>
      <span style={{ fontSize: '0.9rem', fontWeight: 600 }}>URL webhook</span>
      <div className="row" style={{ gap: '0.5rem', alignItems: 'stretch' }}>
        <input value={url} readOnly onFocus={(e) => e.target.select()} style={{ flex: 1, fontFamily: 'monospace', fontSize: '0.82rem' }} />
        <button type="button" className="ghost" onClick={onCopy} style={{ whiteSpace: 'nowrap' }}>
          Salin
        </button>
      </div>
      <span className="muted" style={{ fontSize: '0.82rem' }}>
        Tempel ke menu Callback/Webhook di dashboard {PLATFORM_PROVIDER_LABEL[provider]} agar pelunasan
        langganan otomatis masuk ke sistem.
      </span>
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
