import { Blade } from '@/components/organisms/Blade'
import { CreationSummary, useCreationReview } from '@/components/organisms/CreationReview'
import { useCallback, useEffect, useState } from 'react'
import { typographyStyles } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import {
  disablePortalCredential,
  enablePortalCredential,
  getPortalCredential,
  provisionPortalCredential,
  resetPortalPassword,
  type PortalCredentialProvisioned,
  type PortalCredentialStatus,
} from '@/api/portalAdmin'
import { useCan } from '@/auth/useCan'
import { Badge, Button, Spinner, TextField } from '@/components/atoms'
import { IconKey } from '@/components/atoms/icons'
import { Ess } from '@/components/molecules'
import { useToast } from '@/system'

/**
 * Kartu operator "Kredensial Portal" di detail pelanggan: lihat status, buatkan/nonaktifkan
 * login self-service, dan reset password. Password sementara yang di-generate server
 * ditampilkan SEKALI di sini (operator menyalin & memberikannya ke pelanggan) — server tak
 * pernah menyimpannya dalam bentuk terbaca.
 *
 * Digerbang izin: `portal.credential.view` untuk melihat, `portal.credential.manage` untuk
 * aksi. Server tetap penegak sebenarnya; gerbang ini demi UX.
 */
export function PortalCredentialCard({ customerId }: { customerId: string }) {
  const { can } = useCan()
  const toast = useToast()
  const canView = can('portal.credential.view')
  const canManage = can('portal.credential.manage')

  const [status, setStatus] = useState<PortalCredentialStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  // Password sementara hasil generate — ditahan di UI sampai operator menutupnya.
  const [reveal, setReveal] = useState<PortalCredentialProvisioned | null>(null)
  // Form provisi/reset manual (login/password diketik operator); null = tertutup.
  const [form, setForm] = useState<'provision' | 'reset' | null>(null)
  const [login, setLogin] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const creation = useCreationReview(form !== null, form === 'reset')

  const load = useCallback(() => {
    if (!canView) return
    setLoading(true)
    void getPortalCredential(customerId)
      .then(setStatus)
      .catch(() => setStatus(null))
      .finally(() => setLoading(false))
  }, [canView, customerId])

  useEffect(() => load(), [load])

  if (!canView) return null

  const run = async (action: () => Promise<unknown>, okMessage: string) => {
    setBusy(true)
    try {
      await action()
      toast.success(okMessage)
      load()
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Operasi gagal'
      setError(message); toast.error(message)
    } finally {
      setBusy(false)
    }
  }

  const closeForm = () => {
    setForm(null)
    setError(null)
    setLogin('')
    setPassword('')
  }

  // Provisi/reset yang MUNGKIN mengungkap password: tampung hasilnya untuk ditampilkan.
  const runRevealing = async (action: () => Promise<PortalCredentialProvisioned>, okMessage: string) => {
    setBusy(true); setError(null)
    try {
      const result = await action()
      if (result.temporaryPassword) setReveal(result)
      toast.success(okMessage)
      closeForm()
      load()
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Operasi gagal'
      setError(message); toast.error(message)
    } finally {
      setBusy(false)
    }
  }

  const save = () => {
    if (password.trim() && password.trim().length < 8) { setError('Password minimal 8 karakter.'); return }
    if (creation.beforeSave()) return
    void runRevealing(() => form === 'provision'
      ? provisionPortalCredential(customerId, { login: login.trim() || null, password: password.trim() || null })
      : resetPortalPassword(customerId, password.trim() || null), form === 'provision' ? 'Kredensial dibuat' : 'Password direset').finally(creation.finish)
  }

  return (
    <div className="card stack" style={{ gap: '0.75rem' }}>
      {/* Kepala seksi seragam dengan seksi lain di detail pelanggan: ikon beraksen +
          judul, tanpa bingkai sendiri (wadahnya yang meratakan). */}
      <div className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
        <div className="section-head">
          <span className="ico" aria-hidden>
            <IconKey size={16} />
          </span>
          <h3 className="section-title">Kredensial Portal</h3>
        </div>
        {status?.provisioned && (
          <Badge tone={status.active ? 'good' : 'neutral'}>{status.active ? 'aktif' : 'nonaktif'}</Badge>
        )}
      </div>

      {loading ? (
        <div style={{ display: 'grid', placeItems: 'center', minHeight: 60 }}>
          <Spinner />
        </div>
      ) : !status?.provisioned ? (
        <p className="muted" style={{ margin: 0, ...typographyStyles.body1 }}>
          Pelanggan belum punya login portal self-service.
        </p>
      ) : (
        <dl className="essentials wide">
          <Ess label="Login">
            <span className="tnum">{status.login}</span>
          </Ess>
        </dl>
      )}

      {/* Password sementara — hanya muncul saat server yang membangkitkan. */}
      {reveal?.temporaryPassword && (
        <div
          className="stack"
          style={{ gap: '0.35rem', padding: '0.6rem 0.7rem', borderRadius: 8, background: 'var(--accent-soft)', border: '1px solid var(--border-strong)' }}
        >
          <span style={{ ...typographyStyles.subtitle2 }}>Password sementara (salin sekarang):</span>
          <div className="row" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <code className="tnum" style={{ ...typographyStyles.subtitle1, userSelect: 'all' }}>{reveal.temporaryPassword}</code>
            <Button
              variant="subtle"
              onClick={() => {
                void navigator.clipboard?.writeText(reveal.temporaryPassword ?? '')
                toast.success('Password disalin')
              }}
            >
              Salin
            </Button>
            <Button variant="subtle" onClick={() => setReveal(null)}>Tutup</Button>
          </div>
          <span className="muted" style={{ ...typographyStyles.caption1 }}>
            Login <span className="tnum">{reveal.login}</span> · tak bisa dilihat lagi setelah ditutup.
          </span>
        </div>
      )}

      {canManage && form === null && (
        <div className="row" style={{ gap: '0.4rem', flexWrap: 'wrap' }}>
          {!status?.provisioned ? (
            <>
              <Button
                variant="primary"
                disabled={busy}
                onClick={() => setForm('provision')}
              >
                Buat login portal
              </Button>

            </>
          ) : (
            <>
              <Button
                variant="subtle"
                disabled={busy}
                onClick={() => void runRevealing(() => resetPortalPassword(customerId), 'Password direset')}
              >
                Reset password (otomatis)
              </Button>
              <Button variant="subtle" disabled={busy} onClick={() => setForm('reset')}>
                Reset manual…
              </Button>
              {status.active ? (
                <Button
                  variant="danger"
                  disabled={busy}
                  onClick={() => void run(() => disablePortalCredential(customerId), 'Login dinonaktifkan')}
                >
                  Nonaktifkan
                </Button>
              ) : (
                <Button
                  variant="subtle"
                  disabled={busy}
                  onClick={() => void run(() => enablePortalCredential(customerId), 'Login diaktifkan')}
                >
                  Aktifkan
                </Button>
              )}
            </>
          )}
        </div>
      )}

      {canManage && form !== null && <Blade open title={form === 'provision' ? 'Buat login portal' : 'Reset password portal'} onClose={closeForm}
        creation={{ ...creation, busy, prepare: save, summary: <>{error && <p role="alert">{error}</p>}<CreationSummary rows={[
          ['Login', form === 'provision' ? login.trim() || 'Kode pelanggan' : status?.login], ['Password', password ? 'Ditentukan pengguna' : 'Dibuat otomatis'],
        ]} /></> }} footer={<><Button disabled={busy} onClick={closeForm}>Batal</Button><Button variant="primary" disabled={busy} onClick={save}>Simpan</Button></>}>
        <div className="stack">{error && <p role="alert">{error}</p>}{form === 'provision' && <TextField label="Login" value={login} onChange={(_, data) => setLogin(data.value)} placeholder="Gunakan kode pelanggan" />}
          <TextField label="Password" type="password" minLength={8} value={password} onChange={(_, data) => setPassword(data.value)} autoComplete="new-password" hint="Kosongkan untuk membuat password otomatis." />
        </div>
      </Blade>}

    </div>
  )
}
