import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { usePortalAuth } from './PortalAuthContext'
import { PortalApiError } from './portalClient'
import type { PortalTenantChoice } from './portalApi'
import { Button, TextField } from '@/components/atoms'

/**
 * Halaman masuk PORTAL pelanggan — layar sendiri, terpisah dari login operator.
 *
 * Cukup SATU identitas: email, nomor HP, atau username. Kode ISP sengaja tak lagi diminta —
 * itu hal yang tak pernah dihafal pelanggan, dan server bisa menyimpulkannya sendiri dari
 * identitas + password. Hanya bila identitas yang sama ternyata dipakai di lebih dari satu
 * ISP (dan passwordnya cocok di keduanya) barulah langkah "ISP mana?" muncul.
 *
 * `?tenant=` tetap dihormati sebagai penyaring diam-diam — jalan menuju sub-domain per-ISP
 * nanti — tapi tak pernah muncul sebagai kolom isian.
 */
export function PortalLoginPage() {
  const { login } = usePortalAuth()
  const [params] = useSearchParams()
  const linkedTenant = params.get('tenant')?.trim() || undefined
  const [identifier, setIdentifier] = useState('')
  const [password, setPassword] = useState('')
  const [choices, setChoices] = useState<PortalTenantChoice[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Password ditahan di state sampai ISP terpilih: server memang menuntut percobaan kedua
  // dengan slug, dan meminta pelanggan mengetik ulang passwordnya cuma untuk itu tak sopan.
  async function attempt(tenant?: string) {
    setError(null)
    setBusy(true)
    try {
      const outcome = await login(identifier.trim(), password, tenant ?? linkedTenant)
      if (!outcome.done) setChoices(outcome.choices)
    } catch (err) {
      setError(err instanceof PortalApiError ? err.message : 'Gagal masuk')
    } finally {
      setBusy(false)
    }
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    void attempt()
  }

  if (choices) {
    return (
      <div className="login-shell">
        <div className="card login-card stack">
          <div>
            <h2 style={{ margin: 0, fontSize: '1.15rem' }}>Pilih penyedia</h2>
            <p className="muted" style={{ margin: '0.2rem 0 0', fontSize: '0.83rem' }}>
              Akun dengan identitas ini terdaftar di beberapa ISP. Mana yang ingin dibuka?
            </p>
          </div>

          {choices.map((choice) => (
            <Button
              key={choice.tenantSlug}
              disabled={busy}
              style={{ width: '100%', padding: '0.6rem' }}
              onClick={() => void attempt(choice.tenantSlug)}
            >
              {choice.tenantName}
            </Button>
          ))}

          {error && (
            <p className="error" style={{ margin: 0, fontSize: '0.85rem' }}>
              {error}
            </p>
          )}

          <Button
            variant="subtle"
            disabled={busy}
            style={{ width: '100%' }}
            onClick={() => {
              setChoices(null)
              setPassword('')
            }}
          >
            Kembali
          </Button>
        </div>
      </div>
    )
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
          label="Email, no. HP, atau username"
          value={identifier}
          onChange={(_, data) => setIdentifier(data.value)}
          required
          autoFocus
          autoComplete="username"
          placeholder="budi@email.com"
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

        <Link
          to={linkedTenant ? `/portal/lupa-password?tenant=${encodeURIComponent(linkedTenant)}` : '/portal/lupa-password'}
          className="muted"
          style={{ fontSize: '0.83rem', textAlign: 'center' }}
        >
          Lupa password?
        </Link>
      </form>
    </div>
  )
}
