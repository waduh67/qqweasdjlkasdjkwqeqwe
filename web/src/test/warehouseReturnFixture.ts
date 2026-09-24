import type { ReturnDetails, ReturnSource, WarehouseReturn } from '@/api/warehouse/returns'
import type { WarehouseLocation } from '@/api/warehouse/models'
import { materialIds as id } from './warehouseMaterialFixture'

export const returnIds = { ...id, returnCase: id.document, returnSource: id.issue, repair: id.allocation, vendor: id.supplier, transit: id.plan }
export const returnQuarantine: WarehouseLocation = { id: id.inspection, code: 'RET-Q', name: 'Karantina retur', kind: 'QUARANTINE', issueEligible: false,
  state: 'ACTIVE', revision: 0, areaId: null, siteId: null, custodianId: null, parentLocationId: null }
export const returnBin: WarehouseLocation = { ...returnQuarantine, id: id.source, code: 'BIN-A', name: 'Rak layak pakai', kind: 'BIN', issueEligible: true }
export const repairTransit: WarehouseLocation = { ...returnQuarantine, id: id.plan, code: 'SERVIS', name: 'Penguasaan vendor servis', kind: 'TRANSIT' }
export function returnFixture(serial = false): WarehouseReturn {
  return { id: id.document, revision: serial ? 1 : 0, state: 'RECEIVED_IN_INSPECTION', origin: serial ? 'ASSET_REMOVAL' : 'MATERIAL_RESIDUAL',
    sourceDocumentId: id.issue, stockIdentityId: id.piece, skuId: id.sku, lotId: serial ? null : id.line, baseUnit: serial ? 'EA' : 'MM', quantityBase: serial ? '1' : '17500',
    locationId: id.inspection, condition: 'QUARANTINE', legalOwner: serial ? 'CUSTOMER' : 'ISP', receivedBy: id.plan, recordedAt: '2026-09-24T20:00:00Z', inspection: null, repair: null }
}
export function returnDetailsFixture(view = returnFixture()): ReturnDetails {
  const serial = view.origin === 'ASSET_REMOVAL'
  return { returnCase: view, references: { code: 'RET-001', sourceCode: serial ? 'REMOVE-001' : 'RESIDUAL-001', workOrderId: id.source, workOrderCode: serial ? null : 'WO-001',
    item: { id: id.sku, code: serial ? 'ONU' : 'CABLE', name: serial ? 'ONU pelanggan' : 'Sisa kabel drop', tracking: serial ? 'SERIAL' : 'LOT', serial: serial ? 'ONU-001' : null, lotCode: serial ? null : 'REEL-001' },
    locations: [returnQuarantine, returnBin, repairTransit].map(({ id, code, name }) => ({ id, code, name })), receivedByName: 'Petugas penerimaan',
    vendor: view.repair ? { id: id.supplier, code: 'SERVICE', name: 'Penyedia servis' } : null, rmaHandoverId: null } }
}
export function returnSourceFixture(serial = false): ReturnSource {
  const { returnCase: view, references } = returnDetailsFixture(returnFixture(serial))
  return { sourceDocumentId: view.sourceDocumentId, origin: view.origin, code: references.sourceCode, recordedAt: view.recordedAt,
    workOrderId: references.workOrderId, workOrderCode: references.workOrderCode, stockIdentityId: view.stockIdentityId, lotId: view.lotId, item: references.item,
    quantityBase: view.quantityBase, baseUnit: view.baseUnit, legalOwner: view.legalOwner, location: serial ? repairTransit : returnQuarantine,
    quarantineLocationId: serial ? null : returnQuarantine.id }
}
