import { useEffect, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import {
  getPivotMasterConfig,
  getPlatformBillingSettings,
  PLATFORM_FEE_TYPE_LABEL,
  updatePivotMasterConfig,
  updatePlatformSettings,
  type PivotMasterConfigView,
  type PlatformBillingSettingsView,
  type PlatformFeeType,
} from '../api/platformBilling'
import { useCan } from '../auth/useCan'
import { Badge, EmptyState, useToast } from '../components/ui'
import { IconAlert, IconShield } from '../components/icons'

/**
 * Setelan billing langganan SaaS (level platform) — super-admin mengatur default global (harga/
 * grace/jatuh-tempo) plus akun master Pivot (satu agregator untuk seluruh platform: menagih tenant
 * biaya langganan sekaligus menampung pembayaran pelanggan tiap tenant lewat sub-account).
 *
 * Kredensial Pivot write-only: dikirim saat menyimpan, tak pernah ditarik balik — server hanya
 * menandai sudah terisi (`*Set`). Rahasia null/kosong saat simpan = pertahankan yang tersimpan.
 */

export function PlatformBillingSettingsPage() {
  const { can } = useCan()
  const toast = useToast()
  const manage = can('platform.billing.manage')

  const [settings, setSettings] = useState<PlatformBillingSettingsView | null>(null)
  const [pivot, setPivot] = useState<PivotMasterConfigView | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    Promise.all([
      getPlatformBillingSettings().then(setSettings),
      getPivotMasterConfig()
        .then(setPivot)
        .catch(() => undefined),
    ])
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat setelan billing'))
      .finally(() => setLoading(false))
  }, [toast])

  if (loading) return <p className="muted">Memuat setelan…</p>
  if (!settings) {
    return <EmptyState title="Setelan billing tak tersedia" hint="Coba muat ulang halaman." icon={<IconAlert size={28} />} />
  }

  return (
    <div className="stack settings-page" style={{ gap: '1.5rem' }}>
      <div>
        <h1 className="page-title">Billing Langganan Platform</h1>
        <p className="page-sub">
          Harga langganan default &amp; akun master Pivot untuk menagih tenant memakai aplikasi ini. Setelan
          berlaku global untuk seluruh platform.
        </p>
      </div>

      <GlobalPanel settings={settings} manage={manage} onSaved={setSettings} />

      {pivot && <PivotMasterPanel config={pivot} manage={manage} onSaved={setPivot} />}
    </div>
  )
}

