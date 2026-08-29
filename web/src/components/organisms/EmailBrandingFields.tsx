import { Text } from '@fluentui/react-components'
import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { api, ApiError } from '@/api/client'
import {
  EMAIL_TRIGGER_LABEL,
  PLATFORM_ONLY_TRIGGERS,
  type EmailSubjectView,
  type EmailTestResult,
  type EmailTrigger,
} from '@/api/emailSettings'
import { Button, TextField, TextareaField } from '@/components/atoms'
import { IconAlert } from '@/components/atoms/icons'
import { useToast } from '@/system'

/**
 * Kontrol bersama layar setelan email PLATFORM dan kartu setelan email TENANT.
 *
 * Keduanya menyunting hal yang persis sama (logo, warna aksen, footer, tanda tangan, subjek
 * per pemicu) hanya dengan arti "kosong" yang berbeda — di platform berarti tak diisi, di
 * tenant berarti mewarisi. Perbedaan itu ditampung lewat `placeholder`, bukan lewat dua
 * salinan kontrol yang lambat laun berbeda diam-diam.
 */

/** Warna aksen bawaan renderer server; dipakai sebagai contoh & nilai awal pemilih warna. */
export const DEFAULT_ACCENT = '#2563eb'

const HEX = /^#[0-9a-fA-F]{6}$/

export function isValidAccent(value: string): boolean {
  return value.trim() === '' || HEX.test(value.trim())
}

/**
 * Pemilih logo: pratinjau byte tersimpan (ditarik ber-Bearer), tombol ganti, tombol lepas.
 * Berbeda dari pengunggah QRIS yang menunda unggah sampai tombol simpan — logo diunggah
 * SEKETIKA, karena pratinjau email di sebelahnya hanya berguna kalau logonya sudah nyata.
 */
