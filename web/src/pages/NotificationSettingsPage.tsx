import { useEffect, useState, type ReactNode } from 'react'
import { Text } from '@fluentui/react-components'
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import {
  getNotificationSettings,
  getQontakChannels,
  PROVIDER_LABEL,
  sendWhatsAppTest,
  updateNotificationSettings,
  type NotificationSettingsView,
  type QontakChannelView,
  type UpdateNotificationSettingsRequest,
  type WhatsAppProvider,
  type WhatsAppTestRequest,
} from '../api/notification'
import { useCan } from '../auth/useCan'
import { Button, EmptyState, SelectField, TextareaField, TextField } from '@/components/atoms'
import { Tabs } from '@/components/molecules'
import { TenantEmailBrandingCard, WhatsAppTemplateCard } from '@/components/organisms'
import { Checkbox } from '@fluentui/react-components'
import { useToast } from '@/system'
import { IconAlert } from '@/components/atoms/icons'

/**
 * Pengaturan Notifikasi tenant.
 *
 * Tiga bagian: (1) GATEWAY WhatsApp bawa-sendiri — tiap tenant memakai pengirimnya
 * sendiri (LOG mode uji / HTTP generik / Fonnte / Meta Cloud API / Mekari
 * Qontak) supaya identitas pengirim, biaya, dan risiko blokir terpisah antar-tenant;
 * (2) KANAL EMAIL — cuma satu saklar, karena server SMTP-nya milik platform, bukan
 * milik tenant; (3) SAKLAR pemicu otomatis — nyalakan/matikan tiap jenis pesan
 * (langganan, tagihan, WO, insiden) tanpa mengganggu yang lain; pesan yang menyala
 * berangkat lewat SEMUA kanal yang hidup. Token bersifat write-only: dikirim saat
 * menyimpan, tak pernah ditarik kembali — server hanya menandai sudah terisi atau belum.
 */

const PROVIDERS: WhatsAppProvider[] = ['LOG', 'HTTP_GENERIC', 'FONNTE', 'META_CLOUD', 'QONTAK']
const DEFAULT_TEST_MESSAGE = 'Pesan uji konfigurasi Fonnte dari aplikasi FTTH.'

type NotificationTab = 'whatsapp' | 'email' | 'triggers'

const NOTIFICATION_TABS: { key: NotificationTab; label: string }[] = [
  { key: 'whatsapp', label: 'WhatsApp' },
  { key: 'email', label: 'Email' },
  { key: 'triggers', label: 'Pemicu otomatis' },
]

type TriggerKey =
  | 'notifyOnSubscriptionLifecycle'
  | 'notifyOnInvoiceReminder'
  | 'notifyOnWorkOrderSchedule'
  | 'notifyOnIncidentOpen'

const TRIGGERS: { key: TriggerKey; label: string }[] = [
  { key: 'notifyOnSubscriptionLifecycle', label: 'Perubahan langganan' },
  { key: 'notifyOnInvoiceReminder', label: 'Pengingat tagihan' },
  { key: 'notifyOnWorkOrderSchedule', label: 'Jadwal kunjungan teknisi' },
  { key: 'notifyOnIncidentOpen', label: 'Broadcast gangguan' },
]

const nullify = (s: string | null): string | null => {
  const t = (s ?? '').trim()
  return t ? t : null
}

function isWhatsAppProvider(value: string): value is WhatsAppProvider {
  return PROVIDERS.some((provider) => provider === value)
}

function isTestableProvider(provider: WhatsAppProvider): provider is WhatsAppTestRequest['provider'] {
  return provider === 'FONNTE' || provider === 'HTTP_GENERIC'
}

