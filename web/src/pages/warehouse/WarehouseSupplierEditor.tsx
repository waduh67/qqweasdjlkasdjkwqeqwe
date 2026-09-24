import { useId, useState, type FormEvent } from 'react'
import { saveSupplier } from '@/api/warehouse/masters'
import type { WarehouseSupplier } from '@/api/warehouse/models'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, TextField } from '@/components/atoms'
import { Modal } from '@/components/molecules/Modal'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'

export function WarehouseSupplierEditor({ row, readOnly, onClose, onSaved, onReload }: { row: WarehouseSupplier | null; readOnly: boolean; onClose: () => void; onSaved: () => void; onReload: () => void }) {
  const formId = useId()
  const [code, setCode] = useState(row?.code ?? '')
  const [name, setName] = useState(row?.name ?? '')
  const [contact, setContact] = useState(row?.contactReference ?? '')
  const [operation, setOperation] = useState<WarehouseCommand<WarehouseSupplier> | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault(); if (readOnly) return
    setOperation(saveSupplier({ code: code.trim(), name: name.trim(), contactReference: contact.trim() || null, ...(row ? { expectedRevision: row.revision } : {}) }, row?.id))
  }
  return <>
    <Modal title={readOnly ? 'Detail pemasok' : row ? 'Ubah pemasok' : 'Tambah pemasok'} onClose={onClose} footer={<>
      <Button onClick={onClose}>{readOnly ? 'Tutup' : 'Batal'}</Button>{!readOnly && <Button variant="primary" type="submit" form={formId}>Tinjau perubahan</Button>}
    </>}>
      <form id={formId} className="stack" onSubmit={prepare}>
        {row && <p className="muted">Tersimpan: {row.name} · Revisi {row.revision}</p>}
        <TextField label="Kode pemasok" required pattern="[A-Z0-9][A-Z0-9._-]{0,63}" maxLength={64} value={code} disabled={readOnly} onChange={(_, data) => setCode(data.value.toUpperCase())} />
        <TextField label="Nama pemasok" required maxLength={200} value={name} disabled={readOnly} onChange={(_, data) => setName(data.value)} />
        <TextField label="Kontak / referensi" maxLength={500} value={contact} disabled={readOnly} onChange={(_, data) => setContact(data.value)} />
      </form>
    </Modal>
    {operation && <WarehouseCommandDialog title="Simpan pemasok" command={operation} confirmLabel="Simpan pemasok" onDone={onSaved} onClose={() => setOperation(null)} onReload={onReload}
      summary={<><p><strong>{name.trim()}</strong> · {code.trim()}{row && ` · Revisi ${row.revision}`}</p><p>{contact.trim() || 'Tanpa kontak tambahan'}</p></>} />}
  </>
}
