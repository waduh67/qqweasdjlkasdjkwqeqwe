import { receiptIds as id } from './warehouseReceiptFixture'

export const materialIds = { ...id, plan: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', demandLine: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', allocation: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', issue: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd' }
export const materialSku = { id: id.sku, revision: 1, code: 'CABLE', name: 'Kabel drop', baseUnit: 'MM' as const, tracking: 'LOT' as const }
export const materialPlanFixture = { id: materialIds.plan, workOrderId: id.source, workOrderCode: 'WO-MATERIAL', workType: 'PREVENTIVE', action: 'PREVENTIVE', customerId: null, workOrderRevision: 5, planRevision: 1,
  materialMode: 'MATERIAL_REQUIRED' as const, reason: null, templateId: null, actorId: id.supplier, recordedAt: '2026-09-24T18:20:00Z', lines: [{ id: id.line, lineNumber: 1, sku: materialSku, quantityBase: '100000', continuousCut: true, substitution: null, originalSku: null }] }
export const materialTotalsFixture = { planLineId: id.line, demandLineId: materialIds.demandLine, skuId: id.sku, baseUnit: 'MM' as const, requestedBase: '100000', reservedUnpickedBase: '60000', reservedPickedBase: '0', issuedBase: '0',
  physicallyUsedBase: '0', returnedBase: '0', transferredOutBase: '0', disposedBase: '0', stillAccountableBase: '0', backorderBase: '40000' }
export const materialSummaryFixture = { workOrderId: id.source, materialMode: 'MATERIAL_REQUIRED' as const, noMaterialReason: null, revisions: { workOrderRevision: 5, planRevision: 1, useRevision: 0, settlementRevision: 0 }, demandState: 'PART_RESERVED' as const,
  installationState: 'NOT_APPLICABLE' as const, qaState: 'PENDING' as const, provisioningState: 'NOT_APPLICABLE' as const, settlementState: 'OPEN' as const, lines: [materialTotalsFixture], plan: materialPlanFixture, demandDocumentId: id.document, demandRevision: 2, template: null }
export const materialWorkOrderFixture = { id: id.source, code: 'WO-MATERIAL', title: 'Penarikan kabel', type: 'PREVENTIVE', status: 'ASSIGNED', customerId: null, customerName: null, assignees: [{ id: id.inspection, name: 'Budi Teknisi' }] }
export const allocationFixture = { allocationId: materialIds.allocation, reservationId: id.supplier, reservationRevision: 3, documentId: id.document, documentRevision: 2, demandLineId: materialIds.demandLine, planLineId: id.line, planRevision: 1, workOrderId: id.source,
  stockIdentityId: id.piece, lotId: id.evidence, skuId: id.sku, locationId: id.inspection, stockRevision: 4, reservedUnpickedBase: '60000', reservedPickedBase: '0', baseUnit: 'MM' as const, state: 'OPEN', expiresAt: '2026-09-26T00:00:00Z',
  skuCode: 'CABLE', skuName: 'Kabel drop', serial: null, lotCode: 'REEL-1', locationName: 'Rak A',
  demandSupply: { requestedBase: '100000', reservedUnpickedBase: '60000', reservedPickedBase: '0', issuedBase: '0', backorderBase: '40000', totalReservedBase: '60000', baseUnit: 'MM', demandState: 'PART_RESERVED' } }
const dimension = { skuId: id.sku, stockIdentityId: id.piece, lotId: id.evidence, locationId: id.inspection, custodianId: id.inspection, custodianKind: 'WAREHOUSE', condition: 'SERVICEABLE', legalOwner: 'ISP' }
export const issueSlipFixture = { issueId: materialIds.issue, code: 'ISS-MATERIAL', revision: 1, state: 'PICKED', workOrderId: id.source, workOrderCode: 'WO-MATERIAL', workOrderRevision: 5, customerId: null, customerLabelSnapshot: null,
  demandDocumentId: id.document, demandRevision: 2, planId: materialIds.plan, planRevision: 1, sender: { id: id.source, name: 'Petugas gudang' }, receiver: { id: id.inspection, name: 'Budi Teknisi' }, recordedAt: materialPlanFixture.recordedAt,
  lines: [{ id: id.line, demandLineId: materialIds.demandLine, planLineId: id.line, reservationId: id.supplier, reservationRevision: 4, dimension, sourceIdentityId: id.piece, quantityBase: '60000', baseUnit: 'MM', sku: materialSku,
    serial: null, lotCode: 'REEL-1', locationName: 'Rak A', substitution: null, originalSku: null }], destinations: [] }
export const issueRowFixture = { ...issueSlipFixture, id: materialIds.issue, unpicked: false, createdAt: issueSlipFixture.recordedAt,
  lines: [{ issueLineId: id.line, planLineId: id.line, sku: materialSku, serial: null, lotCode: 'REEL-1', locationName: 'Rak A', baseUnit: 'MM', pickedBase: '60000', dispatchedBase: '0', acceptedBase: '0' }] }
