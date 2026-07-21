import { useState, type FormEvent } from 'react'
import { useAuth } from '../auth/useAuth'
import { ApiError } from '../api/client'
import { IconMap } from '../components/icons'
import { Spinner } from '../components/ui'

export function LoginPage() {
  const { login } = useAuth()
  const [tenantSlug, setTenantSlug] = useState('demo')
  const [email, setEmail] = useState('admin@demo.ftth')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await login(tenantSlug, email, password)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Gagal masuk')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-shell">
      <form className="card login-card stack" onSubmit={onSubmit}>
        <div className="row" style={{ gap: '0.6rem' }}>
          <span className="logo" aria-hidden style={{ width: 34, height: 34 }}>
            <IconMap size={20} />
          </span>
          <div>
            <h2 style={{ margin: 0, fontSize: '1.15rem' }}>FTTH OSS</h2>
            <p className="muted" style={{ margin: 0, fontSize: '0.83rem' }}>
              Masuk ke konsol operasi jaringan
            </p>
          </div>
        </div>

        <label>
          <span>Tenant</span>
          <input value={tenantSlug} onChange={(e) => setTenantSlug(e.target.value)} required autoComplete="organization" />
        </label>
        <label>
          <span>Email</span>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            autoComplete="username"
          />
        </label>
        <label>
          <span>Password</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            autoComplete="current-password"
          />
        </label>

        {error && (
          <p className="error" style={{ margin: 0, fontSize: '0.85rem' }}>
            {error}
          </p>
        )}

        <button className="primary" type="submit" disabled={busy} style={{ width: '100%', padding: '0.6rem' }}>
          {busy ? <Spinner /> : 'Masuk'}
        </button>
      </form>
    </div>
  )
}
