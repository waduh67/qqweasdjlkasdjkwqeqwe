import type { WarehouseLocation } from '@/api/warehouse/models'
import type { StockPosition } from '@/api/warehouse/stock'
import type { TransferDetails, WarehouseTransfer } from '@/api/warehouse/transfers'
import { materialIds as id } from './warehouseMaterialFixture'

export const transferIds = { ...id, transit: id.supplier, destination: id.inspection, sender: id.issue, receiver: id.plan, position: id.allocation }
export const transferLocations: WarehouseLocation[] = [
  { id: id.source, code: 'BIN-A', name: 'Rak A', kind: 'BIN', issueEligible: true },
  { id: id.inspection, code: 'WH-B', name: 'Gudang B', kind: 'WAREHOUSE', issueEligible: true },
  { id: id.supplier, code: 'TRANSFER_TRANSIT', name: 'Dalam perjalanan', kind: 'TRANSIT', issueEligible: false },
].map(row => ({ ...row, state: 'ACTIVE', revision: 0, areaId: null, siteId: null, custodianId: null, parentLocationId: null })) as WarehouseLocation[]
export function transferFixture(): WarehouseTransfer {
  return { id: id.document, code: 'TR-001', revision: 0, state: 'DRAFT', sourceLocationId: id.source, destinationLocationId: id.inspection, transitLocationId: id.supplier,
    senderId: id.issue, receiverId: id.plan, reason: 'Pengisian gudang B', recordedAt: '2026-09-24T19:00:00Z', resolutionDocumentId: null,
    lines: [{ id: id.line, skuId: id.sku, stockIdentityId: id.piece, quantityBase: '100000', baseUnit: 'MM', receivedBase: '0', inTransitBase: '0', resolvedBase: '0', remainingIdentityId: id.piece, condition: 'SERVICEABLE', legalOwner: 'ISP' }] }
}
export function transferDetailsFixture(transfer = transferFixture()): TransferDetails {
  return { transfer, references: { locations: transferLocations.map(({ id, code, name }) => ({ id, code, name })), people: [{ id: id.issue, name: 'Petugas asal' }, { id: id.plan, name: 'Petugas tujuan' }],
    lines: [{ lineId: id.line, skuCode: 'CABLE', skuName: 'Kabel drop', serial: null, lotCode: 'REEL-TRANSFER' }] } }
}
export function transferPositionFixture(): StockPosition {
  const quantity = (amount: string, display: string) => ({ quantityBase: amount, baseUnit: 'MM' as const, displayQuantity: display, displayUnit: 'M' as const })
  return { id: id.allocation, skuId: id.sku, skuCode: 'CABLE', name: 'Kabel drop', tracking: 'LOT', stockIdentityId: id.piece, lotId: id.evidence, serial: null,
    locationId: id.source, locationName: 'Rak A', custodianId: id.source, custodianKind: 'WAREHOUSE', condition: 'SERVICEABLE', legalOwner: 'ISP', status: 'AVAILABLE',
    physical: quantity('1000000', '1000.000'), reservedUnpicked: quantity('0', '0.000'), reservedPicked: quantity('0', '0.000'), available: quantity('1000000', '1000.000') }
}
