import { useEffect, useState, type ReactNode } from 'react'
import { Text } from '@fluentui/react-components'
import { ApiError } from '../api/client'
import {
  getNotificationSettings,
  getQontakChannels,
  PROVIDER_LABEL,
  updateNotificationSettings,
  type NotificationSettingsView,
  type QontakChannelView,
  type UpdateNotificationSettingsRequest,
  type WhatsAppProvider,
} from '../api/notification'
import { useCan } from '../auth/useCan'
import { Button, EmptyState, SelectField, TextField } from '@/components/atoms'
import { TenantEmailBrandingCard, WhatsAppTemplateCard } from '@/components/organisms'
import { Checkbox } from '@fluentui/react-components'
import { useToast } from '@/system'
import { IconAlert } from '@/components/atoms/icons'

/**
 * Pengaturan Notifikasi tenant.
 *
 * Tiga bagian: (1) GATEWAY WhatsApp bawa-sendiri — tiap tenant memakai pengirimnya
 * sendiri (LOG mode uji / HTTP generik ala Fonnte-Wablas / Meta Cloud API / Mekari
 * Qontak) supaya identitas pengirim, biaya, dan risiko blokir terpisah antar-tenant;
 * (2) KANAL EMAIL — cuma satu saklar, karena server SMTP-nya milik platform, bukan
 * milik tenant; (3) SAKLAR pemicu otomatis — nyalakan/matikan tiap jenis pesan
 * (langganan, tagihan, WO, insiden) tanpa mengganggu yang lain; pesan yang menyala
 * berangkat lewat SEMUA kanal yang hidup. Token bersifat write-only: dikirim saat
 * menyimpan, tak pernah ditarik kembali — server hanya menandai sudah terisi atau belum.
 */

