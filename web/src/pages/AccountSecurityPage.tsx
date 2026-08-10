import { useCallback, useEffect, useState } from 'react'
import QRCode from 'react-qr-code'
import { Copy, Download, ShieldCheck, ShieldOff } from 'lucide-react'
import { ApiError } from '../api/client'
import {
  disableTwoFactor,
  enableTwoFactor,
  getTwoFactorStatus,
  regenerateRecoveryCodes,
  startTwoFactorSetup,
  type TotpEnrollment,
  type TwoFactorStatus,
} from '../api/account'
import { useAuth } from '../auth/useAuth'
import { Badge, Button, Spinner, TextField } from '@/components/atoms'
import { Modal, PageHeader } from '@/components/molecules'
import { useToast } from '@/system'

/**
 * Keamanan akun sendiri: verifikasi dua langkah (TOTP).
 *
 * Tiga hal yang membentuk tampilan halaman ini:
 *
 *  1. **Kode pemulihan hanya muncul sekali.** Server menyimpan hash-nya, bukan kodenya —
 *     jadi tak ada tombol "tampilkan lagi", hanya "buat ulang" (yang menghanguskan yang
 *     lama). Karena itu panelnya menonjol dan menyediakan salin + unduh.
 *  2. **Mematikan 2FA dan membuat ulang kode minta password**, bukan sekadar klik: sesi
 *     yang tercuri tak boleh cukup untuk melucuti pagar akun.
 *  3. **Pendaftaran yang tertinggal di tengah jalan tidak mengunci siapa pun** — selama
 *     belum dikonfirmasi satu kode, 2FA belum berlaku dan login tetap satu langkah.
 */
