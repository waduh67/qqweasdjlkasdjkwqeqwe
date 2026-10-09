import type { CountInput, CountSnapshot } from '@/api/warehouse/referenceCounts'
import { quantityFromInput } from '@/api/warehouse/quantity'
import { parseReceiptSerials } from './receiptDraft'

export class CountDraftError extends Error { readonly name = 'CountDraftError' }
export function buildReferenceCount(snapshot: CountSnapshot, draft: { readonly quantity: string; readonly serials: string; readonly reason: string }): CountInput {
  const reason = draft.reason.trim()
  if (!reason || reason.length > 1000) throw new CountDraftError('Isi alasan opname, maksimal 1000 karakter.')
  const physicalBase = quantityFromInput(draft.quantity, snapshot.baseUnit, true)
  const serials = snapshot.tracking === 'SERIAL' ? parseReceiptSerials(draft.serials).map(row => ({ serial: row.serial, ...(row.mac ? { mac: row.mac } : {}) })) : []
  if (snapshot.tracking === 'SERIAL' && BigInt(serials.length) !== BigInt(physicalBase)) throw new CountDraftError('Jumlah serial fisik harus sama dengan jumlah unit yang dihitung.')
  return { snapshotId: snapshot.id, physicalBase, serials, reason }
}
