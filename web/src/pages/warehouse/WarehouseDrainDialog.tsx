import { useRef, useState } from 'react'
import { ApiError } from '@/api/client'
import { warehouseError } from '@/api/warehouse/errors'
import { readWorkflow } from '@/api/warehouse/reference'
import { startWarehouseDrain, type workflowSnapshot } from '@/api/warehouse/referenceWorkflow'
import { captureCommandSession } from '@/api/warehouse/transport'
import { Button } from '@/components/atoms'
import { Modal } from '@/components/molecules/Modal'

export function WarehouseDrainDialog({ snapshot, disabled, onClose, onDone }: {
  readonly snapshot: ReturnType<typeof workflowSnapshot>; readonly disabled: boolean;
  readonly onClose: () => void; readonly onDone: () => void;
}) {
  const [checkSession] = useState(captureCommandSession), active = useRef(false)
  const [busy, setBusy] = useState(false), [uncertain, setUncertain] = useState(false), [error, setError] = useState<unknown>(null)
  async function submit() {
    if (active.current || uncertain || disabled) return
    active.current = true; setBusy(true); setError(null)
    try {
      checkSession()
      await startWarehouseDrain(snapshot.epoch)
      checkSession(); onDone()
    } catch (caught) {
      setError(caught)
      setUncertain(!(caught instanceof ApiError && [400, 402, 403, 404, 409, 422].includes(caught.status)))
    } finally { active.current = false; setBusy(false) }
  }
  async function resolve() {
    if (active.current) return
    active.current = true; setBusy(true); setError(null)
    try {
      checkSession()
      const current = await readWorkflow()
      checkSession()
      if (!current.owner || current.tenantId !== snapshot.tenantId || current.epoch !== snapshot.epoch || current.workflow !== 'LEGACY') onDone()
      else setUncertain(false)
    } catch (caught) { setError(caught) }
    finally { active.current = false; setBusy(false) }
  }
  return <Modal title="Mulai perpindahan gudang" onClose={() => { if (!active.current && !uncertain) onClose() }} footer={<>
    <Button disabled={busy || uncertain} onClick={onClose}>Batal</Button>
    {uncertain ? <Button variant="primary" disabled={busy} onClick={() => void resolve()}>Periksa hasil perpindahan</Button>
      : <Button variant="primary" disabled={busy || disabled || error !== null} onClick={() => void submit()}>Mulai perpindahan</Button>}
  </>}>
    <div className="stack"><p>Dokumen baru pada alur gudang lama akan dihentikan. Selesaikan dokumen dan pekerjaan yang masih berjalan, lalu periksa kesiapan sebelum mengaktifkan alur baru.</p>
      <p>Perpindahan ini satu arah. Stok belum berpindah atau bertambah saat langkah ini disimpan.</p>
      {busy && <p role="status">Memproses perpindahan…</p>}
      {uncertain && <p role="status">Hasil belum terkonfirmasi. Periksa status dari server sebelum mencoba lagi.</p>}
      {error !== null && <p role="alert" className="error">{warehouseError(error)}</p>}
    </div>
  </Modal>
}