export function AccountSecurityPage() {
  const { user, refreshProfile } = useAuth()
  const toast = useToast()

  const [status, setStatus] = useState<TwoFactorStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [enrollment, setEnrollment] = useState<TotpEnrollment | null>(null)
  const [code, setCode] = useState('')
  const [freshCodes, setFreshCodes] = useState<string[] | null>(null)
  /** Aksi yang sedang menunggu password; null = modal tertutup. */
  const [passwordFor, setPasswordFor] = useState<'disable' | 'regenerate' | null>(null)
  const [password, setPassword] = useState('')

  const reload = useCallback(async () => {
    try {
      setStatus(await getTwoFactorStatus())
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat status 2FA')
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    void reload()
  }, [reload])

  async function run(action: () => Promise<void>) {
    setBusy(true)
    try {
      await action()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
    } finally {
      setBusy(false)
    }
  }

  const beginSetup = () =>
    run(async () => {
      setFreshCodes(null)
      setCode('')
      setEnrollment(await startTwoFactorSetup())
    })

  const confirmSetup = () =>
    run(async () => {
      const { codes } = await enableTwoFactor(code)
      setEnrollment(null)
      setCode('')
      setFreshCodes(codes)
      await reload()
      await refreshProfile()
      toast.success('Verifikasi dua langkah aktif')
    })

  const submitPassword = () =>
    run(async () => {
      if (passwordFor === 'disable') {
        await disableTwoFactor(password)
        setFreshCodes(null)
        toast.success('Verifikasi dua langkah dimatikan')
      } else {
        setFreshCodes((await regenerateRecoveryCodes(password)).codes)
        toast.success('Kode pemulihan baru dibuat — yang lama sudah hangus')
      }
      setPasswordFor(null)
      setPassword('')
      await reload()
      await refreshProfile()
    })

  const copyCodes = (codes: string[]) => {
    void navigator.clipboard
      .writeText(codes.join('\n'))
      .then(() => toast.success('Kode pemulihan disalin'))
      .catch(() => toast.error('Gagal menyalin — salin manual dari daftar di layar'))
  }

  const downloadCodes = (codes: string[]) => {
    const body = [
      `Kode pemulihan NetOps Console — ${user?.email ?? ''}`,
      'Setiap kode hanya bisa dipakai SEKALI. Simpan di tempat aman, jangan di ponsel yang sama.',
      '',
      ...codes,
    ].join('\n')
    const url = URL.createObjectURL(new Blob([body], { type: 'text/plain' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `kode-pemulihan-${user?.tenantSlug ?? 'netops'}.txt`
    link.click()
    URL.revokeObjectURL(url)
  }

  if (loading) return <div className="card muted">Memuat…</div>

  const enabled = status?.enabled ?? false
  const left = status?.recoveryCodesLeft ?? 0

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <PageHeader
        title="Keamanan akun"
        subtitle={`Verifikasi dua langkah untuk ${user?.email ?? 'akun ini'}.`}
      />

      <div className="card stack" style={{ gap: '0.9rem' }}>
        <div className="row" style={{ gap: '0.6rem', alignItems: 'center' }}>
          {enabled ? <ShieldCheck size={20} /> : <ShieldOff size={20} />}
          <div style={{ flex: 1 }}>
            <div style={{ fontWeight: 600 }}>Aplikasi autentikator (TOTP)</div>
            <div className="muted" style={{ fontSize: '0.85rem' }}>
              {enabled
                ? 'Setiap kali masuk, akun ini meminta kode 6 digit dari aplikasi autentikator.'
                : 'Password saja cukup untuk masuk ke akun ini. Akun operator bisa memutus layanan pelanggan — pasang pagar keduanya.'}
            </div>
          </div>
          <Badge tone={enabled ? 'good' : 'warning'}>{enabled ? 'Aktif' : 'Belum aktif'}</Badge>
        </div>

        {enabled && (
          <div className="row" style={{ gap: '0.6rem', flexWrap: 'wrap' }}>
            <span className={left <= 2 ? 'error' : 'muted'} style={{ fontSize: '0.85rem' }}>
              Kode pemulihan tersisa: <strong>{left}</strong>
              {left <= 2 && ' — buat ulang sebelum habis.'}
            </span>
            <span style={{ flex: 1 }} />
            <Button onClick={() => setPasswordFor('regenerate')} disabled={busy}>
              Buat ulang kode pemulihan
            </Button>
            <Button onClick={() => setPasswordFor('disable')} disabled={busy}>
              Matikan
            </Button>
          </div>
        )}

        {!enabled && !enrollment && (
          <div className="row" style={{ gap: '0.6rem' }}>
            <Button variant="primary" onClick={() => void beginSetup()} disabled={busy}>
              {busy ? <Spinner /> : 'Aktifkan verifikasi dua langkah'}
            </Button>
            {status?.pending && (
              <span className="muted" style={{ fontSize: '0.83rem' }}>
                Pendaftaran sebelumnya belum selesai — mulai lagi untuk mendapat kode QR baru.
              </span>
            )}
          </div>
        )}

        {enrollment && (
          <div className="stack" style={{ gap: '0.8rem' }}>
            <ol className="muted" style={{ margin: 0, paddingLeft: '1.1rem', fontSize: '0.85rem' }}>
              <li>Buka aplikasi autentikator (Google Authenticator, Aegis, 1Password, Authy).</li>
              <li>Pindai kode QR di bawah, atau ketik kunci manualnya.</li>
              <li>Masukkan kode 6 digit yang muncul untuk memastikan sambungannya benar.</li>
            </ol>

            <div className="row" style={{ gap: '1rem', alignItems: 'flex-start', flexWrap: 'wrap' }}>
              <div style={{ background: '#fff', padding: '0.75rem', borderRadius: 8 }}>
                <QRCode value={enrollment.otpauthUri} size={168} />
              </div>
              <div className="stack" style={{ gap: '0.5rem', minWidth: 240, flex: 1 }}>
                <div>
                  <div className="muted" style={{ fontSize: '0.8rem' }}>
                    Kunci manual (kalau kamera tak bisa memindai)
                  </div>
                  <code style={{ fontSize: '0.85rem', wordBreak: 'break-all' }}>{enrollment.secret}</code>
                </div>
                <TextField
                  label="Kode 6 digit"
                  value={code}
                  onChange={(_, data) => setCode(data.value)}
                  autoFocus
                  autoComplete="one-time-code"
                  placeholder="123456"
                />
                <div className="row" style={{ gap: '0.5rem' }}>
                  <Button variant="primary" onClick={() => void confirmSetup()} disabled={busy || !code.trim()}>
                    {busy ? <Spinner /> : 'Aktifkan'}
                  </Button>
                  <Button onClick={() => setEnrollment(null)} disabled={busy}>
                    Batal
                  </Button>
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      {freshCodes && (
        <div className="card stack" style={{ gap: '0.7rem' }}>
          <div>
            <div style={{ fontWeight: 600 }}>Kode pemulihan</div>
            <div className="muted" style={{ fontSize: '0.85rem' }}>
              Simpan sekarang — kode ini <strong>tak bisa ditampilkan lagi</strong>. Masing-masing hanya
              berlaku sekali, dan gunanya justru saat ponsel autentikatormu hilang: jangan simpan di ponsel
              yang sama.
            </div>
          </div>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))',
              gap: '0.4rem',
            }}
          >
            {freshCodes.map((c) => (
              <code key={c} style={{ fontSize: '0.9rem', letterSpacing: '0.03em' }}>
                {c}
              </code>
            ))}
          </div>
          <div className="row" style={{ gap: '0.5rem' }}>
            <Button icon={<Copy size={16} />} onClick={() => copyCodes(freshCodes)}>
              Salin
            </Button>
            <Button icon={<Download size={16} />} onClick={() => downloadCodes(freshCodes)}>
              Unduh .txt
            </Button>
            <Button variant="subtle" onClick={() => setFreshCodes(null)}>
              Sudah saya simpan
            </Button>
          </div>
        </div>
      )}

      {passwordFor && (
        <Modal
          title={passwordFor === 'disable' ? 'Matikan verifikasi dua langkah' : 'Buat ulang kode pemulihan'}
          onClose={() => {
            setPasswordFor(null)
            setPassword('')
          }}
          footer={
            <>
              <Button
                variant="subtle"
                onClick={() => {
                  setPasswordFor(null)
                  setPassword('')
                }}
              >
                Batal
              </Button>
              <Button variant="primary" onClick={() => void submitPassword()} disabled={busy || !password}>
                {busy ? <Spinner /> : passwordFor === 'disable' ? 'Matikan' : 'Buat ulang'}
              </Button>
            </>
          }
        >
          <div className="stack" style={{ gap: '0.6rem' }}>
            <p style={{ margin: 0, fontSize: '0.88rem' }}>
              {passwordFor === 'disable'
                ? 'Setelah dimatikan, akun ini kembali bisa dimasuki dengan password saja. Kode pemulihan yang ada ikut hangus.'
                : 'Kode pemulihan lama langsung hangus dan diganti delapan kode baru.'}
            </p>
            <TextField
              label="Password"
              type="password"
              value={password}
              onChange={(_, data) => setPassword(data.value)}
              autoFocus
              autoComplete="current-password"
            />
          </div>
        </Modal>
      )}
    </div>
  )
}
