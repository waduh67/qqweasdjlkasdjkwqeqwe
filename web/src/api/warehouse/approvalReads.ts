import { draftExpiryFields } from './draftExpiry'
import { array, boolean, decimal, digest, integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { approval, timestamp, type WarehouseApproval } from './approvals'
import { baseUnit } from './materialModels'
import { CONDITIONS, LEGAL_OWNERS, TRACKING } from './models'
import { parameters, query } from './transport'
import { api } from '../client'

export const APPROVAL_KINDS = ['RECEIPT', 'ADJUSTMENT', 'COUNT', 'TITLE_CORRECTION', 'RETURN_TITLE', 'LOSS', 'SCRAP', 'DISPOSITION_REVERSAL', 'ASSET_LOSS', 'OPENING_BALANCE'] as const
export const POLICY_OPERATIONS = ['RECEIPT', 'ISSUE', 'ISSUE_EXCEPTION', 'OPENING_BALANCE', 'ADJUSTMENT', 'LOSS', 'SCRAP', 'COUNT_VARIANCE', 'TITLE_REACQUISITION'] as const
export const EFFECT_ACTIONS = ['RECEIVE', 'TRANSFER_REMAINDER', 'COUNT_VARIANCE', 'TITLE_REACQUISITION', 'LOSS', 'SCRAP', 'DISPOSITION_REVERSED', 'OPENING_BALANCE'] as const
function person(value: unknown, path = 'person') {
  const row = record(value, path)
  return { id: uuid(row.id, path), name: nullable(row.name, text, path) }
}
function location(value: unknown, path = 'location') {
  const row = record(value, path)
  return { id: uuid(row.id, path), code: text(row.code, path), name: nullable(row.name, text, path) }
}
function line(value: unknown, path = 'line') {
  const row = record(value, path)
  return { id: uuid(row.id, path), skuId: uuid(row.skuId, path), code: text(row.code, path), name: text(row.name, path), tracking: oneOf(row.tracking, TRACKING, path),
    baseUnit: baseUnit(row.baseUnit, path), quantityBase: nullable(row.quantityBase, decimal, path), serial: nullable(row.serial, text, path), lotCode: nullable(row.lotCode, text, path),
    locationId: nullable(row.locationId, uuid, path), destinationLocationId: nullable(row.destinationLocationId, uuid, path),
    condition: oneOf(row.condition, CONDITIONS, path), legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path) }
}
function comparison(value: unknown, path = 'comparison') {
  const row = record(value, path)
  return { balanceId: uuid(row.balanceId, path), skuId: uuid(row.skuId, path), counter: person(row.counter, path), baseUnit: baseUnit(row.baseUnit, path),
    bookQuantityBase: decimal(row.bookQuantityBase, path), quantityBase: decimal(row.quantityBase, path), documentReference: text(row.documentReference, path) }
}
function evidenceReference(value: unknown, path = 'reference') {
  const row = record(value, path)
  return { kind: oneOf(row.kind, ['RECEIPT', 'TRANSFER', 'DISPOSITION', 'COMPENSATION', 'TITLE_TRANSFER', 'MIGRATION'], path), reference: text(row.reference, path) }
}
function migration(value: unknown, path = 'migration') {
  const row = record(value, path), watermark = text(row.watermark, path)
  const result = { batchId: uuid(row.batchId, path), watermark,
    cutoff: timestamp(watermark.replace(' ', 'T').replace(/([+-]\d{2})$/, '$1:00'), path),
    sourceHash: digest(row.sourceHash, path), reviewHash: digest(row.reviewHash, path), caseCount: integer(row.caseCount, path),
    baselineCount: integer(row.baselineCount, path), unresolvedHistoricalCount: integer(row.unresolvedHistoricalCount, path), valuation: oneOf(row.valuation, ['UNKNOWN'], path) }
  if (result.baselineCount + result.unresolvedHistoricalCount > result.caseCount) throw new WarehouseDataError(path)
  return result
}
export function approvalDocument(value: unknown, path = 'document') {
  const row = record(value, path)
  const opening = nullable(row.migration, migration, path)
  const document = { id: uuid(row.id, path), revision: integer(row.revision, path), kind: oneOf(row.kind, APPROVAL_KINDS, path), code: text(row.code, path), state: text(row.state, path),
    reason: nullable(row.reason, text, path), createdAt: timestamp(row.createdAt, path), requester: person(row.requester, path), locations: array(row.locations, location, path, 1000),
    lines: array(row.lines, line, path, 1000), comparisons: array(row.comparisons, comparison, path, 100), evidenceReferences: array(row.evidenceReferences ?? [], evidenceReference, path, 5),
    receiptId: nullable(row.receiptId, uuid, path), countId: nullable(row.countId, uuid, path), transferId: nullable(row.transferId, uuid, path), returnId: nullable(row.returnId, uuid, path),
    ...(opening ? { migration: opening } : {}) }
  if ((document.kind === 'OPENING_BALANCE') !== (opening !== null) || (opening && (opening.baselineCount !== document.lines.length || !document.locations.length)) ||
    (!document.lines.length && !opening) || new Set(document.lines.map(line => line.id)).size !== document.lines.length ||
    document.lines.some(line => [line.locationId, line.destinationLocationId].some(id => id !== null && !document.locations.some(location => location.id === id))) ||
    document.lines.some(line => document.kind === 'COUNT' ? line.quantityBase !== null : line.quantityBase === null) ||
    (document.comparisons.length > 0 && (document.kind !== 'COUNT' || !['SUBMITTED', 'APPROVED', 'POSTED'].includes(document.state))) ||
    document.comparisons.some(row => !document.lines.some(line => line.skuId === row.skuId && line.baseUnit === row.baseUnit)) ||
    (document.kind === 'COUNT' && ['SUBMITTED', 'APPROVED', 'POSTED'].includes(document.state) && (document.comparisons.length !== document.lines.length || new Set(document.comparisons.map(row => row.balanceId)).size !== document.comparisons.length)) ||
    (document.receiptId !== null && (document.receiptId !== document.id || document.kind !== 'RECEIPT')) ||
    (document.countId !== null && (document.countId !== document.id || document.kind !== 'COUNT'))) throw new WarehouseDataError(path)
  return document
}
export type ApprovalDocument = ReturnType<typeof approvalDocument>
export function approvalSource(value: unknown, path = 'source') {
  const row = record(value, path)
  const result = { document: approvalDocument(row.document, path), ...draftExpiryFields(row.draftExpiry), canRequest: boolean(row.canRequest, path), requestBlock: nullable(row.requestBlock, text, path) }
  if (result.canRequest === (result.requestBlock !== null)) throw new WarehouseDataError(path)
  return result
}
export function approvalSummary(value: unknown, path = 'summary') {
  const row = record(value, path)
  return { approval: approval(row.approval, path), documentCode: text(row.documentCode, path), operation: oneOf(row.operation, POLICY_OPERATIONS, path), requester: person(row.requester, path), requestedAt: timestamp(row.requestedAt, path) }
}
function actions(value: unknown, path = 'actions') {
  const row = record(value, path)
  return { canDecide: boolean(row.canDecide, path), decisionBlock: nullable(row.decisionBlock, text, path), canRework: boolean(row.canRework, path),
    reworkSourceRevision: integer(row.reworkSourceRevision, path), currentTier: nullable(row.currentTier, integer, path) }
}
function policy(value: unknown, path = 'policy') {
  const row = record(value, path)
  return { id: uuid(row.id, path), revision: integer(row.revision, path), tiers: array(row.tiers, (value, path = 'tier') => {
    const tier = record(value, path)
    return { number: integer(tier.number, path), approvers: array(tier.approvers, person, path, 1000) }
  }, path, 100) }
}
function effect(value: unknown, path = 'effect') {
  const row = record(value, path)
  const result = { operationId: uuid(row.operationId, path), businessAction: oneOf(row.businessAction, EFFECT_ACTIONS, path), recordedAt: timestamp(row.recordedAt, path), movementIds: array(row.movementIds, uuid, path, 1000) }
  if (!result.movementIds.length) throw new WarehouseDataError(path)
  return result
}
function cost(value: unknown, path = 'cost') {
  const row = record(value, path)
  const result = { numerator: decimal(row.numerator, path), denominator: decimal(row.denominator, path), currency: text(row.currency, path) }
  if (BigInt(result.denominator) <= 0n || !/^[A-Z]{3}$/.test(result.currency)) throw new WarehouseDataError(path)
  return result
}
export function approvalDetails(value: unknown, path = 'details') {
  const row = record(value, path)
  const result = { approval: approval(row.approval, path), document: approvalDocument(row.document, path), currentSourceRevision: integer(row.currentSourceRevision, path), currentSourceState: text(row.currentSourceState, path),
    ...draftExpiryFields(row.draftExpiry, row.currentSourceState === 'EXPIRED'),
    requestedAt: timestamp(row.requestedAt, path), policy: policy(row.policy, path), actions: actions(row.actions, path), effect: nullable(row.effect, effect, path), cost: nullable(row.cost, cost, path) }
  if (result.document.id !== result.approval.sourceDocumentId || result.document.revision !== result.approval.sourceRevision ||
    result.actions.reworkSourceRevision !== result.currentSourceRevision || result.actions.canDecide === (result.actions.decisionBlock !== null) ||
    (result.actions.canDecide && (result.approval.status !== 'PENDING' || result.actions.currentTier === null || result.currentSourceRevision !== result.approval.sourceRevision)) ||
    (result.effect?.operationId ?? null) !== result.approval.effectOperationId || (result.effect && (result.approval.status !== 'APPROVED' || result.effect.businessAction !== ({
      RECEIPT: 'RECEIVE', ADJUSTMENT: 'TRANSFER_REMAINDER', COUNT: 'COUNT_VARIANCE', TITLE_CORRECTION: 'TITLE_REACQUISITION', RETURN_TITLE: 'TITLE_REACQUISITION', LOSS: 'LOSS', SCRAP: 'SCRAP', ASSET_LOSS: 'LOSS', DISPOSITION_REVERSAL: 'DISPOSITION_REVERSED', OPENING_BALANCE: 'OPENING_BALANCE',
    } as const)[result.document.kind])) || (result.document.migration && result.cost !== null)) throw new WarehouseDataError(path)
  return result
}
export type ApprovalDetails = ReturnType<typeof approvalDetails>
export function approvalAttachment(value: unknown, path = 'attachment') {
  const row = record(value, path)
  const file = { id: uuid(row.id, path), kind: oneOf(row.kind, ['RECEIPT', 'SIGNATURE', 'MIGRATION_EVIDENCE'], path), recordedAt: timestamp(row.recordedAt, path),
    contentType: nullable(row.contentType, (value, path) => oneOf(value, ['image/png', 'image/jpeg', 'application/pdf'], path), path),
    sizeBytes: nullable(row.sizeBytes, integer, path), signerLabel: nullable(row.signerLabel, text, path),
    ...(row.kind === 'MIGRATION_EVIDENCE' ? { label: text(row.label, path), sha256: digest(row.sha256, path), caseId: uuid(row.caseId, path) } : {}) }
  if (file.kind !== 'SIGNATURE' && (file.contentType === null || file.sizeBytes === null || file.sizeBytes < 1 || file.sizeBytes > 15728640)) throw new WarehouseDataError(path)
  return file
}
export type ApprovalAttachment = ReturnType<typeof approvalAttachment>
export function approvalHistoryEntry(value: unknown, path = 'history') {
  const row = record(value, path)
  return { id: uuid(row.id, path), tier: integer(row.tier, path), approver: person(row.approver, path), decision: oneOf(row.decision, ['APPROVE', 'REJECT'], path),
    reason: nullable(row.reason, text, path), decidedAt: timestamp(row.decidedAt, path), revision: integer(row.revision, path),
    delegatedFrom: nullable(row.delegatedFrom, person, path), evidenceReference: nullable(row.evidenceReference, uuid, path) }
}
export interface ApprovalFilter { page?: number; size?: number; status?: WarehouseApproval['status']; sourceDocumentId?: string; query?: string; operation?: typeof POLICY_OPERATIONS[number]; locationId?: string; skuId?: string; serial?: string; from?: string; until?: string }
const root = '/api/v1/warehouse/approvals'
export const approvalWorkbench = (filter: ApprovalFilter = {}) => query(`${root}/workbench${parameters({ ...filter })}`, pageOf(approvalSummary))
export const getApprovalSource = (id: string) => query(`${root}/sources/${uuid(id)}`, approvalSource)
export const getApprovalDetails = (id: string) => query(`${root}/${uuid(id)}/details`, approvalDetails)
export const approvalHistory = (id: string, page = 0) => query(`${root}/${uuid(id)}/history/page${parameters({ page, size: 25 })}`, pageOf(approvalHistoryEntry))
export const approvalAttachments = (id: string, page = 0) => query(`${root}/${uuid(id)}/attachments${parameters({ page, size: 25 })}`, pageOf(approvalAttachment))
export async function downloadApprovalAttachment(id: string, file: ApprovalAttachment): Promise<Blob> {
  const blob = await api.blob(`${root}/${uuid(id)}/attachments/${uuid(file.id)}`)
  const types = file.kind === 'SIGNATURE' ? ['image/png', 'image/jpeg', 'image/gif', 'image/webp'] : ['image/png', 'image/jpeg', 'application/pdf']
  if (!types.includes(blob.type) || blob.size < 1 || blob.size > 15728640 || (file.contentType !== null && file.contentType !== blob.type) || (file.sizeBytes !== null && file.sizeBytes !== blob.size)) throw new WarehouseDataError('attachment.file')
  return blob
}
export async function evaluateApprovalSource(sourceDocumentId: string, sourceRevision: number) {
  const row = record(await api.post<unknown>('/api/v1/warehouse/settings/evaluate', { sourceDocumentId: uuid(sourceDocumentId), sourceRevision: integer(sourceRevision) }))
  if (uuid(row.sourceDocumentId) !== sourceDocumentId || integer(row.sourceRevision) !== sourceRevision) throw new WarehouseDataError('evaluation.source')
  return { code: oneOf(row.code, ['IN_POLICY', 'APPROVAL_REQUIRED']), requiredAction: oneOf(row.requiredAction, ['CONTINUE_OPERATION', 'REQUEST_APPROVAL']) }
}
