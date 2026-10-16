import { ResourceForm } from '@/components/organisms/ResourceForm'
import { copyText } from '@/utils/clipboard'
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
import { Button, Spinner, TextField } from '@/components/atoms'
import { Ess } from '@/components/molecules'
import { useToast } from '@/system'

/**
 * Kartu operator "Portal pelanggan" di detail pelanggan: lihat status, buatkan/nonaktifkan
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
  const [loadError, setLoadError] = useState(false)
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
    setLoading(true); setLoadError(false)
    void getPortalCredential(customerId)
      .then(setStatus)
      .catch(() => setLoadError(true))
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
    <section className="customer-section">
      <h2>Portal pelanggan</h2>
      {canManage && !loading && !loadError && form === null && (
        <div className="customer-actions" role="group" aria-label="Aksi portal pelanggan">
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
              <Button variant="subtle" disabled={busy} onClick={() => setForm('reset')}>Reset password</Button>
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

      {loading ? (
        <div style={{ display: 'grid', placeItems: 'center', minHeight: 60 }}>
          <Spinner />
        </div>
      ) : loadError ? <p role="alert">Akun portal gagal dimuat. <Button onClick={load}>Coba lagi</Button></p> : !status?.provisioned ? (
        <p className="muted" style={{ margin: 0, ...typographyStyles.body1 }}>
          Akun portal belum dibuat.
        </p>
      ) : (
        <dl className="essentials wide">
          <Ess label="Status">{status.active ? 'Aktif' : 'Nonaktif'}</Ess>
          <Ess label="Login">
            <span className="tnum">{status.login}</span>
          </Ess>
        </dl>
      )}

      {/* Password sementara — hanya muncul saat server yang membangkitkan. */}
      {reveal?.temporaryPassword && (
        <ResourceForm title="Akun portal pelanggan" readOnly onClose={() => setReveal(null)} onBack={() => {}}
          footer={<Button onClick={() => setReveal(null)}>Selesai</Button>}>
          <div className="stack"><p>Simpan password ini sebelum menutup panel.</p>
            <CreationSummary rows={[
              ['Login', reveal.login], ['Password', <span key="temporary-password" style={{ userSelect: 'all' }}>{reveal.temporaryPassword}</span>],
            ]} />
            <Button onClick={() => void copyText(reveal.temporaryPassword ?? '').then(ok => ok ? toast.success('Password disalin') : toast.error('Gagal menyalin password.'))}>Salin password</Button>
          </div>
        </ResourceForm>
      )}

      {canManage && form !== null && <Blade open title={form === 'provision' ? 'Buat login portal' : 'Reset password portal'} onClose={closeForm}
        creation={{ ...creation, busy, prepare: save, summary: <>{error && <p role="alert">{error}</p>}<CreationSummary rows={[
          ['Login', form === 'provision' ? login.trim() || 'Kode pelanggan' : status?.login], ['Password', password ? 'Ditentukan pengguna' : 'Dibuat otomatis'],
        ]} /></> }} footer={<><Button disabled={busy} onClick={closeForm}>Batal</Button><Button variant="primary" disabled={busy} onClick={save}>Simpan</Button></>}>
        <div className="stack">{error && <p role="alert">{error}</p>}{form === 'provision' && <TextField label="Login" value={login} onChange={(_, data) => setLogin(data.value)} placeholder="Gunakan kode pelanggan" />}
          <TextField label="Password" type="password" minLength={8} value={password} onChange={(_, data) => setPassword(data.value)} autoComplete="new-password" hint="Kosongkan untuk membuat password otomatis." />
        </div>
      </Blade>}

    </section>
  )
}
