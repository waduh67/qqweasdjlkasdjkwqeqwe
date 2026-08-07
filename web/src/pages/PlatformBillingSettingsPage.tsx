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
import { Combobox } from '../components/Combobox'
import { IconAlert, IconShield } from '../components/icons'
import {
  childrenOfIndustry,
  districtNameById,
  mccForIndustry,
  PIVOT_BUSINESS_STRUCTURES,
  PIVOT_COUNTRIES,
  PIVOT_PARENT_INDUSTRIES,
  searchDistricts,
} from '../data/pivotReference'

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
 * Panel akun master Pivot: kredensial (Client ID/Client Secret/Callback Secret — sesuai label
 * dashboard Pivot) write-only, toggle sandbox & aktif, fee platform, rekening payout platform,
 * default sub-account, plus URL callback SaaS untuk disalin.
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
  const [defaults, setDefaults] = useState<SubAccountDefaultsForm>(() => defaultsFromConfig(config))
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
    setDefaults(defaultsFromConfig(config))
  }, [config])

  const credDirty = merchantId.trim() !== '' || merchantSecret.trim() !== '' || callbackApiKey.trim() !== ''
  const savedDefaults = defaultsFromConfig(config)
  const defaultsDirty = (Object.keys(defaults) as (keyof SubAccountDefaultsForm)[]).some(
    (k) => defaults[k].trim() !== savedDefaults[k].trim(),
  )
  const dirty =
    enabled !== config.enabled ||
    sandbox !== config.sandbox ||
    Number(feeMinor) !== config.platformFeeMinor ||
    feeType !== config.platformFeeType ||
    payoutChannel.trim() !== (config.payoutChannelCode ?? '') ||
    payoutAccount.trim() !== (config.payoutAccountNumber ?? '') ||
    defaultsDirty ||
    credDirty

  const copyUrl = async (url: string) => {
    try {
      await navigator.clipboard.writeText(url)
      toast.success('URL callback disalin')
    } catch {
      toast.error('Gagal menyalin — salin manual dari kolomnya')
    }
  }

  const save = async () => {
    setSaving(true)
    try {
      const districtId = defaults.defaultDistrictId.trim()
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
        defaultBusinessType: defaults.defaultBusinessType.trim() || null,
        defaultBusinessStructure: defaults.defaultBusinessStructure.trim() || null,
        defaultParentIndustry: defaults.defaultParentIndustry.trim() || null,
        defaultChildIndustry: defaults.defaultChildIndustry.trim() || null,
        defaultMcc: defaults.defaultMcc.trim() || null,
        defaultDigitalStatus: defaults.defaultDigitalStatus.trim() || null,
        defaultBusinessCountry: defaults.defaultBusinessCountry.trim() || null,
        defaultCountryOfEntity: defaults.defaultCountryOfEntity.trim() || null,
        defaultLogoUrl: defaults.defaultLogoUrl.trim() || null,
        defaultWebsite: defaults.defaultWebsite.trim() || null,
        defaultDistrictId: districtId ? Number(districtId) : null,
        defaultPostCode: defaults.defaultPostCode.trim() || null,
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
            Client ID {config.merchantIdSet && <span className="muted">· tersimpan</span>}
          </span>
          <input
            type="password"
            autoComplete="new-password"
            value={merchantId}
            onChange={(e) => setMerchantId(e.target.value)}
            placeholder={config.merchantIdSet ? 'Biarkan kosong untuk mempertahankan' : 'Client ID dashboard Pivot (dikirim sebagai X-MERCHANT-ID)'}
            disabled={!manage}
          />
        </label>
        <label>
          <span>
            Client Secret {config.merchantSecretSet && <span className="muted">· tersimpan</span>}
          </span>
          <input
            type="password"
            autoComplete="new-password"
            value={merchantSecret}
            onChange={(e) => setMerchantSecret(e.target.value)}
            placeholder={config.merchantSecretSet ? 'Biarkan kosong untuk mempertahankan' : 'Client Secret dashboard Pivot (dikirim sebagai X-MERCHANT-SECRET)'}
            disabled={!manage}
          />
        </label>
        <label>
          <span>
            Callback Secret {config.callbackApiKeySet && <span className="muted">· tersimpan</span>}
          </span>
          <input
            type="password"
            autoComplete="new-password"
            value={callbackApiKey}
            onChange={(e) => setCallbackApiKey(e.target.value)}
            placeholder={config.callbackApiKeySet ? 'Biarkan kosong untuk mempertahankan' : 'Callback Secret untuk verifikasi header X-API-Key'}
            disabled={!manage}
          />
        </label>

        <PivotCallbackUrls onCopy={(url) => void copyUrl(url)} />
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

      {/* Default sub-account (field wajib create sub-merchant yang sama untuk semua tenant) */}
      <SubAccountDefaultsPanel defaults={defaults} onChange={setDefaults} manage={manage} />

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

/**
 * Nilai default field wajib `POST /v1/sub-merchants` yang SAMA untuk semua tenant (referensi
 * bisnis/industri). Diisi sekali oleh super-admin; digabung dengan profil spesifik-tenant saat
 * provisioning. Semua string agar input terkontrol — `defaultDistrictId` dikonversi ke angka saat simpan.
 */
interface SubAccountDefaultsForm {
  defaultBusinessType: string
  defaultBusinessStructure: string
  defaultParentIndustry: string
  defaultChildIndustry: string
  defaultMcc: string
  defaultDigitalStatus: string
  defaultBusinessCountry: string
  defaultCountryOfEntity: string
  defaultLogoUrl: string
  defaultWebsite: string
  defaultDistrictId: string
  defaultPostCode: string
}

/** Ambil nilai default dari config server → bentuk form (null → string kosong). */
function defaultsFromConfig(c: PivotMasterConfigView): SubAccountDefaultsForm {
  return {
    defaultBusinessType: c.defaultBusinessType ?? '',
    defaultBusinessStructure: c.defaultBusinessStructure ?? '',
    defaultParentIndustry: c.defaultParentIndustry ?? '',
    defaultChildIndustry: c.defaultChildIndustry ?? '',
    defaultMcc: c.defaultMcc ?? '',
    defaultDigitalStatus: c.defaultDigitalStatus ?? '',
    defaultBusinessCountry: c.defaultBusinessCountry ?? '',
    defaultCountryOfEntity: c.defaultCountryOfEntity ?? '',
    defaultLogoUrl: c.defaultLogoUrl ?? '',
    defaultWebsite: c.defaultWebsite ?? '',
    defaultDistrictId: c.defaultDistrictId != null ? String(c.defaultDistrictId) : '',
    defaultPostCode: c.defaultPostCode ?? '',
  }
}

const BUSINESS_TYPE_OPTIONS = ['INDIVIDUAL', 'COMPANY']
const DIGITAL_STATUS_OPTIONS = ['Digital', 'Non-digital']

/**
 * Panel default sub-account: field berdaftar-nilai dipilih lewat dropdown (tipe/status/struktur
 * bisnis, industri induk→anak, negara) dan district lewat combobox pencari — supaya super-admin
 * tak salah ketik nilai referensi Pivot (mis. "PT" vs "PERSEROAN TERBATAS", atau MCC yang tak
 * cocok pasangannya). MCC terisi otomatis dari anak industri. Field bebas (kode pos, URL) tetap
 * input. Diisi sekali; provisioning tenant menggabungnya dengan profil spesifik-tenant.
 */
function SubAccountDefaultsPanel({
  defaults,
  onChange,
  manage,
}: {
  defaults: SubAccountDefaultsForm
  onChange: (updater: (d: SubAccountDefaultsForm) => SubAccountDefaultsForm) => void
  manage: boolean
}) {
  const set = (patch: Partial<SubAccountDefaultsForm>) => onChange((d) => ({ ...d, ...patch }))

  // Resolusi nama district untuk nilai tersimpan (data district di-lazy-load): null = memuat,
  // '' = kosong. Combobox baru dirender setelah label siap supaya labelnya benar sejak awal.
  const [districtLabel, setDistrictLabel] = useState<string | null>(null)
  useEffect(() => {
    const id = Number(defaults.defaultDistrictId)
    if (!id) {
      setDistrictLabel('')
      return
    }
    let alive = true
    districtNameById(id).then((name) => {
      if (alive) setDistrictLabel(name ?? `#${id}`)
    })
    return () => {
      alive = false
    }
    // Sekali saat mount: seed label dari nilai tersimpan; pilihan berikutnya dikelola Combobox.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Pertahankan nilai tersimpan yang tak ada di daftar (mis. data lama) sebagai opsi tambahan
  // agar tak diam-diam hilang dari tampilan.
  const withCurrent = (options: readonly string[], current: string): string[] =>
    current && !options.includes(current) ? [current, ...options] : [...options]

  const childOptions = childrenOfIndustry(defaults.defaultParentIndustry)

  return (
    <div className="card stack" style={{ gap: '0.85rem' }} aria-disabled={!manage}>
      <strong style={{ fontSize: '0.95rem' }}>Default Sub-account</strong>
      <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
        Data bisnis/industri yang sama untuk semua sub-account tenant (dipakai saat mendaftarkan
        sub-account ke Pivot). Nilai referensi (industri, struktur bisnis, negara, district) dipilih
        dari daftar Pivot agar tak salah ketik; MCC terisi otomatis dari anak industri.
      </p>

      <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
        <label style={{ flex: 1, minWidth: 160 }}>
          <span>Tipe bisnis</span>
          <select
            value={defaults.defaultBusinessType}
            onChange={(e) => set({ defaultBusinessType: e.target.value })}
            disabled={!manage}
          >
            <option value="">— pilih —</option>
            {BUSINESS_TYPE_OPTIONS.map((o) => (
              <option key={o} value={o}>
                {o}
              </option>
            ))}
          </select>
        </label>
        <label style={{ flex: 1, minWidth: 160 }}>
          <span>Status digital</span>
          <select
            value={defaults.defaultDigitalStatus}
            onChange={(e) => set({ defaultDigitalStatus: e.target.value })}
            disabled={!manage}
          >
            <option value="">— pilih —</option>
            {DIGITAL_STATUS_OPTIONS.map((o) => (
              <option key={o} value={o}>
                {o}
              </option>
            ))}
          </select>
        </label>
        <label style={{ flex: 1, minWidth: 160 }}>
          <span>Struktur bisnis</span>
          <select
            value={defaults.defaultBusinessStructure}
            onChange={(e) => set({ defaultBusinessStructure: e.target.value })}
            disabled={!manage}
          >
            <option value="">— pilih —</option>
            {withCurrent(PIVOT_BUSINESS_STRUCTURES, defaults.defaultBusinessStructure).map((o) => (
              <option key={o} value={o}>
                {o}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
        <label style={{ flex: 1, minWidth: 160 }}>
          <span>Industri induk</span>
          <select
            value={defaults.defaultParentIndustry}
            onChange={(e) =>
              // Ganti induk → reset anak & MCC (pasangan lama tak lagi valid).
              set({ defaultParentIndustry: e.target.value, defaultChildIndustry: '', defaultMcc: '' })
            }
            disabled={!manage}
          >
            <option value="">— pilih —</option>
            {withCurrent(PIVOT_PARENT_INDUSTRIES, defaults.defaultParentIndustry).map((o) => (
              <option key={o} value={o}>
                {o}
              </option>
            ))}
          </select>
        </label>
        <label style={{ flex: 1, minWidth: 160 }}>
          <span>Industri anak</span>
          <select
            value={defaults.defaultChildIndustry}
            onChange={(e) =>
              // Pilih anak → MCC terisi otomatis dari pasangan induk+anak.
              set({
                defaultChildIndustry: e.target.value,
                defaultMcc: mccForIndustry(defaults.defaultParentIndustry, e.target.value) ?? '',
              })
            }
            disabled={!manage || !defaults.defaultParentIndustry}
          >
            <option value="">{defaults.defaultParentIndustry ? '— pilih —' : 'pilih induk dahulu'}</option>
            {childOptions.map((c) => (
              <option key={c.child} value={c.child}>
                {c.child}
              </option>
            ))}
            {/* Nilai tersimpan yang tak ada di daftar anak induk terpilih tetap tampil. */}
            {defaults.defaultChildIndustry &&
              !childOptions.some((c) => c.child === defaults.defaultChildIndustry) && (
                <option value={defaults.defaultChildIndustry}>{defaults.defaultChildIndustry}</option>
              )}
          </select>
        </label>
        <label style={{ flex: 1, minWidth: 120 }}>
          <span>MCC</span>
          <input
            value={defaults.defaultMcc}
            readOnly
            placeholder="otomatis dari industri"
            title="Terisi otomatis dari anak industri"
            disabled={!manage}
          />
        </label>
      </div>

      <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
        <label style={{ flex: 1, minWidth: 120 }}>
          <span>Negara bisnis</span>
          <select
            value={defaults.defaultBusinessCountry}
            onChange={(e) => set({ defaultBusinessCountry: e.target.value })}
            disabled={!manage}
          >
            <option value="">— pilih —</option>
            {PIVOT_COUNTRIES.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name} ({c.code})
              </option>
            ))}
            {defaults.defaultBusinessCountry &&
              !PIVOT_COUNTRIES.some((c) => c.code === defaults.defaultBusinessCountry) && (
                <option value={defaults.defaultBusinessCountry}>{defaults.defaultBusinessCountry}</option>
              )}
          </select>
        </label>
        <label style={{ flex: 1, minWidth: 120 }}>
          <span>Negara entitas</span>
          <select
            value={defaults.defaultCountryOfEntity}
            onChange={(e) => set({ defaultCountryOfEntity: e.target.value })}
            disabled={!manage}
          >
            <option value="">— pilih —</option>
            {PIVOT_COUNTRIES.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name} ({c.code})
              </option>
            ))}
            {defaults.defaultCountryOfEntity &&
              !PIVOT_COUNTRIES.some((c) => c.code === defaults.defaultCountryOfEntity) && (
                <option value={defaults.defaultCountryOfEntity}>{defaults.defaultCountryOfEntity}</option>
              )}
          </select>
        </label>
        <label style={{ flex: 1, minWidth: 200 }}>
          <span>District</span>
          {districtLabel === null ? (
            // Tunggu label district ter-resolusi dari nilai tersimpan sebelum merender Combobox,
            // supaya kolomnya tak sempat menampilkan id mentah lalu berkedip ke nama.
            <input value="Memuat…" readOnly disabled />
          ) : (
            <Combobox
              value={defaults.defaultDistrictId}
              initialLabel={districtLabel}
              onChange={(id) => set({ defaultDistrictId: id })}
              fetchOptions={(t) => searchDistricts(t)}
              toId={(d) => String(d.id)}
              toLabel={(d) => d.name}
              toMeta={(d) => `ID ${d.id}`}
              debounceMs={0}
              placeholder="Cari district…"
              disabled={!manage}
            />
          )}
        </label>
        <label style={{ flex: 1, minWidth: 120 }}>
          <span>Kode pos</span>
          <input
            value={defaults.defaultPostCode}
            onChange={(e) => set({ defaultPostCode: e.target.value })}
            placeholder="mis. 40111"
            maxLength={20}
            disabled={!manage}
          />
        </label>
      </div>

      <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
        <label style={{ flex: 1, minWidth: 200 }}>
          <span>Website</span>
          <input
            value={defaults.defaultWebsite}
            onChange={(e) => set({ defaultWebsite: e.target.value })}
            placeholder="https://…"
            disabled={!manage}
          />
        </label>
        <label style={{ flex: 1, minWidth: 200 }}>
          <span>URL logo</span>
          <input
            value={defaults.defaultLogoUrl}
            onChange={(e) => set({ defaultLogoUrl: e.target.value })}
            placeholder="https://…/logo.png"
            disabled={!manage}
          />
        </label>
      </div>
    </div>
  )
}

/**
 * Callback Pivot didaftarkan **per produk** di akun master: satu URL "Create URL" per produk di
 * dashboard Pivot. Backend meng-expose satu endpoint platform per produk di bawah
 * `/api/platform/pivot/callbacks/*` — semuanya diverifikasi header `X-API-Key` (Callback Secret
 * master yang sama).
 */
const PIVOT_CALLBACK_PRODUCTS: { label: string; product: string; path: string }[] = [
  { label: 'Pembayaran', product: 'PAYMENT', path: '/api/platform/pivot/callbacks/payment' },
  { label: 'Payout', product: 'PAYOUT', path: '/api/platform/pivot/callbacks/payout' },
  { label: 'Withdrawal / Penarikan', product: 'WITHDRAWAL', path: '/api/platform/pivot/callbacks/withdrawal' },
  { label: 'Payout Internasional', product: 'INTERNATIONAL_PAYOUT', path: '/api/platform/pivot/callbacks/international-payout' },
  { label: 'Refund', product: 'REFUND', path: '/api/platform/pivot/callbacks/refund' },
  { label: 'Registrasi Sub-account', product: 'SUB_ACCOUNT_REGISTRATION', path: '/api/platform/pivot/callbacks/sub-account-registration' },
  { label: 'Wallet', product: 'WALLET', path: '/api/platform/pivot/callbacks/wallet' },
  { label: 'Wallets', product: 'WALLETS', path: '/api/platform/pivot/callbacks/wallets' },
  { label: 'Aktivasi Linkage Wallet', product: 'WALLET_ACCOUNT_LINKAGE_ACTIVATION', path: '/api/platform/pivot/callbacks/wallet-account-linkage-activation' },
  { label: 'Aktivasi User Wallet', product: 'WALLET_USER_ACTIVATION', path: '/api/platform/pivot/callbacks/wallet-user-activation' },
]

function PivotCallbackUrls({ onCopy }: { onCopy: (url: string) => void }) {
  const origin = window.location.origin
  return (
    <div className="stack" style={{ gap: '0.5rem' }}>
      <span style={{ fontSize: '0.9rem', fontWeight: 600 }}>URL Callback Pivot (per produk)</span>
      <span className="muted" style={{ fontSize: '0.82rem' }}>
        Akun master Pivot mendaftarkan satu Callback URL per produk. Tempel tiap URL di bawah ke
        kolom “Create URL” produk yang cocok pada dashboard Pivot. Semua produk memakai Callback
        Secret yang sama untuk verifikasi header <code>X-API-Key</code>.
      </span>
      <div className="stack" style={{ gap: '0.4rem' }}>
        {PIVOT_CALLBACK_PRODUCTS.map(({ label, product, path }) => {
          const url = `${origin}${path}`
          return (
            <div key={product} className="stack" style={{ gap: '0.25rem' }}>
              <span style={{ fontSize: '0.82rem', fontWeight: 600 }}>
                {label} <span className="muted" style={{ fontWeight: 400 }}>· {product}</span>
              </span>
              <div className="row" style={{ gap: '0.5rem', alignItems: 'stretch' }}>
                <input
                  value={url}
                  readOnly
                  onFocus={(e) => e.target.select()}
                  style={{ flex: 1, fontFamily: 'monospace', fontSize: '0.82rem' }}
                />
                <button type="button" className="ghost" onClick={() => onCopy(url)} style={{ whiteSpace: 'nowrap' }}>
                  Salin
                </button>
              </div>
            </div>
          )
        })}
      </div>
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
