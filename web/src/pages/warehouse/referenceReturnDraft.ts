import type { ReferencePosition } from '@/api/warehouse/reference'
import { quantityFromInput } from '@/api/warehouse/quantity'

export interface ReferenceReturnRow { readonly key: string; readonly position: ReferencePosition | null; readonly quantity: string }
export class ReturnDraftError extends Error { readonly name = 'ReturnDraftError' }
export function buildReferenceReturn(rows: readonly ReferenceReturnRow[], technicianId: string) {
  const first = rows[0]?.position
  if (!first || rows.length < 1 || rows.length > 100) throw new ReturnDraftError('Pilih 1–100 posisi material milik Anda.')
  const selected = new Set<string>()
  const lines = rows.map(row => {
    const item = row.position
    if (!item || item.holderKind !== 'TECHNICIAN' || item.holderId !== technicianId || item.status !== 'ISSUED') throw new ReturnDraftError('Pilih material yang masih berada di tangan Anda.')
    if (item.skuId !== first.skuId || item.locationId !== first.locationId || item.baseUnit !== first.baseUnit) throw new ReturnDraftError('Satu retur hanya untuk satu material dari lokasi yang sama.')
    if (selected.has(item.stockIdentityId)) throw new ReturnDraftError('Posisi yang sama tidak boleh dipilih dua kali.')
    selected.add(item.stockIdentityId)
    const quantityBase = quantityFromInput(row.quantity, item.baseUnit)
    if (BigInt(quantityBase) > BigInt(item.quantityBase)) throw new ReturnDraftError('Jumlah retur melebihi material di tangan Anda.')
    if (item.tracking === 'SERIAL' && (quantityBase !== '1' || !item.serial)) throw new ReturnDraftError('Setiap perangkat serial harus berjumlah 1 unit.')
    return { stockIdentityId: item.stockIdentityId, quantityBase }
  })
  return { skuId: first.skuId, lines }
}
