import { useCallback, useState } from 'react'
import { Field } from '@fluentui/react-components'
import { api, ApiError } from '@/api/client'
import type { CableAttachmentRole, CableView, FiberConnectionView, SpliceCableView, SpliceWorkbenchView } from '@/api/network'
import { SPLICE_METHOD_LABEL } from '@/api/network'
import type { PageResponse } from '@/api/types'
import { Button, SelectField } from '@/components/atoms'
import { Combobox } from '@/components/molecules'
import { useToast } from '@/system'
import { connectionLabel, spliceDetailsError } from '@/utils/spliceOptions'
import { Blade } from './Blade'
import { CreationSummary, useCreationReview } from './CreationReview'
import { SpliceDetailFields } from './SpliceDetailFields'

export const cableAttachmentLabel = (role: CableAttachmentRole) => role === 'END' ? 'Berakhir di sini' : role === 'TAPPED' ? 'Serat terbuka' : 'Melintas tanpa dikupas'

export function CableAttachmentForm({ data, cable, onClose, onSaved }: {
  data: SpliceWorkbenchView; cable?: SpliceCableView; onClose: () => void; onSaved: () => void
}) {
  const toast = useToast(), creation = useCreationReview(true, !!cable)
  const [pick, setPick] = useState<CableView | null>(null)
  const [role, setRole] = useState<CableAttachmentRole>(cable?.role ?? 'TAPPED')
  const fetchOptions = useCallback(async (term: string) => {
    const page = await api.get<PageResponse<CableView>>(`/api/cables?query=${encodeURIComponent(term)}&size=25`)
    return page.content.filter(item => !data.cables.some(attached => attached.cableId === item.id))
  }, [data.cables])
  const submit = async () => {
    const id = cable?.cableId ?? pick?.id
    if (!id) { toast.error('Pilih kabel.'); return }
    if (creation.beforeSave()) return
    try {
      await api.post(`/api/cables/${id}/attachments`, { nodeKind: data.closureKind, nodeId: data.closureId, role })
      toast.success('Catatan kabel disimpan'); onSaved()
    } catch (error) { toast.error(error instanceof ApiError ? error.message : 'Catatan kabel gagal disimpan.') }
    finally { creation.finish() }
  }
  return <Blade open title={cable ? 'Ubah catatan kabel' : 'Catat kabel melintas'} onClose={onClose}
    creation={{ ...creation, prepare: () => void submit(), summary: <CreationSummary rows={[
      [ 'Lokasi', data.closureCode ], [ 'Kabel', cable?.code ?? pick?.code ], [ 'Akses serat', cableAttachmentLabel(role) ],
    ]} /> }} footer={<Button variant="primary" disabled={creation.busy} onClick={() => void submit()}>Simpan</Button>}>
    <div className="stack">
    {cable ? <CreationSummary rows={[[ 'Kabel', cable.code ]]} /> : <Field className="app-field" size="small" label="Kabel" required>
      <Combobox ariaLabel="Kabel" value={pick?.id ?? ''} initialLabel={pick?.code} fetchOptions={fetchOptions}
        toId={item => item.id} toLabel={item => item.code} toMeta={item => item.name} onChange={(_, item) => setPick(item)} placeholder="Cari kabel" selectionRequired />
    </Field>}
    <SelectField label="Akses serat" value={role} onChange={(_, next) => setRole(next.value as CableAttachmentRole)}>
      <option value="TAPPED">Serat terbuka</option><option value="PASSING">Melintas tanpa dikupas</option>
    </SelectField>
    </div>
  </Blade>
}

export function EditSpliceForm({ row, onClose, onSaved }: { row: FiberConnectionView; onClose: () => void; onSaved: () => void }) {
  const toast = useToast(), creation = useCreationReview(true, true)
  const [details, setDetails] = useState({ method: row.method, loss: row.lossDb == null ? '' : String(row.lossDb),
    note: row.note ?? '', workOrderId: '', workOrderLabel: '' })
  const submit = async () => {
    const error = spliceDetailsError(details.loss)
    if (error) { toast.error(error); return }
    if (creation.beforeSave()) return
    try {
      await api.put(`/api/fiber-connections/${row.id}`, { method: details.method, lossDb: details.loss.trim() ? Number(details.loss) : null,
        note: details.note.trim() || null, workOrderId: details.workOrderId || null })
      toast.success('Sambungan diperbarui'); onSaved()
    } catch (error) { toast.error(error instanceof ApiError ? error.message : 'Sambungan gagal diperbarui.') }
    finally { creation.finish() }
  }
  return <Blade open title="Ubah sambungan" onClose={onClose} creation={{ ...creation, prepare: () => void submit(), summary: <CreationSummary rows={[
    [ 'Titik pertama', connectionLabel(row.a) ], [ 'Titik kedua', connectionLabel(row.b) ], [ 'Metode', SPLICE_METHOD_LABEL[details.method] ],
    [ 'Redaman', details.loss.trim() ? `${details.loss} dB` : 'Belum diukur' ], [ 'Catatan', details.note ],
    [ 'Work order', row.workOrderCode ?? details.workOrderLabel ],
  ]} /> }} footer={<Button variant="primary" disabled={creation.busy} onClick={() => void submit()}>Simpan</Button>}>
    <div className="stack"><SpliceDetailFields value={details} onChange={setDetails} fixedWorkOrder={row.workOrderId ? row.workOrderCode ?? 'Tercatat' : null} /></div>
  </Blade>
}
