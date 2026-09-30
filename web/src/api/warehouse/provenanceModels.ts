import { draftExpiryFields } from './draftExpiry'
import { array, decimal, digest, integer, nullable, oneOf, record, text, uuid, WarehouseDataError } from './codec'
import { timestamp } from './approvals'
import { MIGRATION_SOURCES, migrationRawText, migrationResolution, migrationReviewCase } from './migrationReview'

export function migrationCutover(value: unknown, path = 'cutover') {
  const r = record(value, path)
  const result = { tenantId: uuid(r.tenantId, path), state: oneOf(r.state, ['LEGACY', 'VALIDATING', 'ENFORCED'], path),
    epoch: integer(r.epoch, path), migrationBatchId: nullable(r.migrationBatchId, uuid, path), snapshotWatermark: nullable(r.snapshotWatermark, text, path) }
  if (result.state === 'VALIDATING' && (!result.migrationBatchId || !result.snapshotWatermark)) throw new WarehouseDataError(path)
  return result
}
function sourceCounts(value: unknown, path = 'sourceCounts') {
  const row = record(value, path)
  if (Object.keys(row).length !== MIGRATION_SOURCES.length) throw new WarehouseDataError(path)
  return Object.fromEntries(MIGRATION_SOURCES.map(kind => [kind, integer(row[kind], path)])) as Record<typeof MIGRATION_SOURCES[number], number>
}
export function migrationSummary(value: unknown, path = 'summary') {
  const r = record(value, path)
  const result = { cutover: migrationCutover(r.cutover, path), sourceCount: integer(r.sourceCount, path), preservationHash: digest(r.preservationHash, path),
    sourceCounts: sourceCounts(r.sourceCounts, path), conflictGroupCount: integer(r.conflictGroupCount, path), reservedIdentityCount: integer(r.reservedIdentityCount, path),
    unitUnverifiedBalanceCount: integer(r.unitUnverifiedBalanceCount, path), pendingLegacyMovementCount: integer(r.pendingLegacyMovementCount, path),
    pendingLegacyFulfillmentCount: integer(r.pendingLegacyFulfillmentCount, path), pendingLegacyOutboxCount: integer(r.pendingLegacyOutboxCount, path),
    batch: nullable(r.batch, (value, path = 'batch') => {
      const b = record(value, path)
      return { id: uuid(b.id, path), cutoverEpoch: integer(b.cutoverEpoch, path), snapshotWatermark: text(b.snapshotWatermark, path),
        sourceCount: integer(b.sourceCount, path), sourceHash: digest(b.sourceHash, path), requestedBy: uuid(b.requestedBy, path) }
    }, path) }
  if (Object.values(result.sourceCounts).reduce((total, n) => total + n, 0) !== result.sourceCount ||
    (result.batch && (result.batch.id !== result.cutover.migrationBatchId || result.batch.sourceCount !== result.sourceCount ||
      result.batch.sourceHash !== result.preservationHash || result.batch.cutoverEpoch > result.cutover.epoch))) throw new WarehouseDataError(path)
  return result
}
export type MigrationSummary = ReturnType<typeof migrationSummary>

