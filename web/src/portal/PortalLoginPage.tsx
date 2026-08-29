import { useState, type FormEvent } from 'react'
import { Text } from '@fluentui/react-components'
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
            <Text as="h1" size={500} weight="semibold" style={{ margin: 0 }}>Pilih penyedia</Text>
            <Text as="p" className="muted" size={200} style={{ margin: '0.2rem 0 0' }}>
              Akun dengan identitas ini terdaftar di beberapa ISP. Mana yang ingin dibuka?
            </Text>
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
            <Text as="p" className="error" size={300} style={{ margin: 0 }}>{error}</Text>
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
          <Text as="h1" size={500} weight="semibold" style={{ margin: 0 }}>Portal Pelanggan</Text>
          <Text as="p" className="muted" size={200} style={{ margin: '0.2rem 0 0' }}>
            Masuk untuk melihat tagihan &amp; status layananmu
          </Text>
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
          <Text as="p" className="error" size={300} style={{ margin: 0 }}>{error}</Text>
        )}

        <Button type="submit" variant="primary" disabled={busy} style={{ width: '100%', padding: '0.6rem' }}>
          {busy ? 'Masuk…' : 'Masuk'}
        </Button>

        <Link
          to={linkedTenant ? `/portal/lupa-password?tenant=${encodeURIComponent(linkedTenant)}` : '/portal/lupa-password'}
          className="muted"
          style={{ display: 'block', textAlign: 'center' }}
        >
          Lupa password?
        </Link>
      </form>
    </div>
  )
}
