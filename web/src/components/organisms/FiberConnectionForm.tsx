import { useCallback, useMemo, useState } from 'react'
import { Field } from '@fluentui/react-components'
import { api, ApiError } from '@/api/client'
import type { SpliceMethod, SpliceWorkbenchView } from '@/api/network'
import { SPLICE_METHOD_LABEL } from '@/api/network'
import { Button, SelectField, TextField } from '@/components/atoms'
import { Combobox } from '@/components/molecules'
import { useOpenWorkOrders } from '@/hooks/useOpenWorkOrders'
import { useToast } from '@/system'
import {
  compatibleSplice, groupSpliceOptions, matchesPurpose, purposesFor, spliceDetailsError,
  spliceOptions, splicePurposes, type SplicePurpose,
} from '@/utils/spliceOptions'
import { Blade } from './Blade'
import { CreationSummary, useCreationReview } from './CreationReview'
import { ResourceStep } from './ResourceStep'

export function SplicePicker<T extends { id: string; label: string }>({ label, options, value, onChange, required = true, disabled = false, hint }: {
  label: string; options: T[]; value: T | null | undefined; onChange: (item: T | null) => void; required?: boolean; disabled?: boolean; hint?: string
}) {
  const fetchOptions = useCallback(async (term: string) => options.filter(item => item.label.toLowerCase().includes(term.toLowerCase())), [options])
  return <Field className="app-field" size="small" label={label} required={required} hint={hint}>
    <Combobox ariaLabel={label} value={value?.id ?? ''} initialLabel={value?.label ?? ''} fetchOptions={fetchOptions}
      toId={item => item.id} toLabel={item => item.label} onChange={(_, item) => onChange(item)}
      debounceMs={0} placeholder={`Pilih ${label.toLowerCase()}`} emptyText="Tidak ada pilihan yang tersedia" disabled={disabled} />
  </Field>
}

