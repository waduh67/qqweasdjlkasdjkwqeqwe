import { timestamp } from './approvals'
import { array, boolean, decimal, integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { MASTER_STATES } from './models'
import type { BaseUnit } from './quantity'
import { command, parameters, query } from './transport'

export const REPLENISHMENT_STATES = ['PENDING', 'FULFILLED', 'CANCELLED'] as const
export interface ReplenishmentRuleInput { skuId: string; locationId: string; baseUnit: BaseUnit; minimumBase: string; maximumBase: string; targetBase: string; packageMultipleBase: string; leadTimeDays: number; expectedRevision?: number }
export function replenishmentRule(value: unknown, path = 'rule') {
  const r = record(value, path), minimumBase = decimal(r.minimumBase, path), maximumBase = decimal(r.maximumBase, path), targetBase = decimal(r.targetBase, path), packageMultipleBase = decimal(r.packageMultipleBase, path), leadTimeDays = integer(r.leadTimeDays, path)
  if (BigInt(minimumBase) > BigInt(targetBase) || BigInt(targetBase) > BigInt(maximumBase) || BigInt(packageMultipleBase) <= 0n || leadTimeDays > 3650) throw new WarehouseDataError(path)
  return { id: uuid(r.id, path), revision: integer(r.revision, path), skuId: uuid(r.skuId, path), locationId: uuid(r.locationId, path), baseUnit: oneOf(r.baseUnit, ['EA', 'MM'], path), minimumBase, maximumBase, targetBase, packageMultipleBase, leadTimeDays, active: boolean(r.active, path) }
}
export type ReplenishmentRule = ReturnType<typeof replenishmentRule>
function position(value: unknown, path = 'position') {
  const r = record(value, path)
  return { availableBase: decimal(r.availableBase, path), reservedBase: decimal(r.reservedBase, path), confirmedInboundBase: decimal(r.confirmedInboundBase, path) }
}
export function replenishmentRequest(value: unknown, path = 'request') {
  const r = record(value, path), ruleSnapshot = replenishmentRule(r.ruleSnapshot, path)
  const result = { id: uuid(r.id, path), revision: integer(r.revision, path), ruleId: uuid(r.ruleId, path), ruleRevision: integer(r.ruleRevision, path), state: oneOf(r.state, REPLENISHMENT_STATES, path),
    quantityBase: decimal(r.quantityBase, path), baseUnit: oneOf(r.baseUnit, ['EA', 'MM'], path), ...position(r, path), ruleSnapshot,
    acceptedAt: nullable(r.acceptedAt, timestamp, path), acceptedBy: nullable(r.acceptedBy, uuid, path), receivingDocumentId: nullable(r.receivingDocumentId, uuid, path), receivingLineId: nullable(r.receivingLineId, uuid, path), createdAt: timestamp(r.createdAt, path) }
  if (result.ruleId !== ruleSnapshot.id || result.ruleRevision !== ruleSnapshot.revision || result.baseUnit !== ruleSnapshot.baseUnit ||
    Boolean(result.acceptedAt) !== Boolean(result.acceptedBy) || Boolean(result.receivingDocumentId) !== Boolean(result.receivingLineId) || BigInt(result.quantityBase) === 0n) throw new WarehouseDataError(path)
  return result
}
export type ReplenishmentRequest = ReturnType<typeof replenishmentRequest>
function references(r: Record<string, unknown>, rule: ReplenishmentRule, path: string) {
  const sku = record(r.sku, path), location = record(r.location, path)
  const result = { sku: { id: uuid(sku.id, path), code: text(sku.code, path), name: text(sku.name, path), state: oneOf(sku.state, MASTER_STATES, path) },
    location: { id: uuid(location.id, path), code: text(location.code, path), name: nullable(location.name, text, path), state: oneOf(location.state, MASTER_STATES, path), replenishmentEligible: boolean(location.replenishmentEligible, path) } }
  if (result.sku.id !== rule.skuId || result.location.id !== rule.locationId) throw new WarehouseDataError(path)
  return result
}
export function replenishmentRuleView(value: unknown, path = 'ruleView') {
  const r = record(value, path), rule = replenishmentRule(r.rule, path)
  return { rule, ...references(r, rule, path) }
}
export function replenishmentRequestView(value: unknown, path = 'requestView') {
  const r = record(value, path), request = replenishmentRequest(r.request, path)
  return { request, ...references(r, request.ruleSnapshot, path) }
}
export function replenishmentDetails(value: unknown, path = 'details') {
  const r = record(value, path), view = replenishmentRuleView(value, path), request = nullable(r.request, replenishmentRequest, path)
  if (request && (request.ruleId !== view.rule.id || request.ruleSnapshot.skuId !== view.rule.skuId || request.ruleSnapshot.locationId !== view.rule.locationId || request.baseUnit !== view.rule.baseUnit)) throw new WarehouseDataError(path)
  return { ...view, position: position(r.position, path), suggestedQuantityBase: decimal(r.suggestedQuantityBase, path), request }
}
export type ReplenishmentDetails = ReturnType<typeof replenishmentDetails>
export function replenishmentEvaluation(value: unknown, path = 'evaluation') {
  const r = record(value, path)
  if ('id' in r) return { request: replenishmentRequest(value, path) }
  if (r.request !== null) throw new WarehouseDataError(path)
  return { ruleId: uuid(r.ruleId, path), position: position(r.position, path), request: null }
}
export interface ReplenishmentFilter { page?: number; size?: number; locationId?: string; skuId?: string }
const root = '/api/v1/warehouse/replenishments'
export const listReplenishmentRules = (filter: ReplenishmentFilter & { active?: 'true' | 'false' } = {}) => query(`${root}/workbench/rules${parameters({ ...filter })}`, pageOf(replenishmentRuleView))
export const listReplenishmentRequests = (filter: ReplenishmentFilter & { state?: typeof REPLENISHMENT_STATES[number] } = {}) => query(`${root}/workbench/requests${parameters({ ...filter })}`, pageOf(replenishmentRequestView))
export async function getReplenishment(kind: 'rules' | 'requests', id: string) {
  const value = await query(`${root}/workbench/${kind}/${uuid(id)}`, replenishmentDetails)
  if ((kind === 'rules' ? value.rule.id : value.request?.id)?.toLowerCase() !== id.toLowerCase()) throw new WarehouseDataError('binding')
  return value
}
export const saveReplenishmentRule = (input: ReplenishmentRuleInput, id?: string) => command(`${root}/rules${id ? `/${uuid(id)}` : ''}`, id ? 'PUT' : 'POST', input, replenishmentRule)
export const archiveReplenishmentRule = (rule: ReplenishmentRule) => command(`${root}/rules/${uuid(rule.id)}/archive`, 'POST', { expectedRevision: rule.revision }, replenishmentRule)
export const recomputeReplenishment = (rule: ReplenishmentRule) => command(`${root}/rules/${uuid(rule.id)}/recompute`, 'POST', { expectedRevision: rule.revision }, replenishmentEvaluation)
export const acceptReplenishment = (request: ReplenishmentRequest, rule: ReplenishmentRule) => command(`${root}/requests/${uuid(request.id)}/accept`, 'POST', { expectedRevision: request.revision, expectedRuleRevision: rule.revision, quantityBase: request.quantityBase }, replenishmentRequest)
export const cancelReplenishment = (request: ReplenishmentRequest) => command(`${root}/requests/${uuid(request.id)}/cancel`, 'POST', { expectedRevision: request.revision }, replenishmentRequest)
export const bindReplenishmentReceipt = (request: ReplenishmentRequest, documentId: string, documentRevision: number, lineId: string) => command(`${root}/requests/${uuid(request.id)}/receiving-reference`, 'POST', { expectedRevision: request.revision, documentId: uuid(documentId), documentRevision: integer(documentRevision), lineId: uuid(lineId) }, replenishmentRequest)
export const replenishmentHistory = (id: string, page: number) => query(`${root}/rules/${uuid(id)}/history${parameters({ page, size: 25 })}`, (value, path) => array(value, (v, p) => {
  const r = record(v, p)
  return { id: uuid(r.id, p), action: text(r.action, p), revision: integer(r.revision, p), createdAt: timestamp(r.createdAt, p) }
}, path, 25))
