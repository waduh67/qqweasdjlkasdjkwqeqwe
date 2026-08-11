import { useEffect, useState, type ReactNode } from 'react'
import { ApiError } from '@/api/client'
import {
  deleteTenantEmailLogo,
  getTenantEmailSettings,
  previewTenantEmail,
  sendTenantTestEmail,
  subjectsToPayload,
  updateTenantEmailSettings,
  uploadTenantEmailLogo,
  TENANT_EMAIL_LOGO_PATH,
  type EmailSubjectView,
  type EmailTrigger,
  type TenantEmailSettingsView,
} from '@/api/emailSettings'
import { Button, TextField } from '@/components/atoms'
import {
  EmailAppearanceFields,
  EmailLogoField,
  EmailPreviewPanel,
  EmailSubjectFields,
  DEFAULT_ACCENT,
  SenderDomainWarning,
  isValidAccent,
} from './EmailBrandingFields'
import { useToast } from '@/system'

/**
 * Kartu "Identitas & tampilan email" milik TENANT — timpaan atas bawaan platform.
 *
 * Berdiri sendiri (memuat & menyimpan lewat endpointnya sendiri) alih-alih ikut tombol Simpan
 * halaman Pengaturan Notifikasi: setelan ini tinggal di tabel lain, dan menyatukannya berarti
 * satu kegagalan simpan menggantung dua kelompok setelan yang tak berhubungan.
 *
 * Aturan yang dipegang seluruh kartu: **kosong berarti mewarisi platform**, bukan berarti
 * kosong. Karena itu tiap kolom memasang nilai warisan sebagai placeholder — operator selalu
 * bisa melihat apa yang akan terpakai bila ia tak mengisi apa-apa.
 */

interface TenantForm {
  fromAddress: string
  fromName: string
  accentColor: string
  footerText: string
  signatureText: string
  subjects: EmailSubjectView[]
}

function toForm(v: TenantEmailSettingsView): TenantForm {
  return {
    fromAddress: v.fromAddress ?? '',
    fromName: v.fromName ?? '',
    accentColor: v.accentColor ?? '',
    footerText: v.footerText ?? '',
    signatureText: v.signatureText ?? '',
    subjects: v.subjects.map((s) => ({ ...s })),
  }
}

const EMAIL_RE = /^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/

