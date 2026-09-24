import type { CountDetails, CountPosition, CountFact, WarehouseCount } from '@/api/warehouse/counts'
import type { WarehouseLocation } from '@/api/warehouse/models'

const id = (n: number) => `60000000-0000-4000-8000-${n.toString().padStart(12, '0')}`
export const countIds = { count: id(1), balance: id(2), sku: id(3), identity: id(4), location: id(5), requester: id(6), counter: id(7), other: id(8), fact: id(9) }
export function countFixture(state: WarehouseCount['state'] = 'DRAFT', revision = 0): WarehouseCount {
  return { id: countIds.count, revision, state, locationId: countIds.location, partialLocation: true, roundRevision: state === 'DRAFT' ? null : 1,
    entries: [{ balanceId: countIds.balance, counterId: countIds.counter, stockIdentityId: countIds.identity, skuId: countIds.sku, baseUnit: 'MM' }] }
}
export const countLocation: WarehouseLocation = { id: countIds.location, code: 'BIN-A', name: 'Rak kabel', kind: 'BIN', state: 'ACTIVE', revision: 0, parentLocationId: null, areaId: null, siteId: null, custodianId: countIds.location, issueEligible: true }
export function countPositionFixture(): CountPosition {
  return { id: countIds.balance, stockIdentityId: countIds.identity, item: { skuId: countIds.sku, code: 'DROP', name: 'Kabel drop', tracking: 'LOT', serial: null, lotCode: 'REEL-A' },
    baseUnit: 'MM', location: { id: countIds.location, code: 'BIN-A', name: 'Rak kabel' }, custodianId: countIds.location, custodianKind: 'WAREHOUSE', condition: 'SERVICEABLE', legalOwner: 'ISP', status: 'AVAILABLE' }
}
export function countDetailsFixture(count = countFixture()): CountDetails {
  const position = countPositionFixture()
  return { count, references: { code: 'CNT-001', reason: 'Pemeriksaan akhir bulan', createdAt: '2026-09-25T01:00:00Z', location: position.location,
    requester: { id: countIds.requester, name: 'Pembuat opname' }, counters: [{ id: countIds.counter, name: 'Penghitung A' }],
    lines: [{ balanceId: position.id, item: position.item, custodianId: position.custodianId, custodianKind: position.custodianKind, condition: position.condition, legalOwner: position.legalOwner }] } }
}
export function countFactFixture(quantityBase = '82500'): CountFact {
  return { id: countIds.fact, balanceId: countIds.balance, counterId: countIds.counter, roundRevision: 1, quantityBase, baseUnit: 'MM', reason: 'Ukur fisik reel', documentReference: 'LEMBAR-001' }
}
