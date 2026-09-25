import { array, boolean, decimal, digest, integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { timestamp } from './approvals'
import { baseUnit } from './materialModels'
import { TRACKING } from './models'
import { parameters, query } from './transport'
import { api } from '../client'

export const MIGRATION_SOURCES = ['inventory_serialized_asset', 'inventory_balance_projection', 'onu', 'inventory_serial_tombstone',
  'inventory_movement', 'inventory_movement_leg', 'inventory_fulfillment_effect', 'inventory_customer_material_fact', 'fulfillment_checkpoint', 'fulfillment_outbox'] as const
export function migrationStock(value: unknown, path = 'stock') {
  const row = record(value, path)
  const result = { skuId: uuid(row.skuId, path), skuRevision: integer(row.skuRevision, path), locationId: uuid(row.locationId, path), locationRevision: integer(row.locationRevision, path),
    tracking: oneOf(row.tracking, TRACKING, path), sourceUnit: oneOf(row.sourceUnit, ['EA', 'MM', 'M'], path),
    quantityBase: decimal(row.quantityBase, path), baseUnit: baseUnit(row.baseUnit, path), legalOwner: oneOf(row.legalOwner, ['ISP'], path) }
  if (BigInt(result.quantityBase) < 1n || result.baseUnit !== (result.sourceUnit === 'EA' ? 'EA' : 'MM') ||
    (result.tracking === 'SERIAL' && (result.baseUnit !== 'EA' || result.quantityBase !== '1'))) throw new WarehouseDataError(path)
  return result
}
function resolution(value: unknown, path = 'resolution') {
  const row = record(value, path)
  const result = { id: uuid(row.id, path), batchId: uuid(row.batchId, path), caseId: uuid(row.caseId, path), sourceHash: digest(row.sourceHash, path),
    revision: integer(row.revision, path), kind: oneOf(row.kind, ['BASELINE_STOCK', 'PROVENANCE_ONLY', 'DUPLICATE', 'CANCEL_PENDING'], path), reason: text(row.reason, path),
    stock: nullable(row.stock, migrationStock, path), duplicateCaseId: nullable(row.duplicateCaseId, uuid, path), duplicateResolutionId: nullable(row.duplicateResolutionId, uuid, path),
    resolvedBy: uuid(row.resolvedBy, path), createdAt: timestamp(row.createdAt, path), evidence: array(row.evidence, (value, path = 'evidence') => {
      const file = record(value, path)
      return { id: uuid(file.id, path), sourceHash: digest(file.sourceHash, path), sha256: digest(file.sha256, path), uploadedBy: uuid(file.uploadedBy, path) }
    }, path, 10) }
  if ((result.kind === 'BASELINE_STOCK') !== (result.stock !== null) || (result.kind === 'DUPLICATE' ? result.duplicateCaseId === null || result.duplicateResolutionId === null : result.duplicateCaseId !== null || result.duplicateResolutionId !== null) ||
    !result.evidence.length || result.evidence.some(file => file.sourceHash !== result.sourceHash)) throw new WarehouseDataError(path)
  return result
}
function rawText(value: unknown, path = 'legacy'): string {
  if (typeof value !== 'string' || value.length > 4096) throw new WarehouseDataError(path)
  return value
}
export function migrationReviewCase(value: unknown, path = 'case') {
  const row = record(value, path), snapshot = record(row.sourceSnapshot, path)
  const result = { caseId: uuid(row.caseId, path), sourceTable: oneOf(row.sourceTable, MIGRATION_SOURCES, path), sourceId: uuid(row.sourceId, path), sourceHash: digest(row.sourceHash, path),
    resolutionRequired: boolean(row.resolutionRequired, path), resolution: nullable(row.resolution, resolution, path),
    source: { serial: nullable(snapshot.serialNumber, rawText, path), mac: nullable(snapshot.macAddress, rawText, path), model: nullable(snapshot.model, rawText, path),
      state: nullable(snapshot.state ?? snapshot.status ?? snapshot.checkpointState, text, path),
      legacyQuantity: nullable(snapshot.legacyQuantity, (value, path) => decimal(value, path, true), path) } }
  if (uuid(snapshot.id, path) !== result.sourceId || (result.resolution && (result.resolution.caseId !== result.caseId || result.resolution.sourceHash !== result.sourceHash))) throw new WarehouseDataError(path)
  return result
}
export type MigrationReviewCase = ReturnType<typeof migrationReviewCase>
export async function approvalMigrationCases(id: string, page = 0) {
  const result = await query('/api/v1/warehouse/approvals/' + uuid(id) + '/migration-cases' + parameters({ page, size: 25 }), pageOf(migrationReviewCase))
  if (result.items.some(row => row.resolutionRequired && row.resolution === null) || new Set(result.items.map(row => row.caseId)).size !== result.items.length) throw new WarehouseDataError('migration.review')
  return result
}
export async function approvalMigrationEvidence(id: string, reference: { id: string; sha256: string }): Promise<Blob> {
  const blob = await api.blob('/api/v1/warehouse/approvals/' + uuid(id) + '/attachments/' + uuid(reference.id))
  if (!['image/png', 'image/jpeg', 'application/pdf'].includes(blob.type) || blob.size < 1 || blob.size > 15728640) throw new WarehouseDataError('migration.evidence')
  if (!globalThis.crypto?.subtle) throw new WarehouseDataError('migration.evidence.verification')
  const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())), byte => byte.toString(16).padStart(2, '0')).join('')
  if (hash !== digest(reference.sha256)) throw new WarehouseDataError('migration.evidence.sha256')
  return blob
}
