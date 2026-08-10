import { useState, type FormEvent } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { ApiError } from '../api/client'
import { IconShield } from '@/components/atoms/icons'
import { BrandMark, Button, Spinner, TextField } from '@/components/atoms'

/**
 * Masuk dua langkah, tanpa state di server: langkah pertama mengirim email+password;
 * kalau akunnya berpagar 2FA, server membalas 401 ber-penanda `TWO_FACTOR_REQUIRED`
 * dan halaman ini menampilkan kolom kode lalu MENGIRIM ULANG ketiganya sekaligus.
 *
 * Sengaja tak ada "token setengah jadi" di antara dua langkah: token semacam itu harus
 * disimpan, dibatasi umurnya, dan dicabut — tiga hal yang bisa salah demi menghemat satu
 * pengiriman ulang password yang sudah ada di memori halaman ini.
 */
export function LoginPage() {
  const { login } = useAuth()
  // Setelah daftar, SignupPage mengarahkan ke sini sambil membawa email untuk diisi otomatis.
  const location = useLocation()
  const prefillEmail = (location.state as { email?: string } | null)?.email ?? ''
  const [email, setEmail] = useState(prefillEmail)
  const [password, setPassword] = useState('')
  const [otpCode, setOtpCode] = useState('')
  const [otpRequired, setOtpRequired] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await login(email, password, otpRequired ? otpCode : undefined)
    } catch (err) {
      if (err instanceof ApiError && err.code === 'TWO_FACTOR_REQUIRED') {
        setOtpRequired(true)
      } else {
        setOtpCode('')
        setError(err instanceof ApiError ? err.message : 'Gagal masuk')
      }
    } finally {
      setBusy(false)
    }
  }

  function backToCredentials() {
    setOtpRequired(false)
    setOtpCode('')
    setPassword('')
    setError(null)
  }

  return (
    <div className="login-shell">
      <form className="card login-card stack" onSubmit={onSubmit}>
        <div className="row" style={{ gap: '0.6rem' }}>
          <span className="logo" aria-hidden style={{ width: 34, height: 34 }}>
            {otpRequired ? <IconShield size={20} /> : <BrandMark size={26} />}
          </span>
          <div>
            <h2 style={{ margin: 0, fontSize: '1.15rem' }}>NetOps Console</h2>
            <p className="muted" style={{ margin: 0, fontSize: '0.83rem' }}>
              {otpRequired ? 'Verifikasi dua langkah' : 'Masuk ke konsol operasi jaringan'}
            </p>
          </div>
        </div>

        {otpRequired ? (
          <>
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
              Buka aplikasi autentikator untuk <strong>{email}</strong>, lalu masukkan kode 6 digit yang
              sedang tampil. Kehilangan ponsel? Pakai salah satu kode pemulihan.
            </p>
            <TextField
              label="Kode verifikasi"
              value={otpCode}
              onChange={(_, data) => setOtpCode(data.value)}
              required
              autoFocus
              // Kode pemulihan berhuruf, jadi jangan dipaksa numerik; `one-time-code`
              // tetap membuat iOS/Android menawarkan kode dari aplikasi autentikator.
              autoComplete="one-time-code"
              placeholder="123456 atau kode pemulihan"
            />
          </>
        ) : (
          <>
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
          </>
        )}

        {error && (
          <p className="error" style={{ margin: 0, fontSize: '0.85rem' }}>
            {error}
          </p>
        )}

        <Button variant="primary" type="submit" disabled={busy} style={{ width: '100%', padding: '0.6rem' }}>
          {busy ? <Spinner /> : otpRequired ? 'Verifikasi' : 'Masuk'}
        </Button>

        {otpRequired ? (
          <p className="muted" style={{ margin: 0, fontSize: '0.83rem', textAlign: 'center' }}>
            <Button variant="subtle" onClick={backToCredentials} type="button">
              Masuk sebagai akun lain
            </Button>
          </p>
        ) : (
          <p className="muted" style={{ margin: 0, fontSize: '0.83rem', textAlign: 'center' }}>
            Punya jaringan FTTH sendiri? <Link to="/signup">Daftar ISP baru</Link>
          </p>
        )}
      </form>
    </div>
  )
}
