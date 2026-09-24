import type { WarehouseReceipt } from '@/api/warehouse/receipts'

export const receiptIds = { document: '0f3017da-ef2e-4b96-8c02-cb2a2832dfb4', line: 'fd9dcf46-a8b8-48a4-84b3-2d8ab046c68d', sku: 'f57a90c5-c9bb-4c4b-88be-fd8d7b84508a',
  supplier: 'e6e90760-8fbe-45c2-9a46-914650932459', source: '04503b0d-b46e-4ced-9b75-71780fc03f7a', inspection: '81fc445d-8d5c-4ee4-aa04-6cc2b3be8b4d', piece: 'cb59b5b3-e818-421f-abfb-e794cb3e58e5', evidence: 'c28d3781-3187-42ed-b562-42af5256d26f' }
export function receiptFixture(): WarehouseReceipt {
  const id = receiptIds
  return { id: id.document, revision: 4, state: 'DRAFT', createdAt: '2026-09-24T17:00:00Z', supplierId: id.supplier, supplierName: 'Distributor kabel', externalReference: 'SJ-001',
    sourceLocationId: id.source, inspectionLocationId: id.inspection, sourceLocationName: 'Penerimaan pemasok', inspectionLocationName: 'Pemeriksaan barang', costVisible: true,
    lines: [{ id: id.line, inputLineNumber: 1, skuId: id.sku, skuCode: 'CABLE', skuName: 'Kabel drop', tracking: 'LOT', baseUnit: 'MM', quantityBase: '1000000', serial: null, mac: null, lotCode: 'R1', inspectionRequired: true,
      conversion: null, cost: { totalMinor: '1000006', currency: 'IDR', costBasisQuantityBase: '1000000' }, pieces: [], acceptedBase: '0', rejectedBase: '0', putawayBase: '0' }], inspections: [] }
}
export const receiptPieceFixture = () => ({ stockIdentityId: receiptIds.piece, lotId: receiptIds.line, quantityBase: '1000000', revision: 1, disposition: null, locationId: receiptIds.inspection,
  condition: 'QUARANTINE' as const, legalOwner: 'ISP' as const, status: 'QUARANTINE' as const, custodianId: receiptIds.inspection, custodianKind: 'WAREHOUSE' as const })
