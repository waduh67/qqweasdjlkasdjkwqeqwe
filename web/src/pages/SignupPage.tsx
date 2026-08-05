import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { signupTenant, type SignupResult } from '../api/signup'
import { IconMap } from '../components/icons'
import { Spinner } from '../components/ui'

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
          <button
            className="primary"
            style={{ width: '100%', padding: '0.6rem' }}
            onClick={() => navigate('/login', { state: { email: done.adminEmail } })}
          >
            Masuk sekarang
          </button>
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

        <label>
          <span>Nama ISP</span>
          <input value={name} onChange={(e) => onNameChange(e.target.value)} required autoFocus placeholder="mis. Net Media" />
        </label>
        <label>
          <span>Kode ISP</span>
          <input
            value={slug}
            onChange={(e) => {
              setSlugTouched(true)
              setSlug(e.target.value)
            }}
            required
            autoComplete="off"
            placeholder="mis. netmedia"
          />
          <span className="muted" style={{ fontSize: '0.75rem' }}>
            Huruf kecil, angka &amp; strip; diawali huruf. Dipakai pelanggan &amp; tim untuk masuk.
          </span>
        </label>
        <label>
          <span>Nama admin</span>
          <input value={adminName} onChange={(e) => setAdminName(e.target.value)} required autoComplete="name" />
        </label>
        <label>
          <span>Email admin</span>
          <input type="email" value={adminEmail} onChange={(e) => setAdminEmail(e.target.value)} required autoComplete="email" />
        </label>
        <label>
          <span>Password (min. 8 karakter)</span>
          <input
            type="password"
            value={adminPassword}
            onChange={(e) => setAdminPassword(e.target.value)}
            required
            minLength={8}
            autoComplete="new-password"
          />
        </label>

        {error && (
          <p className="error" style={{ margin: 0, fontSize: '0.85rem' }}>
            {error}
          </p>
        )}

        <button className="primary" type="submit" disabled={busy} style={{ width: '100%', padding: '0.6rem' }}>
          {busy ? <Spinner /> : 'Daftar'}
        </button>
        <p className="muted" style={{ margin: 0, fontSize: '0.83rem', textAlign: 'center' }}>
          Sudah punya akun? <Link to="/login">Masuk</Link>
        </p>
      </form>
    </div>
  )
}
