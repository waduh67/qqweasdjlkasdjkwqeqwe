import { timestamp } from './approvals'
import { decimal, integer, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { baseUnit } from './materialModels'
import { LEGAL_OWNERS } from './models'
import type { BaseUnit } from './quantity'
import { command, parameters, query } from './transport'

export interface DispositionInput { sourceDocumentId: string; expectedRevision: number; stockIdentityId: string; quantityBase: string; baseUnit: BaseUnit; destinationLocationId: string; action: 'LOSS' | 'SCRAP'; reason: string; evidenceReference: string }
export interface CompensationInput { expectedRevision: number; expectedReturnRevision: number; destinationLocationId: string; reason: string; evidenceReference: string }
function common(value: unknown, path: string) {
  const row = record(value, path)
  const result = { id: uuid(row.id, path), code: text(row.code, path), revision: integer(row.revision, path), state: oneOf(row.state, ['DRAFT', 'REWORK_REQUIRED', 'POSTED'], path),
    stockIdentityId: uuid(row.stockIdentityId, path), quantityBase: decimal(row.quantityBase, path), baseUnit: baseUnit(row.baseUnit, path),
    sourceLocationId: uuid(row.sourceLocationId, path), destinationLocationId: uuid(row.destinationLocationId, path), reason: text(row.reason, path), evidenceReference: text(row.evidenceReference, path), recordedAt: timestamp(row.recordedAt, path) }
  if (BigInt(result.quantityBase) <= 0n || result.sourceLocationId === result.destinationLocationId) throw new WarehouseDataError(path)
  return result
}
export function disposition(value: unknown, path = 'disposition') {
  const row = record(value, path)
  return { ...common(row, path), action: oneOf(row.action, ['LOSS', 'SCRAP'], path), sourceDocumentId: uuid(row.sourceDocumentId, path), sourceRevision: integer(row.sourceRevision, path), skuId: uuid(row.skuId, path), legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path) }
}
export type Disposition = ReturnType<typeof disposition>
export function compensation(value: unknown, path = 'compensation') {
  const row = record(value, path)
  return { ...common(row, path), originalDispositionId: uuid(row.originalDispositionId, path), originalPostingId: uuid(row.originalPostingId, path), returnId: uuid(row.returnId, path) }
}
export type Compensation = ReturnType<typeof compensation>
const root = '/api/v1/warehouse/dispositions'
export const listDispositions = (sourceDocumentId: string, page = 0) => query(`${root}${parameters({ sourceDocumentId: uuid(sourceDocumentId), page, size: 25 })}`, value => {
  const result = pageOf(disposition)(value)
  if (result.items.some(row => row.sourceDocumentId !== sourceDocumentId)) throw new WarehouseDataError('disposition.source')
  return result
})
export const createDisposition = (input: DispositionInput) => command(root, 'POST', input, disposition)
export const listCompensations = (id: string, page = 0) => query(`${root}/${uuid(id)}/compensations${parameters({ page, size: 25 })}`, value => {
  const result = pageOf(compensation)(value)
  if (result.items.some(row => row.originalDispositionId !== id)) throw new WarehouseDataError('compensation.source')
  return result
})
export const createCompensation = (id: string, input: CompensationInput) => command(`${root}/${uuid(id)}/compensations`, 'POST', input, compensation)