export function migrationCase(value: unknown, path = 'case') {
  const r = record(value, path), snapshot = record(r.sourceSnapshot, path)
  const named = (value: unknown, path = 'reference') => {
    const row = record(value, path)
    return { id: uuid(row.id, path), name: nullable(row.name, migrationRawText, path), code: nullable(row.code, migrationRawText, path) }
  }
  const result = { id: uuid(r.id, path), sourceTable: oneOf(r.sourceTable, MIGRATION_SOURCES, path), sourceId: uuid(r.sourceId, path), sourceHash: digest(r.sourceHash, path),
    source: { serial: nullable(snapshot.serialNumber, migrationRawText, path), mac: nullable(snapshot.macAddress, migrationRawText, path),
      model: nullable(snapshot.model, migrationRawText, path), legacySkuId: nullable(snapshot.legacySkuId, uuid, path),
      state: nullable(snapshot.state ?? snapshot.status ?? snapshot.checkpointState, text, path), locationId: nullable(snapshot.locationId, uuid, path),
      legacyQuantity: nullable(snapshot.legacyQuantity, migrationRawText, path), baseUnit: nullable(snapshot.baseUnit, migrationRawText, path),
      quantityBase: nullable(snapshot.quantityBase, migrationRawText, path), condition: nullable(snapshot.condition, text, path),
      legalOwner: nullable(snapshot.legalOwner, text, path), custodyOwnerKind: nullable(snapshot.custodyOwnerKind, text, path),
      installedOnuId: nullable(snapshot.installedOnuId, uuid, path), warehouseSkuId: nullable(snapshot.warehouseSkuId, uuid, path) },
    location: nullable(r.location, named, path), customer: nullable(r.customer, named, path), workOrder: nullable(r.workOrder, named, path),
    claims: array(r.claims, (value, path = 'claim') => {
      const row = record(value, path)
      const claim = { id: nullable(row.id, uuid, path), identityType: oneOf(row.identityType, ['SERIAL', 'MAC'], path), rawValue: migrationRawText(row.rawValue, path),
        canonicalValue: nullable(row.canonicalValue, text, path), state: nullable(row.state, (v, p) => oneOf(v, ['LEGACY_RESERVED', 'CONFLICT', 'ADMITTED', 'RETIRED'], p), path),
        admittedAssetId: nullable(row.admittedAssetId, uuid, path), candidateCount: integer(row.candidateCount, path) }
      if ((claim.id === null) !== (claim.state === null) || (claim.state === 'ADMITTED' && claim.admittedAssetId === null)) throw new WarehouseDataError(path)
      return claim
    }, path) }
  if (uuid(snapshot.id, path) !== result.sourceId || (result.location && result.location.id !== result.source.locationId)) throw new WarehouseDataError(path)
  return result
}
export type MigrationCase = ReturnType<typeof migrationCase>
export function migrationEvidence(value: unknown, path = 'evidence') {
  const r = record(value, path)
  const result = { id: uuid(r.id, path), batchId: uuid(r.batchId, path), caseId: uuid(r.caseId, path), sourceHash: digest(r.sourceHash, path),
    label: text(r.label, path), contentType: oneOf(r.contentType, ['application/pdf', 'image/png', 'image/jpeg'], path), sizeBytes: integer(r.sizeBytes, path),
    sha256: digest(r.sha256, path), uploadedBy: uuid(r.uploadedBy, path), createdAt: timestamp(r.createdAt, path) }
  if (result.sizeBytes < 1 || result.sizeBytes > 15728640) throw new WarehouseDataError(path)
  return result
}
export type MigrationEvidence = ReturnType<typeof migrationEvidence>
export type MigrationResolution = ReturnType<typeof migrationResolution>
function manifest(value: unknown, path = 'manifest') {
  const r = record(value, path)
  const result = { batchId: uuid(r.batchId, path), cutoverEpoch: integer(r.cutoverEpoch, path), watermark: text(r.watermark, path), sourceHash: digest(r.sourceHash, path),
    cases: array(r.cases, migrationReviewCase, path, 100000) }
  if (new Set(result.cases.map(row => row.caseId)).size !== result.cases.length ||
    result.cases.some(row => row.resolution && row.resolution.batchId !== result.batchId)) throw new WarehouseDataError(path)
  return result
}
export function migrationReview(value: unknown, path = 'review') {
  const r = record(value, path)
  const result = { manifest: manifest(r.manifest, path), reviewHash: digest(r.reviewHash, path), issues: array(r.issues, (value, path = 'issue') => {
    const row = record(value, path)
    return { caseId: uuid(row.caseId, path), code: text(row.code, path) }
  }, path, 100000) }
  if (result.issues.some(issue => !result.manifest.cases.some(row => row.caseId === issue.caseId))) throw new WarehouseDataError(path)
  return result
}
export type MigrationReview = ReturnType<typeof migrationReview>
export function migrationOpening(value: unknown, path = 'opening') {
  const r = record(value, path), location = record(r.reviewLocation, path)
  const result = { id: uuid(r.id, path), code: text(r.code, path), batchId: uuid(r.batchId, path),
    ...(r.state == null ? {} : { state: oneOf(r.state, ['DRAFT', 'EXPIRED', 'POSTED'], path) }), ...draftExpiryFields(r.draftExpiry, r.state === 'EXPIRED'), reviewHash: digest(r.reviewHash, path),
    manifest: manifest(r.manifest, path), reviewLocation: { id: uuid(location.id, path), revision: integer(location.revision, path), areaId: nullable(location.areaId, uuid, path) },
    requestedBy: uuid(r.requestedBy, path), authorityEpoch: integer(r.authorityEpoch, path), cutoverEpoch: integer(r.cutoverEpoch, path),
    migrationReference: text(r.migrationReference, path), reason: text(r.reason, path), createdAt: timestamp(r.createdAt, path) }
  if (result.batchId !== result.manifest.batchId || result.cutoverEpoch !== result.manifest.cutoverEpoch ||
    result.manifest.cases.some(row => row.resolutionRequired && !row.resolution)) throw new WarehouseDataError(path)
  return result
}
export type MigrationOpening = ReturnType<typeof migrationOpening>
export function migrationOpeningSummary(value: unknown, path = 'openingSummary') {
  const r = record(value, path), location = record(r.reviewLocation, path)
  return { id: uuid(r.id, path), batchId: uuid(r.batchId, path), code: text(r.code, path), state: oneOf(r.state, ['DRAFT', 'EXPIRED', 'POSTED'], path), ...draftExpiryFields(r.draftExpiry, r.state === 'EXPIRED'),
    reviewHash: digest(r.reviewHash, path), requestedBy: uuid(r.requestedBy, path), createdAt: timestamp(r.createdAt, path), migrationReference: text(r.migrationReference, path),
    reviewLocation: { id: uuid(location.id, path), code: text(location.code, path), name: nullable(location.name, migrationRawText, path) } }
}
export type MigrationOpeningSummary = ReturnType<typeof migrationOpeningSummary>

