import type { MaterialSummary } from '@/api/warehouse/materialModels'
import type { PickInput, ReservationCommand } from '@/api/warehouse/materials'
import { quantityFromInput } from '@/api/warehouse/quantity'
import type { ReservationAllocation } from '@/api/warehouse/reservations'
import type { StockPosition } from '@/api/warehouse/stock'

export interface ReserveDraft { planLineId: string; selected: boolean; quantity: string; position: StockPosition | null }
export interface AllocationDraft { allocation: ReservationAllocation; selected: boolean; quantity: string; scan: string }
function reservationRevisions(summary: MaterialSummary) {
  if (!summary.demandDocumentId || summary.demandRevision === null) throw new Error('Ajukan rencana material sebelum mencadangkan stok.')
  return { expectedRevision: summary.demandRevision, workOrderRevision: summary.revisions.workOrderRevision, planRevision: summary.revisions.planRevision }
}
export function buildReservation(summary: MaterialSummary, drafts: ReserveDraft[], reason: string, override: boolean, automatic = false): ReservationCommand {
  const revisions = reservationRevisions(summary)
  if (automatic) return { ...revisions, lines: [] }
  const chosen = drafts.filter(row => row.selected)
  if (!chosen.length) throw new Error('Pilih setidaknya satu baris permintaan.')
  if (chosen.some(row => row.position) && (!override || !reason.trim() || reason.trim().length > 1000)) throw new Error('Pemilihan identitas stok membutuhkan izin override dan alasan, maksimal 1000 karakter.')
  return { ...revisions, reason: reason.trim() || undefined, lines: chosen.map(row => {
    const line = summary.lines.find(line => line.planLineId === row.planLineId)
    if (!line?.demandLineId) throw new Error('Baris permintaan belum terverifikasi. Muat ulang dokumen.')
    const amount = quantityFromInput(row.quantity, line.baseUnit)
    if (BigInt(amount) > BigInt(line.backorderBase)) throw new Error('Jumlah reservasi melebihi sisa permintaan. Muat ulang dan tinjau jumlahnya.')
    if (row.position && (row.position.skuId !== line.skuId || row.position.available.baseUnit !== line.baseUnit || BigInt(row.position.available.quantityBase) < BigInt(amount))) throw new Error('Stok pilihan tidak sesuai atau jumlahnya kurang. Pilih ulang stok.')
    return { demandLineId: line.demandLineId, partialQuantityBase: amount, ...(row.position ? { stockIdentityId: row.position.stockIdentityId } : {}) }
  }) }
}

export function currentAllocations(summary: MaterialSummary, rows: ReservationAllocation[]) {
  return rows.filter(row => row.documentId === summary.demandDocumentId && row.planRevision === summary.revisions.planRevision && row.state === 'OPEN')
}
export function buildAllocationCommand(summary: MaterialSummary, drafts: AllocationDraft[], action: 'pick' | 'release', reason = ''): PickInput | ReservationCommand {
  const revisions = reservationRevisions(summary), chosen = drafts.filter(row => row.selected)
  if (chosen.length < 1 || chosen.length > 100) throw new Error('Pilih 1–100 alokasi barang.')
  if (new Set(chosen.map(row => row.allocation.stockIdentityId)).size !== chosen.length) throw new Error('Identitas stok yang sama hanya boleh dipilih sekali.')
  if (action === 'release' && (!reason.trim() || reason.trim().length > 1000)) throw new Error('Isi alasan pelepasan reservasi, maksimal 1000 karakter.')
  const lines = chosen.map(({ allocation: row, quantity, scan }) => {
    if (row.workOrderId !== summary.workOrderId || row.documentId !== summary.demandDocumentId || row.documentRevision !== summary.demandRevision || row.planRevision !== summary.revisions.planRevision || row.state !== 'OPEN') throw new Error('Alokasi berubah. Muat ulang dokumen dan tinjau pilihan.')
    if (row.reservedPickedBase !== '0') throw new Error('Alokasi terikat slip yang sedang disiapkan. Gunakan batal siapkan pada slip tersebut.')
    const quantityBase = quantityFromInput(quantity, row.baseUnit)
    if (BigInt(quantityBase) > BigInt(row.reservedUnpickedBase)) throw new Error('Jumlah melebihi reservasi yang belum disiapkan.')
    if (action === 'release' && BigInt(quantityBase) !== BigInt(row.reservedUnpickedBase)) throw new Error('Reservasi dilepas seluruhnya per alokasi. Lepas lalu cadangkan kembali untuk mengubah jumlah.')
    if (row.serial && (quantityBase !== '1' || (scan.trim() && scan.trim().toUpperCase() !== row.serial.toUpperCase()))) throw new Error('Serial hasil pindai harus sesuai dengan satu unit yang dipilih.')
    return { reservationId: row.reservationId, expectedRevision: row.reservationRevision, stockIdentityId: row.stockIdentityId, stockRevision: row.stockRevision, quantityBase, baseUnit: row.baseUnit, ...(row.serial && scan.trim() ? { scan: scan.trim() } : {}) }
  })
  return action === 'pick' ? { expectedRevision: summary.revisions.planRevision, workOrderRevision: summary.revisions.workOrderRevision, demandRevision: revisions.expectedRevision, lines }
    : { ...revisions, reason: reason.trim(), allocations: lines.map(({ reservationId, expectedRevision, quantityBase }) => ({ reservationId, expectedRevision, quantityBase })) }
}
