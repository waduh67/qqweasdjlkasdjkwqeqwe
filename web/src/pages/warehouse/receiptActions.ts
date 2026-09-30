import type { ReceiptLine, WarehouseReceipt } from '@/api/warehouse/receipts'

export function receiptCandidates(receipt: WarehouseReceipt, line: ReceiptLine, mode: 'inspect' | 'putaway') {
  return line.pieces.filter(piece => piece.locationId === receipt.inspectionLocationId && piece.condition === 'QUARANTINE' && piece.status === 'QUARANTINE'
    && piece.custodianKind === 'WAREHOUSE' && piece.custodianId === receipt.inspectionLocationId && piece.legalOwner !== 'UNKNOWN'
    && (mode === 'inspect' ? piece.disposition === null : piece.legalOwner === 'ISP' && (piece.disposition === 'ACCEPTED' || (!line.inspectionRequired && piece.disposition === null))))
}
