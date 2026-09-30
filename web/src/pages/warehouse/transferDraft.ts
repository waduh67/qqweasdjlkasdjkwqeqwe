import type { WarehouseLocation } from '@/api/warehouse/models'
import type { StockPosition } from '@/api/warehouse/stock'
import type { TransferDraft, TransferReceipt, WarehouseTransfer } from '@/api/warehouse/transfers'
import { quantityFromInput } from '@/api/warehouse/quantity'

export interface TransferDraftLine { key: string; position: StockPosition | null; quantity: string }
export interface TransferReceiptLine { lineId: string; selected: boolean; quantity: string }
export const transferLocationKinds = ['WAREHOUSE', 'BIN', 'VEHICLE', 'TECHNICIAN', 'QUARANTINE']
export function eligibleTransferPosition(row: StockPosition, actorId: string) {
  return ['AVAILABLE', 'QUARANTINE', 'RETURNED'].includes(row.status) && row.reservedUnpicked.quantityBase === '0' && row.reservedPicked.quantityBase === '0' &&
    BigInt(row.physical.quantityBase) > 0n && (!['TECHNICIAN', 'VEHICLE'].includes(row.custodianKind) || row.custodianId === actorId)
}
export function buildTransferDraft(source: WarehouseLocation | null, destination: WarehouseLocation | null, transit: WarehouseLocation | null,
  receiverId: string, actorId: string, reason: string, rows: TransferDraftLine[]): TransferDraft {
  if (!source || !destination || !transit || !receiverId || !reason.trim() || reason.trim().length > 1000) throw new Error('Lengkapi lokasi, penerima dan alasan transfer (maksimal 1000 karakter).')
  if (!transferLocationKinds.includes(source.kind) || !transferLocationKinds.includes(destination.kind) || transit.kind !== 'TRANSIT' || transit.issueEligible || transit.code === 'RECEIPT_SOURCE' ||
    [source, destination, transit].some(row => row.state !== 'ACTIVE') || new Set([source.id, destination.id, transit.id]).size !== 3) throw new Error('Pilih lokasi asal, tujuan dan transit aktif yang berbeda dan sesuai peruntukannya.')
  if (['TECHNICIAN', 'VEHICLE'].includes(destination.kind) && destination.custodianId !== receiverId) throw new Error('Penerima harus sama dengan penanggung jawab lokasi tujuan.')
  if (!rows.length || rows.length > 100 || new Set(rows.map(row => row.position?.stockIdentityId)).size !== rows.length) throw new Error('Pilih 1–100 identitas barang berbeda.')
  return { sourceLocationId: source.id, destinationLocationId: destination.id, transitLocationId: transit.id, receiverId, reason: reason.trim(), lines: rows.map(row => {
    if (!row.position || row.position.locationId !== source.id || !eligibleTransferPosition(row.position, actorId)) throw new Error('Stok tidak sesuai lokasi, sedang dicadangkan, atau harus memakai alur material WO. Pilih ulang stok.')
    const quantityBase = quantityFromInput(row.quantity, row.position.physical.baseUnit)
    if (BigInt(quantityBase) > BigInt(row.position.physical.quantityBase) || (row.position.tracking === 'SERIAL' && quantityBase !== '1')) throw new Error('Jumlah transfer melebihi fisik tersedia atau bukan satu unit serial.')
    return { stockIdentityId: row.position.stockIdentityId, sourceBalanceId: row.position.id, quantityBase, baseUnit: row.position.physical.baseUnit }
  }) }
}
export function buildTransferReceipt(transfer: WarehouseTransfer, evidence: string, rows: TransferReceiptLine[]): TransferReceipt {
  if (!['DISPATCHED', 'PART_RECEIVED'].includes(transfer.state)) throw new Error('Transfer tidak sedang menunggu penerimaan. Muat ulang dokumen.')
  if (!evidence.trim() || evidence.trim().length > 500) throw new Error('Isi referensi bukti penerimaan, maksimal 500 karakter.')
  const chosen = rows.filter(row => row.selected)
  if (!chosen.length || chosen.length > 100 || new Set(chosen.map(row => row.lineId)).size !== chosen.length) throw new Error('Pilih baris yang benar-benar diterima.')
  return { expectedRevision: transfer.revision, evidenceReference: evidence.trim(), lines: chosen.map(row => {
    const line = transfer.lines.find(line => line.id === row.lineId)
    if (!line) throw new Error('Baris transfer berubah. Muat ulang dokumen.')
    const quantityBase = quantityFromInput(row.quantity, line.baseUnit)
    if (BigInt(quantityBase) > BigInt(line.inTransitBase)) throw new Error('Jumlah diterima melebihi sisa dalam perjalanan.')
    return { lineId: line.id, quantityBase, baseUnit: line.baseUnit }
  }) }
}
