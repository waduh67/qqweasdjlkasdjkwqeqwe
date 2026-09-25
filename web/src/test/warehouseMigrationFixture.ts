import { approvalDocumentFixture, approvalIds as id } from './warehouseApprovalFixture'

export const migrationHash = 'a'.repeat(64)
export function migrationDocumentFixture(empty = false) {
  const prior = approvalDocumentFixture(0)
  return { ...prior, kind: 'OPENING_BALANCE', code: 'OPEN-LEGACY', receiptId: null, reason: 'Pemeriksaan fisik stok lama',
    lines: empty ? [] : prior.lines.map(line => ({ ...line, quantityBase: '82500', destinationLocationId: null, lotCode: null })),
    evidenceReferences: [{ kind: 'MIGRATION', reference: 'Catatan gudang sebelum aktivasi' }],
    migration: { batchId: id.other, watermark: '2026-09-25 01:00:00+00', sourceHash: migrationHash, reviewHash: migrationHash,
      caseCount: empty ? 0 : 2, baselineCount: empty ? 0 : 1, unresolvedHistoricalCount: empty ? 0 : 1, valuation: 'UNKNOWN' } }
}
export function migrationCaseFixture() {
  return { caseId: id.line, sourceId: id.sku, sourceTable: 'inventory_balance_projection', sourceHash: migrationHash,
    sourceSnapshot: { id: id.sku, legacyQuantity: '82500', status: 'AVAILABLE' }, resolutionRequired: true,
    resolution: { id: id.effect, batchId: id.other, caseId: id.line, sourceHash: migrationHash, revision: 1, kind: 'BASELINE_STOCK', reason: 'Kabel diperiksa dalam milimeter',
      stock: { skuId: id.sku, skuRevision: 0, locationId: id.source, locationRevision: 0, tracking: 'LOT', sourceUnit: 'MM', quantityBase: '82500', baseUnit: 'MM', legalOwner: 'ISP' },
      duplicateCaseId: null, duplicateResolutionId: null, resolvedBy: id.requester, createdAt: '2026-09-25T01:00:00Z',
      evidence: [{ id: id.decision, sourceHash: migrationHash, sha256: migrationHash, uploadedBy: id.requester }] } }
}
