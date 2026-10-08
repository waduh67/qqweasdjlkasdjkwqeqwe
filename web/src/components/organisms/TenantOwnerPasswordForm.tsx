import { useEffect, useRef, useState } from 'react'
import { ApiError } from '@/api/client'
import type { TenantOwner } from '@/api/tenant'
import { Button, TextField } from '@/components/atoms'

export function TenantOwnerPasswordForm({ owner, busy, resetting, onReset }: {
  readonly owner: TenantOwner
  readonly busy: boolean
  readonly resetting: boolean
  readonly onReset: (password: string) => Promise<boolean>
}) {
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const active = useRef(true)
  useEffect(() => {
    active.current = true
    return () => { active.current = false }
  }, [])
  const [error, setError] = useState<string | null>(null)
  const [identityChanged, setIdentityChanged] = useState(false)
  async function submit() {
    if (busy || identityChanged) return
    if (password.length < 8) { setError('Password minimal 8 karakter'); return }
    if (password !== confirmation) { setError('Konfirmasi password belum cocok'); return }
    setError(null)
    try {
      if (await onReset(password) && active.current) { setPassword(''); setConfirmation('') }
    } catch (err) {
      if (!active.current) return
      setError(err instanceof ApiError ? err.message : 'Gagal mengganti password owner')
      if (err instanceof ApiError && err.status === 409) setIdentityChanged(true)
    }
  }
  return <form className="stack" onSubmit={event => { event.preventDefault(); void submit() }}>
    <p>Password baru untuk <strong>{owner.name}</strong> ({owner.email}). Sesi lama akan berakhir; status akun dan verifikasi dua langkah tetap berlaku.</p>
    <TextField required label="Password baru" type="password" autoComplete="new-password" value={password} disabled={busy || identityChanged}
      onChange={(_, data) => setPassword(data.value)} hint="Minimal 8 karakter." />
    <TextField required label="Konfirmasi password baru" type="password" autoComplete="new-password" value={confirmation} disabled={busy || identityChanged}
      onChange={(_, data) => setConfirmation(data.value)} />
    {error && <p role="alert" className="error">{error}</p>}
    {identityChanged && <p className="form-note">Tutup panel dan buka kembali untuk memuat owner terbaru.</p>}
    <Button type="submit" disabled={busy || identityChanged}>{resetting ? 'Menyimpan…' : 'Ganti password owner'}</Button>
  </form>
}