export interface SpliceDetails {
  method: SpliceMethod; loss: string; note: string; workOrderId: string; workOrderLabel: string
}
export const initialSpliceDetails: SpliceDetails = { method: 'FUSION', loss: '', note: '', workOrderId: '', workOrderLabel: '' }
export function SpliceDetailFields({ value, onChange, fixedWorkOrder, bulk = false }: {
  value: SpliceDetails; onChange: (value: SpliceDetails) => void; fixedWorkOrder?: string | null; bulk?: boolean
}) {
  const { canPick, fetchWorkOrders } = useOpenWorkOrders()
  return <>
    <SelectField label="Metode" value={value.method} onChange={(_, data) => onChange({ ...value, method: data.value as SpliceMethod })}>
      {Object.entries(SPLICE_METHOD_LABEL).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
    </SelectField>
    {!bulk && <>
      <TextField label="Redaman (dB)" value={value.loss} inputMode="decimal" placeholder="Belum diukur"
        onChange={(_, data) => onChange({ ...value, loss: data.value })} />
      <TextField label="Catatan" value={value.note} maxLength={200} onChange={(_, data) => onChange({ ...value, note: data.value })} />
    </>}
    {fixedWorkOrder ? <CreationSummary rows={[[ 'Work order', fixedWorkOrder ]]} /> : canPick && <Field className="app-field" size="small" label="Work order">
      <Combobox ariaLabel="Work order" value={value.workOrderId} initialLabel={value.workOrderLabel} fetchOptions={fetchWorkOrders}
        toId={item => item.id} toLabel={item => `${item.code} · ${item.title}`} placeholder="Pilih work order"
        onChange={(id, item) => onChange({ ...value, workOrderId: id, workOrderLabel: item ? `${item.code} · ${item.title}` : '' })} />
    </Field>}
  </>
}

export function FiberConnectionForm({ data, onClose, onSaved }: { data: SpliceWorkbenchView; onClose: () => void; onSaved: () => void }) {
  const toast = useToast()
  const creation = useCreationReview(true)
  const purposes = purposesFor(data)
  const [purpose, setPurpose] = useState<SplicePurpose>(purposes[0])
  const [sourceGroupId, setSourceGroupId] = useState<string | undefined>(), [targetGroupId, setTargetGroupId] = useState<string | undefined>()
  const [sourceId, setSourceId] = useState<string | undefined>(), [targetId, setTargetId] = useState<string | undefined>()
  const [details, setDetails] = useState(initialSpliceDetails)
  const options = useMemo(() => spliceOptions(data), [data])
  const sourceGroups = groupSpliceOptions(options.filter(option => matchesPurpose(option, purpose, 'source')))
  const sourceGroup = sourceGroups.find(group => group.id === sourceGroupId) ?? (sourceGroupId === undefined && sourceGroups.length === 1 ? sourceGroups[0] : null)
  const source = sourceGroup?.options.find(option => option.id === sourceId)
    ?? (sourceId === undefined && sourceGroup?.options.length === 1 && sourceGroup.options[0].point.kind !== 'CORE' ? sourceGroup.options[0] : null)
  const targetGroups = groupSpliceOptions(source ? options.filter(option => matchesPurpose(option, purpose, 'target') && compatibleSplice(source, option, data)) : [])
  const targetGroup = targetGroups.find(group => group.id === targetGroupId) ?? (targetGroupId === undefined && targetGroups.length === 1 ? targetGroups[0] : null)
  const target = targetGroup?.options.find(option => option.id === targetId)
    ?? (targetId === undefined && targetGroup?.options.length === 1 && targetGroup.options[0].point.kind !== 'CORE' ? targetGroup.options[0] : null)
  const clearTarget = () => { setTargetGroupId(undefined); setTargetId(undefined) }
  const sourceCable = ['INPUT', 'CABLE', 'TERMINATION'].includes(purpose)
  const targetCable = ['OUTPUT', 'CABLE'].includes(purpose)
  const validate = () => !source ? 'Pilih titik sumber.' : !target ? 'Pilih titik tujuan.' : null
  const submit = async () => {
    const error = validate() ?? spliceDetailsError(details.loss)
    if (error || !source || !target) { toast.error(error ?? 'Pilih pasangan sambungan.'); return }
    if (creation.beforeSave()) return
    try {
      await api.post('/api/fiber-connections', {
        closureKind: data.closureKind, closureId: data.closureId, a: source.point, b: target.point,
        method: details.method, lossDb: details.loss.trim() ? Number(details.loss) : null,
        note: details.note.trim() || null, workOrderId: details.workOrderId || null,
      })
      toast.success('Sambungan ditambahkan'); onSaved()
    } catch (error) { toast.error(error instanceof ApiError ? error.message : 'Sambungan gagal disimpan.') }
    finally { creation.finish() }
  }
  return <Blade open title="Tambah sambungan" onClose={onClose} creation={{ ...creation, prepare: () => void submit(),
    steps: [{ key: 'connection', label: 'Sambungan', validate }, { key: 'details', label: 'Detail', validate: () => spliceDetailsError(details.loss) }],
    summary: <CreationSummary rows={[[ 'Lokasi', data.closureCode ], [ 'Jenis sambungan', splicePurposes[purpose] ],
      [ 'Sumber', source?.fullLabel ], [ 'Tujuan', target?.fullLabel ], [ 'Metode', SPLICE_METHOD_LABEL[details.method] ],
      [ 'Redaman', details.loss.trim() ? `${details.loss} dB` : 'Belum diukur' ], [ 'Work order', details.workOrderLabel ], [ 'Catatan', details.note ]]} />,
  }} footer={<Button variant="primary" disabled={creation.busy} onClick={() => void submit()}>Buat sambungan</Button>}>
    <ResourceStep step="connection">
      <SelectField label="Jenis sambungan" value={purpose} onChange={(_, next) => {
        setPurpose(next.value as SplicePurpose); setSourceGroupId(undefined); setSourceId(undefined); clearTarget()
      }}>{purposes.map(key => <option key={key} value={key}>{splicePurposes[key]}</option>)}</SelectField>
      <SplicePicker label={sourceCable ? 'Kabel sumber' : purpose === 'PATCH' ? 'Perangkat sumber' : 'Splitter sumber'}
        options={sourceGroups} value={sourceGroup} onChange={group => { setSourceGroupId(group?.id ?? ''); setSourceId(undefined); clearTarget() }} />
      <SplicePicker label={sourceCable ? 'Core sumber' : 'Port sumber'} options={sourceGroup?.options ?? []} value={source}
        onChange={option => { setSourceId(option?.id ?? ''); clearTarget() }} disabled={!sourceGroup}
        hint={source?.connectedElsewhere ? 'Core ini memiliki sambungan di lokasi lain.' : undefined} />
      <SplicePicker label={targetCable ? 'Kabel tujuan' : purpose === 'TERMINATION' || purpose === 'PATCH' ? 'Perangkat tujuan' : 'Splitter tujuan'}
        options={targetGroups} value={targetGroup} onChange={group => { setTargetGroupId(group?.id ?? ''); setTargetId(undefined) }} disabled={!source} />
      <SplicePicker label={targetCable ? 'Core tujuan' : 'Port tujuan'} options={targetGroup?.options ?? []} value={target}
        onChange={option => setTargetId(option?.id ?? '')} disabled={!targetGroup} />
      {source && !targetGroups.length && <p role="status" className="splice-field-note">{purpose === 'CABLE'
        ? 'Belum ada kabel lain dengan core tersedia di lokasi ini.' : 'Belum ada tujuan yang tersedia untuk sambungan ini.'}</p>}
    </ResourceStep>
    <ResourceStep step="details"><SpliceDetailFields value={details} onChange={setDetails} /></ResourceStep>
  </Blade>
}
