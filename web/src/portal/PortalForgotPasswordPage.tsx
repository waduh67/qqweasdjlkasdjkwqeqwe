import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { portalForgotPassword, portalResetPassword } from './portalApi'
import { PortalApiError } from './portalClient'
import { Button, TextField } from '@/components/atoms'
import { useToast } from '@/system'

/**
 * Pemulihan password portal — dua langkah di satu layar: minta kode, lalu tukar kode.
 *
 * Aturan kalimat di sini penting: server SENGAJA tak memberi tahu apakah identitas yang
 * diketik dikenal atau tidak (kalau iya, ia jadi alat pengendus akun). Jadi UI tak boleh
 * menjanjikan "kode sudah dikirim ke email Anda" — yang boleh cuma kalimat yang tetap benar
 * seandainya identitasnya memang tak ada. Kanal (email/WhatsApp) juga tak disebut pasti,
 * karena server yang memilih berdasarkan kontak yang tercatat di ISP.
 */
export function PortalForgotPasswordPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const toast = useToast()
  const linkedTenant = params.get('tenant')?.trim() || undefined

  const [step, setStep] = useState<'identifier' | 'code'>('identifier')
  const [identifier, setIdentifier] = useState('')
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const loginHref = linkedTenant ? `/portal/login?tenant=${encodeURIComponent(linkedTenant)}` : '/portal/login'

  async function requestCode(resend: boolean) {
    setError(null)
    setBusy(true)
    try {
      await portalForgotPassword(identifier.trim(), linkedTenant)
      setStep('code')
      if (resend) toast.info('Kode dikirim ulang bila akunnya ada')
    } catch (err) {
      setError(err instanceof PortalApiError ? err.message : 'Gagal meminta kode')
    } finally {
      setBusy(false)
    }
  }

  function onRequest(event: FormEvent) {
    event.preventDefault()
    void requestCode(false)
  }

  async function onReset(event: FormEvent) {
    event.preventDefault()
    // Dicek di klien lebih dulu supaya salah ketik password tak menggerus jatah tebakan kode.
    if (password.length < 8) {
      setError('Password baru minimal 8 karakter')
      return
    }
    if (password !== confirm) {
      setError('Ulangi password belum sama')
      return
    }
    setError(null)
    setBusy(true)
    try {
      await portalResetPassword(identifier.trim(), code.trim(), password)
      toast.success('Password berhasil diganti, silakan masuk')
      navigate(loginHref, { replace: true })
    } catch (err) {
      setError(err instanceof PortalApiError ? err.message : 'Gagal mengganti password')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-shell">
      <form className="card login-card stack" onSubmit={step === 'identifier' ? onRequest : onReset}>
        <div>
          <h2 style={{ margin: 0, fontSize: '1.15rem' }}>Lupa password</h2>
          <p className="muted" style={{ margin: '0.2rem 0 0', fontSize: '0.83rem' }}>
            {step === 'identifier'
              ? 'Masukkan identitas akunmu, kami kirimkan kode pemulihan'
              : 'Masukkan kode yang kamu terima beserta password barumu'}
          </p>
        </div>

        {step === 'identifier' ? (
          <>
            <TextField
              label="Email, no. HP, atau username"
              value={identifier}
              onChange={(_, data) => setIdentifier(data.value)}
              required
              autoFocus
              autoComplete="username"
              placeholder="budi@email.com"
            />
            {error && (
              <p className="error" style={{ margin: 0, fontSize: '0.85rem' }}>
                {error}
              </p>
            )}
            <Button type="submit" variant="primary" disabled={busy} style={{ width: '100%', padding: '0.6rem' }}>
              {busy ? 'Mengirim…' : 'Kirim kode'}
            </Button>
          </>
        ) : (
          <>
            <p className="muted" style={{ margin: 0, fontSize: '0.83rem' }}>
              Kalau <strong>{identifier.trim()}</strong> terdaftar, kode 6 angka sudah dikirim ke email atau
              WhatsApp yang tercatat di ISP-mu. Kode berlaku 15 menit dan hanya bisa dipakai sekali — jangan
              berikan ke siapa pun, termasuk yang mengaku petugas.
            </p>

            <TextField
              label="Kode pemulihan"
              value={code}
              onChange={(_, data) => setCode(data.value.replace(/\D/g, '').slice(0, 6))}
              required
              autoFocus
              inputMode="numeric"
              autoComplete="one-time-code"
              placeholder="123456"
            />
            <TextField
              label="Password baru"
              type="password"
              value={password}
              onChange={(_, data) => setPassword(data.value)}
              required
              autoComplete="new-password"
              hint="Minimal 8 karakter"
            />
            <TextField
              label="Ulangi password baru"
              type="password"
              value={confirm}
              onChange={(_, data) => setConfirm(data.value)}
              required
              autoComplete="new-password"
            />

            {error && (
              <p className="error" style={{ margin: 0, fontSize: '0.85rem' }}>
                {error}
              </p>
            )}

            <Button type="submit" variant="primary" disabled={busy} style={{ width: '100%', padding: '0.6rem' }}>
              {busy ? 'Menyimpan…' : 'Simpan password baru'}
            </Button>
            <Button
              type="button"
              variant="subtle"
              disabled={busy}
              style={{ width: '100%' }}
              onClick={() => void requestCode(true)}
            >
              Kirim ulang kode
            </Button>
          </>
        )}

        <Link to={loginHref} className="muted" style={{ fontSize: '0.83rem', textAlign: 'center' }}>
          Kembali ke halaman masuk
        </Link>
      </form>
    </div>
  )
}
