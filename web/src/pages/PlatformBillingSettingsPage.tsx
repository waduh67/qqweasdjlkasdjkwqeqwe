import { useEffect, useState, type ReactNode } from 'react'
import { Text, tokens } from '@fluentui/react-components'

const monospaceToken = `font${'FamilyMonospace'}` satisfies keyof typeof tokens
const monospaceFont = tokens[monospaceToken]
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
import { Badge, Button, EmptyState, Segmented, SelectField, TextField } from '@/components/atoms'
import { useToast } from '@/system'
import { Combobox } from '@/components/molecules'
import { PageHeader } from '@/components/molecules'
import { IconAlert, IconShield } from '@/components/atoms/icons'
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

  if (loading) return <Text as="p" className="muted">Memuat setelan…</Text>
  if (!settings) {
    return <EmptyState title="Setelan billing tak tersedia" hint="Coba muat ulang halaman." icon={<IconAlert size={28} />} />
  }

  return (
    <div className="stack settings-page" style={{ gap: '1.5rem' }}>
      <PageHeader
        title="Billing Langganan Platform"
        subtitle="Harga langganan default & akun master Pivot untuk menagih tenant memakai aplikasi ini. Setelan berlaku global untuk seluruh platform."
      />

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
        <Text as="strong" size={400} weight="semibold">Default global</Text>
      </div>

      <FormRow
        label="Harga bulanan default (Rp)"
        hint="Biaya langganan bulanan yang sama untuk semua tenant. Saat onboarding tenant baru, super-admin bisa menimpanya jadi harga khusus."
      >
        <TextField
          type="number"
          min={0}
          step={1000}
          value={monthlyFee}
          onChange={(_, data) => setMonthlyFee(data.value)}
          disabled={!manage}
        />
      </FormRow>

      <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
        <TextField
          label="Jatuh tempo (hari)"
          type="number"
          min={0}
          max={90}
          value={dueDays}
          onChange={(_, data) => setDueDays(data.value)}
          disabled={!manage}
          style={{ flex: 1, minWidth: 140 }}
        />
        <TextField
          label="Masa tenggang (hari)"
          type="number"
          min={0}
          max={90}
          value={graceDays}
          onChange={(_, data) => setGraceDays(data.value)}
          disabled={!manage}
          style={{ flex: 1, minWidth: 140 }}
        />
        <TextField
          label="Tanggal tagih"
          type="number"
          min={1}
          max={28}
          value={billingDay}
          onChange={(_, data) => setBillingDay(data.value)}
          disabled={!manage}
          style={{ flex: 1, minWidth: 120 }}
        />
        <TextField
          label="Mata uang"
          value={currency}
          onChange={(_, data) => setCurrency(data.value)}
          maxLength={3}
          disabled={!manage}
          style={{ flex: 1, minWidth: 100 }}
        />
      </div>
      <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
        Jatuh tempo = umur tagihan sejak terbit; masa tenggang = jeda setelah jatuh tempo sebelum tenant
        di-suspend otomatis. Tanggal tagih = hari penerbitan tagihan tiap bulan.
      </Text>

      {manage && (
        <>
          <div className="hr" />
          <div className="row" style={{ justifyContent: 'flex-end' }}>
            <Button variant="primary" onClick={() => void save()} disabled={!dirty || saving}>
              {saving ? 'Menyimpan…' : 'Simpan setelan global'}
            </Button>
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
  const [payoutFeeMinor, setPayoutFeeMinor] = useState(String(config.payoutFeeMinor))
  const [payoutFeeType, setPayoutFeeType] = useState<PlatformFeeType>(config.payoutFeeType)
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
    setPayoutFeeMinor(String(config.payoutFeeMinor))
    setPayoutFeeType(config.payoutFeeType)
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
    Number(payoutFeeMinor) !== config.payoutFeeMinor ||
    payoutFeeType !== config.payoutFeeType ||
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
        payoutFeeMinor: Number(payoutFeeMinor),
        payoutFeeType: payoutFeeType,
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
      <Text as="h2" size={500} weight="semibold" style={{ margin: 0 }}>Akun Master Pivot</Text>
      {!manage && (
        <Text as="p" className="muted" style={{ margin: 0 }}>
          Anda hanya bisa melihat. Perlu izin “Kelola gateway billing platform” untuk mengubah.
        </Text>
      )}

      {/* Kredensial + status */}
      <div className="card stack" style={{ gap: '0.85rem' }} aria-disabled={!manage}>
        <div className="spread" style={{ alignItems: 'center' }}>
          <div className="row" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <Text as="strong" size={400} weight="semibold">Kredensial &amp; status</Text>
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

        <TextField
          label={<>Client ID {config.merchantIdSet && <Text as="span" className="muted">· tersimpan</Text>}</>}
          type="password"
          autoComplete="new-password"
          value={merchantId}
          onChange={(_, data) => setMerchantId(data.value)}
          placeholder={config.merchantIdSet ? 'Biarkan kosong untuk mempertahankan' : 'Client ID dashboard Pivot (dikirim sebagai X-MERCHANT-ID)'}
          disabled={!manage}
        />
        <TextField
          label={<>Client Secret {config.merchantSecretSet && <Text as="span" className="muted">· tersimpan</Text>}</>}
          type="password"
          autoComplete="new-password"
          value={merchantSecret}
          onChange={(_, data) => setMerchantSecret(data.value)}
          placeholder={config.merchantSecretSet ? 'Biarkan kosong untuk mempertahankan' : 'Client Secret dashboard Pivot (dikirim sebagai X-MERCHANT-SECRET)'}
          disabled={!manage}
        />
        <TextField
          label={<>Callback Secret {config.callbackApiKeySet && <Text as="span" className="muted">· tersimpan</Text>}</>}
          type="password"
          autoComplete="new-password"
          value={callbackApiKey}
          onChange={(_, data) => setCallbackApiKey(data.value)}
          placeholder={config.callbackApiKeySet ? 'Biarkan kosong untuk mempertahankan' : 'Callback Secret untuk verifikasi header X-API-Key'}
          disabled={!manage}
        />

        <PivotCallbackUrls onCopy={(url) => void copyUrl(url)} />
      </div>

      {/* Fee platform */}
      <div className="card stack" style={{ gap: '0.85rem' }} aria-disabled={!manage}>
        <Text as="strong" size={400} weight="semibold">Fee Platform</Text>
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Potongan platform per transaksi pembayaran pelanggan tenant. Untuk <Text as="strong" weight="semibold" >Nominal tetap</Text> isi
          rupiah (mis. 1000 = Rp1.000); untuk <Text as="strong" weight="semibold" >Persentase</Text> isi angka persen (mis. 2 = 2%).
        </Text>
        <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
          <SelectField
            label="Jenis fee"
            value={feeType}
            onChange={(_, data) => setFeeType(data.value as PlatformFeeType)}
            disabled={!manage}
            style={{ flex: 1, minWidth: 180 }}
          >
            {FEE_TYPES.map((t) => (
              <option key={t} value={t}>
                {PLATFORM_FEE_TYPE_LABEL[t]}
              </option>
            ))}
          </SelectField>
          <TextField
            label={feeType === 'PERCENTAGE' ? 'Nilai (%)' : 'Nilai (Rp)'}
            type="number"
            min={0}
            step={feeType === 'PERCENTAGE' ? 0.1 : 100}
            value={feeMinor}
            onChange={(_, data) => setFeeMinor(data.value)}
            disabled={!manage}
            style={{ flex: 1, minWidth: 160 }}
          />
        </div>
      </div>

      {/* Biaya payout */}
      <div className="card stack" style={{ gap: '0.85rem' }} aria-disabled={!manage}>
        <Text as="strong" size={400} weight="semibold">Biaya Payout</Text>
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Potongan per <Text as="strong" weight="semibold" >penyaluran dana tenant</Text> — beda dari Fee Platform yang dipotong dari
          pembayaran pelanggan. Pivot menagih biaya tiap payout ke saldo master platform, jadi selama ini
          <Text as="strong" weight="semibold" > platform yang menanggung</Text>. Isi sesuai tarif Pivot (sandbox: Rp 4.000) supaya
          balik modal, atau lebih besar bila mau ambil margin. <Text as="strong" weight="semibold" >0 = platform tetap menanggung.</Text></Text>
        <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
          <SelectField
            label="Jenis biaya"
            value={payoutFeeType}
            onChange={(_, data) => setPayoutFeeType(data.value as PlatformFeeType)}
            disabled={!manage}
            style={{ flex: 1, minWidth: 180 }}
          >
            {FEE_TYPES.map((t) => (
              <option key={t} value={t}>
                {PLATFORM_FEE_TYPE_LABEL[t]}
              </option>
            ))}
          </SelectField>
          <TextField
            label={payoutFeeType === 'PERCENTAGE' ? 'Nilai (%)' : 'Nilai (Rp)'}
            type="number"
            min={0}
            step={payoutFeeType === 'PERCENTAGE' ? 0.1 : 500}
            value={payoutFeeMinor}
            onChange={(_, data) => setPayoutFeeMinor(data.value)}
            disabled={!manage}
            style={{ flex: 1, minWidth: 160 }}
          />
        </div>
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Dipotong dari nominal yang diminta tenant: minta Rp 50.000 dengan biaya Rp 4.000 → Rp 46.000 masuk
          rekening tujuan, Rp 4.000 pindah ke saldo platform.
        </Text>
      </div>

      {/* Rekening payout platform */}
      <div className="card stack" style={{ gap: '0.85rem' }} aria-disabled={!manage}>
        <Text as="strong" size={400} weight="semibold">Rekening Payout Platform</Text>
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Rekening tujuan pencairan dana platform (fee terkumpul &amp; penagihan langganan tenant).
        </Text>
        <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
          <TextField
            label="Kode channel bank"
            value={payoutChannel}
            onChange={(_, data) => setPayoutChannel(data.value)}
            placeholder="mis. BCA, MANDIRI"
            disabled={!manage}
            style={{ flex: 1, minWidth: 140 }}
          />
          <TextField
            label="Nomor rekening"
            value={payoutAccount}
            onChange={(_, data) => setPayoutAccount(data.value)}
            placeholder="mis. 1234567890"
            disabled={!manage}
            style={{ flex: 1, minWidth: 160 }}
          />
        </div>
      </div>

      {/* Default sub-account (field wajib create sub-merchant yang sama untuk semua tenant) */}
      <SubAccountDefaultsPanel defaults={defaults} onChange={setDefaults} manage={manage} />

      {manage && (
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          <Button variant="primary" onClick={() => void save()} disabled={!dirty || saving}>
            {saving ? 'Menyimpan…' : 'Simpan konfigurasi Pivot'}
          </Button>
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
      <Text as="strong" size={400} weight="semibold">Default Sub-account</Text>
      <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
        Data bisnis/industri yang sama untuk semua sub-account tenant (dipakai saat mendaftarkan
        sub-account ke Pivot). Nilai referensi (industri, struktur bisnis, negara, district) dipilih
        dari daftar Pivot agar tak salah ketik; MCC terisi otomatis dari anak industri.
      </Text>

      <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
        <SelectField
          label="Tipe bisnis"
          value={defaults.defaultBusinessType}
          onChange={(_, data) => set({ defaultBusinessType: data.value })}
          disabled={!manage}
          style={{ flex: 1, minWidth: 160 }}
        >
          <option value="">— pilih —</option>
          {BUSINESS_TYPE_OPTIONS.map((o) => (
            <option key={o} value={o}>
              {o}
            </option>
          ))}
        </SelectField>
        <SelectField
          label="Status digital"
          value={defaults.defaultDigitalStatus}
          onChange={(_, data) => set({ defaultDigitalStatus: data.value })}
          disabled={!manage}
          style={{ flex: 1, minWidth: 160 }}
        >
          <option value="">— pilih —</option>
          {DIGITAL_STATUS_OPTIONS.map((o) => (
            <option key={o} value={o}>
              {o}
            </option>
          ))}
        </SelectField>
        <SelectField
          label="Struktur bisnis"
          value={defaults.defaultBusinessStructure}
          onChange={(_, data) => set({ defaultBusinessStructure: data.value })}
          disabled={!manage}
          style={{ flex: 1, minWidth: 160 }}
        >
          <option value="">— pilih —</option>
          {withCurrent(PIVOT_BUSINESS_STRUCTURES, defaults.defaultBusinessStructure).map((o) => (
            <option key={o} value={o}>
              {o}
            </option>
          ))}
        </SelectField>
      </div>

      <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
        <SelectField
          label="Industri induk"
          value={defaults.defaultParentIndustry}
          onChange={(_, data) =>
            // Ganti induk → reset anak & MCC (pasangan lama tak lagi valid).
            set({ defaultParentIndustry: data.value, defaultChildIndustry: '', defaultMcc: '' })
          }
          disabled={!manage}
          style={{ flex: 1, minWidth: 160 }}
        >
          <option value="">— pilih —</option>
          {withCurrent(PIVOT_PARENT_INDUSTRIES, defaults.defaultParentIndustry).map((o) => (
            <option key={o} value={o}>
              {o}
            </option>
          ))}
        </SelectField>
        <SelectField
          label="Industri anak"
          value={defaults.defaultChildIndustry}
          onChange={(_, data) =>
            // Pilih anak → MCC terisi otomatis dari pasangan induk+anak.
            set({
              defaultChildIndustry: data.value,
              defaultMcc: mccForIndustry(defaults.defaultParentIndustry, data.value) ?? '',
            })
          }
          disabled={!manage || !defaults.defaultParentIndustry}
          style={{ flex: 1, minWidth: 160 }}
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
        </SelectField>
        <TextField
          label="MCC"
          value={defaults.defaultMcc}
          readOnly
          placeholder="otomatis dari industri"
          title="Terisi otomatis dari anak industri"
          disabled={!manage}
          style={{ flex: 1, minWidth: 120 }}
        />
      </div>

      <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
        <SelectField
          label="Negara bisnis"
          value={defaults.defaultBusinessCountry}
          onChange={(_, data) => set({ defaultBusinessCountry: data.value })}
          disabled={!manage}
          style={{ flex: 1, minWidth: 120 }}
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
        </SelectField>
        <SelectField
          label="Negara entitas"
          value={defaults.defaultCountryOfEntity}
          onChange={(_, data) => set({ defaultCountryOfEntity: data.value })}
          disabled={!manage}
          style={{ flex: 1, minWidth: 120 }}
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
        </SelectField>
        <label style={{ flex: 1, minWidth: 200 }}>
          <Text as="span" >District</Text>
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
        <TextField
          label="Kode pos"
          value={defaults.defaultPostCode}
          onChange={(_, data) => set({ defaultPostCode: data.value })}
          placeholder="mis. 40111"
          maxLength={20}
          disabled={!manage}
          style={{ flex: 1, minWidth: 120 }}
        />
      </div>

      <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
        <TextField
          label="Website"
          value={defaults.defaultWebsite}
          onChange={(_, data) => set({ defaultWebsite: data.value })}
          placeholder="https://…"
          disabled={!manage}
          style={{ flex: 1, minWidth: 200 }}
        />
        <TextField
          label="URL logo"
          value={defaults.defaultLogoUrl}
          onChange={(_, data) => set({ defaultLogoUrl: data.value })}
          placeholder="https://…/logo.png"
          disabled={!manage}
          style={{ flex: 1, minWidth: 200 }}
        />
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
      <Text as="span" size={300} weight="semibold">URL Callback Pivot (per produk)</Text>
      <Text as="span" className="muted" size={200}>
        Akun master Pivot mendaftarkan satu Callback URL per produk. Tempel tiap URL di bawah ke
        kolom “Create URL” produk yang cocok pada dashboard Pivot. Semua produk memakai Callback
        Secret yang sama untuk verifikasi header <code>X-API-Key</code>.
      </Text>
      <div className="stack" style={{ gap: '0.4rem' }}>
        {PIVOT_CALLBACK_PRODUCTS.map(({ label, product, path }) => {
          const url = `${origin}${path}`
          return (
            <div key={product} className="stack" style={{ gap: '0.25rem' }}>
              <Text as="span" size={200} weight="semibold">{label} <Text as="span" className="muted" weight="regular">· {product}</Text></Text>
              <div className="row" style={{ gap: '0.5rem', alignItems: 'stretch' }}>
                <TextField
                  value={url}
                  readOnly
                  onFocus={(e) => e.target.select()}
                  style={{ flex: 1, font: `1em ${monospaceFont}` }}
                />
                <Button type="button" variant="subtle" onClick={() => onCopy(url)} style={{ whiteSpace: 'nowrap' }}>
                  Salin
                </Button>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function FormRow({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="stack" style={{ gap: '0.35rem' }}>
      <Text as="span" size={300} weight="semibold">{label}</Text>
      {children}
      {hint && (
        <Text as="span" className="muted" size={200}>{hint}</Text>
      )}
    </div>
  )
}
