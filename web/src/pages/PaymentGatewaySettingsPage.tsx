import { useCallback, useEffect, useMemo, useState, type CSSProperties, type ReactNode } from 'react'
import { Copy } from 'lucide-react'
import { api, ApiError } from '../api/client'
import {
  deleteQrisImage,
  getPaymentGatewaySettings,
  PAYMENT_PROVIDER_LABEL,
  QRIS_IMAGE_PATH,
  updatePaymentGatewaySettings,
  uploadQrisImage,
  type PaymentGatewaySettingsView,
  type PaymentProvider,
  type UpdatePaymentGatewaySettingsRequest,
} from '../api/payment'
import {
  assignSubAccountUser,
  createPivotPayout,
  getPivotAccount,
  getPivotBalance,
  listPivotPayouts,
  PIVOT_ACCOUNT_STATUS_LABEL,
  PIVOT_ACCOUNT_TYPE_LABEL,
  PIVOT_KYC_STATUS_LABEL,
  provisionPivotAccount,
  refreshPivotAccount,
  requestPivotKyc,
  resendSubAccountInvitation,
  savePivotProfile,
  setPivotPayoutAccount,
  type PivotAccountStatus,
  type PivotBalanceView,
  type PivotKycStatus,
  type PivotPayoutStatus,
  type PivotProfileRequest,
  type TenantPayoutView,
  type TenantPivotAccountView,
} from '../api/pivotAccount'
import { useCan } from '../auth/useCan'
import { Badge, Button, EmptyState, Segmented, TextField, TextareaField, type Tone } from '@/components/atoms'
import { Modal } from '@/components/molecules'
import { useToast } from '@/system'
import { Combobox } from '@/components/molecules'
import { PageHeader } from '@/components/molecules'
import { IconAlert, IconShield } from '@/components/atoms/icons'
import {
  channelNameByCode,
  PIVOT_CHANNEL_TYPE_LABEL,
  searchChannelCodes,
  type PivotChannel,
} from '../data/pivotReference'

/**
 * Pengaturan Payment Gateway tenant — halaman kritis: salah setel = tagihan tak dapat tautan
 * bayar atau pelanggan tak tahu cara membayar. Karena itu UX-nya dijaga:
 *
 *  1. **Status live dipisah dari form edit** — kartu atas menampilkan konfigurasi yang BENAR-BENAR
 *     berlaku sekarang (`saved`); form di bawah menampung suntingan yang belum disimpan (`form`).
 *  2. **Lacak perubahan (dirty)** — tombol simpan mati sampai ada yang berubah; "Batalkan" mengembalikan.
 *  3. **Wajib konfirmasi** — menyimpan memunculkan ringkasan diff yang harus dikonfirmasi.
 *
 * Penagihan otomatis memakai **Pivot** lewat akun master platform + sub-account tenant (dikelola di
 * kartu "Sub-account Pivot" di bawah, endpoint terpisah). Tak ada lagi kredensial per-tenant.
 */

/** Metode pembayaran tenant: PIVOT (otomatis via platform) atau MANUAL (transfer/QRIS). */
const PROVIDER_OPTIONS: PaymentProvider[] = ['PIVOT', 'MANUAL']

