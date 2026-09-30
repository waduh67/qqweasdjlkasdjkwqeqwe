import { approvalIds as id } from './warehouseApprovalFixture'
import { migrationCaseFixture, migrationHash } from './warehouseMigrationFixture'
import { MIGRATION_SOURCES } from '@/api/warehouse/migrationReview'

export function provenanceSummaryFixture(state: 'LEGACY' | 'VALIDATING' | 'ENFORCED' = 'VALIDATING', empty = false) {
  const counts = Object.fromEntries(MIGRATION_SOURCES.map(kind => [kind, !empty && kind === 'inventory_balance_projection' ? 1 : 0]))
  return { cutover: { tenantId: id.policy, state, epoch: state === 'LEGACY' ? 0 : state === 'VALIDATING' ? 1 : 2,
    migrationBatchId: state === 'LEGACY' ? null : id.other, snapshotWatermark: state === 'LEGACY' ? null : '2026-09-25 01:00:00+00' },
    sourceCount: empty ? 0 : 1, preservationHash: migrationHash, sourceCounts: counts, conflictGroupCount: 0, reservedIdentityCount: 0,
    unitUnverifiedBalanceCount: empty ? 0 : 1, pendingLegacyMovementCount: 0, pendingLegacyFulfillmentCount: 0, pendingLegacyOutboxCount: 0,
    batch: state === 'LEGACY' ? null : { id: id.other, cutoverEpoch: 1, snapshotWatermark: '2026-09-25 01:00:00+00', sourceCount: empty ? 0 : 1,
      sourceHash: migrationHash, requestedBy: id.requester } }
}
export function provenanceCaseFixture() {
  const row = migrationCaseFixture()
  return { id: row.caseId, sourceId: row.sourceId, sourceTable: row.sourceTable, sourceHash: row.sourceHash,
    sourceSnapshot: { ...row.sourceSnapshot, locationId: id.source, legacySkuId: id.sku, custodyOwnerKind: 'WAREHOUSE',
      custodyOwnerId: id.source, condition: 'SERVICEABLE', legalOwner: 'UNKNOWN' },
    location: { id: id.source, code: 'OLD', name: 'Gudang lama' }, customer: null, workOrder: null, claims: [] }
}
export function provenanceEvidenceFixture() {
  return { id: id.decision, batchId: id.other, caseId: id.line, sourceHash: migrationHash, label: 'Berita acara pemeriksaan kabel',
    contentType: 'application/pdf', sizeBytes: 20, sha256: migrationHash, uploadedBy: id.requester, createdAt: '2026-09-25T01:00:00Z' }
}
export function provenanceReviewFixture(empty = false) {
  return { manifest: { batchId: id.other, cutoverEpoch: 1, watermark: '2026-09-25 01:00:00+00', sourceHash: migrationHash, cases: empty ? [] : [migrationCaseFixture()] },
    reviewHash: migrationHash, issues: [] }
}
export function provenanceOpeningFixture(empty = false) {
  return { id: id.document, code: 'OPEN-LEGACY', batchId: id.other, reviewHash: migrationHash, manifest: provenanceReviewFixture(empty).manifest,
    reviewLocation: { id: id.source, revision: 0, areaId: id.target }, requestedBy: id.requester, authorityEpoch: 1, cutoverEpoch: 1,
    migrationReference: 'Catatan gudang sebelum aktivasi', reason: 'Hasil pemeriksaan fisik dan bukti asli', createdAt: '2026-09-25T01:00:00Z' }
}
export function provenanceOpeningSummaryFixture() {
  return { id: id.document, code: 'OPEN-LEGACY', batchId: id.other, reviewHash: migrationHash, state: 'DRAFT', requestedBy: id.requester,
    createdAt: '2026-09-25T01:00:00Z', migrationReference: 'Catatan gudang sebelum aktivasi', reviewLocation: { id: id.source, code: 'OLD', name: 'Gudang lama' } }
}
export function provenanceFinalizationFixture(empty = false) {
  const summary = provenanceSummaryFixture('ENFORCED', empty)
  return { id: id.effect, batchId: id.other, cutover: summary.cutover, openingDocumentId: id.document, approvalId: id.request,
    reviewHash: migrationHash, sourceHash: migrationHash, sourceCount: summary.sourceCount, sourceCounts: summary.sourceCounts,
    baselineCount: empty ? 0 : 1, baselineTotals: empty ? {} : { MM: '82500' }, unresolvedHistoricalCount: 0, cancellationCount: 0,
    retainedIdentityCount: 0, finalizedBy: id.requester, authorityEpoch: 2, reason: 'Pemeriksaan independen selesai', finalizedAt: '2026-09-25T02:00:00Z' }
}
export function provenanceFinalizationReviewFixture(ready = true, empty = false) {
  const row = provenanceFinalizationFixture(empty)
  return { ...row, cutover: { ...row.cutover, state: 'VALIDATING', epoch: 1 }, finalization: null,
    openingDocumentId: ready ? row.openingDocumentId : null, approvalId: ready ? row.approvalId : null, reviewHash: ready ? row.reviewHash : null,
    baselineCount: ready ? row.baselineCount : 0, baselineTotals: ready ? row.baselineTotals : {}, issues: ready ? [] : ['APPROVED_OPENING_REQUIRED'] }
}
