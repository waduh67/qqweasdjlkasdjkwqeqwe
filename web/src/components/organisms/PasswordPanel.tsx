import { useId, useRef, useState } from 'react'
import { Button, TextField } from '@/components/atoms'
import { ResourceForm } from './ResourceForm'

export function PasswordPanel({ title, name, email, own = false, onClose, onSave, onSaved }: {
  title: string; name?: string; email: string; own?: boolean
  onClose: () => void; onSave: (password: string, currentPassword: string) => Promise<void>; onSaved: () => void
}) {
  const id = useId(), inFlight = useRef(false)
  const [current, setCurrent] = useState(''), [password, setPassword] = useState(''), [confirmation, setConfirmation] = useState('')
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  async function submit() {
    if (inFlight.current) return
    if (password.length < 8) { setError('Password minimal 8 karakter.'); return }
    if (password !== confirmation) { setError('Konfirmasi password belum sesuai.'); return }
    inFlight.current = true; setBusy(true); setError('')
    try { await onSave(password, current); setCurrent(''); setPassword(''); setConfirmation(''); onSaved() }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Password belum berhasil disimpan. Coba lagi.') }
    finally { inFlight.current = false; setBusy(false) }
  }
  return <ResourceForm title={title} editing navigation={<></>} onClose={onClose} onBack={() => {}} busy={busy}
    dirty={!!(current || password || confirmation)} footer={<><Button disabled={busy} onClick={onClose}>Batal</Button>
      <Button form={id} type="submit" variant="primary" disabled={busy}>{busy ? 'Menyimpan…' : 'Simpan'}</Button></>}>
    <form id={id} className="stack" style={{ gap: 20, maxWidth: 700 }} onSubmit={event => { event.preventDefault(); void submit() }}>
      <p className="form-note">{name ? `${name} · ${email}` : email}</p>
      {own && <TextField label="Password saat ini" type="password" autoComplete="current-password" required maxLength={72} value={current}
        disabled={busy} onChange={(_, data) => setCurrent(data.value)} />}
      <TextField label="Password baru" type="password" autoComplete="new-password" required minLength={8} maxLength={72} value={password}
        hint="Minimal 8 karakter." disabled={busy} onChange={(_, data) => setPassword(data.value)} />
      <TextField label="Konfirmasi password" type="password" autoComplete="new-password" required maxLength={72} value={confirmation}
        disabled={busy} onChange={(_, data) => setConfirmation(data.value)} />
      <p className="form-note">{own ? 'Masuk kembali setelah password diganti.' : 'Pengguna perlu masuk kembali setelah password direset.'}</p>
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
