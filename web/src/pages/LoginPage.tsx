import { useState, type FormEvent } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { ApiError } from '../api/client'
import { IconMap } from '@/components/atoms/icons'
import { Button, Spinner, TextField } from '@/components/atoms'

export function LoginPage() {
  const { login } = useAuth()
  // Setelah daftar, SignupPage mengarahkan ke sini sambil membawa email untuk diisi otomatis.
  const location = useLocation()
  const prefillEmail = (location.state as { email?: string } | null)?.email ?? ''
  const [email, setEmail] = useState(prefillEmail)
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await login(email, password)
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
            <h2 style={{ margin: 0, fontSize: '1.15rem' }}>NetOps Console</h2>
            <p className="muted" style={{ margin: 0, fontSize: '0.83rem' }}>
              Masuk ke konsol operasi jaringan
            </p>
          </div>
        </div>

        <TextField
          label="Email"
          type="email"
          value={email}
          onChange={(_, data) => setEmail(data.value)}
          required
          autoFocus
          autoComplete="username"
        />
        <TextField
          label="Password"
          type="password"
          value={password}
          onChange={(_, data) => setPassword(data.value)}
          required
          autoComplete="current-password"
        />

        {error && (
          <p className="error" style={{ margin: 0, fontSize: '0.85rem' }}>
            {error}
          </p>
        )}

        <Button variant="primary" type="submit" disabled={busy} style={{ width: '100%', padding: '0.6rem' }}>
          {busy ? <Spinner /> : 'Masuk'}
        </Button>
        <p className="muted" style={{ margin: 0, fontSize: '0.83rem', textAlign: 'center' }}>
          Punya jaringan FTTH sendiri? <Link to="/signup">Daftar ISP baru</Link>
        </p>
      </form>
    </div>
  )
}
