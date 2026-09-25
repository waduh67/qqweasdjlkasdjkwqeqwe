import { api } from '../client'
import { digest, pageOf, uuid, WarehouseDataError } from './codec'
import { captureCommandSession, command, parameters, query, type WarehouseCommand } from './transport'
import { migrationResolution, type MIGRATION_SOURCES } from './migrationReview'
import { migrationCase, migrationEvidence, migrationFinalization, migrationFinalizationReview, migrationOpening, migrationOpeningSummary, migrationReview, migrationSummary,
  type MigrationCase, type MigrationEvidence, type MigrationSummary } from './provenanceModels'

const root = '/api/v1/warehouse/provenance'
const batchPath = (batch: string) => root + '/batches/' + uuid(batch)
const casePath = (batch: string, id: string) => batchPath(batch) + '/cases/' + uuid(id)
function bound<T extends { batchId: string; caseId: string; sourceHash: string }>(row: T, batch: string, source: MigrationCase): T {
  if (row.batchId !== batch || row.caseId !== source.id || row.sourceHash !== source.sourceHash) throw new WarehouseDataError('migration.binding')
  return row
}
export const getMigrationSummary = () => query(root, migrationSummary)
export const listMigrationCases = (page = 0, sourceTable?: typeof MIGRATION_SOURCES[number]) =>
  query(root + '/cases' + parameters({ page, size: 25, sourceTable }), pageOf(migrationCase))
export async function getMigrationCase(id: string) {
  const result = await query(root + '/cases/' + uuid(id), migrationCase)
  if (result.id !== id) throw new WarehouseDataError('migration.case')
  return result
}
export const beginMigration = (summary: MigrationSummary) => command(root + '/batches', 'POST',
  { expectedEpoch: summary.cutover.epoch, expectedPreservationHash: summary.preservationHash }, migrationSummary)
export async function getMigrationReview(batch: string) {
  const result = await query(batchPath(batch) + '/review', migrationReview)
  if (result.manifest.batchId !== batch) throw new WarehouseDataError('migration.review.batch')
  return result
}
export async function getMigrationFinalization(batch: string) {
  const result = await query(batchPath(batch) + '/finalization', migrationFinalizationReview)
  if (result.batchId !== batch) throw new WarehouseDataError('migration.finalization.batch')
  return result
}
export const listMigrationEvidence = async (batch: string, source: MigrationCase, page = 0) => {
  const result = await query(casePath(batch, source.id) + '/evidence' + parameters({ page, size: 25 }), pageOf(migrationEvidence))
  result.items.forEach(row => bound(row, batch, source))
  return result
}
export const listMigrationResolutions = async (batch: string, source: MigrationCase, page = 0) => {
  const result = await query(casePath(batch, source.id) + '/resolutions' + parameters({ page, size: 25 }), pageOf(migrationResolution))
  result.items.forEach(row => bound(row, batch, source))
  return result
}
export interface MigrationResolutionInput {
  expectedEpoch: number; expectedCaseHash: string; expectedResolutionRevision: number;
  kind: 'BASELINE_STOCK' | 'PROVENANCE_ONLY' | 'DUPLICATE' | 'CANCEL_PENDING'; reason: string; evidenceIds: string[];
  stock: { skuId: string; sourceUnit: 'EA' | 'MM' | 'M'; legalOwner: 'ISP' } | null; duplicateCaseId: string | null;
}
export const resolveMigrationCase = (batch: string, source: MigrationCase, input: MigrationResolutionInput) =>
  command(casePath(batch, source.id) + '/resolutions', 'POST', input, value => bound(migrationResolution(value), batch, source))
export interface MigrationOpeningInput {
  expectedEpoch: number; expectedReviewHash: string; reviewLocationId: string; expectedReviewLocationRevision: number; migrationReference: string; reason: string;
}
export const requestMigrationOpening = (batch: string, input: MigrationOpeningInput) =>
  command(batchPath(batch) + '/opening', 'POST', input, value => {
    const result = migrationOpening(value)
    if (result.batchId !== batch || result.reviewHash !== input.expectedReviewHash) throw new WarehouseDataError('migration.opening')
    return result
  })
export async function getMigrationOpening(batch: string, id: string) {
  const result = await query(batchPath(batch) + '/opening/' + uuid(id), migrationOpening)
  if (result.batchId !== batch || result.id !== id) throw new WarehouseDataError('migration.opening')
  return result
}
export async function listMigrationOpenings(batch: string, page = 0) {
  const result = await query(batchPath(batch) + '/opening' + parameters({ page, size: 25 }), pageOf(migrationOpeningSummary))
  if (result.items.some(row => row.batchId !== batch)) throw new WarehouseDataError('migration.opening.batch')
  return result
}
export interface MigrationFinalizeInput { expectedEpoch: number; openingDocumentId: string; expectedReviewHash: string; reason: string }
export const finalizeMigration = (batch: string, input: MigrationFinalizeInput) =>
  command(batchPath(batch) + '/finalization', 'POST', input, value => {
    const result = migrationFinalization(value)
    if (result.batchId !== batch || result.openingDocumentId !== input.openingDocumentId || result.reviewHash !== input.expectedReviewHash ||
      result.cutover.epoch !== input.expectedEpoch + 1) throw new WarehouseDataError('migration.finalization')
    return result
  })

/** Retain original file bytes and command metadata through response-loss retries. */
export function uploadMigrationEvidence(batch: string, source: MigrationCase, expectedEpoch: number, label: string, file: File): WarehouseCommand<MigrationEvidence> {
  if (!['application/pdf', 'image/png', 'image/jpeg'].includes(file.type) || file.size < 1 || file.size > 15728640) throw new WarehouseDataError('migration.evidence.file')
  const request = JSON.stringify({ expectedEpoch, expectedCaseHash: source.sourceHash, label })
  const bytes = file.slice(0, file.size, file.type), filename = file.name, key = crypto.randomUUID(), path = casePath(batch, source.id)
  const checkSession = captureCommandSession()
  const body = JSON.stringify({ request, filename, size: bytes.size, contentType: bytes.type })
  let pending: Promise<MigrationEvidence> | null = null
  return Object.freeze({ path: path + '/evidence', body, key, execute() {
    checkSession()
    if (pending) return pending
    const form = new FormData()
    form.append('request', request); form.append('file', bytes, filename)
    pending = api.request<unknown>(path + '/evidence', { method: 'POST', body: form, headers: { 'Idempotency-Key': key } })
      .then(value => {
        const result = bound(migrationEvidence(value), batch, source)
        if (result.sizeBytes !== bytes.size || result.contentType !== bytes.type || result.label !== label) throw new WarehouseDataError('migration.evidence')
        return result
      }).finally(() => { pending = null })
    return pending
  } })
}
export async function downloadMigrationEvidence(batch: string, source: MigrationCase, file: MigrationEvidence): Promise<Blob> {
  bound(file, batch, source)
  const blob = await api.blob(casePath(batch, source.id) + '/evidence/' + uuid(file.id))
  if (blob.type !== file.contentType || blob.size !== file.sizeBytes || !globalThis.crypto?.subtle) throw new WarehouseDataError('migration.evidence')
  const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())), byte => byte.toString(16).padStart(2, '0')).join('')
  if (hash !== digest(file.sha256)) throw new WarehouseDataError('migration.evidence.sha256')
  return blob
}
