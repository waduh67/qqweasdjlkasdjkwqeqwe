import { useCallback } from 'react'
import { Field } from '@fluentui/react-components'
import type { SpliceMethod } from '@/api/network'
import { SPLICE_METHOD_LABEL } from '@/api/network'
import { SelectField, TextField } from '@/components/atoms'
import { Combobox } from '@/components/molecules'
import { useOpenWorkOrders } from '@/hooks/useOpenWorkOrders'
import { CreationSummary } from './CreationReview'

export function SplicePicker<T extends { id: string; label: string }>({ label, options, value, onChange, required = true, disabled = false, hint }: {
  label: string; options: T[]; value: T | null | undefined; onChange: (item: T | null) => void; required?: boolean; disabled?: boolean; hint?: string
}) {
  const fetchOptions = useCallback(async (term: string) => options.filter(item => item.label.toLowerCase().includes(term.toLowerCase())), [options])
  return <Field className="app-field" size="small" label={label} required={required} hint={hint}
    data-resource-required={required && !disabled} data-resource-selected={!!value} data-resource-label={label}>
    <Combobox ariaLabel={label} value={value?.id ?? ''} initialLabel={value?.label ?? ''} fetchOptions={fetchOptions}
      toId={item => item.id} toLabel={item => item.label} onChange={(_, item) => onChange(item)}
      debounceMs={0} placeholder={`Pilih ${label.toLowerCase()}`} emptyText="Tidak ada pilihan yang tersedia" disabled={disabled} selectionRequired={required} />
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
