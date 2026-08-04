import { useEffect, useMemo, useState, type CSSProperties, type ReactNode } from 'react'
import { api, ApiError } from '../api/client'
import {
  deleteQrisImage,
  getPaymentGatewaySettings,
  GATEWAY_MODE_LABEL,
  PAYMENT_PROVIDER_LABEL,
  QRIS_IMAGE_PATH,
  SUPPORTED_PROVIDERS,
  updatePaymentGatewaySettings,
  uploadQrisImage,
  type GatewayMode,
  type PaymentGatewaySettingsView,
  type PaymentProvider,
  type UpdatePaymentGatewaySettingsRequest,
} from '../api/payment'
import { useAuth } from '../auth/useAuth'
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

/**
 * Penyedia gateway otomatis (tanpa MANUAL): hanya relevan saat gateway AKTIF. Saat nonaktif,
 * penyedia otomatis MANUAL (pelanggan bayar transfer/QRIS) tanpa dropdown — dropdown baru muncul
 * ketika operator mengaktifkan gateway.
 */
const GATEWAY_PROVIDERS: PaymentProvider[] = ['XENDIT', 'PIVOT', 'PAYWUZ']

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
  const { user } = useAuth()
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
    if (form.provider === 'PAYWUZ' && (form.paymentMethod ?? '') !== (saved.paymentMethod ?? '')) {
      out.push({
        label: 'Metode Paywuz',
        from: saved.paymentMethod || 'default server',
        to: form.paymentMethod || 'default server',
      })
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
  }, [saved, form, creds, qrisFile, qrisRemoved])

  const dirty = changes.length > 0
  const enabling = !!saved && !!form && form.enabled && !saved.enabled

  const onProvider = (provider: PaymentProvider) => {
    // Ganti penyedia mengosongkan input kredensial (spesifik penyedia) & memaksa BYO bila
    // penyedia tak mendukung PLATFORM (hanya Xendit/xenPlatform yang punya mode agregator).
    // Metode Paywuz hanya relevan untuk Paywuz — dibuang bila pindah penyedia.
    clearCreds()
    setForm((f) =>
      f
        ? {
            ...f,
            provider,
            mode: provider === 'XENDIT' ? f.mode : 'BYO',
            paymentMethod: provider === 'PAYWUZ' ? f.paymentMethod : null,
          }
        : f,
    )
  }

  // Nyalakan/matikan gateway. Nonaktif = penyedia otomatis MANUAL (pelanggan bayar manual),
  // dropdown penyedia disembunyikan; aktif = pilih penyedia gateway (default ke yang tersimpan
  // atau Xendit bila sebelumnya manual).
  const onToggleEnabled = (enabled: boolean) => {
    if (enabled) {
      const fallback: PaymentProvider = saved && saved.provider !== 'MANUAL' ? saved.provider : 'XENDIT'
      setForm((f) =>
        f ? { ...f, enabled: true, provider: f.provider !== 'MANUAL' ? f.provider : fallback } : f,
      )
    } else {
      clearCreds()
      setForm((f) => (f ? { ...f, enabled: false, provider: 'MANUAL', mode: 'BYO', paymentMethod: null } : f))
    }
  }

  const discard = () => {
    if (saved) setForm(saved)
    clearCreds()
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
      mode: form.mode,
      enabled: form.enabled,
      apiKey: creds.apiKey.trim() || null,
      secretKey: creds.secretKey.trim() || null,
      webhookToken: creds.webhookToken.trim() || null,
      // Metode hanya bermakna untuk Paywuz; penyedia lain selalu kirim null (kosongkan).
      paymentMethod: form.provider === 'PAYWUZ' ? form.paymentMethod?.trim() || null : null,
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
      clearCreds()
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

  const supported = SUPPORTED_PROVIDERS.includes(form.provider)
  const fields = credFields(form.provider)
  // URL webhook Paywuz per-tenant (readonly, untuk disalin ke dashboard Paywuz). Origin = URL aplikasi
  // saat ini; di produksi inilah alamat publik yang dipanggil balik oleh Paywuz.
  const paywuzWebhookUrl = `${window.location.origin}/api/billing/webhooks/${user?.tenantId ?? '<tenant>'}/paywuz`
  // Saat gateway mati (atau penyedia MANUAL), pembayaran manual adalah satu-satunya cara bayar.
  const showManual = !form.enabled || form.provider === 'MANUAL'

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

        <FormRow label="Status gateway" hint="Saat aktif, penerbitan tagihan otomatis membuat tautan bayar lewat penyedia. Saat mati, penyedia otomatis manual — tagihan terbit tanpa tautan & pelanggan bayar transfer/QRIS.">
          <Segmented
            value={form.enabled ? 'on' : 'off'}
            onChange={(v) => onToggleEnabled(v === 'on')}
            disabled={!manage}
            options={[
              { value: 'off', label: 'Nonaktif' },
              { value: 'on', label: 'Aktif' },
            ]}
          />
        </FormRow>

        {!form.enabled && (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Gateway nonaktif — pembayaran <strong>manual</strong> (transfer/QRIS), tanpa penyedia otomatis. Aktifkan
            untuk memilih penyedia (Xendit/Paywuz/Pivot).
          </p>
        )}

        {form.enabled && (
          <>
            <FormRow label="Penyedia">
              <select value={form.provider} onChange={(e) => onProvider(e.target.value as PaymentProvider)} disabled={!manage}>
                {GATEWAY_PROVIDERS.map((p) => (
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

            {form.provider === 'PAYWUZ' && (
              <>
                <PaywuzMethodField
                  value={form.paymentMethod}
                  onChange={(paymentMethod) => setForm({ ...form, paymentMethod })}
                  disabled={!manage}
                />
                <WebhookField url={paywuzWebhookUrl} onCopy={() => void copyToClipboard(paywuzWebhookUrl)} />
              </>
            )}

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
                <strong>API key</strong> proyek diambil dari dashboard Paywuz (<code>pk_live_…</code> untuk produksi,{' '}
                <code>pk_sand_…</code> untuk uji coba). Satu key ini dipakai untuk menagih <em>sekaligus</em> memverifikasi
                webhook — jadi tak ada token webhook terpisah. <strong>Metode pembayaran</strong> menentukan cara pelanggan
                membayar: <code>QRIS</code> menampilkan satu kode QR untuk semua bank/e-wallet, sedangkan{' '}
                <strong>Virtual Account</strong> membuat nomor VA dan pelanggan memilih banknya saat membayar. Salin{' '}
                <strong>URL webhook</strong> di bawah dan tempel ke dashboard Paywuz (menu Callback/Webhook) agar status
                pembayaran otomatis masuk ke sistem.
              </p>
            )}
              </div>
            )}
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

      {(saved.provider === 'MANUAL' || !saved.enabled) && <ManualSummary saved={saved} />}

      {saved.provider === 'MANUAL' ? null : saved.mode === 'BYO' ? (
        <div className="stack" style={{ gap: '0.4rem' }}>
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
          {saved.provider === 'PAYWUZ' && (
            <span className="muted" style={{ fontSize: '0.82rem' }}>
              Metode bayar: <strong>{saved.paymentMethod || 'default server'}</strong>
            </span>
          )}
        </div>
      ) : null}
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

/**
 * Seksi setelan pembayaran manual (tunai / transfer / QRIS) — muncul saat gateway nonaktif atau
 * penyedia MANUAL, di mana inilah satu-satunya cara pelanggan membayar. Tiap metode punya saklar;
 * Transfer membuka field rekening, QRIS membuka pengunggah gambar. Toggle & field ikut tombol
 * simpan utama; gambar QRIS diunggah/dihapus langsung (multipart) lewat [onUploadQris]/[onDeleteQris].
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
          Cara pelanggan membayar saat gateway otomatis nonaktif. Nyalakan metode yang Anda terima —
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

/** Kode meta-metode Paywuz yang didukung (dikirim apa adanya ke `POST /v1/transactions`). */
const PAYWUZ_METHODS: { code: string; label: string; hint: string }[] = [
  { code: 'QRIS', label: 'QRIS', hint: 'Satu kode QR untuk semua bank & e-wallet.' },
  { code: 'VA', label: 'Virtual Account (Pilih Bank)', hint: 'Pelanggan memilih bank lalu dapat nomor VA.' },
]

/**
 * Pemilih metode bayar Paywuz per-tenant — dropdown tetap berisi meta-metode yang didukung
 * (QRIS / Virtual Account). Nilai tersimpan yang di luar daftar tetap dimunculkan agar tak
 * diam-diam hilang. Kosong = pakai default server (`FTTH_BILLING_PAYWUZ_PAYMENT_METHOD`, mis. QRIS).
 */
function PaywuzMethodField({
  value,
  onChange,
  disabled,
}: {
  value: string | null
  onChange: (value: string | null) => void
  disabled?: boolean
}) {
  const known = PAYWUZ_METHODS.some((m) => m.code === value)
  const selected = PAYWUZ_METHODS.find((m) => m.code === value)
  return (
    <div className="stack" style={{ gap: '0.4rem' }}>
      <span style={{ fontSize: '0.9rem', fontWeight: 600 }}>Metode pembayaran</span>
      <select value={value ?? ''} onChange={(e) => onChange(e.target.value || null)} disabled={disabled}>
        <option value="">Default server (QRIS)</option>
        {value && !known && <option value={value}>{value} (tersimpan)</option>}
        {PAYWUZ_METHODS.map((m) => (
          <option key={m.code} value={m.code}>
            {m.label}
          </option>
        ))}
      </select>
      <span className="muted" style={{ fontSize: '0.82rem' }}>
        {selected ? selected.hint : 'Kosongkan untuk memakai metode default server (QRIS).'}
      </span>
    </div>
  )
}

/**
 * Menampilkan URL webhook per-tenant (readonly) + tombol salin. Operator menempelkannya ke
 * dashboard penyedia agar status pembayaran otomatis masuk. URL tak bisa disunting di sini —
 * ia turunan tenant + alamat aplikasi.
 */
function WebhookField({ url, onCopy }: { url: string; onCopy: () => void }) {
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
        Tempel ke menu Callback/Webhook di dashboard Paywuz. Alamatnya unik per-tenant.
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
