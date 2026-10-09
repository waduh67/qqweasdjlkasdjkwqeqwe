import type { ReferenceRequest, ReferenceRequestLine } from '@/api/warehouse/referenceRequests'
import { RequestDraftError } from './referenceRequestDraft'
import type { ReferenceTransferRow } from './referenceTransferDraft'
import { quantityFromInput } from '@/api/warehouse/quantity'

export function requestHandoverRemaining(request: ReferenceRequest, line: ReferenceRequestLine) {
  const approved = BigInt(line.approvedBase) - BigInt(line.fulfilledBase)
  const received = BigInt(line.receivedBase) - BigInt(line.fulfilledBase)
  return (request.kind === 'PROCUREMENT' && received < approved ? received : approved).toString()
}
export function buildRequestHandover(request: ReferenceRequest, line: ReferenceRequestLine, rows: readonly ReferenceTransferRow[], warehouseId: string) {
  if (!request.technicianId || rows.length < 1 || rows.length > 100) throw new RequestDraftError('Pilih 1–100 posisi stok untuk teknisi.')
  const identities = new Set<string>()
  const lines = rows.map(row => {
    const position = row.position
    if (!position || position.locationId !== warehouseId || position.holderKind !== 'WAREHOUSE' || position.status !== 'AVAILABLE' || position.skuId !== line.skuId || position.baseUnit !== line.baseUnit) throw new RequestDraftError('Pilih stok tersedia untuk material ini di gudang penyerahan.')
    if (identities.has(position.stockIdentityId)) throw new RequestDraftError('Posisi stok yang sama tidak boleh dipilih dua kali.')
    identities.add(position.stockIdentityId)
    const quantityBase = quantityFromInput(row.quantity, line.baseUnit)
    if (BigInt(quantityBase) > BigInt(position.quantityBase) || position.tracking === 'SERIAL' && quantityBase !== '1') throw new RequestDraftError('Jumlah melebihi stok tersedia atau jumlah perangkat serial.')
    return { stockIdentityId: position.stockIdentityId, quantityBase }
  })
  if (lines.reduce((total, row) => total + BigInt(row.quantityBase), 0n) > BigInt(requestHandoverRemaining(request, line))) throw new RequestDraftError('Jumlah melebihi sisa yang dapat diserahkan.')
  return lines
}