export function TenantEmailBrandingCard({ manage }: { manage: boolean }) {
  const toast = useToast()
  const [saved, setSaved] = useState<TenantEmailSettingsView | null>(null)
  const [form, setForm] = useState<TenantForm | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [logoBusy, setLogoBusy] = useState(false)
  const [freshness, setFreshness] = useState(0)

  useEffect(() => {
    getTenantEmailSettings()
      .then((s) => {
        setSaved(s)
        setForm(toForm(s))
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat identitas email'))
      .finally(() => setLoading(false))
  }, [toast])

  const patch = (p: Partial<TenantForm>) => setForm((f) => (f ? { ...f, ...p } : f))

  const patchSubject = (trigger: EmailTrigger, subject: string) =>
    setForm((f) => (f ? { ...f, subjects: f.subjects.map((s) => (s.trigger === trigger ? { ...s, subject } : s)) } : f))

  const adopt = (result: TenantEmailSettingsView) => {
    setSaved(result)
    setForm(toForm(result))
    setFreshness((n) => n + 1)
  }

  const addressOk = !!form && (form.fromAddress.trim() === '' || EMAIL_RE.test(form.fromAddress.trim()))
  const accentOk = !!form && isValidAccent(form.accentColor)

  const save = async () => {
    if (!form) return
    setSaving(true)
    try {
      adopt(
        await updateTenantEmailSettings({
          fromAddress: form.fromAddress.trim() || null,
          fromName: form.fromName.trim() || null,
          accentColor: form.accentColor.trim() || null,
          footerText: form.footerText.trim() || null,
          signatureText: form.signatureText.trim() || null,
          subjects: subjectsToPayload(form.subjects),
        }),
      )
      toast.success('Identitas & tampilan email disimpan')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menyimpan identitas email')
    } finally {
      setSaving(false)
    }
  }

  const pickLogo = async (file: File) => {
    setLogoBusy(true)
    try {
      adopt(await uploadTenantEmailLogo(file))
      toast.success('Logo email diperbarui')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengunggah logo')
    } finally {
      setLogoBusy(false)
    }
  }

  const restoreLogo = async () => {
    setLogoBusy(true)
    try {
      adopt(await deleteTenantEmailLogo())
      toast.success('Logo kembali mengikuti bawaan platform')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengembalikan logo bawaan')
    } finally {
      setLogoBusy(false)
    }
  }

  if (loading) {
    return (
      <div className="card stack">
        <SectionTitle>Identitas &amp; tampilan email</SectionTitle>
        <p className="muted" style={{ margin: 0 }}>
          Memuat…
        </p>
      </div>
    )
  }
  if (!form || !saved) return null

  return (
    <div className="card stack">
      <SectionTitle>Identitas &amp; tampilan email</SectionTitle>
      <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
        Semua kolom di kartu ini <strong>opsional</strong>. Yang dibiarkan kosong mengikuti bawaan
        platform — nilai warisannya ditampilkan sebagai teks samar di dalam kolomnya.
      </p>

      <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap', alignItems: 'flex-start' }}>
        <TextField
          label="Alamat pengirim"
          type="email"
          value={form.fromAddress}
          onChange={(_, d) => patch({ fromAddress: d.value })}
          placeholder={saved.inheritedFromAddress ?? 'Ikut bawaan platform'}
          disabled={!manage}
          maxLength={254}
          validationState={addressOk ? 'none' : 'error'}
          validationMessage={addressOk ? undefined : 'Format alamat email tidak sah.'}
          style={{ minWidth: 260 }}
        />
        <TextField
          label="Nama pengirim"
          value={form.fromName}
          onChange={(_, d) => patch({ fromName: d.value })}
          placeholder={saved.inheritedFromName}
          disabled={!manage}
          maxLength={100}
          hint="Nama yang tampil di kotak masuk pelanggan."
          style={{ minWidth: 220 }}
        />
      </div>

      {form.fromAddress.trim() !== '' && <SenderDomainWarning address={form.fromAddress.trim()} />}

      <div className="hr" />

      <EmailLogoField
        logoSet={saved.logoSet}
        logoPath={TENANT_EMAIL_LOGO_PATH}
        version={freshness}
        disabled={!manage}
        busy={logoBusy}
        emptyHint="Belum ada logo sendiri — email memakai logo bawaan platform. Unggah PNG/JPG/SVG maksimal 2 MB."
        removeLabel="Kembalikan ke bawaan"
        onPick={(f) => void pickLogo(f)}
        onRemove={() => void restoreLogo()}
      />

      <div className="hr" />

      <EmailAppearanceFields
        accentColor={form.accentColor}
        footerText={form.footerText}
        signatureText={form.signatureText}
        placeholders={{
          accentColor: saved.inheritedAccentColor ?? DEFAULT_ACCENT,
          footerText: saved.inheritedFooterText ?? 'Ikut bawaan platform',
          signatureText: saved.inheritedSignatureText ?? 'Ikut bawaan platform',
        }}
        disabled={!manage}
        onChange={patch}
      />

      <div className="hr" />

      <SectionTitle>Subjek per pemicu</SectionTitle>
      <EmailSubjectFields rows={form.subjects} disabled={!manage} onChange={patchSubject} />

      {manage && (
        <div className="spread" style={{ alignItems: 'center' }}>
          <span className="muted" style={{ fontSize: '0.85rem' }}>
            Logo tersimpan seketika; kolom lain menunggu tombol di samping.
          </span>
          <Button variant="primary" onClick={() => void save()} disabled={saving || !addressOk || !accentOk}>
            {saving ? 'Menyimpan…' : 'Simpan identitas email'}
          </Button>
        </div>
      )}

      <div className="hr" />

      <SectionTitle>Pratinjau &amp; uji kirim</SectionTitle>
      <EmailPreviewPanel
        reloadKey={freshness}
        canSendTest={manage}
        defaultTo={saved.fromAddress}
        loadPreview={previewTenantEmail}
        sendTest={sendTenantTestEmail}
      />
    </div>
  )
}

function SectionTitle({ children }: { children: ReactNode }) {
  return <h3 style={{ margin: '0.25rem 0 0', fontSize: '0.95rem', fontWeight: 600 }}>{children}</h3>
}
