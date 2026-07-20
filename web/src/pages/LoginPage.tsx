import { useState, type FormEvent } from 'react'
import { useAuth } from '../auth/useAuth'
import { ApiError } from '../api/client'

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
      <form className="card login-card" onSubmit={onSubmit}>
        <h2 style={{ marginTop: 0 }}>Masuk ke FTTH OSS</h2>
        <label>
          <span>Tenant</span>
          <input value={tenantSlug} onChange={(e) => setTenantSlug(e.target.value)} required />
        </label>
        <label>
          <span>Email</span>
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          <span>Password</span>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        {error && <p className="error">{error}</p>}
        <button className="primary" type="submit" disabled={busy} style={{ width: '100%' }}>
          {busy ? 'Memproses…' : 'Masuk'}
        </button>
      </form>
    </div>
  )
}
