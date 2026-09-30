import { useCallback } from 'react'
import { ApiError } from '@/api/client'
import { getLocation } from '@/api/warehouse/masters'
import { getPosition } from '@/api/warehouse/stock'
import type { TransferDetails, WarehouseTransfer } from '@/api/warehouse/transfers'
import { formatBaseQuantity } from '@/api/warehouse/quantity'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { Button } from '@/components/atoms'
import { WarehouseTransferEditor, type TransferEditorInitial } from './WarehouseTransferEditor'
import { transferPersonLabel } from './transferPresentation'

export function WarehouseTransferEdit({ details, onSaved, onClose, onReload }: {
  details: TransferDetails; onSaved: (row: WarehouseTransfer) => void; onClose: () => void; onReload: () => void;
}) {
  const loader = useCallback(async (): Promise<TransferEditorInitial> => {
    const transfer = details.transfer
    const [source, destination, transit, rows] = await Promise.all([
      getLocation(transfer.sourceLocationId), getLocation(transfer.destinationLocationId), getLocation(transfer.transitLocationId),
      Promise.all(transfer.lines.map(async line => {
        const position = line.sourceBalanceId ? await getPosition(line.sourceBalanceId).catch(error => {
          if (error instanceof ApiError && error.status === 404) return null
          throw error
        }) : null
        return { key: line.id, position: position?.stockIdentityId === line.stockIdentityId ? position : null,
          quantity: formatBaseQuantity(line.quantityBase, line.baseUnit) }
      })),
    ])
    const active = details.references.people.find(person => person.id === transfer.receiverId)?.active === true
    return { transfer, source, destination, transit, rows,
      receiver: { id: transfer.receiverId, name: transferPersonLabel(details, transfer.receiverId), status: active ? 'ACTIVE' : 'DISABLED' } }
  }, [details])
  const result = useWarehouseQuery(loader)
  return <><Button onClick={onClose}>Kembali ke transfer</Button><WarehouseState {...result}>{initial =>
    <WarehouseTransferEditor initial={initial} onSaved={onSaved} onClose={onClose} onReload={onReload} />
  }</WarehouseState></>
}