const PROVIDERS: WhatsAppProvider[] = ['LOG', 'HTTP_GENERIC', 'META_CLOUD', 'QONTAK']

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
  const [qontakToken, setQontakToken] = useState('')
  // Daftar kanal Qontak ditarik atas permintaan, bukan saat memuat halaman: panggilannya
  // menembak API Qontak dan hanya relevan bagi tenant yang memakai penyedia itu.
  const [channels, setChannels] = useState<QontakChannelView[] | null>(null)
  const [loadingChannels, setLoadingChannels] = useState(false)

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

  if (loading) return <Text as="p" className="muted">Memuat setelan…</Text>
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
          <Text as="h2" size={500} weight="semibold" style={{ margin: 0 }}>Pengaturan Notifikasi</Text>
          <Text as="p" className="muted" style={{ margin: '0.25rem 0 0' }}>
            Kanal pengiriman (WhatsApp &amp; email) dan saklar pemicu pesan otomatis ke pelanggan.
          </Text>
        </div>
        {manage && (
          <Button variant="primary" onClick={() => void save()} disabled={saving}>
            {saving ? 'Menyimpan…' : 'Simpan'}
          </Button>
        )}
      </div>

      {!manage && (
        <Text as="p" className="muted" style={{ margin: 0 }}>
          Anda hanya bisa melihat setelan ini. Perlu izin “Kelola gateway WA &amp; pemicu” untuk mengubahnya.
        </Text>
      )}

      {/* ---- Gateway WhatsApp ---- */}
      <div className="card stack">
        <SectionTitle>Gateway WhatsApp</SectionTitle>

        <Checkbox
          label="Aktifkan pengiriman (gateway hidup)"
          checked={form.gatewayEnabled}
          onChange={(_, data) => patch({ gatewayEnabled: !!data.checked })}
          disabled={!manage}
        />
        <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
          Saat mati, pesan pemicu tetap dicatat di riwayat sebagai <Text as="em" italic>SKIPPED</Text> — tak ada yang benar-benar terkirim.
        </Text>

        <SelectField
          label="Penyedia"
          value={form.provider}
          onChange={(_, data) => patch({ provider: data.value as WhatsAppProvider })}
          disabled={!manage}
        >
          {PROVIDERS.map((p) => (
            <option key={p} value={p}>
              {PROVIDER_LABEL[p]}
            </option>
          ))}
        </SelectField>

        {form.provider === 'LOG' && (
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
            Mode uji: pesan hanya dicatat ke log server, tak dikirim ke mana pun. Cocok untuk mencoba pemicu tanpa
            biaya WhatsApp.
          </Text>
        )}

        {form.provider === 'HTTP_GENERIC' && (
          <>
            <TextField
              label="URL endpoint"
              value={form.httpEndpointUrl ?? ''}
              onChange={(_, data) => patch({ httpEndpointUrl: data.value })}
              placeholder="https://api.fonnte.com/send"
              disabled={!manage}
            />
            <TextField
              label={<>Token / API key {form.httpTokenSet && <Text as="span" className="muted">(tersimpan)</Text>}</>}
              type="password"
              value={httpToken}
              onChange={(_, data) => setHttpToken(data.value)}
              placeholder={form.httpTokenSet ? 'Biarkan kosong untuk mempertahankan' : 'Token dikirim sebagai header Authorization'}
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
            <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
              Server mengirim POST <code>form-urlencoded</code> dengan kedua field di atas. Sesuaikan namanya dengan
              dokumentasi penyedia Anda.
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
              placeholder={form.metaAccessTokenSet ? 'Biarkan kosong untuk mempertahankan' : 'Token permanen dari Meta'}
              disabled={!manage}
            />
            <TextField
              label="WhatsApp Business Account ID (opsional)"
              value={form.metaWabaId ?? ''}
              onChange={(_, data) => patch({ metaWabaId: data.value })}
              placeholder="102290129340398"
              disabled={!manage}
              hint="Terlihat di Meta Business Manager → WhatsApp Accounts; dipakai untuk menarik daftar template."
            />
            <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
              Template yang dipakai tiap pemicu diatur di kartu <Text as="strong" weight="semibold" >Template pesan WhatsApp</Text> di bawah.
              Pemicu tanpa template dikirim sebagai teks biasa (hanya dalam jendela 24 jam).
            </Text>
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
                form.qontakAccessTokenSet ? 'Biarkan kosong untuk mempertahankan' : 'Access token dari dasbor Qontak'
              }
              disabled={!manage}
              hint="Dasbor Qontak → Integration → API. Token ini juga dipakai untuk mengelola template."
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
              Daftar channel ditarik memakai token yang <Text as="strong" weight="semibold" >sudah disimpan</Text> — tempel token lalu klik
              Simpan dulu, baru “Muat daftar channel”.
            </Text>
            <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
              Qontak <Text as="strong" weight="semibold" >hanya bisa mengirim template</Text>; tak ada jalur teks biasa. Setiap pemicu yang
              ingin dipakai wajib dipetakan ke satu template di kartu{' '}
              <Text as="strong" weight="semibold" >Template pesan WhatsApp</Text> di bawah, kalau tidak pesannya dilewati.
            </Text>
          </>
        )}
      </div>

      {/* ---- Kanal email ---- */}
      <div className="card stack">
        <SectionTitle>Kanal email</SectionTitle>

        <Checkbox
          label="Kirim juga lewat email"
          checked={form.emailEnabled}
          onChange={(_, data) => patch({ emailEnabled: !!data.checked })}
          disabled={!manage}
        />
        <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
          Berdiri sendiri dari gateway WhatsApp: boleh keduanya hidup (pelanggan menerima dua-duanya, tercatat
          sebagai dua siaran terpisah), atau email saja bila Anda belum punya gateway WA.
        </Text>
        <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
          Server email disediakan platform — tak ada SMTP yang perlu Anda setel. Surat berangkat atas{' '}
          <Text as="strong" weight="semibold" >nama perusahaan Anda</Text>, jadi pelanggan tetap mengenali pengirimnya. Pelanggan tanpa
          alamat email dilewati dan tercatat di riwayat.
        </Text>
      </div>

      {/* ---- Identitas & tampilan email (timpaan atas bawaan platform) ---- */}
      <TenantEmailBrandingCard manage={manage} />

      {/* ---- Template pesan WhatsApp ---- */}
      <WhatsAppTemplateCard templateReady={form.templateReady} />

      {/* ---- Pemicu otomatis ---- */}
      <div className="card stack">
        <SectionTitle>Pemicu otomatis</SectionTitle>
        <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
          Nyalakan jenis pesan yang ingin dikirim otomatis. Tiap pesan berangkat lewat semua kanal yang hidup di
          atas; tanpa satu pun kanal hidup, pesannya hanya tercatat sebagai <Text as="em" italic>SKIPPED</Text>.
        </Text>
        {(form.provider === 'META_CLOUD' || form.provider === 'QONTAK') && (
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
            Template yang dipakai tiap pemicu diatur di kartu <Text as="strong" weight="semibold" >Template pesan WhatsApp</Text> di atas.
          </Text>
        )}
        {TRIGGERS.map((t) => (
          <Checkbox
            key={t.key}
            label={
              <Text as="span">{t.label}
              <br />
              <Text as="span" className="muted" size={200}>
                {t.hint}
              </Text></Text>
            }
            checked={form[t.key] as boolean}
            onChange={(_, data) => patch({ [t.key]: !!data.checked } as Partial<NotificationSettingsView>)}
            disabled={!manage}
          />
        ))}
      </div>
    </div>
  )
}

function SectionTitle({ children }: { children: ReactNode }) {
  return (
    <Text as="h3" size={400} weight="semibold" style={{ margin: '0.25rem 0 0' }}>{children}</Text>
  )
}
