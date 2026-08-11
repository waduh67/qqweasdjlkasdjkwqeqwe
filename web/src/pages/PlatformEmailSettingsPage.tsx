import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import {
  deletePlatformEmailLogo,
  getPlatformEmailSettings,
  previewPlatformEmail,
  sendPlatformTestEmail,
  subjectsToPayload,
  updatePlatformEmailSettings,
  uploadPlatformEmailLogo,
  EMAIL_TRIGGER_LABEL,
  PLATFORM_EMAIL_LOGO_PATH,
  type EmailSubjectView,
  type EmailTrigger,
  type PlatformEmailSettingsView,
  type UpdatePlatformEmailSettingsRequest,
} from '../api/emailSettings'
import { useCan } from '../auth/useCan'
import { Badge, Button, EmptyState, Segmented, TextField } from '@/components/atoms'
import { IconAlert } from '@/components/atoms/icons'
import { Modal, PageHeader } from '@/components/molecules'
import {
  DEFAULT_ACCENT,
  EmailAppearanceFields,
  EmailLogoField,
  EmailPreviewPanel,
  EmailSubjectFields,
  isValidAccent,
} from '@/components/organisms'
import { useToast } from '@/system'

/**
 * Setelan email milik PLATFORM: satu relay SMTP dan satu tampilan bawaan untuk semua tenant.
 *
 * Halaman ini adalah "lantai dasar" — apa pun yang tak ditimpa tenant di Pengaturan Notifikasi
 * jatuh ke nilai di sini. Karena itu kolom yang kosong bukan berarti fitur mati: host SMTP
 * kosong berarti pengiriman memakai kredensial dari env (perilaku deploy lama), bukan berarti
 * email berhenti terkirim. Ketegasan itu ditulis di teks bantu supaya tak ada yang mengisi
 * ulang setelan yang sudah berjalan hanya karena kolomnya terlihat kosong.
 *
 * Pola suntingannya sama dengan [TaxSettingsPage]: `saved` (yang berlaku) dipisah dari `form`
 * (suntingan), simpan minta konfirmasi diff. Logo dikecualikan — ia diunggah seketika, karena
 * byte gambar tak punya wujud "belum disimpan" yang masuk akal untuk ditinjau di dialog diff.
 */

interface PlatformForm {
  smtpHost: string
  smtpPort: string
  smtpUsername: string
  smtpPassword: string
  smtpAuth: boolean
  smtpStartTls: boolean
  fromAddress: string
  fromName: string
  accentColor: string
  footerText: string
  signatureText: string
  publicBaseUrl: string
  subjects: EmailSubjectView[]
}

function toForm(v: PlatformEmailSettingsView): PlatformForm {
  return {
    smtpHost: v.smtpHost ?? '',
    smtpPort: String(v.smtpPort),
    smtpUsername: v.smtpUsername ?? '',
    smtpPassword: '', // write-only: yang tersimpan tak pernah dikirim balik ke layar
    smtpAuth: v.smtpAuth,
    smtpStartTls: v.smtpStartTls,
    fromAddress: v.fromAddress ?? '',
    fromName: v.fromName,
    accentColor: v.accentColor ?? '',
    footerText: v.footerText ?? '',
    signatureText: v.signatureText ?? '',
    publicBaseUrl: v.publicBaseUrl ?? '',
    subjects: v.subjects.map((s) => ({ ...s })),
  }
}

interface FieldChange {
  label: string
  from: string
  to: string
}

const EMAIL_RE = /^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/

function validPort(value: string): boolean {
  const n = Number(value)
  return value.trim() !== '' && Number.isInteger(n) && n >= 1 && n <= 65535
}

