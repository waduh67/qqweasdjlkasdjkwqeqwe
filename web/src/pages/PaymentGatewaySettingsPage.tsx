import { useEffect, useMemo, useState, type CSSProperties, type ReactNode } from 'react'
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
  getPivotAccount,
  PIVOT_ACCOUNT_STATUS_LABEL,
  PIVOT_ACCOUNT_TYPE_LABEL,
  PIVOT_KYC_STATUS_LABEL,
  provisionPivotAccount,
  refreshPivotAccount,
  requestPivotKyc,
  setPivotPayoutAccount,
  type PivotAccountStatus,
  type PivotKycStatus,
  type TenantPivotAccountView,
} from '../api/pivotAccount'
import { useAuth } from '../auth/useAuth'
import { useCan } from '../auth/useCan'
import { Badge, EmptyState, Modal, useToast, type Tone } from '../components/ui'
import { IconAlert, IconShield } from '../components/icons'

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

interface FieldChange {
  label: string
  from: string
  to: string
}

export function PaymentGatewaySettingsPage() {
  const { can } = useCan()
  const { user } = useAuth()
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

  const copyToClipboard = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text)
      toast.success('URL webhook disalin')
    } catch {
      toast.error('Gagal menyalin — salin manual dari kolomnya')
    }
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

  // URL callback Pivot per-tenant (readonly, untuk disalin ke dashboard Pivot). Origin = URL aplikasi
  // saat ini; di produksi inilah alamat publik yang dipanggil balik. Path memakai SLUG tenant —
  // BillingWebhookController me-resolve tenant lewat slug, bukan UUID.
  const webhookUrl = `${window.location.origin}/api/billing/webhooks/${user?.tenantSlug ?? '<tenant>'}/pivot`
  const showManual = form.provider === 'MANUAL'

  return (
    <div className="stack settings-page">
      <div>
        <h1 className="page-title">Payment Gateway</h1>
        <p className="page-sub" style={{ margin: '0.25rem 0 0' }}>
          Cara pelanggan Anda membayar: otomatis lewat <strong>Pivot</strong> atau manual (transfer/QRIS).
          Perubahan minta konfirmasi sebelum berlaku.
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
          <>
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
              Penagihan otomatis via Pivot memakai sub-account tenant Anda. Pastikan sub-account sudah
              diprovisi &amp; rekening payout tersetel di kartu <strong>Sub-account Pivot</strong> di bawah.
            </p>
            <WebhookField
              url={webhookUrl}
              hint="Tempel sebagai Callback URL di dashboard Pivot (menu Callbacks)."
              onCopy={() => void copyToClipboard(webhookUrl)}
            />
          </>
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

      {/* ---- Sub-account Pivot (endpoint terpisah) ---- */}
      {form.provider === 'PIVOT' && <PivotAccountCard manage={manage} />}

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
function PivotAccountCard({ manage }: { manage: boolean }) {
  const toast = useToast()
  const [account, setAccount] = useState<TenantPivotAccountView | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [channelCode, setChannelCode] = useState('')
  const [accountNumber, setAccountNumber] = useState('')

  useEffect(() => {
    getPivotAccount()
      .then((a) => {
        setAccount(a)
        setChannelCode(a.payoutChannelCode ?? '')
        setAccountNumber(a.payoutAccountNumber ?? '')
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat sub-account Pivot'))
      .finally(() => setLoading(false))
  }, [toast])

  const run = async (fn: () => Promise<TenantPivotAccountView>, okMsg: string) => {
    if (busy) return
    setBusy(true)
    try {
      const a = await fn()
      setAccount(a)
      setChannelCode(a.payoutChannelCode ?? '')
      setAccountNumber(a.payoutAccountNumber ?? '')
      toast.success(okMsg)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
    } finally {
      setBusy(false)
    }
  }

  const payoutDirty =
    !!account &&
    (channelCode.trim() !== (account.payoutChannelCode ?? '') ||
      accountNumber.trim() !== (account.payoutAccountNumber ?? ''))

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

          {manage && (
            <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap' }}>
              {!account.provisioned && (
                <button
                  className="primary"
                  disabled={busy || !account.masterActive}
                  onClick={() => void run(provisionPivotAccount, 'Sub-account Pivot diprovisi')}
                >
                  Provision
                </button>
              )}
              <button
                className="ghost"
                disabled={busy || !account.provisioned}
                onClick={() => void run(refreshPivotAccount, 'Status sub-account disegarkan')}
              >
                Refresh
              </button>
              {account.provisioned && account.type === 'NON_KYC' && (
                <button
                  className="ghost"
                  disabled={busy}
                  onClick={() => void run(requestPivotKyc, 'Pengajuan KYC dikirim')}
                >
                  Ajukan KYC
                </button>
              )}
            </div>
          )}

          {/* Rekening payout: ke mana dana pelanggan diteruskan */}
          <div className="hr" />
          <SectionTitle>Rekening payout</SectionTitle>
          <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
            Rekening tujuan pencairan dana dari pembayaran pelanggan Anda.
            {account.payoutAccountName && (
              <>
                {' '}Terverifikasi atas nama <strong>{account.payoutAccountName}</strong>.
              </>
            )}
          </p>
          <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
            <label style={{ flex: 1, minWidth: 140 }}>
              <span>Kode channel bank</span>
              <input
                value={channelCode}
                onChange={(e) => setChannelCode(e.target.value)}
                placeholder="mis. BCA, MANDIRI"
                disabled={!manage || !account.provisioned}
              />
            </label>
            <label style={{ flex: 1, minWidth: 160 }}>
              <span>Nomor rekening</span>
              <input
                value={accountNumber}
                onChange={(e) => setAccountNumber(e.target.value)}
                placeholder="mis. 1234567890"
                disabled={!manage || !account.provisioned}
              />
            </label>
            {manage && (
              <button
                className="primary"
                disabled={busy || !account.provisioned || !payoutDirty || !channelCode.trim() || !accountNumber.trim()}
                onClick={() =>
                  void run(
                    () => setPivotPayoutAccount({ channelCode: channelCode.trim(), accountNumber: accountNumber.trim() }),
                    'Rekening payout tersimpan',
                  )
                }
              >
                Simpan rekening
              </button>
            )}
          </div>
        </>
      )}
    </div>
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
          <label>
            <span>Nama bank</span>
            <input
              value={form.bankName ?? ''}
              onChange={(e) => onChange({ bankName: e.target.value })}
              placeholder="mis. BCA, Mandiri, BRI"
              maxLength={120}
              disabled={disabled}
            />
          </label>
          <label>
            <span>Nomor rekening</span>
            <input
              value={form.accountNumber ?? ''}
              onChange={(e) => onChange({ accountNumber: e.target.value })}
              placeholder="mis. 1234567890"
              maxLength={60}
              disabled={disabled}
            />
          </label>
          <label>
            <span>Atas nama</span>
            <input
              value={form.accountHolder ?? ''}
              onChange={(e) => onChange({ accountHolder: e.target.value })}
              placeholder="nama pemilik rekening"
              maxLength={160}
              disabled={disabled}
            />
          </label>
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
                    <button className="ghost" onClick={onRemoveQris}>
                      Batalkan
                    </button>
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
                    <button className="ghost" onClick={onRemoveQris}>
                      Hapus
                    </button>
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
    <label className="ghost" style={{ cursor: 'pointer' }}>
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

/**
 * Menampilkan URL callback per-tenant (readonly) + tombol salin. Operator menempelkannya ke
 * dashboard Pivot agar status pembayaran otomatis masuk. URL tak bisa disunting di sini —
 * ia turunan tenant + alamat aplikasi.
 */
function WebhookField({ url, hint, onCopy }: { url: string; hint: string; onCopy: () => void }) {
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
        {hint} Alamatnya unik per-tenant.
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
