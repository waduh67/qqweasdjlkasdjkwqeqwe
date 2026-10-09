import type { ReferencePosition } from '@/api/warehouse/reference'
import { quantityFromInput } from '@/api/warehouse/quantity'

export interface ReferenceTransferRow {
  readonly key: string; readonly position: ReferencePosition | null; readonly quantity: string
}
export class TransferDraftError extends Error {
  readonly name = 'TransferDraftError'
}
export function buildReferenceTransferLines(rows: readonly ReferenceTransferRow[], sourceId: string, destinationId: string) {
  if (sourceId === destinationId) throw new TransferDraftError('Gudang asal dan tujuan harus berbeda.')
  if (rows.length < 1 || rows.length > 100) throw new TransferDraftError('Transfer membutuhkan 1–100 baris barang.')
  const identities = new Set<string>()
  return rows.map((row, index) => {
    const position = row.position
    if (!position) throw new TransferDraftError('Pilih stok pada baris ' + (index + 1) + '.')
    if (position.locationId !== sourceId || position.holderKind !== 'WAREHOUSE' || position.status !== 'AVAILABLE') throw new TransferDraftError('Pilih stok tersedia di gudang asal.')
    if (identities.has(position.stockIdentityId)) throw new TransferDraftError('Posisi stok yang sama tidak boleh dipilih dua kali.')
    identities.add(position.stockIdentityId)
    const quantityBase = quantityFromInput(row.quantity, position.baseUnit)
    if (BigInt(quantityBase) > BigInt(position.quantityBase)) throw new TransferDraftError('Jumlah ' + position.skuName + ' melebihi stok tersedia.')
    if (position.tracking === 'SERIAL' && quantityBase !== '1') throw new TransferDraftError('Transfer perangkat serial harus berjumlah 1 unit.')
    return { stockIdentityId: position.stockIdentityId, quantityBase }
  })
}