/** Setelan global: default grace/jatuh-tempo/tanggal-tagih/harga/mata-uang. */
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
  const [graceDays, setGraceDays] = useState(String(settings.defaultGraceDays))
  const [dueDays, setDueDays] = useState(String(settings.defaultDueDays))
  const [billingDay, setBillingDay] = useState(String(settings.defaultBillingDay))
  const [monthlyFee, setMonthlyFee] = useState(String(settings.defaultMonthlyFee))
  const [currency, setCurrency] = useState(settings.currency)
  const [saving, setSaving] = useState(false)

  const dirty =
    Number(graceDays) !== settings.defaultGraceDays ||
    Number(dueDays) !== settings.defaultDueDays ||
    Number(billingDay) !== settings.defaultBillingDay ||
    Number(monthlyFee) !== settings.defaultMonthlyFee ||
    currency.trim().toUpperCase() !== settings.currency

  const save = async () => {
    setSaving(true)
    try {
      const result = await updatePlatformSettings({
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
      <div className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
        <IconShield size={16} />
        <strong style={{ fontSize: '0.95rem' }}>Default global</strong>
      </div>

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

const FEE_TYPES: PlatformFeeType[] = ['FIXED', 'PERCENTAGE']

/**
 * Panel akun master Pivot: kredensial (Merchant ID/Secret/Callback API Key) write-only, toggle
 * sandbox & aktif, fee platform, rekening payout platform, plus URL callback SaaS untuk disalin.
 */
function PivotMasterPanel({
  config,
  manage,
  onSaved,
}: {
  config: PivotMasterConfigView
  manage: boolean
  onSaved: (c: PivotMasterConfigView) => void
}) {
  const toast = useToast()
  const [enabled, setEnabled] = useState(config.enabled)
  const [sandbox, setSandbox] = useState(config.sandbox)
  const [merchantId, setMerchantId] = useState('')
  const [merchantSecret, setMerchantSecret] = useState('')
  const [callbackApiKey, setCallbackApiKey] = useState('')
  const [feeMinor, setFeeMinor] = useState(String(config.platformFeeMinor))
  const [feeType, setFeeType] = useState<PlatformFeeType>(config.platformFeeType)
  const [payoutChannel, setPayoutChannel] = useState(config.payoutChannelCode ?? '')
  const [payoutAccount, setPayoutAccount] = useState(config.payoutAccountNumber ?? '')
  const [saving, setSaving] = useState(false)

  // Sinkron ulang saat config diperbarui dari server.
  useEffect(() => {
    setEnabled(config.enabled)
    setSandbox(config.sandbox)
    setMerchantId('')
    setMerchantSecret('')
    setCallbackApiKey('')
    setFeeMinor(String(config.platformFeeMinor))
    setFeeType(config.platformFeeType)
    setPayoutChannel(config.payoutChannelCode ?? '')
    setPayoutAccount(config.payoutAccountNumber ?? '')
  }, [config])

  const credDirty = merchantId.trim() !== '' || merchantSecret.trim() !== '' || callbackApiKey.trim() !== ''
  const dirty =
    enabled !== config.enabled ||
    sandbox !== config.sandbox ||
    Number(feeMinor) !== config.platformFeeMinor ||
    feeType !== config.platformFeeType ||
    payoutChannel.trim() !== (config.payoutChannelCode ?? '') ||
    payoutAccount.trim() !== (config.payoutAccountNumber ?? '') ||
    credDirty

  const callbackUrl = `${window.location.origin}/api/platform/billing/webhooks/pivot`

  const copyCallback = async () => {
    try {
      await navigator.clipboard.writeText(callbackUrl)
      toast.success('URL callback disalin')
    } catch {
      toast.error('Gagal menyalin — salin manual dari kolomnya')
    }
  }

  const save = async () => {
    setSaving(true)
    try {
      const result = await updatePivotMasterConfig({
        enabled,
        sandbox,
        merchantId: merchantId.trim() || null,
        merchantSecret: merchantSecret.trim() || null,
        callbackApiKey: callbackApiKey.trim() || null,
        platformFeeMinor: Number(feeMinor),
        platformFeeType: feeType,
        payoutChannelCode: payoutChannel.trim() || null,
        payoutAccountNumber: payoutAccount.trim() || null,
      })
      onSaved(result)
      toast.success('Konfigurasi Pivot master disimpan')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan konfigurasi')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="stack" style={{ gap: '0.85rem' }}>
      <h2 style={{ margin: 0, fontSize: '1.05rem' }}>Akun Master Pivot</h2>
      {!manage && (
        <p className="muted" style={{ margin: 0 }}>
          Anda hanya bisa melihat. Perlu izin “Kelola gateway billing platform” untuk mengubah.
        </p>
      )}

      {/* Kredensial + status */}
      <div className="card stack" style={{ gap: '0.85rem' }} aria-disabled={!manage}>
        <div className="spread" style={{ alignItems: 'center' }}>
          <div className="row" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <strong style={{ fontSize: '0.95rem' }}>Kredensial &amp; status</strong>
            <Badge tone={sandbox ? 'warning' : 'accent'}>{sandbox ? 'sandbox' : 'produksi'}</Badge>
            <Badge tone={config.credentialsSet ? 'good' : 'neutral'}>
              {config.credentialsSet ? 'kredensial terisi' : 'kredensial kosong'}
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

        <FormRow label="Mode" hint="Sandbox untuk uji coba; produksi untuk transaksi sungguhan.">
          <Segmented
            value={sandbox ? 'sandbox' : 'prod'}
            onChange={(v) => setSandbox(v === 'sandbox')}
            disabled={!manage}
            options={[
              { value: 'prod', label: 'Produksi' },
              { value: 'sandbox', label: 'Sandbox' },
            ]}
          />
        </FormRow>

        <label>
          <span>
            Merchant ID {config.merchantIdSet && <span className="muted">· tersimpan</span>}
          </span>
          <input
            type="password"
            autoComplete="new-password"
            value={merchantId}
            onChange={(e) => setMerchantId(e.target.value)}
            placeholder={config.merchantIdSet ? 'Biarkan kosong untuk mempertahankan' : 'X-MERCHANT-ID dari dashboard Pivot'}
            disabled={!manage}
          />
        </label>
        <label>
          <span>
            Merchant Secret {config.merchantSecretSet && <span className="muted">· tersimpan</span>}
          </span>
          <input
            type="password"
            autoComplete="new-password"
            value={merchantSecret}
            onChange={(e) => setMerchantSecret(e.target.value)}
            placeholder={config.merchantSecretSet ? 'Biarkan kosong untuk mempertahankan' : 'X-MERCHANT-SECRET dari dashboard Pivot'}
            disabled={!manage}
          />
        </label>
        <label>
          <span>
            Callback API Key {config.callbackApiKeySet && <span className="muted">· tersimpan</span>}
          </span>
          <input
            type="password"
            autoComplete="new-password"
            value={callbackApiKey}
            onChange={(e) => setCallbackApiKey(e.target.value)}
            placeholder={config.callbackApiKeySet ? 'Biarkan kosong untuk mempertahankan' : 'X-API-Key untuk verifikasi callback'}
            disabled={!manage}
          />
        </label>

        <WebhookField url={callbackUrl} onCopy={() => void copyCallback()} />
      </div>

      {/* Fee platform */}
      <div className="card stack" style={{ gap: '0.85rem' }} aria-disabled={!manage}>
        <strong style={{ fontSize: '0.95rem' }}>Fee Platform</strong>
        <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
          Potongan platform per transaksi pembayaran pelanggan tenant. Untuk <strong>Nominal tetap</strong> isi
          rupiah (mis. 1000 = Rp1.000); untuk <strong>Persentase</strong> isi angka persen (mis. 2 = 2%).
        </p>
        <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
          <label style={{ flex: 1, minWidth: 180 }}>
            <span>Jenis fee</span>
            <select value={feeType} onChange={(e) => setFeeType(e.target.value as PlatformFeeType)} disabled={!manage}>
              {FEE_TYPES.map((t) => (
                <option key={t} value={t}>
                  {PLATFORM_FEE_TYPE_LABEL[t]}
                </option>
              ))}
            </select>
          </label>
          <label style={{ flex: 1, minWidth: 160 }}>
            <span>{feeType === 'PERCENTAGE' ? 'Nilai (%)' : 'Nilai (Rp)'}</span>
            <input
              type="number"
              min={0}
              step={feeType === 'PERCENTAGE' ? 0.1 : 100}
              value={feeMinor}
              onChange={(e) => setFeeMinor(e.target.value)}
              disabled={!manage}
            />
          </label>
        </div>
      </div>

      {/* Rekening payout platform */}
      <div className="card stack" style={{ gap: '0.85rem' }} aria-disabled={!manage}>
        <strong style={{ fontSize: '0.95rem' }}>Rekening Payout Platform</strong>
        <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
          Rekening tujuan pencairan dana platform (fee terkumpul &amp; penagihan langganan tenant).
        </p>
        <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
          <label style={{ flex: 1, minWidth: 140 }}>
            <span>Kode channel bank</span>
            <input
              value={payoutChannel}
              onChange={(e) => setPayoutChannel(e.target.value)}
              placeholder="mis. BCA, MANDIRI"
              disabled={!manage}
            />
          </label>
          <label style={{ flex: 1, minWidth: 160 }}>
            <span>Nomor rekening</span>
            <input
              value={payoutAccount}
              onChange={(e) => setPayoutAccount(e.target.value)}
              placeholder="mis. 1234567890"
              disabled={!manage}
            />
          </label>
        </div>
      </div>

      {manage && (
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          <button className="primary" onClick={() => void save()} disabled={!dirty || saving}>
            {saving ? 'Menyimpan…' : 'Simpan konfigurasi Pivot'}
          </button>
        </div>
      )}
    </div>
  )
}

function WebhookField({ url, onCopy }: { url: string; onCopy: () => void }) {
  return (
    <div className="stack" style={{ gap: '0.4rem' }}>
      <span style={{ fontSize: '0.9rem', fontWeight: 600 }}>URL callback</span>
      <div className="row" style={{ gap: '0.5rem', alignItems: 'stretch' }}>
        <input value={url} readOnly onFocus={(e) => e.target.select()} style={{ flex: 1, fontFamily: 'monospace', fontSize: '0.82rem' }} />
        <button type="button" className="ghost" onClick={onCopy} style={{ whiteSpace: 'nowrap' }}>
          Salin
        </button>
      </div>
      <span className="muted" style={{ fontSize: '0.82rem' }}>
        Tempel sebagai Callback URL di dashboard Pivot agar pelunasan langganan tenant otomatis masuk ke sistem.
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
