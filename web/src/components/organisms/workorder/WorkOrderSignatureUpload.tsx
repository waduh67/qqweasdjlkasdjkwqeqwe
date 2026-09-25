import { useRef, useState, type FormEvent } from 'react'
import { api, ApiError } from '@/api/client'
import { useAuth } from '@/auth/useAuth'
import { Button, TextareaField, TextField } from '@/components/atoms'
import { useFieldConnection } from '@/hooks/useFieldConnection'

export function WorkOrderSignatureUpload({ workOrderId, existing, onDone }: { workOrderId: string; existing: boolean; onDone: () => void }) {
  const [name, setName] = useState(''), [reason, setReason] = useState(''), [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null), file = useRef<HTMLInputElement>(null), active = useRef(false)
  const online = useFieldConnection(), { readOnly } = useAuth()
  const disabled = busy || !online || readOnly
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (disabled || active.current) return
    const selected = file.current?.files?.[0]
    if (!selected || !name.trim() || (existing && !reason.trim())) { setError('Lengkapi nama, berkas tanda tangan, dan alasan bila mengoreksi bukti.'); return }
    const form = new FormData()
    form.set('file', selected); form.set('signerName', name.trim())
    if (reason.trim()) form.set('correctionReason', reason.trim())
    active.current = true; setBusy(true); setError(null)
    try {
      await api.putForm(`/api/work-orders/${workOrderId}/signature`, form)
      setName(''); setReason(''); if (file.current) file.current.value = ''
      onDone()
    } catch (caught) { setError(caught instanceof ApiError ? caught.message : 'Tanda tangan belum berhasil disimpan. Muat ulang bukti sebelum mencoba lagi.') }
    finally { active.current = false; setBusy(false) }
  }
  return <form className="stack" aria-label="Unggah tanda tangan pelanggan" onSubmit={event => void submit(event)}>
    <h3>Tanda tangan pelanggan</h3>
    <p>Unggah bukti yang sudah ditandatangani pelanggan. Bukti ini dipakai untuk persetujuan pekerjaan dan serah-terima perangkat.</p>
    <TextField label="Nama penanda tangan" required maxLength={200} value={name} disabled={disabled} onChange={(_, data) => setName(data.value)} />
    <label className="stack">Berkas tanda tangan<input ref={file} type="file" accept="image/*" required disabled={disabled} /></label>
    {existing && <TextareaField label="Alasan koreksi tanda tangan" required maxLength={1000} value={reason} disabled={disabled} onChange={(_, data) => setReason(data.value)} />}
    {error && <p role="alert">{error}</p>}
    <Button type="submit" disabled={disabled}>{busy ? 'Menyimpan tanda tangan…' : existing ? 'Simpan koreksi tanda tangan' : 'Simpan tanda tangan'}</Button>
  </form>
}
