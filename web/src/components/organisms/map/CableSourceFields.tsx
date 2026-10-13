import type { CablePortOption, NodeKind } from '@/api/network'
import type { SourcePort } from '@/map/cableFormat'
import { Button, SelectField } from '@/components/atoms'
import { CreationSummary } from '../CreationReview'

export function CableSourceFields({ from, to, fromKind, options, selected, error, onPick, onRetry }: {
  from: string; to: string; fromKind: NodeKind; options: CablePortOption[] | null; selected: SourcePort | null
  error: boolean; onPick: (port: SourcePort | null) => void; onRetry: () => void
}) {
  const key = (port: SourcePort) => port.ponPortId ?? `p${port.portNumber}`
  return <>
    <CreationSummary rows={[[ 'Sumber', from ], [ 'Tujuan', to ]]} />
    {error ? <div role="alert"><p>Port sumber gagal dimuat.</p><Button onClick={onRetry}>Coba lagi</Button></div> : options === null ? <p role="status">Memuat port…</p> : options.length ?
      <SelectField label={fromKind === 'OLT' ? 'Port PON' : 'Port sumber'} required value={selected ? key(selected) : ''}
        onChange={(_, data) => {
          const port = options.find(item => key(item) === data.value && !item.occupied)
          onPick(port ? { ponPortId: port.ponPortId, portNumber: port.portNumber } : null)
        }}>
        <option value="">Pilih port</option>
        {options.map(port => <option key={key(port)} value={key(port)} disabled={port.occupied}>
          {port.label}{port.occupied ? ` · Terpakai${port.occupiedByCable ? ` (${port.occupiedByCable})` : ''}` : ''}
        </option>)}
      </SelectField> : fromKind === 'OLT' ? <p>Belum ada port PON. Tambahkan melalui detail OLT → PON Port.</p> :
      fromKind === 'SITE' ? null : <p className="cable-next-step">{fromKind === 'JOINT_BOX'
        ? `Setelah kabel disimpan, pasangkan core kabel masuk dan keluar melalui Sambungan serat di ${from}.`
        : `Setelah kabel disimpan, atur sambungan core dan port melalui Sambungan serat di ${from}.`}</p>}
  </>
}
