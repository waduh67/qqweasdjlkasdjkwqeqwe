import { useCallback } from 'react'
import { getCountDraft, type WarehouseCount } from '@/api/warehouse/counts'
import { getLocation } from '@/api/warehouse/masters'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { Button } from '@/components/atoms'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseCountEditor, type CountEditorInitial } from './WarehouseCountEditor'
import { countLineLabel } from './countPresentation'

export function WarehouseCountEdit({ id, onSaved, onClose, onReload }: {
  id: string; onSaved: (count: WarehouseCount) => void; onClose: () => void; onReload: () => void;
}) {
  const loader = useCallback(async (): Promise<CountEditorInitial> => {
    const draft = await getCountDraft(id)
    const { details } = draft
    const location = await getLocation(details.count.locationId)
    return { details, location, rows: details.count.entries.map(entry => ({
      key: entry.balanceId,
      position: draft.positions.find(position => position.id === entry.balanceId) ?? null,
      counter: details.references.counters.find(person => person.id === entry.counterId) ?? null,
      counterValid: draft.eligibleAssignedCounterIds.includes(entry.counterId),
      priorLabel: countLineLabel(details, entry.balanceId),
    })) }
  }, [id])
  const result = useWarehouseQuery(loader)
  return <><Button onClick={onClose}>Kembali ke stock opname</Button><WarehouseState {...result}>{initial =>
    <WarehouseCountEditor initial={initial} onSaved={onSaved} onClose={onClose} onReload={onReload} />
  }</WarehouseState></>
}