export function PlatformEmailSettingsPage() {
  const { can } = useCan()
  const toast = useToast()
  const manage = can('platform.email.manage')

  const [saved, setSaved] = useState<PlatformEmailSettingsView | null>(null)
  const [form, setForm] = useState<PlatformForm | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [logoBusy, setLogoBusy] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  // Dinaikkan tiap kali setelan tersimpan berubah, supaya pratinjau & logo ditarik ulang.
  const [freshness, setFreshness] = useState(0)

  useEffect(() => {
    getPlatformEmailSettings()
      .then((s) => {
        setSaved(s)
        setForm(toForm(s))
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat setelan email'))
      .finally(() => setLoading(false))
  }, [toast])

  const patch = (p: Partial<PlatformForm>) => setForm((f) => (f ? { ...f, ...p } : f))

  const patchSubject = (trigger: EmailTrigger, subject: string) =>
    setForm((f) =>
      f
        ? { ...f, subjects: f.subjects.map((s) => (s.trigger === trigger ? { ...s, subject } : s)) }
        : f,
    )

  const changes = useMemo<FieldChange[]>(() => {
    if (!saved || !form) return []
    const base = toForm(saved)
    const out: FieldChange[] = []
    const kosong = '(kosong)'
    const text = (label: string, from: string, to: string) => {
      if (from !== to) out.push({ label, from: from || kosong, to: to || kosong })
    }
    text('Host SMTP', base.smtpHost, form.smtpHost)
    text('Port SMTP', base.smtpPort, form.smtpPort)
    text('Username SMTP', base.smtpUsername, form.smtpUsername)
    if (form.smtpPassword.trim()) {
      out.push({ label: 'Password SMTP', from: saved.smtpPasswordSet ? '(tersimpan)' : kosong, to: '(diganti)' })
    }
    if (form.smtpAuth !== base.smtpAuth) {
      out.push({ label: 'Autentikasi SMTP', from: base.smtpAuth ? 'Aktif' : 'Nonaktif', to: form.smtpAuth ? 'Aktif' : 'Nonaktif' })
    }
    if (form.smtpStartTls !== base.smtpStartTls) {
      out.push({ label: 'STARTTLS', from: base.smtpStartTls ? 'Aktif' : 'Nonaktif', to: form.smtpStartTls ? 'Aktif' : 'Nonaktif' })
    }
    text('Alamat pengirim', base.fromAddress, form.fromAddress)
    text('Nama pengirim', base.fromName, form.fromName)
    text('URL publik aplikasi', base.publicBaseUrl, form.publicBaseUrl)
    text('Warna aksen', base.accentColor, form.accentColor)
    text('Tanda tangan', base.signatureText, form.signatureText)
    text('Footer', base.footerText, form.footerText)
    for (const row of form.subjects) {
      const before = base.subjects.find((s) => s.trigger === row.trigger)?.subject ?? ''
      text(`Subjek — ${EMAIL_TRIGGER_LABEL[row.trigger]}`, before, row.subject ?? '')
    }
    return out
  }, [saved, form])

  const dirty = changes.length > 0
  const portOk = !!form && validPort(form.smtpPort)
  const fromOk = !!form && (form.fromAddress.trim() === '' || EMAIL_RE.test(form.fromAddress.trim()))
  const accentOk = !!form && isValidAccent(form.accentColor)
  const valid = portOk && fromOk && accentOk && !!form && form.fromName.trim() !== ''

  const discard = () => {
    if (saved) setForm(toForm(saved))
  }

  /** Terapkan hasil server ke `saved` + `form` sekaligus; dipakai simpan maupun aksi logo. */
  const adopt = (result: PlatformEmailSettingsView) => {
    setSaved(result)
    setForm(toForm(result))
    setFreshness((n) => n + 1)
  }

  const doSave = async () => {
    if (!form) return
    setSaving(true)
    const body: UpdatePlatformEmailSettingsRequest = {
      smtpHost: form.smtpHost.trim() || null,
      smtpPort: Number(form.smtpPort),
      smtpUsername: form.smtpUsername.trim() || null,
      smtpPassword: form.smtpPassword.trim() || null,
      smtpAuth: form.smtpAuth,
      smtpStartTls: form.smtpStartTls,
      fromAddress: form.fromAddress.trim() || null,
      fromName: form.fromName.trim() || null,
      accentColor: form.accentColor.trim() || null,
      footerText: form.footerText.trim() || null,
      signatureText: form.signatureText.trim() || null,
      publicBaseUrl: form.publicBaseUrl.trim() || null,
      subjects: subjectsToPayload(form.subjects),
    }
    try {
      adopt(await updatePlatformEmailSettings(body))
      setConfirmOpen(false)
      toast.success('Setelan email platform disimpan')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan setelan email')
    } finally {
      setSaving(false)
    }
  }

  const pickLogo = async (file: File) => {
    setLogoBusy(true)
    try {
      adopt(await uploadPlatformEmailLogo(file))
      toast.success('Logo email diperbarui')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengunggah logo')
    } finally {
      setLogoBusy(false)
    }
  }

  const removeLogo = async () => {
    setLogoBusy(true)
    try {
      adopt(await deletePlatformEmailLogo())
      toast.success('Logo email dihapus')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus logo')
    } finally {
      setLogoBusy(false)
    }
  }

  if (loading) return <p className="muted">Memuat setelan…</p>
  if (!form || !saved) {
    return (
      <EmptyState
        title="Setelan email tak tersedia"
        hint="Coba muat ulang halaman."
        icon={<IconAlert size={28} />}
      />
    )
  }

  return (
    <div className="stack settings-page">
      <PageHeader
        title="Setelan Email"
        subtitle="Relay SMTP, identitas pengirim, dan tampilan bawaan email untuk seluruh tenant. Tenant boleh menimpa identitas, logo, warna, dan subjeknya sendiri."
      />

      <div className="card stack" style={{ gap: '0.75rem' }}>
        <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap', alignItems: 'center' }}>
          <span className="muted" style={{ fontSize: '0.82rem' }}>
            Sumber SMTP:
          </span>
          <Badge tone={saved.smtpConfigured ? 'good' : 'neutral'}>
            {saved.smtpConfigured ? 'Setelan di halaman ini' : 'Variabel lingkungan (env)'}
          </Badge>
          <span className="muted" style={{ fontSize: '0.82rem' }}>
            Logo:
          </span>
          <Badge tone={saved.logoSet ? 'good' : 'neutral'}>{saved.logoSet ? 'Terpasang' : 'Belum ada'}</Badge>
          <span className="muted" style={{ fontSize: '0.82rem' }}>
            URL publik:
          </span>
          <Badge tone={saved.logoUrl ? 'good' : 'warning'}>{saved.logoUrl ? 'Siap' : 'Belum disetel'}</Badge>
        </div>
        {!saved.logoUrl && (
          <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
            Tanpa URL publik aplikasi, logo tak bisa dirangkai ke badan email — surat tetap terkirim,
            hanya tampil tanpa gambar.
          </p>
        )}
      </div>

      {!manage && (
        <p className="muted" style={{ margin: 0 }}>
          Anda hanya bisa melihat setelan ini. Perlu izin “Kelola SMTP, logo &amp; template email
          platform” untuk mengubahnya.
        </p>
      )}

      <div className="card stack" aria-disabled={!manage}>
        <SectionTitle>Server SMTP</SectionTitle>
        <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
          Kosongkan <strong>host</strong> untuk memakai kredensial dari variabel lingkungan seperti
          sebelumnya. Begitu host diisi, baris inilah yang dipakai — tanpa perlu restart container.
        </p>
        <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap', alignItems: 'flex-start' }}>
          <TextField
            label="Host"
            value={form.smtpHost}
            onChange={(_, d) => patch({ smtpHost: d.value })}
            placeholder="smtp.contoh.net"
            disabled={!manage}
            maxLength={255}
            style={{ minWidth: 240 }}
          />
          <TextField
            label="Port"
            value={form.smtpPort}
            onChange={(_, d) => patch({ smtpPort: d.value })}
            disabled={!manage}
            maxLength={5}
            validationState={portOk ? 'none' : 'error'}
            validationMessage={portOk ? undefined : 'Port 1–65535.'}
            style={{ width: 110 }}
          />
        </div>
        <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap', alignItems: 'flex-start' }}>
          <TextField
            label="Username"
            value={form.smtpUsername}
            onChange={(_, d) => patch({ smtpUsername: d.value })}
            disabled={!manage}
            maxLength={255}
            autoComplete="off"
            style={{ minWidth: 240 }}
          />
          <TextField
            label="Password"
            type="password"
            value={form.smtpPassword}
            onChange={(_, d) => patch({ smtpPassword: d.value })}
            placeholder={saved.smtpPasswordSet ? '•••••••• (tersimpan)' : 'Belum disetel'}
            hint="Biarkan kosong untuk mempertahankan password yang tersimpan."
            disabled={!manage}
            maxLength={255}
            autoComplete="new-password"
            style={{ minWidth: 240 }}
          />
        </div>
        <div className="row" style={{ gap: '1.5rem', flexWrap: 'wrap' }}>
          <FormRow label="Autentikasi" hint="Matikan untuk relay lokal tanpa login (mis. Mailpit).">
            <Segmented
              value={form.smtpAuth ? 'on' : 'off'}
              onChange={(v) => patch({ smtpAuth: v === 'on' })}
              disabled={!manage}
              options={[
                { value: 'off', label: 'Nonaktif' },
                { value: 'on', label: 'Aktif' },
              ]}
            />
          </FormRow>
          <FormRow label="STARTTLS" hint="Naikkan sambungan ke TLS setelah tersambung.">
            <Segmented
              value={form.smtpStartTls ? 'on' : 'off'}
              onChange={(v) => patch({ smtpStartTls: v === 'on' })}
              disabled={!manage}
              options={[
                { value: 'off', label: 'Nonaktif' },
                { value: 'on', label: 'Aktif' },
              ]}
            />
          </FormRow>
        </div>

        <div className="hr" />

        <SectionTitle>Identitas pengirim</SectionTitle>
        <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap', alignItems: 'flex-start' }}>
          <TextField
            label="Alamat pengirim"
            type="email"
            value={form.fromAddress}
            onChange={(_, d) => patch({ fromAddress: d.value })}
            placeholder="noreply@contoh.net"
            disabled={!manage}
            maxLength={254}
            validationState={fromOk ? 'none' : 'error'}
            validationMessage={fromOk ? undefined : 'Format alamat email tidak sah.'}
            hint={fromOk ? 'Kosong = kanal email mati; pesan hanya masuk log server.' : undefined}
            style={{ minWidth: 260 }}
          />
          <TextField
            label="Nama pengirim"
            value={form.fromName}
            onChange={(_, d) => patch({ fromName: d.value })}
            disabled={!manage}
            maxLength={100}
            required
            validationState={form.fromName.trim() ? 'none' : 'error'}
            validationMessage={form.fromName.trim() ? undefined : 'Wajib diisi.'}
            style={{ minWidth: 220 }}
          />
        </div>
        <TextField
          label="URL publik aplikasi"
          value={form.publicBaseUrl}
          onChange={(_, d) => patch({ publicBaseUrl: d.value })}
          placeholder="https://app.contoh.net"
          disabled={!manage}
          maxLength={300}
          hint="Dipakai merangkai alamat gambar logo di badan email. Kosong = email tanpa logo."
        />
      </div>

      <div className="card stack" aria-disabled={!manage}>
        <SectionTitle>Tampilan email</SectionTitle>
        <EmailLogoField
          logoSet={saved.logoSet}
          logoPath={PLATFORM_EMAIL_LOGO_PATH}
          version={freshness}
          disabled={!manage}
          busy={logoBusy}
          emptyHint="Belum ada logo. Unggah PNG/JPG/SVG maksimal 2 MB; lebar ±200 px sudah cukup untuk kepala surat."
          removeLabel="Hapus logo"
          onPick={(f) => void pickLogo(f)}
          onRemove={() => void removeLogo()}
        />
        <div className="hr" />
        <EmailAppearanceFields
          accentColor={form.accentColor}
          footerText={form.footerText}
          signatureText={form.signatureText}
          placeholders={{
            accentColor: DEFAULT_ACCENT,
            footerText: 'Email otomatis, mohon tidak dibalas.',
            signatureText: 'Salam, Tim Dukungan',
          }}
          disabled={!manage}
          onChange={patch}
        />
      </div>

      <div className="card stack" aria-disabled={!manage}>
        <SectionTitle>Subjek per pemicu</SectionTitle>
        <EmailSubjectFields rows={form.subjects} disabled={!manage} onChange={patchSubject} />
      </div>

      {manage && (
        <div className="card spread" style={{ alignItems: 'center' }}>
          <span className="muted" style={{ fontSize: '0.85rem' }}>
            {dirty ? `${changes.length} perubahan belum disimpan` : 'Tak ada perubahan'}
          </span>
          <div className="row" style={{ gap: '0.5rem' }}>
            <Button variant="subtle" onClick={discard} disabled={!dirty || saving}>
              Batalkan
            </Button>
            <Button variant="primary" onClick={() => setConfirmOpen(true)} disabled={!dirty || !valid || saving}>
              Tinjau &amp; simpan…
            </Button>
          </div>
        </div>
      )}

      <div className="card stack">
        <SectionTitle>Pratinjau &amp; uji kirim</SectionTitle>
        <EmailPreviewPanel
          reloadKey={freshness}
          canSendTest={manage}
          loadPreview={previewPlatformEmail}
          sendTest={sendPlatformTestEmail}
        />
      </div>

      {confirmOpen && (
        <Modal
          title="Konfirmasi setelan email"
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
              Tinjau perubahan berikut sebelum berlaku untuk <strong>seluruh tenant</strong>:
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
            {saved.smtpConfigured !== !!form.smtpHost.trim() && (
              <Callout>
                {form.smtpHost.trim() ? (
                  <>
                    Mulai sekarang email dikirim lewat <strong>{form.smtpHost.trim()}</strong>, bukan
                    lagi lewat setelan dari variabel lingkungan. Kirim email uji setelah menyimpan.
                  </>
                ) : (
                  <>
                    Host dikosongkan — pengiriman kembali memakai kredensial{' '}
                    <strong>variabel lingkungan</strong>. Bila env juga kosong, email hanya tercatat
                    di log server dan tak pernah sampai ke pelanggan.
                  </>
                )}
              </Callout>
            )}
          </div>
        </Modal>
      )}
    </div>
  )
}

function SectionTitle({ children }: { children: ReactNode }) {
  return <strong style={{ fontSize: '0.95rem' }}>{children}</strong>
}

function FormRow({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="stack" style={{ gap: '0.3rem' }}>
      <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>{label}</span>
      {children}
      {hint && (
        <span className="muted" style={{ fontSize: '0.78rem' }}>
          {hint}
        </span>
      )}
    </div>
  )
}

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
