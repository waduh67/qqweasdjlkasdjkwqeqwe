import { useId, useState, type FormEvent } from 'react'
import { activateReferenceWarehouse, type WarehouseActivationReview } from '@/api/warehouse/referenceWorkflow'
import { Button, TextField } from '@/components/atoms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'

export function WarehouseActivationEditor({ review, disabled, onClose, onDone }: {
  readonly review: WarehouseActivationReview; readonly disabled: boolean;
  readonly onClose: () => void; readonly onDone: () => void;
}) {
  const formId = useId(), [reason, setReason] = useState(''), [error, setError] = useState<string | null>(null)
  const [command, setCommand] = useState<ReturnType<typeof activateReferenceWarehouse> | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault()
    if (disabled || command) return
    const value = reason.trim()
    if (!value || value.length > 1000 || [...value].some(char => {
      const code = char.charCodeAt(0)
      return code < 32 || (code >= 127 && code <= 159)
    })) { setError('Isi alasan aktivasi dalam satu baris, maksimal 1.000 karakter.'); return }
    setCommand(activateReferenceWarehouse(review, value)); setError(null)
  }
  return <ResourceForm editing title="Aktifkan alur gudang baru" onClose={onClose} onBack={() => setCommand(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary" disabled={disabled}>Tinjau aktivasi</Button></>}
    review={command && <WarehouseCommandDialog embedded title="Konfirmasi aktivasi gudang" command={command} disabled={disabled}
      confirmLabel="Aktifkan alur baru" onClose={() => setCommand(null)} onDone={onDone} onReload={onDone}
      summary={<><p>Semua penghambat pada pemeriksaan server telah selesai.</p><p>{review.documents} dokumen · {review.balances} posisi stok · {review.segments} identitas fisik.</p>
        <p>Alasan: {reason.trim()}</p><p>Alur baru akan menjadi ruang kerja gudang dan teknisi. Stok terverifikasi tetap memakai ledger yang sama; arsip dokumen lama tetap dapat dibaca. Aktivasi ini satu arah.</p></>} />}
  ><form id={formId} className="stack" onSubmit={prepare}><p>Catat alasan setelah memeriksa dokumen, stok, identitas dan pemegang material.</p>
      <TextField label="Alasan aktivasi" required maxLength={1000} value={reason} onChange={(_, data) => setReason(data.value)} />
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
