import { useEffect, useState, type ReactNode } from 'react'
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
import { EmptyState, useToast } from '../components/ui'
import { IconAlert } from '../components/icons'

/**
 * Pengaturan Payment Gateway tenant.
 *
 * Tiap tenant memilih penyedia (Xendit/Paywuz/Pivot/Manual) & mode: BYO (akun sendiri) atau
 * PLATFORM (akun agregator platform lewat sub-account). Kredensial bersifat write-only:
 * dikirim saat menyimpan, tak pernah ditarik kembali — server hanya menandai sudah terisi.
 * Mode PLATFORM disiapkan admin platform (sub-account di-provisi), operator tenant hanya
 * mengaktifkannya di sini.
 */

const PROVIDERS: PaymentProvider[] = ['XENDIT', 'PAYWUZ', 'PIVOT', 'MANUAL']
const MODES: GatewayMode[] = ['BYO', 'PLATFORM']

const nullify = (s: string): string | null => {
  const t = s.trim()
  return t ? t : null
}

export function PaymentGatewaySettingsPage() {
  const { can } = useCan()
  const toast = useToast()
  const manage = can('billing.gateway.manage')

  const [form, setForm] = useState<PaymentGatewaySettingsView | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  // Input kredensial write-only, terpisah dari view: kosong = pertahankan yang tersimpan.
  const [apiKey, setApiKey] = useState('')
  const [secretKey, setSecretKey] = useState('')
  const [webhookToken, setWebhookToken] = useState('')

  useEffect(() => {
    getPaymentGatewaySettings()
      .then((s) => setForm(s))
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat setelan gateway'))
      .finally(() => setLoading(false))
  }, [toast])

  const patch = (delta: Partial<PaymentGatewaySettingsView>) => setForm((f) => (f ? { ...f, ...delta } : f))

  const save = async () => {
    if (!form) return
    setSaving(true)
    const body: UpdatePaymentGatewaySettingsRequest = {
      provider: form.provider,
      mode: form.mode,
      enabled: form.enabled,
      apiKey: nullify(apiKey),
      secretKey: nullify(secretKey),
      webhookToken: nullify(webhookToken),
    }
    try {
      const saved = await updatePaymentGatewaySettings(body)
      setForm(saved)
      setApiKey('')
      setSecretKey('')
      setWebhookToken('')
      toast.success('Setelan payment gateway disimpan')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan setelan')
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <p className="muted">Memuat setelan…</p>
  if (!form) {
    return (
      <EmptyState
        title="Setelan gateway tak tersedia"
        hint="Coba muat ulang halaman."
        icon={<IconAlert size={28} />}
      />
    )
  }

  const isXendit = form.provider === 'XENDIT'
  const isPivot = form.provider === 'PIVOT'
  const isPaywuz = form.provider === 'PAYWUZ'
  const unsupported = isPaywuz // Pivot kini didukung penuh; hanya Paywuz masih kerangka.

  return (
    <div className="stack">
      <div className="spread">
        <div>
          <h2 style={{ margin: 0 }}>Payment Gateway</h2>
          <p className="muted" style={{ margin: '0.25rem 0 0' }}>
            Penyedia pembayaran &amp; kredensial untuk menagih pelanggan otomatis.
          </p>
        </div>
        {manage && (
          <button className="primary" onClick={() => void save()} disabled={saving}>
            {saving ? 'Menyimpan…' : 'Simpan'}
          </button>
        )}
      </div>

      {!manage && (
        <p className="muted" style={{ margin: 0 }}>
          Anda hanya bisa melihat setelan ini. Perlu izin “Kelola payment gateway” untuk mengubahnya.
        </p>
      )}

      <div className="card stack">
        <SectionTitle>Penyedia &amp; mode</SectionTitle>

        <label className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
          <input
            type="checkbox"
            checked={form.enabled}
            onChange={(e) => patch({ enabled: e.target.checked })}
            disabled={!manage}
            style={{ width: 'auto' }}
          />
          <span>Aktifkan gateway (buat charge otomatis saat menerbitkan tagihan)</span>
        </label>
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Saat mati, tagihan tetap terbit tapi tanpa tautan bayar — pelunasan lewat catatan manual.
        </p>

        <label>
          <span>Penyedia</span>
          <select
            value={form.provider}
            onChange={(e) => patch({ provider: e.target.value as PaymentProvider })}
            disabled={!manage}
          >
            {PROVIDERS.map((p) => (
              <option key={p} value={p}>
                {PAYMENT_PROVIDER_LABEL[p]}
              </option>
            ))}
          </select>
        </label>

        {!SUPPORTED_PROVIDERS.includes(form.provider) && (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            ⚠️ Penyedia ini <strong>belum didukung</strong> — dokumentasi API-nya belum tersedia. Setelan bisa
            disimpan, tapi penerbitan tagihan otomatis akan gagal untuk tenant ini sampai integrasinya rampung.
          </p>
        )}

        <label>
          <span>Mode</span>
          <select
            value={form.mode}
            onChange={(e) => patch({ mode: e.target.value as GatewayMode })}
            disabled={!manage}
          >
            {MODES.map((m) => (
              <option key={m} value={m}>
                {GATEWAY_MODE_LABEL[m]}
              </option>
            ))}
          </select>
        </label>
      </div>

      {form.mode === 'PLATFORM' ? (
        <div className="card stack">
          <SectionTitle>Akun platform (agregator)</SectionTitle>
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Tagihan dibuat atas nama sub-account platform Anda; platform menampung dan meneruskan dana lalu
            memotong komisi. Sub-account disiapkan oleh admin platform.
          </p>
          <label>
            <span>Sub-account ID</span>
            <input
              value={form.subAccountId ?? ''}
              placeholder="belum diprovisi — hubungi admin platform"
              disabled
              readOnly
            />
          </label>
          {!form.subAccountId && (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
              Belum ada sub-account. Mode PLATFORM tak akan aktif sampai admin platform memprovisi sub-account Xendit
              untuk tenant ini.
            </p>
          )}
        </div>
      ) : (
        <div className="card stack">
          <SectionTitle>Kredensial (akun sendiri)</SectionTitle>

          {form.provider === 'MANUAL' && (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
              Mode manual tak butuh kredensial penyedia — pembayaran dicatat operator atau lewat webhook bersecret
              bersama. Kosongkan bagian ini.
            </p>
          )}

          {/* Merchant ID (apiKey) — Pivot: X-MERCHANT-ID; Paywuz: API key penyedia. */}
          {(isPivot || isPaywuz) && (
            <label>
              <span>
                {isPivot ? 'Merchant ID' : 'API key'} {form.apiKeySet && <span className="muted">(tersimpan)</span>}
              </span>
              <input
                type="password"
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
                placeholder={
                  form.apiKeySet
                    ? 'Biarkan kosong untuk mempertahankan'
                    : isPivot
                      ? 'X-MERCHANT-ID dari dashboard Pivot'
                      : 'API key penyedia'
                }
                disabled={!manage || unsupported}
              />
            </label>
          )}

          {/* Secret key (secretKey) — Xendit: secret key; Pivot: X-MERCHANT-SECRET. */}
          {(isXendit || isPivot) && (
            <label>
              <span>
                {isPivot ? 'Merchant Secret' : 'Secret key'}{' '}
                {form.secretKeySet && <span className="muted">(tersimpan)</span>}
              </span>
              <input
                type="password"
                value={secretKey}
                onChange={(e) => setSecretKey(e.target.value)}
                placeholder={
                  form.secretKeySet
                    ? 'Biarkan kosong untuk mempertahankan'
                    : isPivot
                      ? 'X-MERCHANT-SECRET dari dashboard Pivot'
                      : 'xnd_production_... / xnd_development_...'
                }
                disabled={!manage}
              />
            </label>
          )}

          {/* Token verifikasi callback (webhookToken) — Xendit: x-callback-token; Pivot: X-API-Key. */}
          {form.provider !== 'MANUAL' && (
            <label>
              <span>
                {isPivot ? 'Callback API Key' : 'Webhook token'}{' '}
                {form.webhookTokenSet && <span className="muted">(tersimpan)</span>}
              </span>
              <input
                type="password"
                value={webhookToken}
                onChange={(e) => setWebhookToken(e.target.value)}
                placeholder={
                  form.webhookTokenSet
                    ? 'Biarkan kosong untuk mempertahankan'
                    : isPivot
                      ? 'X-API-Key untuk verifikasi callback'
                      : 'Token verifikasi callback (x-callback-token)'
                }
                disabled={!manage}
              />
            </label>
          )}

          {isXendit && (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
              Secret key dari dashboard Xendit (Settings → API Keys). Webhook token = <code>x-callback-token</code> yang
              Xendit kirim di header callback; arahkan URL callback Xendit ke{' '}
              <code>/api/billing/webhooks/&lt;tenant&gt;/xendit</code>.
            </p>
          )}

          {isPivot && (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
              Merchant ID &amp; Secret dari dashboard Pivot (Settings → API Keys) — dipakai menukar access token.
              Callback API Key dari halaman Callbacks; arahkan URL callback Pivot ke{' '}
              <code>/api/billing/webhooks/&lt;tenant&gt;/pivot</code>. Pastikan server sudah mengisi{' '}
              <code>FTTH_BILLING_PIVOT_REDIRECT_BASE_URL</code> (mode REDIRECT butuh URL balik).
            </p>
          )}
        </div>
      )}
    </div>
  )
}

function SectionTitle({ children }: { children: ReactNode }) {
  return <h3 style={{ margin: '0.25rem 0 0', fontSize: '0.95rem', fontWeight: 650 }}>{children}</h3>
}
