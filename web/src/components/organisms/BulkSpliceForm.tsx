import { useMemo, useState } from 'react'
import { Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow } from '@fluentui/react-components'
import { api, ApiError } from '@/api/client'
import type { SpliceWorkbenchView } from '@/api/network'
import { SPLICE_METHOD_LABEL } from '@/api/network'
import { Button } from '@/components/atoms'
import { useToast } from '@/system'
import { groupSpliceOptions, matchingCorePairs, spliceOptions } from '@/utils/spliceOptions'
import { Blade } from './Blade'
import { CreationSummary, useCreationReview } from './CreationReview'
import { initialSpliceDetails, SpliceDetailFields, SplicePicker } from './SpliceDetailFields'
import { ResourceStep } from './ResourceStep'

export function BulkSpliceForm({ data, onClose, onSaved }: { data: SpliceWorkbenchView; onClose: () => void; onSaved: () => void }) {
  const toast = useToast(), creation = useCreationReview(true)
  const [sourceId, setSourceId] = useState(''), [targetId, setTargetId] = useState('')
  const [details, setDetails] = useState(initialSpliceDetails)
  const groups = useMemo(() => groupSpliceOptions(spliceOptions(data).filter(option => option.point.kind === 'CORE')), [data])
  const source = groups.find(group => group.id === sourceId)
  const targets = groups.filter(group => group.id !== sourceId)
  const target = targets.find(group => group.id === targetId)
  const pairs = matchingCorePairs(source, target, data)
  const remaining = data.spliceCapacity == null ? Infinity : data.spliceCapacity - data.spliceCount
  const validate = () => !source || !target ? 'Pilih dua kabel berbeda.' : !pairs.length ? 'Tidak ada pasangan core dengan nomor yang sama dan tersedia.'
    : pairs.length > remaining ? `Kapasitas tersisa ${remaining} sambungan.` : null
  const submit = async () => {
    const error = validate()
    if (error) { toast.error(error); return }
    if (creation.beforeSave()) return
    try {
      await api.post('/api/fiber-connections/bulk', { closureKind: data.closureKind, closureId: data.closureId,
        pairs: pairs.map(pair => ({ a: pair.a.point, b: pair.b.point, method: details.method })), workOrderId: details.workOrderId || null })
      toast.success(`${pairs.length} sambungan ditambahkan`); onSaved()
    } catch (error) { toast.error(error instanceof ApiError ? error.message : 'Sambungan gagal disimpan.') }
    finally { creation.finish() }
  }
  return <Blade open title="Sambung core 1:1" onClose={onClose} creation={{ ...creation, prepare: () => void submit(),
    steps: [{ key: 'cables', label: 'Kabel', validate }, { key: 'details', label: 'Detail' }],
    summary: <><CreationSummary rows={[[ 'Lokasi', data.closureCode ], [ 'Kabel sumber', source?.label ], [ 'Kabel tujuan', target?.label ],
      [ 'Jumlah sambungan', String(pairs.length) ], [ 'Metode', SPLICE_METHOD_LABEL[details.method] ], [ 'Work order', details.workOrderLabel ]]} />
      <div className="table-wrap"><Table aria-label="Pasangan core"><TableHeader><TableRow><TableHeaderCell>Sumber</TableHeaderCell><TableHeaderCell>Tujuan</TableHeaderCell></TableRow></TableHeader>
        <TableBody>{pairs.map(pair => <TableRow key={pair.a.id}><TableCell>{pair.a.fullLabel}</TableCell><TableCell>{pair.b.fullLabel}</TableCell></TableRow>)}</TableBody>
      </Table></div></>,
  }} footer={<Button variant="primary" disabled={creation.busy} onClick={() => void submit()}>Buat {pairs.length} sambungan</Button>}>
    <ResourceStep step="cables">
      <SplicePicker label="Kabel sumber" options={groups} value={source} onChange={group => { setSourceId(group?.id ?? ''); setTargetId('') }} />
      <SplicePicker label="Kabel tujuan" options={targets} value={target} onChange={group => setTargetId(group?.id ?? '')} disabled={!source} />
      <p className="splice-field-note">Core dipasangkan berdasarkan nomor yang sama. Core yang tidak tersedia dilewati.</p>
      {source && target && <p role="status">{pairs.length} pasangan core tersedia.</p>}
    </ResourceStep>
    <ResourceStep step="details"><SpliceDetailFields value={details} onChange={setDetails} bulk /></ResourceStep>
  </Blade>
}