function finalizationCounts(value: unknown, path = 'finalization') {
  const r = record(value, path)
  const result = { batchId: uuid(r.batchId, path), cutover: migrationCutover(r.cutover, path), openingDocumentId: nullable(r.openingDocumentId, uuid, path),
    approvalId: nullable(r.approvalId, uuid, path), reviewHash: nullable(r.reviewHash, digest, path), sourceHash: digest(r.sourceHash, path),
    sourceCount: integer(r.sourceCount, path), sourceCounts: sourceCounts(r.sourceCounts, path), baselineCount: integer(r.baselineCount, path),
    baselineTotals: Object.fromEntries(Object.entries(record(r.baselineTotals, path)).map(([unit, amount]) => [oneOf(unit, ['EA', 'MM'], path), decimal(amount, path)])),
    unresolvedHistoricalCount: integer(r.unresolvedHistoricalCount, path), cancellationCount: integer(r.cancellationCount, path), retainedIdentityCount: integer(r.retainedIdentityCount, path) }
  if (result.batchId !== result.cutover.migrationBatchId || result.sourceCount !== Object.values(result.sourceCounts).reduce((total, n) => total + n, 0) ||
    result.baselineCount > result.sourceCount || (result.openingDocumentId === null) !== (result.approvalId === null) ||
    (result.openingDocumentId === null) !== (result.reviewHash === null) || (result.baselineCount === 0 && Object.keys(result.baselineTotals).length !== 0)) throw new WarehouseDataError(path)
  return result
}
export function migrationFinalization(value: unknown, path = 'finalized') {
  const r = record(value, path), result = { ...finalizationCounts(value, path), id: uuid(r.id, path), finalizedBy: uuid(r.finalizedBy, path),
    authorityEpoch: integer(r.authorityEpoch, path), reason: text(r.reason, path), finalizedAt: timestamp(r.finalizedAt, path) }
  if (result.cutover.state !== 'ENFORCED' || result.cutover.epoch < 2 || !result.openingDocumentId) throw new WarehouseDataError(path)
  return result
}
export type MigrationFinalization = ReturnType<typeof migrationFinalization>
export function migrationFinalizationReview(value: unknown, path = 'finalizationReview') {
  const r = record(value, path)
  const result = { ...finalizationCounts(value, path), issues: array(r.issues, text, path, 30), finalization: nullable(r.finalization, migrationFinalization, path) }
  if (result.finalization && (result.finalization.batchId !== result.batchId || result.finalization.openingDocumentId !== result.openingDocumentId ||
    result.finalization.reviewHash !== result.reviewHash || result.cutover.state !== 'ENFORCED')) throw new WarehouseDataError(path)
  return result
}
export type MigrationFinalizationReview = ReturnType<typeof migrationFinalizationReview>
