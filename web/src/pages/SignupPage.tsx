import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { signupTenant, type SignupResult } from '../api/signup'
import { IconMap } from '@/components/atoms/icons'
import { Button, Spinner, TextField } from '@/components/atoms'

/** Ubah nama ISP jadi kandidat slug: huruf kecil, spasi/simbol → strip. */
function slugify(value: string): string {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 63)
}

/**
 * Pendaftaran mandiri ISP — layar publik terpisah dari login. Membuat tenant + admin awal
 * lewat `POST /api/signup`; sukses → arahkan ke halaman masuk. Slug (Kode ISP) otomatis
 * disarankan dari nama selama pengguna belum menyuntingnya sendiri.
 */
export function SignupPage() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [slug, setSlug] = useState('')
  const [slugTouched, setSlugTouched] = useState(false)
  const [adminName, setAdminName] = useState('')
  const [adminEmail, setAdminEmail] = useState('')
  const [adminPassword, setAdminPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState<SignupResult | null>(null)

  function onNameChange(value: string) {
    setName(value)
    if (!slugTouched) setSlug(slugify(value))
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      const result = await signupTenant({ name, slug, adminName, adminEmail, adminPassword })
      setDone(result)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Gagal mendaftar')
    } finally {
      setBusy(false)
    }
  }

  if (done) {
    return (
      <div className="login-shell">
        <div className="card login-card stack">
          <div>
            <h2 style={{ margin: 0, fontSize: '1.15rem' }}>Pendaftaran berhasil 🎉</h2>
            <p className="muted" style={{ margin: '0.4rem 0 0', fontSize: '0.88rem' }}>{done.message}</p>
          </div>
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
            <IconMap size={20} />
          </span>
          <div>
            <h2 style={{ margin: 0, fontSize: '1.15rem' }}>Daftar ISP baru</h2>
            <p className="muted" style={{ margin: 0, fontSize: '0.83rem' }}>
              Buat ruang kerja NetOps untuk jaringan FTTH-mu
            </p>
          </div>
        </div>

        <TextField
          label="Nama ISP"
          value={name}
          onChange={(_, data) => onNameChange(data.value)}
          required
          autoFocus
          placeholder="mis. Net Media"
        />
        <TextField
          label="Kode ISP"
          value={slug}
          onChange={(_, data) => {
            setSlugTouched(true)
            setSlug(data.value)
          }}
          required
          autoComplete="off"
          placeholder="mis. netmedia"
          hint="Huruf kecil, angka & strip; diawali huruf. Dipakai pelanggan & tim untuk masuk."
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
          <p className="error" style={{ margin: 0, fontSize: '0.85rem' }}>
            {error}
          </p>
        )}

        <Button variant="primary" type="submit" disabled={busy} style={{ width: '100%', padding: '0.6rem' }}>
          {busy ? <Spinner /> : 'Daftar'}
        </Button>
        <p className="muted" style={{ margin: 0, fontSize: '0.83rem', textAlign: 'center' }}>
          Sudah punya akun? <Link to="/login">Masuk</Link>
        </p>
      </form>
    </div>
  )
}