export function NotificationSettingsPage() {
  const { can } = useCan()
  const toast = useToast()
  const manage = can('notification.settings.manage')
  const location = useLocation()
  const navigate = useNavigate()

  const [form, setForm] = useState<NotificationSettingsView | null>(null)
  const [persistedProvider, setPersistedProvider] = useState<WhatsAppProvider | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  // Input token write-only, terpisah dari view: kosong = pertahankan yang tersimpan.
  const [httpToken, setHttpToken] = useState('')
  const [metaToken, setMetaToken] = useState('')
  const [qontakToken, setQontakToken] = useState('')
  const [testDestination, setTestDestination] = useState('')
  const [testMessage, setTestMessage] = useState(DEFAULT_TEST_MESSAGE)
  const [testingWhatsApp, setTestingWhatsApp] = useState(false)
  // Daftar kanal Qontak ditarik atas permintaan, bukan saat memuat halaman: panggilannya
  // menembak API Qontak dan hanya relevan bagi tenant yang memakai penyedia itu.
  const [channels, setChannels] = useState<QontakChannelView[] | null>(null)
  const [loadingChannels, setLoadingChannels] = useState(false)

  useEffect(() => {
    getNotificationSettings()
      .then((settings) => {
        setForm(settings)
        setPersistedProvider(settings.provider)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat setelan notifikasi'))
      .finally(() => setLoading(false))
  }, [toast])

  const patch = (delta: Partial<NotificationSettingsView>) => setForm((f) => (f ? { ...f, ...delta } : f))
  const patchTrigger = (key: TriggerKey, checked: boolean) => {
    setForm((current) => (current ? { ...current, [key]: checked } : current))
  }

  const save = async () => {
    if (!form) return
    setSaving(true)
    const body: UpdateNotificationSettingsRequest = {
      provider: form.provider,
      gatewayEnabled: form.gatewayEnabled,
      emailEnabled: form.emailEnabled,
      httpEndpointUrl: nullify(form.httpEndpointUrl),
      httpToken: nullify(httpToken),
      httpPhoneField: nullify(form.httpPhoneField),
      httpMessageField: nullify(form.httpMessageField),
      metaPhoneNumberId: nullify(form.metaPhoneNumberId),
      metaAccessToken: nullify(metaToken),
      metaWabaId: nullify(form.metaWabaId),
      qontakAccessToken: nullify(qontakToken),
      qontakChannelIntegrationId: nullify(form.qontakChannelIntegrationId),
      notifyOnSubscriptionLifecycle: form.notifyOnSubscriptionLifecycle,
      notifyOnInvoiceReminder: form.notifyOnInvoiceReminder,
      notifyOnWorkOrderSchedule: form.notifyOnWorkOrderSchedule,
      notifyOnIncidentOpen: form.notifyOnIncidentOpen,
    }
    try {
      const saved = await updateNotificationSettings(body)
      setForm(saved)
      setPersistedProvider(saved.provider)
      setHttpToken('')
      setMetaToken('')
      setQontakToken('')
      toast.success('Setelan notifikasi disimpan')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan setelan')
    } finally {
      setSaving(false)
    }
  }

  const loadChannels = async () => {
    setLoadingChannels(true)
    try {
      const list = await getQontakChannels()
      setChannels(list)
      if (list.length === 0) {
        toast.error('Tak ada kanal WhatsApp aktif di akun Qontak — pastikan tokennya sudah disimpan')
      }
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat daftar channel Qontak')
    } finally {
      setLoadingChannels(false)
    }
  }

  const testWhatsApp = async () => {
    if (!form || !isTestableProvider(form.provider)) return
    if (!testDestination.trim() || !testMessage.trim()) return
    const provider = form.provider
    setTestingWhatsApp(true)
    try {
      const result = await sendWhatsAppTest({
        provider,
        destination: testDestination.trim(),
        message: testMessage.trim(),
        httpToken: nullify(httpToken),
        httpEndpointUrl: nullify(form.httpEndpointUrl),
        httpPhoneField: nullify(form.httpPhoneField),
        httpMessageField: nullify(form.httpMessageField),
      })
      if (result.delivered) {
        toast.success(`${PROVIDER_LABEL[provider]} menerima permintaan uji. Pesan masih dapat menunggu di antrean penyedia.`)
      } else {
        toast.error(`${PROVIDER_LABEL[provider]} tidak menerima permintaan uji: ${result.detail}`)
      }
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Pengujian WhatsApp gagal')
    } finally {
      setTestingWhatsApp(false)
    }
  }

  if (loading) return <Text as="p" className="muted">Memuat setelan…</Text>
  if (!form) {
    return (
      <EmptyState
        title="Setelan notifikasi tak tersedia"
        icon={<IconAlert size={28} />}
      />
    )
  }

  const pathSegment = location.pathname.split('/').filter(Boolean).at(-1)
  const activeTab: NotificationTab =
    pathSegment === 'email' || pathSegment === 'triggers' ? pathSegment : 'whatsapp'
  const storedHttpTokenAvailable = persistedProvider === form.provider && form.httpTokenSet
  const canTestWhatsApp =
    isTestableProvider(form.provider) &&
    testDestination.trim().length > 0 &&
    testMessage.trim().length > 0 &&
    (form.provider === 'HTTP_GENERIC'
      ? Boolean(form.httpEndpointUrl?.trim())
      : Boolean(httpToken.trim()) || storedHttpTokenAvailable)

  return (
    <div className="stack">
      <div className="spread">
        <div>
          <Text as="h2" size={500} weight="semibold" style={{ margin: 0 }}>Pengaturan Notifikasi</Text>
        </div>
        {manage && (
          <Button variant="primary" onClick={() => void save()} disabled={saving || testingWhatsApp}>
            {saving ? 'Menyimpan…' : 'Simpan'}
          </Button>
        )}
      </div>

      <div className="workspace-tabs">
        <Tabs
          tabs={NOTIFICATION_TABS}
          active={activeTab}
          onChange={(tab) => navigate(`/notifications/${tab}`)}
          idPrefix="notification-settings"
        />
      </div>

      <Routes>
        <Route index element={<Navigate to="/notifications/whatsapp" replace />} />
        <Route
          path="whatsapp"
          element={(
            <div
              className="stack"
              role="tabpanel"
              id="notification-settings-panel-whatsapp"
              aria-labelledby="notification-settings-tab-whatsapp"
            >
              <div className="card stack">
        <SectionTitle>Gateway WhatsApp</SectionTitle>

        <Checkbox
              label="Aktifkan pengiriman"
          checked={form.gatewayEnabled}
          onChange={(_, data) => patch({ gatewayEnabled: !!data.checked })}
          disabled={!manage}
        />
        <SelectField
          label="Penyedia"
          value={form.provider}
          onChange={(_, data) => {
            if (isWhatsAppProvider(data.value)) patch({ provider: data.value })
          }}
          disabled={!manage}
        >
          {PROVIDERS.map((p) => (
            <option key={p} value={p}>
              {PROVIDER_LABEL[p]}
            </option>
          ))}
        </SelectField>

        {form.provider === 'HTTP_GENERIC' && (
          <>
            <TextField
              label="URL endpoint"
              value={form.httpEndpointUrl ?? ''}
              onChange={(_, data) => patch({ httpEndpointUrl: data.value })}
              placeholder="https://gateway.example/api/send"
              disabled={!manage}
            />
            <TextField
              label={<>Token / API key {storedHttpTokenAvailable && <Text as="span" className="muted">(tersimpan)</Text>}</>}
              type="password"
              value={httpToken}
              onChange={(_, data) => setHttpToken(data.value)}
              placeholder={storedHttpTokenAvailable ? 'Kosongkan untuk mempertahankan token' : 'Token dikirim sebagai header Authorization'}
              disabled={!manage}
            />
            <div className="row">
              <TextField
                label="Nama field nomor"
                value={form.httpPhoneField}
                onChange={(_, data) => patch({ httpPhoneField: data.value })}
                placeholder="target"
                disabled={!manage}
                style={{ flex: 1 }}
              />
              <TextField
                label="Nama field pesan"
                value={form.httpMessageField}
                onChange={(_, data) => patch({ httpMessageField: data.value })}
                placeholder="message"
                disabled={!manage}
                style={{ flex: 1 }}
              />
            </div>
          </>
        )}

        {form.provider === 'FONNTE' && (
          <>
            <TextField
              label={<>Token Fonnte {storedHttpTokenAvailable && <Text as="span" className="muted">(tersimpan)</Text>}</>}
              type="password"
              value={httpToken}
              onChange={(_, data) => setHttpToken(data.value)}
              placeholder={storedHttpTokenAvailable ? 'Kosongkan untuk mempertahankan token' : 'Token dari dasbor Fonnte'}
              disabled={!manage || testingWhatsApp}
            />
            <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
              Endpoint dan format Fonnte sudah tetap: aplikasi mengirim field <code>target</code> dan <code>message</code>.
            </Text>
          </>
        )}

        {form.provider === 'META_CLOUD' && (
          <>
            <TextField
              label="Phone Number ID"
              value={form.metaPhoneNumberId ?? ''}
              onChange={(_, data) => patch({ metaPhoneNumberId: data.value })}
              placeholder="1234567890"
              disabled={!manage}
            />
            <TextField
              label={<>Access token {form.metaAccessTokenSet && <Text as="span" className="muted">(tersimpan)</Text>}</>}
              type="password"
              value={metaToken}
              onChange={(_, data) => setMetaToken(data.value)}
               placeholder={form.metaAccessTokenSet ? 'Kosongkan untuk mempertahankan token' : 'Token permanen dari Meta'}
              disabled={!manage}
            />
            <TextField
              label="WhatsApp Business Account ID (opsional)"
              value={form.metaWabaId ?? ''}
              onChange={(_, data) => patch({ metaWabaId: data.value })}
              placeholder="102290129340398"
              disabled={!manage}

            />

          </>
        )}

        {form.provider === 'QONTAK' && (
          <>
            <TextField
              label={<>Access token {form.qontakAccessTokenSet && <Text as="span" className="muted">(tersimpan)</Text>}</>}
              type="password"
              value={qontakToken}
              onChange={(_, data) => setQontakToken(data.value)}
              placeholder={
                 form.qontakAccessTokenSet ? 'Kosongkan untuk mempertahankan token' : 'Access token dari dasbor Qontak'
              }
              disabled={!manage}

            />
            <div className="row" style={{ alignItems: 'flex-end' }}>
              <SelectField
                label="Channel WhatsApp"
                value={form.qontakChannelIntegrationId ?? ''}
                onChange={(_, data) => patch({ qontakChannelIntegrationId: data.value || null })}
                disabled={!manage}
                style={{ flex: 1 }}
              >
                <option value="">— belum dipilih —</option>
                {/*
                  Kanal tersimpan selalu ikut ditampilkan meski daftar belum ditarik, supaya
                  membuka halaman lalu menyimpan tak diam-diam mengosongkan pilihan yang sudah ada.
                */}
                {channels === null && form.qontakChannelIntegrationId && (
                  <option value={form.qontakChannelIntegrationId}>{form.qontakChannelIntegrationId}</option>
                )}
                {(channels ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </SelectField>
              {manage && (
                <Button onClick={() => void loadChannels()} disabled={loadingChannels}>
                  {loadingChannels ? 'Memuat…' : 'Muat daftar channel'}
                </Button>
              )}
            </div>


            <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
              Qontak <Text as="strong" weight="semibold" >hanya bisa mengirim template</Text>; tak ada jalur teks biasa. Setiap pemicu yang
              ingin dipakai wajib dipetakan ke satu template di kartu{' '}
              <Text as="strong" weight="semibold" >Template pesan WhatsApp</Text> di bawah, kalau tidak pesannya dilewati.
            </Text>
          </>
        )}
              </div>

              {isTestableProvider(form.provider) && (
                <div className="card stack">
                  <SectionTitle>Test pengiriman WhatsApp</SectionTitle>
                  <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
                    Kirim pesan uji memakai konfigurasi yang sedang diisi, tanpa perlu menyimpan atau mengaktifkan gateway.
                  </Text>
                  <TextField
                    label="Nomor tujuan uji"
                    value={testDestination}
                    onChange={(_, data) => setTestDestination(data.value)}
                    placeholder="628123456789"
                    disabled={!manage || saving || testingWhatsApp}
                  />
                  <TextareaField
                    label="Pesan uji"
                    value={testMessage}
                    onChange={(_, data) => setTestMessage(data.value)}
                    rows={4}
                    maxLength={2000}
                    disabled={!manage || saving || testingWhatsApp}
                  />
                  <div className="row">
                    <Button
                      onClick={() => void testWhatsApp()}
                      disabled={!manage || saving || testingWhatsApp || !canTestWhatsApp}
                    >
                      {testingWhatsApp ? 'Mengirim test…' : 'Kirim pesan test'}
                    </Button>
                  </div>
                </div>
              )}

              <WhatsAppTemplateCard templateReady={form.templateReady} />
            </div>
          )}
        />
        <Route
          path="email"
          element={(
            <div
              className="stack"
              role="tabpanel"
              id="notification-settings-panel-email"
              aria-labelledby="notification-settings-tab-email"
            >
              <div className="card stack">
        <SectionTitle>Kanal email</SectionTitle>

        <Checkbox
          label="Kirim juga lewat email"
          checked={form.emailEnabled}
          onChange={(_, data) => patch({ emailEnabled: !!data.checked })}
          disabled={!manage}
        />


              </div>
              <TenantEmailBrandingCard manage={manage} />
            </div>
          )}
        />
        <Route
          path="triggers"
          element={(
            <div
              className="card stack"
              role="tabpanel"
              id="notification-settings-panel-triggers"
              aria-labelledby="notification-settings-tab-triggers"
            >
        <SectionTitle>Pemicu otomatis</SectionTitle>
        {TRIGGERS.map((t) => (
          <Checkbox
            key={t.key}
            label={t.label}
            checked={form[t.key]}
            onChange={(_, data) => patchTrigger(t.key, Boolean(data.checked))}
            disabled={!manage}
          />
        ))}
            </div>
          )}
        />
        <Route path="*" element={<Navigate to="/notifications/whatsapp" replace />} />
      </Routes>
    </div>
  )
}

function SectionTitle({ children }: { children: ReactNode }) {
  return (
    <Text as="h3" size={400} weight="semibold" style={{ margin: '0.25rem 0 0' }}>{children}</Text>
  )
}
