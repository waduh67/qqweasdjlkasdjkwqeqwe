import { useEffect, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import {
  getNotificationSettings,
  PROVIDER_LABEL,
  updateNotificationSettings,
  type NotificationSettingsView,
  type UpdateNotificationSettingsRequest,
  type WhatsAppProvider,
} from '../api/notification'
import { useCan } from '../auth/useCan'
import { EmptyState, useToast } from '../components/ui'
import { IconAlert } from '../components/icons'

/**
 * Pengaturan Notifikasi tenant.
 *
 * Dua bagian: (1) GATEWAY WhatsApp bawa-sendiri — tiap tenant memakai pengirimnya
 * sendiri (LOG mode uji / HTTP generik ala Fonnte-Wablas / Meta Cloud API) supaya
 * identitas pengirim, biaya, dan risiko blokir terpisah antar-tenant; (2) SAKLAR
 * pemicu otomatis — nyalakan/matikan tiap jenis pesan (langganan, tagihan, WO, insiden)
 * tanpa mengganggu yang lain. Token bersifat write-only: dikirim saat menyimpan, tak
 * pernah ditarik kembali — server hanya menandai sudah terisi atau belum.
 */

const PROVIDERS: WhatsAppProvider[] = ['LOG', 'HTTP_GENERIC', 'META_CLOUD']

const TRIGGERS: { key: keyof NotificationSettingsView; label: string; hint: string }[] = [
  {
    key: 'notifyOnSubscriptionLifecycle',
    label: 'Perubahan langganan',
    hint: 'Kirim saat langganan pelanggan aktif, diisolir, atau dihentikan.',
  },
  {
    key: 'notifyOnInvoiceReminder',
    label: 'Pengingat tagihan',
    hint: 'Ingatkan pelanggan menjelang jatuh tempo dan saat tagihan menunggak.',
  },
  {
    key: 'notifyOnWorkOrderSchedule',
    label: 'Jadwal kunjungan teknisi',
    hint: 'Beri tahu pelanggan saat work order dengan jadwal ditugaskan.',
  },
  {
    key: 'notifyOnIncidentOpen',
    label: 'Broadcast gangguan',
    hint: 'Siarkan otomatis ke seluruh pelanggan terdampak saat insiden terbuka.',
  },
]

const nullify = (s: string | null): string | null => {
  const t = (s ?? '').trim()
  return t ? t : null
}

export function NotificationSettingsPage() {
  const { can } = useCan()
  const toast = useToast()
  const manage = can('notification.settings.manage')

  const [form, setForm] = useState<NotificationSettingsView | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  // Input token write-only, terpisah dari view: kosong = pertahankan yang tersimpan.
  const [httpToken, setHttpToken] = useState('')
  const [metaToken, setMetaToken] = useState('')

  useEffect(() => {
    getNotificationSettings()
      .then((s) => setForm(s))
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat setelan notifikasi'))
      .finally(() => setLoading(false))
  }, [toast])

  const patch = (delta: Partial<NotificationSettingsView>) => setForm((f) => (f ? { ...f, ...delta } : f))

  const save = async () => {
    if (!form) return
    setSaving(true)
    const body: UpdateNotificationSettingsRequest = {
      provider: form.provider,
      gatewayEnabled: form.gatewayEnabled,
      httpEndpointUrl: nullify(form.httpEndpointUrl),
      httpToken: nullify(httpToken),
      httpPhoneField: nullify(form.httpPhoneField),
      httpMessageField: nullify(form.httpMessageField),
      metaPhoneNumberId: nullify(form.metaPhoneNumberId),
      metaAccessToken: nullify(metaToken),
      metaTemplateName: nullify(form.metaTemplateName),
      metaTemplateLang: nullify(form.metaTemplateLang),
      notifyOnSubscriptionLifecycle: form.notifyOnSubscriptionLifecycle,
      notifyOnInvoiceReminder: form.notifyOnInvoiceReminder,
      notifyOnWorkOrderSchedule: form.notifyOnWorkOrderSchedule,
      notifyOnIncidentOpen: form.notifyOnIncidentOpen,
    }
    try {
      const saved = await updateNotificationSettings(body)
      setForm(saved)
      setHttpToken('')
      setMetaToken('')
      toast.success('Setelan notifikasi disimpan')
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
        title="Setelan notifikasi tak tersedia"
        hint="Coba muat ulang halaman."
        icon={<IconAlert size={28} />}
      />
    )
  }

  return (
    <div className="stack">
      <div className="spread">
        <div>
          <h2 style={{ margin: 0 }}>Pengaturan Notifikasi</h2>
          <p className="muted" style={{ margin: '0.25rem 0 0' }}>
            Gateway WhatsApp bawa-sendiri &amp; saklar pemicu pesan otomatis ke pelanggan.
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
          Anda hanya bisa melihat setelan ini. Perlu izin “Kelola gateway WA &amp; pemicu” untuk mengubahnya.
        </p>
      )}

      {/* ---- Gateway WhatsApp ---- */}
      <div className="card stack">
        <SectionTitle>Gateway WhatsApp</SectionTitle>

        <label className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
          <input
            type="checkbox"
            checked={form.gatewayEnabled}
            onChange={(e) => patch({ gatewayEnabled: e.target.checked })}
            disabled={!manage}
            style={{ width: 'auto' }}
          />
          <span>Aktifkan pengiriman (gateway hidup)</span>
        </label>
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Saat mati, pesan pemicu tetap dicatat di riwayat sebagai <em>SKIPPED</em> — tak ada yang benar-benar terkirim.
        </p>

        <label>
          <span>Penyedia</span>
          <select
            value={form.provider}
            onChange={(e) => patch({ provider: e.target.value as WhatsAppProvider })}
            disabled={!manage}
          >
            {PROVIDERS.map((p) => (
              <option key={p} value={p}>
                {PROVIDER_LABEL[p]}
              </option>
            ))}
          </select>
        </label>

        {form.provider === 'LOG' && (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            Mode uji: pesan hanya dicatat ke log server, tak dikirim ke mana pun. Cocok untuk mencoba pemicu tanpa
            biaya WhatsApp.
          </p>
        )}

        {form.provider === 'HTTP_GENERIC' && (
          <>
            <label>
              <span>URL endpoint</span>
              <input
                value={form.httpEndpointUrl ?? ''}
                onChange={(e) => patch({ httpEndpointUrl: e.target.value })}
                placeholder="https://api.fonnte.com/send"
                disabled={!manage}
              />
            </label>
            <label>
              <span>Token / API key {form.httpTokenSet && <span className="muted">(tersimpan)</span>}</span>
              <input
                type="password"
                value={httpToken}
                onChange={(e) => setHttpToken(e.target.value)}
                placeholder={form.httpTokenSet ? 'Biarkan kosong untuk mempertahankan' : 'Token dikirim sebagai header Authorization'}
                disabled={!manage}
              />
            </label>
            <div className="row">
              <label style={{ flex: 1 }}>
                <span>Nama field nomor</span>
                <input
                  value={form.httpPhoneField}
                  onChange={(e) => patch({ httpPhoneField: e.target.value })}
                  placeholder="target"
                  disabled={!manage}
                />
              </label>
              <label style={{ flex: 1 }}>
                <span>Nama field pesan</span>
                <input
                  value={form.httpMessageField}
                  onChange={(e) => patch({ httpMessageField: e.target.value })}
                  placeholder="message"
                  disabled={!manage}
                />
              </label>
            </div>
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
              Server mengirim POST <code>form-urlencoded</code> dengan kedua field di atas. Sesuaikan namanya dengan
              dokumentasi penyedia Anda.
            </p>
          </>
        )}

        {form.provider === 'META_CLOUD' && (
          <>
            <label>
              <span>Phone Number ID</span>
              <input
                value={form.metaPhoneNumberId ?? ''}
                onChange={(e) => patch({ metaPhoneNumberId: e.target.value })}
                placeholder="1234567890"
                disabled={!manage}
              />
            </label>
            <label>
              <span>Access token {form.metaAccessTokenSet && <span className="muted">(tersimpan)</span>}</span>
              <input
                type="password"
                value={metaToken}
                onChange={(e) => setMetaToken(e.target.value)}
                placeholder={form.metaAccessTokenSet ? 'Biarkan kosong untuk mempertahankan' : 'Token permanen dari Meta'}
                disabled={!manage}
              />
            </label>
            <div className="row">
              <label style={{ flex: 2 }}>
                <span>Nama template (opsional)</span>
                <input
                  value={form.metaTemplateName ?? ''}
                  onChange={(e) => patch({ metaTemplateName: e.target.value })}
                  placeholder="kosong = kirim pesan teks biasa"
                  disabled={!manage}
                />
              </label>
              <label style={{ flex: 1 }}>
                <span>Bahasa template</span>
                <input
                  value={form.metaTemplateLang}
                  onChange={(e) => patch({ metaTemplateLang: e.target.value })}
                  placeholder="id"
                  disabled={!manage}
                />
              </label>
            </div>
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
              Bila template diisi, pesan dikirim sebagai template dengan isi sebagai parameter body{' '}
              <code>{'{{1}}'}</code>. Kosongkan untuk pesan teks biasa (hanya dalam jendela 24 jam).
            </p>
          </>
        )}
      </div>

      {/* ---- Pemicu otomatis ---- */}
      <div className="card stack">
        <SectionTitle>Pemicu otomatis</SectionTitle>
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Nyalakan jenis pesan yang ingin dikirim otomatis. Semua butuh gateway di atas hidup.
        </p>
        {TRIGGERS.map((t) => (
          <label key={t.key} className="row" style={{ gap: '0.6rem', alignItems: 'flex-start' }}>
            <input
              type="checkbox"
              checked={form[t.key] as boolean}
              onChange={(e) => patch({ [t.key]: e.target.checked } as Partial<NotificationSettingsView>)}
              disabled={!manage}
              style={{ width: 'auto', marginTop: '0.2rem' }}
            />
            <span>
              {t.label}
              <br />
              <span className="muted" style={{ fontSize: '0.85rem' }}>
                {t.hint}
              </span>
            </span>
          </label>
        ))}
      </div>
    </div>
  )
}

function SectionTitle({ children }: { children: ReactNode }) {
  return (
    <h3 style={{ margin: '0.25rem 0 0', fontSize: '0.95rem', fontWeight: 650 }}>{children}</h3>
  )
}
