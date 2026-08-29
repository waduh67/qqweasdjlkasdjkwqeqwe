import { useState, type FormEvent } from 'react'
import { Text } from '@fluentui/react-components'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { signupTenant, type SignupResult } from '../api/signup'
import { BrandMark, Button, Spinner, TextField } from '@/components/atoms'

/**
 * Pendaftaran mandiri ISP — layar publik terpisah dari login. Membuat tenant + admin awal
 * lewat `POST /api/signup`; sukses → arahkan ke halaman masuk.
 *
 * Kode ISP TIDAK lagi diketik pendaftar: server yang menurunkannya dari nama dan menjamin
 * keunikannya. Dulu field ini ada di sini, dan bentroknya baru ketahuan setelah tombol
 * "Daftar" ditekan — sekarang kode itu hanya ditampilkan setelah berhasil.
 */
export function SignupPage() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [adminName, setAdminName] = useState('')
  const [adminEmail, setAdminEmail] = useState('')
  const [adminPassword, setAdminPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState<SignupResult | null>(null)
  const [copied, setCopied] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      const result = await signupTenant({ name, adminName, adminEmail, adminPassword })
      setDone(result)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Gagal mendaftar')
    } finally {
      setBusy(false)
    }
  }

  /** Salin kode ISP ke papan klip; gagal (izin ditolak / konteks tak aman) dibiarkan diam —
   *  kodenya tetap terbaca di layar dan sudah dikirim ke email. */
  async function copySlug(slug: string) {
    try {
      await navigator.clipboard.writeText(slug)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      /* diabaikan sengaja */
    }
  }

  if (done) {
    return (
      <div className="login-shell">
        <div className="card login-card stack">
          <div>
            <Text as="h2" size={400} weight="semibold" style={{ margin: 0 }}>Pendaftaran berhasil</Text>
            <Text as="p" className="muted" size={300} style={{ margin: '0.4rem 0 0' }}>
              ISP <Text as="strong" weight="semibold">{done.name}</Text> sudah terdaftar. Simpan kode di bawah — ia diminta bersama
              email dan password setiap kali Anda atau staf Anda masuk.
            </Text>
          </div>
          {/* Kode ISP dipisah dari kalimat supaya mudah dibaca ulang & disalin: inilah satu-satunya
              hal di layar ini yang akan dicari pendaftar lagi besok. */}
          <div
            className="row"
            style={{
              justifyContent: 'space-between',
              gap: '0.6rem',
              padding: '0.7rem 0.85rem',
              borderRadius: 10,
              border: '1px solid var(--border-strong)',
              background: 'var(--surface-2)',
            }}
          >
            <div style={{ minWidth: 0 }}>
              <Text as="span" className="muted" size={100} weight="semibold" style={{ textTransform: 'uppercase' }}>
                Kode ISP
              </Text>
              <Text as="span" size={400} weight="semibold" style={{ display: 'block' }}>
                {done.slug}
              </Text>
            </div>
            <Button onClick={() => void copySlug(done.slug)}>{copied ? 'Tersalin' : 'Salin'}</Button>
          </div>
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
            Kode ini juga kami kirim ke <strong>{done.adminEmail}</strong>. Tak menemukannya? Periksa folder
            spam sebelum menghubungi kami.
          </Text>
          <Button
            variant="primary"
            style={{ width: '100%', padding: '0.6rem' }}
            onClick={() => navigate('/login', { state: { email: done.adminEmail } })}
          >
            Masuk sekarang
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="login-shell">
      <form className="card login-card stack" onSubmit={onSubmit}>
        <div className="row" style={{ gap: '0.6rem' }}>
          <span className="logo" aria-hidden style={{ width: 34, height: 34 }}>
            <BrandMark size={26} />
          </span>
          <div>
            <Text as="h2" size={400} weight="semibold" style={{ margin: 0 }}>Daftar ISP baru</Text>
            <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
              Buat ruang kerja NetOps untuk jaringan FTTH-mu
            </Text>
          </div>
        </div>

        <TextField
          label="Nama ISP"
          value={name}
          onChange={(_, data) => setName(data.value)}
          required
          autoFocus
          placeholder="mis. Net Media"
          hint="Kode ISP untuk masuk kami buatkan otomatis dari nama ini."
        />
        <TextField
          label="Nama admin"
          value={adminName}
          onChange={(_, data) => setAdminName(data.value)}
          required
          autoComplete="name"
        />
        <TextField
          label="Email admin"
          type="email"
          value={adminEmail}
          onChange={(_, data) => setAdminEmail(data.value)}
          required
          autoComplete="email"
        />
        <TextField
          label="Password (min. 8 karakter)"
          type="password"
          value={adminPassword}
          onChange={(_, data) => setAdminPassword(data.value)}
          required
          minLength={8}
          autoComplete="new-password"
        />

        {error && (
          <Text as="p" className="error" size={300} style={{ margin: 0 }}>
            {error}
          </Text>
        )}

        <Button variant="primary" type="submit" disabled={busy} style={{ width: '100%', padding: '0.6rem' }}>
          {busy ? <Spinner /> : 'Daftar'}
        </Button>
        <Text as="p" className="muted" size={300} style={{ margin: 0, textAlign: 'center' }}>
          Sudah punya akun? <Link to="/login">Masuk</Link>
        </Text>
      </form>
    </div>
  )
}