export function EmailLogoField({
  logoSet,
  logoPath,
  version,
  disabled,
  busy,
  emptyHint,
  removeLabel,
  onPick,
  onRemove,
}: {
  logoSet: boolean
  logoPath: string
  version: number
  disabled?: boolean
  busy?: boolean
  emptyHint: string
  removeLabel: string
  onPick: (file: File) => void
  onRemove: () => void
}) {
  return (
    <div className="row" style={{ gap: '0.75rem', alignItems: 'flex-start', flexWrap: 'wrap' }}>
      {logoSet ? (
        <AuthedImage path={logoPath} version={version} alt="Logo email" />
      ) : (
        <div style={{ ...logoBox, display: 'grid', placeItems: 'center' }} className="muted">
          <Text as="span" size={100}>tanpa logo</Text>
        </div>
      )}
      <div className="stack" style={{ gap: '0.4rem' }}>
        <Text as="span" size={200} className="muted" style={{ maxWidth: 380 }}>
          {logoSet ? 'Logo tersimpan.' : emptyHint}
        </Text>
        {!disabled && (
          <div className="row" style={{ gap: '0.5rem' }}>
            <FilePickButton label={logoSet ? 'Ganti logo' : 'Pilih logo'} disabled={busy} onPick={onPick} />
            {logoSet && (
              <Button variant="subtle" onClick={onRemove} disabled={busy}>
                {removeLabel}
              </Button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

/** Warna aksen + footer + tanda tangan. `placeholders` mengisi nilai warisan di sisi tenant. */
export function EmailAppearanceFields({
  accentColor,
  footerText,
  signatureText,
  placeholders,
  disabled,
  onChange,
}: {
  accentColor: string
  footerText: string
  signatureText: string
  placeholders: { accentColor: string; footerText: string; signatureText: string }
  disabled?: boolean
  onChange: (patch: { accentColor?: string; footerText?: string; signatureText?: string }) => void
}) {
  const accentInvalid = !isValidAccent(accentColor)
  return (
    <div className="stack" style={{ gap: '0.9rem' }}>
      <div className="row" style={{ gap: '0.6rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <TextField
          label="Warna aksen"
          value={accentColor}
          onChange={(_, data) => onChange({ accentColor: data.value })}
          placeholder={placeholders.accentColor}
          disabled={disabled}
          maxLength={9}
          validationState={accentInvalid ? 'error' : 'none'}
          validationMessage={accentInvalid ? 'Isi kode warna #RRGGBB.' : undefined}
          style={{ width: 160 }}
        />
        <input
          type="color"
          aria-label="Pilih warna aksen"
          value={HEX.test(accentColor.trim()) ? accentColor.trim() : placeholders.accentColor}
          onChange={(e) => onChange({ accentColor: e.target.value })}
          disabled={disabled}
          style={{ width: 44, height: 34, padding: 2, borderRadius: 'var(--radius-sm)', cursor: 'pointer' }}
        />
      </div>
      <TextareaField
        label="Tanda tangan"
        value={signatureText}
        onChange={(_, data) => onChange({ signatureText: data.value })}
        placeholder={placeholders.signatureText}
        disabled={disabled}
        maxLength={200}
        resize="vertical"
      />
      <TextareaField
        label="Footer"
        value={footerText}
        onChange={(_, data) => onChange({ footerText: data.value })}
        placeholder={placeholders.footerText}
        disabled={disabled}
        maxLength={500}
        resize="vertical"
      />
    </div>
  )
}

/** Satu baris subjek per pemicu; placeholder = subjek yang terpakai bila dikosongkan. */
export function EmailSubjectFields({
  rows,
  disabled,
  onChange,
}: {
  rows: EmailSubjectView[]
  disabled?: boolean
  onChange: (trigger: EmailTrigger, value: string) => void
}) {
  return (
    <div className="stack" style={{ gap: '0.7rem' }}>
      <Text as="p" size={200} className="muted" style={{ margin: 0 }}>
        Hanya baris SUBJEK yang disetel di sini — isi pesannya dirakit sistem sesuai peristiwa yang
        terjadi. Kosongkan sebuah baris untuk memakai subjek bawaan (tampil sebagai teks samar).
        Tulis <code>{'{isp}'}</code> di mana pun untuk menyisipkan nama ISP penerima.
      </Text>
      {rows.map((row) => (
        <TextField
          key={row.trigger}
          label={EMAIL_TRIGGER_LABEL[row.trigger]}
          value={row.subject ?? ''}
          onChange={(_, data) => onChange(row.trigger, data.value)}
          placeholder={row.inheritedSubject}
          disabled={disabled}
          maxLength={200}
          hint={
            // Baris ini hanya pernah muncul di layar platform — server tak mengirimkannya ke
            // tenant. Keterangannya tetap ditulis di sini supaya platform admin tahu bahwa
            // yang ia ketik berlaku untuk SEMUA ISP dan takkan ditimpa siapa pun.
            PLATFORM_ONLY_TRIGGERS.includes(row.trigger)
              ? 'Berlaku untuk semua ISP — tenant tak bisa menimpanya.'
              : undefined
          }
        />
      ))}
    </div>
  )
}

/**
 * Pratinjau HTML + kirim uji.
 *
 * Pratinjaunya dirender SERVER lewat jalur yang sama dengan email sungguhan, lalu ditaruh di
 * `<iframe srcDoc>` — sandbox-nya menjaga gaya email kuno (tabel 600px, style inline) tak
 * bocor ke halaman setelan, dan sebaliknya. Keduanya membaca setelan TERSIMPAN, jadi tombol
 * di sini sengaja mengingatkan untuk menyimpan lebih dulu.
 */
export function EmailPreviewPanel({
  reloadKey,
  canSendTest,
  defaultTo,
  loadPreview,
  sendTest,
}: {
  reloadKey: number
  canSendTest: boolean
  defaultTo?: string | null
  loadPreview: () => Promise<string>
  sendTest: (to: string) => Promise<EmailTestResult>
}) {
  const toast = useToast()
  const [html, setHtml] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [to, setTo] = useState(defaultTo ?? '')
  const [sending, setSending] = useState(false)
  const [result, setResult] = useState<EmailTestResult | null>(null)

  useEffect(() => {
    let active = true
    setHtml(null)
    setFailed(false)
    loadPreview()
      .then((doc) => active && setHtml(doc))
      .catch(() => active && setFailed(true))
    return () => {
      active = false
    }
    // `loadPreview` sengaja tak masuk dependensi: pemanggil kerap mengirim lambda baru tiap
    // render, dan memasukkannya berarti pratinjau dimuat ulang tanpa henti.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reloadKey])

  const doSend = async () => {
    setSending(true)
    setResult(null)
    try {
      setResult(await sendTest(to.trim()))
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengirim email uji')
    } finally {
      setSending(false)
    }
  }

  const validTo = /^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/.test(to.trim())

  return (
    <div className="stack" style={{ gap: '0.9rem' }}>
      {failed ? (
        <Text as="p" size={200} className="muted" style={{ margin: 0 }}>
          Pratinjau gagal dimuat.
        </Text>
      ) : html === null ? (
        <Text as="p" size={200} className="muted" style={{ margin: 0 }}>
          Memuat pratinjau…
        </Text>
      ) : (
        <iframe
          title="Pratinjau email"
          srcDoc={html}
          sandbox=""
          style={{
            width: '100%',
            height: 420,
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius-sm)',
            background: 'var(--surface-2)',
          }}
        />
      )}

      <div className="hr" />

      <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <TextField
          label="Kirim email uji ke"
          type="email"
          value={to}
          onChange={(_, data) => setTo(data.value)}
          placeholder="alamat@contoh.com"
          disabled={!canSendTest || sending}
          maxLength={254}
          style={{ minWidth: 260 }}
        />
        <Button variant="primary" onClick={() => void doSend()} disabled={!canSendTest || !validTo || sending}>
          {sending ? 'Mengirim…' : 'Kirim email uji'}
        </Button>
      </div>
      {result && (
        <div
          className="row"
          style={{
            gap: '0.5rem',
            alignItems: 'flex-start',
            padding: '0.6rem 0.75rem',
            borderRadius: 'var(--radius-sm)',
            background: `color-mix(in srgb, var(${result.delivered ? '--good' : '--critical'}) 12%, var(--surface))`,
            border: `1px solid color-mix(in srgb, var(${result.delivered ? '--good' : '--critical'}) 32%, transparent)`,
          }}
        >
          <IconAlert size={16} />
          <Text as="span" size={200}>
            {result.delivered ? 'Terkirim.' : 'Gagal.'} {result.detail}
          </Text>
        </div>
      )}
    </div>
  )
}

/**
 * Peringatan sender terverifikasi — sekarang milik layar PLATFORM, bukan tenant.
 *
 * Dulu ia dipasang di kartu tenant karena tenant boleh mengisi alamat pengirimnya sendiri.
 * Sejak alamat itu dikunci, satu-satunya alamat yang berangkat adalah alamat di layar ini —
 * dan justru inilah yang harus terdaftar di relay. Nadanya juga naik: pada penyedia yang
 * memverifikasi sender (mis. Brevo) alamat asing bukan "berisiko spam" melainkan ditolak,
 * jadi salah isi di sini mematikan email SELURUH tenant sekaligus.
 */
export function SenderDomainWarning({ address }: { address: string }) {
  return (
    <Callout>
      Alamat <strong>{address || 'ini'}</strong> berangkat lewat relay SMTP platform, dan seluruh
      tenant memakainya. Pastikan ia terdaftar sebagai <strong>sender terverifikasi</strong> di
      relay itu (mis. Brevo) dan domainnya mengizinkan lewat <strong>SPF/DKIM</strong> — kalau
      tidak, email semua ISP gagal terkirim, bukan sekadar masuk spam.
    </Callout>
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
      }}
    >
      <IconAlert size={16} />
      <Text as="span" size={200}>{children}</Text>
    </div>
  )
}

const logoBox: CSSProperties = {
  width: 140,
  height: 80,
  borderRadius: 'var(--radius-sm)',
  objectFit: 'contain',
  background: 'var(--surface-2)',
  border: '1px solid var(--border-strong)',
}

/**
 * Tombol biasa yang memicu `<input type=file>` tersembunyi — supaya pemilih berkas
 * tetap tombol bergaya tema, bukan kontrol bawaan browser yang gayanya sendiri.
 */
function FilePickButton({
  label,
  disabled,
  onPick,
}: {
  label: string
  disabled?: boolean
  onPick: (file: File) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  return (
    <>
      <Button onClick={() => input.current?.click()} disabled={disabled}>
        {label}
      </Button>
      <input
        ref={input}
        type="file"
        accept="image/png,image/jpeg,image/gif,image/webp,image/svg+xml"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) onPick(file)
          e.target.value = '' // izinkan memilih berkas yang sama lagi
        }}
      />
    </>
  )
}

/**
 * Gambar berkonten terautentikasi: byte ditarik ber-Bearer lalu dijadikan object URL, karena
 * `<img>` biasa tak bisa mengirim header. [version] membatalkan cache — satu key logo per
 * pemilik, jadi unggah ulang menimpa byte di URL yang sama.
 */
function AuthedImage({ path, version, alt }: { path: string; version: number; alt: string }) {
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

  if (failed) {
    return (
      <div style={{ ...logoBox, display: 'grid', placeItems: 'center' }} className="muted">
        <Text as="span" size={100}>gagal</Text>
      </div>
    )
  }
  if (!url) return <div style={logoBox} aria-busy="true" />
  return <img src={url} alt={alt} style={logoBox} />
}
