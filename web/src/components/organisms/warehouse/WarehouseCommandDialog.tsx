import { useRef, useState, type ReactNode } from 'react'
import { ApiError } from '@/api/client'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button } from '@/components/atoms'
import { Modal } from '@/components/molecules/Modal'
import { warehouseError } from '@/api/warehouse/errors'

/** Mount with a captured command; keep this same instance through every ambiguous retry. */
export function WarehouseCommandDialog<T>({ title, summary, command, confirmLabel = 'Simpan', onDone, onClose, onReload }: {
  title: string; summary: ReactNode; command: WarehouseCommand<T>; confirmLabel?: string;
  onDone: (result: T) => void; onClose: () => void; onReload?: () => void
}) {
  const captured = useRef(command)
  const active = useRef(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const rejected = error instanceof ApiError && [400, 402, 403, 404, 409, 422].includes(error.status)
  const uncertain = error !== null && !rejected
  const close = () => { if (!active.current && !uncertain) onClose() }
  async function submit() {
    if (active.current || rejected) return
    active.current = true; setBusy(true); setError(null)
    try { const result = await captured.current.execute(); onDone(result) }
    catch (caught) { setError(caught) }
    finally { active.current = false; setBusy(false) }
  }
  return <Modal title={title} onClose={close} footer={<>
    <Button variant="subtle" disabled={busy || uncertain} onClick={close}>{rejected ? 'Kembali' : 'Batal'}</Button>
    {rejected && onReload ? <Button variant="primary" onClick={onReload}>Muat ulang dokumen</Button> : <Button variant="primary" disabled={busy || rejected} onClick={() => void submit()}>{busy ? 'Memproses…' : uncertain ? 'Coba transaksi yang sama' : confirmLabel}</Button>}
  </>}>
    <div className="stack">{summary}
      {error !== null && <div role="alert"><p className="error">{warehouseError(error)}</p>{uncertain && <p>Hasil transaksi belum terkonfirmasi. Coba lagi dengan transaksi yang sama sebelum mengubah isinya.</p>}</div>}
    </div>
  </Modal>
}
