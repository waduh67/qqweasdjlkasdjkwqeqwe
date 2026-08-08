import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { usePortalAuth } from './PortalAuthContext'
import { PortalApiError } from './portalClient'
import { Button, TextField } from '@/components/atoms'

/**
 * Halaman masuk PORTAL pelanggan — sengaja layar sendiri, terpisah dari login operator.
 * Butuh slug tenant (ISP) + login pelanggan + password; login boleh kembar antar-tenant,
 * jadi slug wajib. Slug bisa diisi lewat `?tenant=` (jalan menuju sub-domain per-ISP nanti).
 */
export function PortalLoginPage() {
  const { login } = usePortalAuth()
  const [params] = useSearchParams()
  const [tenant, setTenant] = useState(params.get('tenant') ?? '')
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await login(tenant.trim(), loginId.trim(), password)
    } catch (err) {
      setError(err instanceof PortalApiError ? err.message : 'Gagal masuk')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-shell">
      <form className="card login-card stack" onSubmit={onSubmit}>
        <div>
          <h2 style={{ margin: 0, fontSize: '1.15rem' }}>Portal Pelanggan</h2>
          <p className="muted" style={{ margin: '0.2rem 0 0', fontSize: '0.83rem' }}>
            Masuk untuk melihat tagihan &amp; status layananmu
          </p>
        </div>

        <TextField
          label="Kode ISP"
          value={tenant}
          onChange={(_, data) => setTenant(data.value)}
          required
          autoFocus={!tenant}
          autoComplete="organization"
          placeholder="mis. netmedia"
        />
        <TextField
          label="Login"
          value={loginId}
          onChange={(_, data) => setLoginId(data.value)}
          required
          autoFocus={!!tenant}
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

        <Button type="submit" variant="primary" disabled={busy} style={{ width: '100%', padding: '0.6rem' }}>
          {busy ? 'Masuk…' : 'Masuk'}
        </Button>
      </form>
    </div>
  )
}