/** Profil sub-account kosong (string kosong agar input terkontrol, bukan null). */
const EMPTY_PROFILE: PivotProfileRequest = {
  legalName: '',
  merchantEmail: '',
  merchantPhone: '',
  picName: '',
  picEmail: '',
  picPhone: '',
  address: '',
  channelCode: '',
  accountNumber: '',
  accountName: '',
}

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
  // Gambar QRIS ikut tombol simpan utama (bukan unggah langsung) supaya transfer + toggle +
  // gambar tersimpan sekaligus dalam satu aksi — tak ada "auto-submit" saat memilih berkas.
  // `qrisFile` = berkas baru yang menunggu diunggah; `qrisRemoved` = minta hapus yang tersimpan.
  // `qrisVersion` membatalkan cache preview: satu key per tenant, byte baru menimpa URL yang sama.
  const [qrisFile, setQrisFile] = useState<File | null>(null)
  const [qrisRemoved, setQrisRemoved] = useState(false)
  const [qrisVersion, setQrisVersion] = useState(0)

  useEffect(() => {
    getPaymentGatewaySettings()
      .then((s) => {
        setSaved(s)
        setForm(s)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat setelan gateway'))
      .finally(() => setLoading(false))
  }, [toast])

  // Ringkasan perubahan (dipakai untuk dirty-state + konfirmasi).
  const changes = useMemo<FieldChange[]>(() => {
    if (!saved || !form) return []
    const out: FieldChange[] = []
    if (form.provider !== saved.provider) {
      out.push({ label: 'Metode', from: PAYMENT_PROVIDER_LABEL[saved.provider], to: PAYMENT_PROVIDER_LABEL[form.provider] })
    }
    // Pembayaran manual (non-rahasia) — gambar QRIS dikelola langsung, tak masuk diff ini.
    const onOff = (b: boolean) => (b ? 'nyala' : 'mati')
    if (form.manualTransferEnabled !== saved.manualTransferEnabled) {
      out.push({ label: 'Transfer', from: onOff(saved.manualTransferEnabled), to: onOff(form.manualTransferEnabled) })
    }
    if ((form.bankName ?? '') !== (saved.bankName ?? '')) {
      out.push({ label: 'Nama bank', from: saved.bankName || 'kosong', to: form.bankName || 'kosong' })
    }
    if ((form.accountNumber ?? '') !== (saved.accountNumber ?? '')) {
      out.push({ label: 'No. rekening', from: saved.accountNumber || 'kosong', to: form.accountNumber || 'kosong' })
    }
    if ((form.accountHolder ?? '') !== (saved.accountHolder ?? '')) {
      out.push({ label: 'Atas nama', from: saved.accountHolder || 'kosong', to: form.accountHolder || 'kosong' })
    }
    if (form.manualQrisEnabled !== saved.manualQrisEnabled) {
      out.push({ label: 'QRIS', from: onOff(saved.manualQrisEnabled), to: onOff(form.manualQrisEnabled) })
    }
    if (qrisFile) {
      out.push({ label: 'Gambar QRIS', from: saved.qrisImageSet ? 'tersimpan' : 'kosong', to: 'unggah gambar baru' })
    } else if (qrisRemoved && saved.qrisImageSet) {
      out.push({ label: 'Gambar QRIS', from: 'tersimpan', to: 'dihapus' })
    }
    return out
  }, [saved, form, qrisFile, qrisRemoved])

  const dirty = changes.length > 0
  const enabling = !!saved && !!form && form.provider === 'PIVOT' && saved.provider !== 'PIVOT'

  // Toggle metode PIVOT/MANUAL. PIVOT = penagihan otomatis (enabled); MANUAL = pelanggan bayar
  // transfer/QRIS (enabled false). Field manual dipertahankan agar tak hilang saat berpindah.
  const onProvider = (provider: PaymentProvider) => {
    setForm((f) => (f ? { ...f, provider, enabled: provider === 'PIVOT' } : f))
  }

  const discard = () => {
    if (saved) setForm(saved)
    setQrisFile(null)
    setQrisRemoved(false)
  }

  // Pilih/ganti/hapus gambar QRIS hanya menyentuh state lokal — unggahan sesungguhnya terjadi saat
  // menyimpan (doSave), jadi memilih berkas tak lagi men-submit apa pun.
  const onPickQris = (file: File) => {
    setQrisFile(file)
    setQrisRemoved(false)
  }
  const onRemoveQris = () => {
    setQrisFile(null)
    setQrisRemoved(true)
  }

  const doSave = async () => {
    if (!form) return
    setSaving(true)
    const body: UpdatePaymentGatewaySettingsRequest = {
      provider: form.provider,
      enabled: form.enabled,
      manualTransferEnabled: form.manualTransferEnabled,
      bankName: form.bankName?.trim() || null,
      accountNumber: form.accountNumber?.trim() || null,
      accountHolder: form.accountHolder?.trim() || null,
      manualQrisEnabled: form.manualQrisEnabled,
    }
    try {
      // Simpan setelan dulu (baris pasti ada), lalu terapkan perubahan gambar QRIS bila ada —
      // hasil terakhir jadi sumber kebenaran untuk `saved`/`form`.
      let result = await updatePaymentGatewaySettings(body)
      if (qrisFile) {
        result = await uploadQrisImage(qrisFile)
      } else if (qrisRemoved && result.qrisImageSet) {
        result = await deleteQrisImage()
      }
      setSaved(result)
      setForm(result)
      setQrisFile(null)
      setQrisRemoved(false)
      setQrisVersion((v) => v + 1)
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

  const showManual = form.provider === 'MANUAL'

  return (
    <div className="stack settings-page">
      <PageHeader
        title="Payment Gateway"
        subtitle={
          <>
            Cara pelanggan Anda membayar: otomatis lewat <strong>Pivot</strong> atau manual (transfer/QRIS).
            Perubahan minta konfirmasi sebelum berlaku.
          </>
        }
      />

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

        <FormRow
          label="Metode pembayaran"
          hint="Pivot menerbitkan tautan bayar otomatis (QRIS/VA) lewat akun platform. Manual = tagihan terbit tanpa tautan; pelanggan bayar transfer/QRIS statis Anda."
        >
          <Segmented
            value={form.provider}
            onChange={(v) => onProvider(v as PaymentProvider)}
            disabled={!manage}
            options={PROVIDER_OPTIONS.map((p) => ({ value: p, label: PAYMENT_PROVIDER_LABEL[p] }))}
          />
        </FormRow>

        {form.provider === 'PIVOT' && (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Penagihan otomatis via Pivot memakai sub-account tenant Anda. Pastikan sub-account sudah
            diprovisi &amp; rekening payout tersetel di kartu <strong>Sub-account Pivot</strong> di bawah.
          </p>
        )}

        {showManual && (
          <>
            <div className="hr" />
            <ManualPaymentSection
              form={form}
              onChange={(patch) => setForm((f) => (f ? { ...f, ...patch } : f))}
              disabled={!manage}
              qrisFile={qrisFile}
              qrisRemoved={qrisRemoved}
              qrisVersion={qrisVersion}
              onPickQris={onPickQris}
              onRemoveQris={onRemoveQris}
            />
          </>
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
                <Button variant="primary" onClick={() => setConfirmOpen(true)} disabled={!dirty || saving}>
                  Tinjau &amp; simpan…
                </Button>
              </div>
            </div>
          </>
        )}
      </div>

      {/* ---- Sub-account Pivot (endpoint terpisah) ---- */}
      {form.provider === 'PIVOT' && <PivotAccountCard manage={manage} />}

      {confirmOpen && (
        <Modal
          title="Konfirmasi perubahan gateway"
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
                Metode akan beralih ke <strong>Pivot</strong> — tagihan berikutnya otomatis dibuatkan tautan bayar.
                Pastikan sub-account Pivot sudah aktif &amp; rekening payout tersetel.
              </Callout>
            )}
            {saved.provider === 'PIVOT' && form.provider === 'MANUAL' && (
              <Callout>
                Metode akan beralih ke <strong>Manual</strong> — tagihan tetap terbit tapi tanpa tautan bayar; pelunasan
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
  const auto = saved.provider === 'PIVOT'
  return (
    <div className="card stack" style={{ gap: '0.75rem' }}>
      <div className="spread" style={{ alignItems: 'center' }}>
        <div className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
          <IconShield size={16} />
          <strong style={{ fontSize: '0.95rem' }}>Berlaku sekarang</strong>
        </div>
        <Badge tone={auto ? 'good' : 'neutral'}>{auto ? 'Otomatis' : 'Manual'}</Badge>
      </div>

      <div className="row" style={{ gap: '0.4rem', flexWrap: 'wrap' }}>
        <Badge tone="accent">{PAYMENT_PROVIDER_LABEL[saved.provider]}</Badge>
      </div>

      {!auto && <ManualSummary saved={saved} />}
    </div>
  )
}

/** Ringkasan metode manual aktif untuk kartu "Berlaku sekarang". */
function ManualSummary({ saved }: { saved: PaymentGatewaySettingsView }) {
  const active: string[] = []
  if (saved.manualTransferEnabled) active.push('Transfer')
  if (saved.manualQrisEnabled) active.push('QRIS')
  return (
    <div className="row" style={{ gap: '0.4rem', flexWrap: 'wrap', alignItems: 'center', fontSize: '0.82rem' }}>
      <span className="muted">Pembayaran manual:</span>
      {active.length === 0 ? (
        <span className="muted">belum ada metode aktif</span>
      ) : (
        active.map((m) => <Badge key={m}>{m}</Badge>)
      )}
    </div>
  )
}

const PIVOT_STATUS_TONE: Record<PivotAccountStatus, Tone> = {
  NOT_PROVISIONED: 'neutral',
  CREATED: 'accent',
  ACTIVE: 'good',
  DEACTIVATED: 'warning',
  REJECTED: 'critical',
}

const PIVOT_KYC_TONE: Record<PivotKycStatus, Tone> = {
  NOT_REQUIRED: 'neutral',
  WAITING_FOR_DOCUMENT: 'warning',
  IN_REVIEW: 'accent',
  APPROVED: 'good',
  REJECTED: 'critical',
}

/**
 * Kartu Sub-account Pivot tenant — dikelola lewat endpoint `/api/billing/pivot-account` (terpisah
 * dari setelan gateway). Menampilkan status provisioning + badge status/KYC/jenis, menjaga guard
 * `masterActive` (platform harus mengaktifkan Pivot dulu), lalu menyediakan aksi Provision/Refresh/
 * Ajukan KYC + form rekening payout.
 */

/**
 * Pemilih kode channel payout (Pivot): bisa dicari & dikelompokkan per tipe (Bank/E-Wallet/Virtual
 * Account) supaya operator tak salah ketik (mis. `MANDIRI` vs `MANDIRI_TASPEN`). Nilai yang di-seed
 * asinkron sudah ditangani `Combobox` lewat `initialLabel` — JANGAN me-remount lewat `key`, itu
 * bikin dropdown-nya terbuka lagi tepat setelah opsi dipilih.
 */
function ChannelCodeField({
  value,
  onChange,
  disabled,
}: {
  value: string
  onChange: (code: string) => void
  disabled?: boolean
}) {
  return (
    <Combobox<PivotChannel>
      value={value}
      onChange={(code) => onChange(code)}
      fetchOptions={(t) => Promise.resolve(searchChannelCodes(t))}
      debounceMs={0}
      toId={(c) => c.code}
      toLabel={(c) => c.name}
      toMeta={(c) => `${PIVOT_CHANNEL_TYPE_LABEL[c.type]} · ${c.code}`}
      groupOf={(c) => PIVOT_CHANNEL_TYPE_LABEL[c.type]}
      initialLabel={value ? (channelNameByCode(value) ?? value) : ''}
      placeholder="cari bank / e-wallet"
      disabled={disabled}
      emptyText="Channel tak ditemukan"
    />
  )
}

function PivotAccountCard({ manage }: { manage: boolean }) {
  const toast = useToast()
  const [account, setAccount] = useState<TenantPivotAccountView | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  // Profil bisnis sub-account (identitas + PIC + alamat + rekening payout) — wajib sebelum
  // provisioning. Rekening payout ikut di sini karena Pivot mewajibkan `bankAccount` saat create.
  const [profile, setProfile] = useState<PivotProfileRequest>(EMPTY_PROFILE)

  const syncFrom = (a: TenantPivotAccountView) => {
    setAccount(a)
    setProfile({
      legalName: a.legalName ?? '',
      merchantEmail: a.merchantEmail ?? '',
      merchantPhone: a.merchantPhone ?? '',
      picName: a.picName ?? '',
      picEmail: a.picEmail ?? '',
      picPhone: a.picPhone ?? '',
      address: a.address ?? '',
      channelCode: a.payoutChannelCode ?? '',
      accountNumber: a.payoutAccountNumber ?? '',
      accountName: a.payoutAccountName ?? '',
    })
  }

  useEffect(() => {
    getPivotAccount()
      .then(syncFrom)
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat sub-account Pivot'))
      .finally(() => setLoading(false))
  }, [toast])

  const run = async (fn: () => Promise<TenantPivotAccountView>, okMsg: string) => {
    if (busy) return
    setBusy(true)
    try {
      syncFrom(await fn())
      toast.success(okMsg)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
    } finally {
      setBusy(false)
    }
  }

  const payoutDirty =
    !!account &&
    ((profile.channelCode ?? '').trim() !== (account.payoutChannelCode ?? '') ||
      (profile.accountNumber ?? '').trim() !== (account.payoutAccountNumber ?? '') ||
      (profile.accountName ?? '').trim() !== (account.payoutAccountName ?? ''))

  // Profil dianggap lengkap secara lokal (mirror `profileComplete` server) untuk meng-gate tombol —
  // termasuk rekening payout, yang kini wajib karena Pivot menuntut `bankAccount` saat create.
  const profileFilled =
    profile.merchantEmail!.trim() !== '' &&
    profile.merchantPhone!.trim() !== '' &&
    profile.picName!.trim() !== '' &&
    profile.picEmail!.trim() !== '' &&
    profile.picPhone!.trim() !== '' &&
    profile.address!.trim() !== '' &&
    profile.channelCode!.trim() !== '' &&
    profile.accountNumber!.trim() !== '' &&
    profile.accountName!.trim() !== ''

  const profileDirty =
    !!account &&
    ((profile.legalName ?? '') !== (account.legalName ?? '') ||
      (profile.merchantEmail ?? '') !== (account.merchantEmail ?? '') ||
      (profile.merchantPhone ?? '') !== (account.merchantPhone ?? '') ||
      (profile.picName ?? '') !== (account.picName ?? '') ||
      (profile.picEmail ?? '') !== (account.picEmail ?? '') ||
      (profile.picPhone ?? '') !== (account.picPhone ?? '') ||
      (profile.address ?? '') !== (account.address ?? '') ||
      payoutDirty)

  const trimmedProfile = (): PivotProfileRequest => ({
    legalName: profile.legalName?.trim() || null,
    merchantEmail: profile.merchantEmail?.trim() || null,
    merchantPhone: profile.merchantPhone?.trim() || null,
    picName: profile.picName?.trim() || null,
    picEmail: profile.picEmail?.trim() || null,
    picPhone: profile.picPhone?.trim() || null,
    address: profile.address?.trim() || null,
    channelCode: profile.channelCode?.trim() || null,
    accountNumber: profile.accountNumber?.trim() || null,
    accountName: profile.accountName?.trim() || null,
  })

  return (
    <div className="card stack" style={{ gap: '0.85rem' }} aria-disabled={!manage}>
      <div className="spread" style={{ alignItems: 'center' }}>
        <div className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
          <IconShield size={16} />
          <strong style={{ fontSize: '0.95rem' }}>Sub-account Pivot</strong>
        </div>
        {account && (
          <Badge tone={account.provisioned ? 'good' : 'neutral'}>
            {account.provisioned ? 'diprovisi' : 'belum diprovisi'}
          </Badge>
        )}
      </div>

      {loading ? (
        <p className="muted" style={{ margin: 0 }}>Memuat sub-account…</p>
      ) : !account ? (
        <p className="muted" style={{ margin: 0 }}>Data sub-account tak tersedia.</p>
      ) : (
        <>
          {!account.masterActive && (
            <Callout>
              Platform belum mengaktifkan Pivot. Provisioning sub-account &amp; penagihan otomatis tak akan jalan
              sampai admin platform mengaktifkan &amp; mengonfigurasi akun master Pivot.
            </Callout>
          )}

          <div className="row" style={{ gap: '0.4rem', flexWrap: 'wrap' }}>
            <Badge tone={PIVOT_STATUS_TONE[account.status]}>{PIVOT_ACCOUNT_STATUS_LABEL[account.status]}</Badge>
            <Badge>{PIVOT_ACCOUNT_TYPE_LABEL[account.type]}</Badge>
            {account.type === 'KYC' || account.kycStatus !== 'NOT_REQUIRED' ? (
              <Badge tone={PIVOT_KYC_TONE[account.kycStatus]}>KYC: {PIVOT_KYC_STATUS_LABEL[account.kycStatus]}</Badge>
            ) : null}
            <Badge tone={account.payoutReady ? 'good' : 'warning'}>
              {account.payoutReady ? 'payout siap' : 'payout belum siap'}
            </Badge>
          </div>

          {account.shortName && (
            <span className="muted" style={{ fontSize: '0.82rem' }}>
              Nama singkat: <strong>{account.shortName}</strong>
            </span>
          )}

          {/* Sub-merchant ID dipakai saat rekonsiliasi & panel Simulasi Pembayaran platform. */}
          {account.subMerchantUuid && (
            <span className="row muted" style={{ gap: '0.35rem', alignItems: 'center', fontSize: '0.82rem' }}>
              <span>Sub-merchant ID:</span>
              <code style={{ fontFamily: 'monospace' }}>{account.subMerchantUuid}</code>
              <Button
                variant="subtle"
                icon={<Copy size={13} />}
                onClick={() =>
                  void navigator.clipboard
                    ?.writeText(account.subMerchantUuid ?? '')
                    .then(() => toast.success('Sub-merchant ID disalin'))
                }
                title="Salin sub-merchant ID"
                aria-label="Salin sub-merchant ID"
                style={{ minWidth: 'auto', padding: '0.1rem 0.25rem' }}
              />
            </span>
          )}

          {/* Profil bisnis sub-account — wajib diisi lengkap sebelum bisa diprovisi ke Pivot. */}
          {!account.provisioned && (
            <>
              <div className="hr" />
              <SectionTitle>Profil sub-account</SectionTitle>
              <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
                Data bisnis yang didaftarkan ke Pivot. Semua kolom (kecuali nama legal) wajib diisi
                sebelum sub-account bisa diprovisi.
              </p>
              <div className="stack" style={{ gap: '0.6rem' }}>
                <TextField
                  label="Nama legal bisnis (opsional)"
                  value={profile.legalName ?? ''}
                  onChange={(_, data) => setProfile((p) => ({ ...p, legalName: data.value }))}
                  placeholder="kosong = pakai nama tenant"
                  maxLength={200}
                  disabled={!manage}
                />
                <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
                  <TextField
                    label="Email bisnis"
                    type="email"
                    value={profile.merchantEmail ?? ''}
                    onChange={(_, data) => setProfile((p) => ({ ...p, merchantEmail: data.value }))}
                    placeholder="mis. billing@usaha.co.id"
                    maxLength={160}
                    disabled={!manage}
                    style={{ flex: 1, minWidth: 200 }}
                  />
                  <TextField
                    label="Telepon bisnis"
                    value={profile.merchantPhone ?? ''}
                    onChange={(_, data) => setProfile((p) => ({ ...p, merchantPhone: data.value }))}
                    placeholder="mis. 081234567890"
                    maxLength={40}
                    disabled={!manage}
                    style={{ flex: 1, minWidth: 160 }}
                  />
                </div>
                <TextField
                  label="Nama PIC"
                  value={profile.picName ?? ''}
                  onChange={(_, data) => setProfile((p) => ({ ...p, picName: data.value }))}
                  placeholder="penanggung jawab"
                  maxLength={160}
                  disabled={!manage}
                />
                <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
                  <TextField
                    label="Email PIC"
                    type="email"
                    value={profile.picEmail ?? ''}
                    onChange={(_, data) => setProfile((p) => ({ ...p, picEmail: data.value }))}
                    placeholder="mis. pic@usaha.co.id"
                    maxLength={160}
                    disabled={!manage}
                    style={{ flex: 1, minWidth: 200 }}
                  />
                  <TextField
                    label="Telepon PIC"
                    value={profile.picPhone ?? ''}
                    onChange={(_, data) => setProfile((p) => ({ ...p, picPhone: data.value }))}
                    placeholder="mis. 081234567890"
                    maxLength={40}
                    disabled={!manage}
                    style={{ flex: 1, minWidth: 160 }}
                  />
                </div>
                <TextareaField
                  label="Alamat bisnis"
                  value={profile.address ?? ''}
                  onChange={(_, data) => setProfile((p) => ({ ...p, address: data.value }))}
                  placeholder="alamat lengkap usaha"
                  maxLength={500}
                  rows={2}
                  disabled={!manage}
                />
                {/* Rekening payout — bagian profil karena Pivot mewajibkan bankAccount saat create. */}
                <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
                  <label style={{ flex: 1, minWidth: 140 }}>
                    <span>Kode channel bank</span>
                    <ChannelCodeField
                      value={profile.channelCode ?? ''}
                      onChange={(code) => setProfile((p) => ({ ...p, channelCode: code }))}
                      disabled={!manage}
                    />
                  </label>
                  <TextField
                    label="Nomor rekening"
                    value={profile.accountNumber ?? ''}
                    onChange={(_, data) => setProfile((p) => ({ ...p, accountNumber: data.value }))}
                    placeholder="mis. 1234567890"
                    maxLength={60}
                    disabled={!manage}
                    style={{ flex: 1, minWidth: 160 }}
                  />
                  <TextField
                    label="Nama pemilik rekening"
                    value={profile.accountName ?? ''}
                    onChange={(_, data) => setProfile((p) => ({ ...p, accountName: data.value }))}
                    placeholder="sesuai catatan bank"
                    maxLength={60}
                    disabled={!manage}
                    style={{ flex: 1, minWidth: 180 }}
                  />
                </div>
                <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
                  Rekening tujuan pencairan dana pelanggan. Nama pemilik harus persis seperti catatan
                  bank — dicocokkan otomatis setelah sub-account terdaftar. Menekan “Daftarkan
                  sub-account” otomatis menyimpan profil.
                </p>
                {manage && (
                  <div className="row" style={{ gap: '0.5rem', justifyContent: 'flex-end' }}>
                    <Button
                      variant="subtle"
                      disabled={busy || !profileDirty}
                      onClick={() => void run(() => savePivotProfile(trimmedProfile()), 'Profil sub-account tersimpan')}
                    >
                      Simpan profil
                    </Button>
                  </div>
                )}
              </div>
            </>
          )}

          {manage && (
            <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap' }}>
              {!account.provisioned && (
                <Button
                  variant="primary"
                  disabled={busy || !account.masterActive || !profileFilled}
                  title={!profileFilled ? 'Lengkapi profil sub-account dulu' : undefined}
                  onClick={() =>
                    void run(async () => {
                      // Satu klik: simpan profil dulu bila ada perubahan, lalu daftarkan.
                      if (profileDirty) await savePivotProfile(trimmedProfile())
                      return provisionPivotAccount()
                    }, 'Sub-account Pivot diprovisi')
                  }
                >
                  Daftarkan sub-account
                </Button>
              )}
              <Button
                variant="subtle"
                disabled={busy || !account.provisioned}
                onClick={() => void run(refreshPivotAccount, 'Status sub-account disegarkan')}
              >
                Refresh
              </Button>
              {account.provisioned && account.type === 'NON_KYC' && (
                <Button
                  variant="subtle"
                  disabled={busy}
                  onClick={() => void run(requestPivotKyc, 'Pengajuan KYC dikirim')}
                >
                  Ajukan KYC
                </Button>
              )}
            </div>
          )}

          {account.provisioned && <PivotUsersSection manage={manage} />}
          {/* Rekening payout TIDAK lagi punya seksi sendiri: dulu ia berdiri terpisah dari form
              payout, jadi rekening yang sama diketik di dua tempat dan tenant mengubahnya berkali-
              kali tanpa perlu — padahal tiap perubahan memicu inquiry berbayar. Sekarang jadi satu
              di dalam Saldo & Payout, ditampilkan sebagai ringkasan yang dikunci. */}
          {account.provisioned && (
            <PivotPayoutSection manage={manage} account={account} onAccountSaved={syncFrom} />
          )}
        </>
      )}
    </div>
  )
}

const PAYOUT_STATUS_TONE: Record<PivotPayoutStatus, Tone> = {
  PENDING: 'neutral',
  PROCESSING: 'warning',
  SUCCESS: 'good',
  FAILED: 'critical',
}

const PAYOUT_STATUS_LABEL: Record<PivotPayoutStatus, string> = {
  PENDING: 'Menunggu',
  PROCESSING: 'Diproses',
  SUCCESS: 'Berhasil',
  FAILED: 'Gagal',
}

/** Format rupiah utuh (tanpa desimal) sesuai lokal id-ID. */
const formatRupiah = (n: number) => `Rp ${n.toLocaleString('id-ID')}`

/**
 * Manajemen pengguna sub-account: undang admin & kirim ulang undangan. Keduanya aksi SEKALI-SEKALI
 * (sekali saat menambah admin baru, sekali lagi kalau emailnya tak sampai), jadi kolomnya tak
 * dibiarkan menetap di halaman — cukup satu tombol yang membuka dialog. Kirim ulang menumpang
 * dialog yang sama karena datanya sama: satu alamat email.
 *
 * Aksi Pivot on-behalf sub-account tenant, tak mengubah state akun → seksi ini mandiri (busy lokal).
 * Muncul hanya saat sub-account sudah terprovisi.
 */
function PivotUsersSection({ manage }: { manage: boolean }) {
  const toast = useToast()
  const [open, setOpen] = useState(false)
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)

  const close = () => {
    setOpen(false)
    setEmail('')
    setName('')
  }

  const run = async (fn: () => Promise<unknown>, okMsg: string) => {
    if (busy) return
    setBusy(true)
    try {
      await fn()
      toast.success(okMsg)
      close()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <div className="hr" />
      <div className="spread" style={{ gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
        <div className="stack" style={{ gap: '0.15rem' }}>
          <SectionTitle>Pengguna sub-account</SectionTitle>
          <span className="muted" style={{ fontSize: '0.82rem' }}>
            Undang admin ke sub-account Pivot Anda — Pivot mengirim email undangannya.
          </span>
        </div>
        {manage && (
          <Button variant="subtle" onClick={() => setOpen(true)}>
            Undang pengguna
          </Button>
        )}
      </div>
      {open && (
        <Modal
          title="Undang pengguna sub-account"
          onClose={close}
          footer={
            <>
              <Button variant="subtle" disabled={busy} onClick={close}>
                Batal
              </Button>
              <Button
                variant="primary"
                disabled={busy || !email.trim() || !name.trim()}
                onClick={() =>
                  void run(
                    () => assignSubAccountUser({ email: email.trim(), name: name.trim() }),
                    'Undangan pengguna terkirim',
                  )
                }
              >
                Kirim undangan
              </Button>
            </>
          }
        >
          <div className="stack" style={{ gap: '0.75rem' }}>
            <TextField
              label="Email pengguna"
              type="email"
              value={email}
              onChange={(_, data) => setEmail(data.value)}
              placeholder="mis. admin@usaha.co.id"
              maxLength={255}
            />
            <TextField
              label="Nama pengguna"
              value={name}
              onChange={(_, data) => setName(data.value)}
              placeholder="nama lengkap"
              maxLength={255}
            />
            <div className="spread" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
              <span className="muted" style={{ fontSize: '0.8rem' }}>
                Sudah pernah diundang tapi emailnya tak sampai?
              </span>
              {/* Kirim ulang cuma butuh email — nama diabaikan, jadi tombolnya hidup lebih awal. */}
              <Button
                variant="subtle"
                disabled={busy || !email.trim()}
                onClick={() =>
                  void run(
                    () => resendSubAccountInvitation({ email: email.trim() }),
                    'Undangan dikirim ulang',
                  )
                }
              >
                Kirim ulang undangan
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </>
  )
}

/**
 * Saldo & payout sub-account: saldo PEMBAYARAN (dana hasil tagihan pelanggan, on-behalf sub-account)
 * lalu form kirim dana. Saldo payout sengaja tak ditampilkan — dompet terpisah di Pivot, dan server
 * yang mengurusnya: kekurangannya dipindahkan otomatis dari saldo pembayaran sebelum payout dikirim.
 *
 * Rekening tujuan **dikunci sebagai ringkasan**, bukan tiga kolom yang selalu terbuka. Alasannya
 * bukan kosmetik: tiap perubahan rekening memicu `POST /v1/inquiry-account` yang ditagih Pivot per
 * panggilan, sedangkan rekening yang tak berubah memakai `inquiryId` tersimpan dan gratis. Kolomnya
 * cuma terbuka pada tiga keadaan — belum pernah diisi, tenant menekan "Ubah rekening", atau
 * rekeningnya memang belum lolos validasi bank.
 */
function PivotPayoutSection({
  manage,
  account,
  onAccountSaved,
}: {
  manage: boolean
  account: TenantPivotAccountView
  onAccountSaved: (a: TenantPivotAccountView) => void
}) {
  const toast = useToast()
  const [balance, setBalance] = useState<PivotBalanceView | null>(null)
  const [payouts, setPayouts] = useState<TenantPayoutView[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState(false)
  const [channelCode, setChannelCode] = useState(account.payoutChannelCode ?? '')
  const [accountNumber, setAccountNumber] = useState(account.payoutAccountNumber ?? '')
  const [accountName, setAccountName] = useState(account.payoutAccountName ?? '')
  const [amount, setAmount] = useState('')
  const [description, setDescription] = useState('')

  const loadData = useCallback(async () => {
    const [b, p] = await Promise.all([getPivotBalance(), listPivotPayouts()])
    setBalance(b)
    setPayouts(p)
  }, [])

  useEffect(() => {
    loadData()
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat saldo/payout'))
      .finally(() => setLoading(false))
  }, [loadData, toast])

  const refresh = async () => {
    if (busy) return
    setBusy(true)
    try {
      await loadData()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyegarkan saldo')
    } finally {
      setBusy(false)
    }
  }

  // Rekening tujuan yang TERSIMPAN — bukan isi kolom. Kolomnya cuma buffer suntingan.
  const savedChannel = account.payoutChannelCode ?? ''
  const savedNumber = account.payoutAccountNumber ?? ''
  const savedName = account.payoutAccountName ?? ''
  const hasDestination = savedChannel !== '' && savedNumber !== '' && savedName !== ''
  // Terkunci hanya bila rekeningnya lengkap DAN lolos validasi bank; rekening yang ditolak wajib
  // dibetulkan, jadi kolomnya dibuka sendiri tanpa tenant perlu menekan "Ubah rekening".
  const editorOpen = editing || !hasDestination || !account.payoutReady

  const destinationDirty =
    channelCode.trim() !== savedChannel ||
    accountNumber.trim() !== savedNumber ||
    accountName.trim() !== savedName
  const destinationFilled =
    channelCode.trim() !== '' && accountNumber.trim() !== '' && accountName.trim() !== ''

  const openEditor = () => {
    setChannelCode(savedChannel)
    setAccountNumber(savedNumber)
    setAccountName(savedName)
    setEditing(true)
  }

  const cancelEditor = () => {
    setChannelCode(savedChannel)
    setAccountNumber(savedNumber)
    setAccountName(savedName)
    setEditing(false)
  }

  /**
   * Simpan rekening tujuan sekali di sini — server yang menembak inquiry (berbayar) lalu menyimpan
   * `inquiryId`-nya, jadi payout berikutnya tinggal pakai. Rekening yang tak berubah tak ditembak
   * ulang, tapi tetap tak perlu ditekan: tombolnya mati saat tak ada yang berubah.
   */
  const saveDestination = async () => {
    if (busy || !destinationFilled) return
    setBusy(true)
    try {
      onAccountSaved(
        await setPivotPayoutAccount({
          channelCode: channelCode.trim(),
          accountNumber: accountNumber.trim(),
          accountName: accountName.trim(),
        }),
      )
      setEditing(false)
      toast.success('Rekening tujuan tersimpan & tervalidasi')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Rekening tujuan gagal divalidasi')
    } finally {
      setBusy(false)
    }
  }

  const amountMinor = Math.trunc(Number(amount))
  const amountValid = amount.trim() !== '' && Number.isFinite(amountMinor) && amountMinor > 0
  // Payout memakai rekening TERSIMPAN, jadi syaratnya cuma rekening siap + nominal sah.
  const canSubmit = manage && !busy && account.payoutReady && !editorOpen && amountValid

  const submit = async () => {
    if (!canSubmit) return
    setBusy(true)
    try {
      await createPivotPayout({
        channelCode: savedChannel,
        accountNumber: savedNumber,
        accountName: savedName,
        amountMinor,
        description: description.trim() || null,
      })
      toast.success('Payout terkirim')
      setAmount('')
      setDescription('')
      await loadData()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Payout gagal')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <div className="hr" />
      <SectionTitle>Saldo &amp; Payout</SectionTitle>
      {loading ? (
        <p className="muted" style={{ margin: 0 }}>Memuat saldo…</p>
      ) : (
        <>
          <div className="row" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <span className="muted" style={{ fontSize: '0.82rem' }}>Saldo pembayaran:</span>
            <strong>{balance ? formatRupiah(balance.availableMinor) : '—'}</strong>
            <Button variant="subtle" disabled={busy} onClick={() => void refresh()}>
              Segarkan saldo
            </Button>
          </div>

          {editorOpen ? (
            <>
              <div className="spread" style={{ gap: '0.75rem', alignItems: 'center' }}>
                <span style={{ fontWeight: 600, fontSize: '0.9rem' }}>Rekening tujuan payout</span>
                {hasDestination && account.payoutReady && (
                  <Button variant="subtle" disabled={busy} onClick={cancelEditor}>
                    Batal
                  </Button>
                )}
              </div>
              <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
                Diisi sekali saja. Nama pemilik harus persis seperti catatan bank — dicocokkan ke
                bank sebelum disimpan, dan ditolak kalau berbeda.
              </p>
              <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
                <label style={{ flex: 1, minWidth: 140 }}>
                  <span>Bank tujuan</span>
                  <ChannelCodeField value={channelCode} onChange={setChannelCode} disabled={!manage} />
                </label>
                <TextField
                  label="Nomor rekening"
                  value={accountNumber}
                  onChange={(_, data) => setAccountNumber(data.value)}
                  placeholder="mis. 1234567890"
                  maxLength={60}
                  disabled={!manage}
                  style={{ flex: 1, minWidth: 160 }}
                />
                <TextField
                  label="Nama pemilik rekening"
                  value={accountName}
                  onChange={(_, data) => setAccountName(data.value)}
                  placeholder="sesuai catatan bank"
                  maxLength={60}
                  disabled={!manage}
                  style={{ flex: 1, minWidth: 180 }}
                />
              </div>
              {manage && (
                <div className="row" style={{ gap: '0.5rem', justifyContent: 'flex-end' }}>
                  <Button
                    variant="primary"
                    disabled={busy || !destinationFilled || (!destinationDirty && account.payoutReady)}
                    onClick={() => void saveDestination()}
                  >
                    Simpan &amp; validasi rekening
                  </Button>
                </div>
              )}
            </>
          ) : (
            <div
              className="spread"
              style={{ gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap' }}
            >
              <div className="stack" style={{ gap: '0.15rem' }}>
                <span className="muted" style={{ fontSize: '0.78rem' }}>Rekening tujuan payout</span>
                <strong style={{ fontSize: '0.9rem' }}>
                  {channelNameByCode(savedChannel) ?? savedChannel} · {savedNumber}
                </strong>
                <span className="muted" style={{ fontSize: '0.78rem' }}>
                  a.n. {savedName} <Badge tone="good">Tervalidasi</Badge>
                </span>
              </div>
              {manage && (
                <Button variant="subtle" disabled={busy} onClick={openEditor}>
                  Ubah rekening
                </Button>
              )}
            </div>
          )}

          <div className="hr" />
          <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
            <TextField
              label="Nominal payout (Rp)"
              inputMode="numeric"
              value={amount}
              onChange={(_, data) => setAmount(data.value.replace(/[^\d]/g, ''))}
              placeholder="mis. 100000"
              disabled={!manage || editorOpen}
              style={{ flex: 1, minWidth: 140 }}
            />
            <TextField
              label="Deskripsi (opsional)"
              value={description}
              onChange={(_, data) => setDescription(data.value)}
              placeholder="tampil di mutasi penerima — maks 20 huruf/angka"
              maxLength={20}
              disabled={!manage || editorOpen}
              style={{ flex: 2, minWidth: 200 }}
            />
            {manage && (
              <Button variant="primary" disabled={!canSubmit} onClick={() => void submit()}>
                Kirim payout
              </Button>
            )}
          </div>
          <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
            {editorOpen
              ? 'Simpan rekening tujuan dulu — payout selalu dikirim ke rekening tersimpan itu.'
              : 'Payout ditarik dari saldo payout (dompet terpisah dari saldo pembayaran di atas); bila kurang, kekurangannya dipindahkan otomatis dari saldo pembayaran.'}
          </p>

          <div className="hr" />
          <SectionTitle>Riwayat payout</SectionTitle>
          {payouts.length === 0 ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>Belum ada penyaluran dana.</p>
          ) : (
            <div className="stack" style={{ gap: '0.4rem' }}>
              {payouts.map((p) => {
                const dest = [p.channelCode, p.accountNumber].filter(Boolean).join(' ')
                const desc = [p.accountName, dest].filter(Boolean).join(' · ')
                return (
                  <div
                    key={p.id}
                    className="spread"
                    style={{ gap: '0.75rem', fontSize: '0.85rem', alignItems: 'center' }}
                  >
                    <div className="stack" style={{ gap: '0.15rem' }}>
                      <strong>{formatRupiah(p.amountMinor)}</strong>
                      <span className="muted" style={{ fontSize: '0.78rem' }}>{desc || '—'}</span>
                      {p.failureReason && (
                        <span style={{ fontSize: '0.78rem', color: 'var(--critical)' }}>{p.failureReason}</span>
                      )}
                    </div>
                    <Badge tone={PAYOUT_STATUS_TONE[p.status]}>{PAYOUT_STATUS_LABEL[p.status]}</Badge>
                  </div>
                )
              })}
            </div>
          )}
        </>
      )}
    </>
  )
}

/**
 * Seksi setelan pembayaran manual (tunai / transfer / QRIS) — muncul saat metode MANUAL, di mana
 * inilah satu-satunya cara pelanggan membayar. Tiap metode punya saklar; Transfer membuka field
 * rekening, QRIS membuka pengunggah gambar. Toggle & field ikut tombol simpan utama; gambar QRIS
 * diunggah/dihapus saat menyimpan (multipart).
 */
function ManualPaymentSection({
  form,
  onChange,
  disabled,
  qrisFile,
  qrisRemoved,
  qrisVersion,
  onPickQris,
  onRemoveQris,
}: {
  form: PaymentGatewaySettingsView
  onChange: (patch: Partial<PaymentGatewaySettingsView>) => void
  disabled: boolean
  qrisFile: File | null
  qrisRemoved: boolean
  qrisVersion: number
  onPickQris: (file: File) => void
  onRemoveQris: () => void
}) {
  return (
    <div className="stack" style={{ gap: '0.85rem' }}>
      <div>
        <SectionTitle>Pembayaran manual</SectionTitle>
        <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.82rem' }}>
          Cara pelanggan membayar saat metode manual dipilih. Nyalakan metode yang Anda terima —
          hanya yang menyala ditampilkan di tagihan pelanggan.
        </p>
      </div>

      {/* Transfer — saklar + field rekening */}
      <FormRow label="Transfer bank">
        <Segmented
          value={form.manualTransferEnabled ? 'on' : 'off'}
          onChange={(v) => onChange({ manualTransferEnabled: v === 'on' })}
          disabled={disabled}
          options={[
            { value: 'off', label: 'Nonaktif' },
            { value: 'on', label: 'Aktif' },
          ]}
        />
      </FormRow>
      {form.manualTransferEnabled && (
        <div className="stack" style={{ gap: '0.6rem', paddingLeft: '0.5rem' }}>
          <TextField
            label="Nama bank"
            value={form.bankName ?? ''}
            onChange={(_, data) => onChange({ bankName: data.value })}
            placeholder="mis. BCA, Mandiri, BRI"
            maxLength={120}
            disabled={disabled}
          />
          <TextField
            label="Nomor rekening"
            value={form.accountNumber ?? ''}
            onChange={(_, data) => onChange({ accountNumber: data.value })}
            placeholder="mis. 1234567890"
            maxLength={60}
            disabled={disabled}
          />
          <TextField
            label="Atas nama"
            value={form.accountHolder ?? ''}
            onChange={(_, data) => onChange({ accountHolder: data.value })}
            placeholder="nama pemilik rekening"
            maxLength={160}
            disabled={disabled}
          />
        </div>
      )}

      {/* QRIS — saklar + pengunggah gambar */}
      <FormRow label="QRIS">
        <Segmented
          value={form.manualQrisEnabled ? 'on' : 'off'}
          onChange={(v) => onChange({ manualQrisEnabled: v === 'on' })}
          disabled={disabled}
          options={[
            { value: 'off', label: 'Nonaktif' },
            { value: 'on', label: 'Aktif' },
          ]}
        />
      </FormRow>
      {form.manualQrisEnabled && (
        <div className="stack" style={{ gap: '0.6rem', paddingLeft: '0.5rem' }}>
          {qrisFile ? (
            // Berkas baru dipilih — pratinjau dari berkas lokal, unggah menyusul saat menyimpan.
            <div className="row" style={{ gap: '0.75rem', alignItems: 'flex-start', flexWrap: 'wrap' }}>
              <LocalImage file={qrisFile} alt="Gambar QRIS baru" size={140} />
              <div className="stack" style={{ gap: '0.4rem' }}>
                <span className="muted" style={{ fontSize: '0.82rem' }}>Gambar baru — akan diunggah saat menyimpan.</span>
                {!disabled && (
                  <div className="row" style={{ gap: '0.5rem' }}>
                    <QrisUploadButton label="Ganti gambar" onPick={onPickQris} />
                    <Button variant="subtle" onClick={onRemoveQris}>
                      Batalkan
                    </Button>
                  </div>
                )}
              </div>
            </div>
          ) : form.qrisImageSet && !qrisRemoved ? (
            <div className="row" style={{ gap: '0.75rem', alignItems: 'flex-start', flexWrap: 'wrap' }}>
              <AuthedImage path={QRIS_IMAGE_PATH} version={qrisVersion} alt="Gambar QRIS" size={140} />
              <div className="stack" style={{ gap: '0.4rem' }}>
                <span className="muted" style={{ fontSize: '0.82rem' }}>Gambar QRIS tersimpan.</span>
                {!disabled && (
                  <div className="row" style={{ gap: '0.5rem' }}>
                    <QrisUploadButton label="Ganti gambar" onPick={onPickQris} />
                    <Button variant="subtle" onClick={onRemoveQris}>
                      Hapus
                    </Button>
                  </div>
                )}
              </div>
            </div>
          ) : (
            <div className="stack" style={{ gap: '0.4rem' }}>
              <span className="muted" style={{ fontSize: '0.82rem' }}>
                {qrisRemoved
                  ? 'Gambar QRIS akan dihapus saat menyimpan.'
                  : 'Belum ada gambar QRIS. Pilih gambar QRIS statis Anda (PNG/JPG, maks 5 MB).'}
              </span>
              {!disabled && <QrisUploadButton label={qrisRemoved ? 'Pilih gambar lain' : 'Pilih gambar QRIS'} onPick={onPickQris} />}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

/** Tombol pemicu `<input type=file>` tersembunyi untuk memilih gambar QRIS (unggah saat menyimpan). */
function QrisUploadButton({ label, onPick }: { label: string; onPick: (file: File) => void }) {
  return (
    <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', cursor: 'pointer', padding: '0.4rem 0.7rem', borderRadius: 6, border: '1px solid var(--border)', fontSize: '0.85rem' }}>
      {label}
      <input
        type="file"
        accept="image/*"
        style={{ display: 'none' }}
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) onPick(file)
          e.target.value = '' // izinkan pilih berkas sama lagi
        }}
      />
    </label>
  )
}

/** Pratinjau berkas gambar lokal (belum terunggah) — object URL dicabut saat unmount/ganti berkas. */
function LocalImage({ file, alt, size }: { file: File; alt: string; size: number }) {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    const objectUrl = URL.createObjectURL(file)
    setUrl(objectUrl)
    return () => URL.revokeObjectURL(objectUrl)
  }, [file])
  const box: CSSProperties = {
    width: size,
    height: size,
    borderRadius: 8,
    objectFit: 'contain',
    background: 'var(--surface-2, #1e2530)',
    border: '1px solid var(--border, #2a3340)',
  }
  if (!url) return <div style={box} aria-busy="true" />
  return <img src={url} alt={alt} style={box} />
}

/**
 * Gambar berkonten terautentikasi (byte ditarik lewat header Bearer lalu dijadikan object URL).
 * [version] membatalkan cache: satu key QRIS per tenant, jadi unggah ulang menimpa byte di URL
 * yang sama — menaikkan [version] memaksa ambil ulang. Object URL dicabut saat unmount/ganti.
 */
function AuthedImage({ path, version, alt, size }: { path: string; version: number; alt: string; size: number }) {
  const [url, setUrl] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let active = true
    let objectUrl: string | null = null
    setUrl(null)
    setFailed(false)
    api
      .blob(path)
      .then((b) => {
        if (!active) return
        objectUrl = URL.createObjectURL(b)
        setUrl(objectUrl)
      })
      .catch(() => active && setFailed(true))
    return () => {
      active = false
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [path, version])

  const box: CSSProperties = {
    width: size,
    height: size,
    borderRadius: 8,
    objectFit: 'contain',
    background: 'var(--surface-2, #1e2530)',
    border: '1px solid var(--border, #2a3340)',
  }
  if (failed) return <div style={{ ...box, display: 'grid', placeItems: 'center', fontSize: '0.7rem' }} className="muted">gagal</div>
  if (!url) return <div style={box} aria-busy="true" />
  return (
    <a href={url} target="_blank" rel="noreferrer" title={alt}>
      <img src={url} alt={alt} style={box} />
    </a>
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
  return <h3 style={{ margin: '0.25rem 0 0', fontSize: '0.95rem', fontWeight: 600 }}>{children}</h3>
}
