import { useRef, useState, type ReactNode } from 'react'
import { ApiError } from '@/api/client'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button } from '@/components/atoms'
import { Modal } from '@/components/molecules/Modal'
import { useResourceReviewLock } from '../ResourceForm'
import { warehouseError } from '@/api/warehouse/errors'

/** Mount with a captured command; keep this same instance through every ambiguous retry. */
export function WarehouseCommandDialog<T>({ title, summary, command, confirmLabel = 'Simpan', onDone, onClose, onReload, disabled = false, embedded = false }: {
  title: string; summary: ReactNode; command: WarehouseCommand<T>; confirmLabel?: string;
  onDone: (result: T) => void; onClose: () => void; onReload?: () => void; disabled?: boolean; embedded?: boolean
}) {
  const captured = useRef(command)
  const active = useRef(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const rejected = error instanceof ApiError && [400, 402, 403, 404, 409, 422].includes(error.status)
  const uncertain = error !== null && !rejected
  useResourceReviewLock(busy || uncertain)
  const close = () => { if (!active.current && !uncertain) onClose() }
  async function submit() {
    if (active.current || rejected || disabled) return
    active.current = true; setBusy(true); setError(null)
    try { const result = await captured.current.execute(); onDone(result) }
    catch (caught) { setError(caught) }
    finally { active.current = false; setBusy(false) }
  }
  const actions = <>
    <Button variant="subtle" disabled={busy || uncertain} onClick={close}>{rejected ? 'Kembali' : 'Batal'}</Button>
    {rejected && onReload ? <Button variant="primary" onClick={onReload}>Muat ulang dokumen</Button> : <Button variant="primary" disabled={busy || rejected || disabled} onClick={() => void submit()}>{busy ? 'Memproses…' : uncertain ? 'Coba transaksi yang sama' : confirmLabel}</Button>}
  </>
  const content = <div className="stack resource-review-summary">{summary}
      {error !== null && <div role="alert"><p className="error">{warehouseError(error)}</p>{uncertain && <p>Penyimpanan belum terkonfirmasi. Coba lagi untuk memastikan hasilnya.</p>}</div>}
    </div>
  return embedded ? <><h2 className="resource-review-heading">{title}</h2>{content}<div className="resource-form-review-footer">{actions}</div></> : <Modal title={title} onClose={close} footer={actions}>{content}</Modal>
}
