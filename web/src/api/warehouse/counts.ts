import { draftExpiryFields } from './draftExpiry'
import { array, decimal, integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { baseUnit } from './materialModels'
import { timestamp } from './approvals'
import { CONDITIONS, LEGAL_OWNERS, STOCK_STATES, TRACKING } from './models'
import { CUSTODIAN_KINDS } from './stock'
import { command, parameters, query } from './transport'

export const COUNT_STATES = ['DRAFT', 'EXPIRED', 'COUNTING', 'SUBMITTED', 'RECOUNT_REQUIRED', 'APPROVED', 'POSTED'] as const
export interface CountDraft { locationId: string; partialLocation: true; reason: string; entries: { balanceId: string; counterId: string }[] }
export interface CountObservation { expectedRevision: number; balanceId: string; quantityBase: string; reason: string; documentReference: string }
function countEntry(value: unknown, path = 'entry') {
  const row = record(value, path)
  return { balanceId: uuid(row.balanceId, path), counterId: uuid(row.counterId, path), stockIdentityId: uuid(row.stockIdentityId, path), skuId: uuid(row.skuId, path), baseUnit: baseUnit(row.baseUnit, path) }
}
/** The blind count contract contains identities and assignments, never expected quantities. */
export function countView(value: unknown, path = 'count') {
  const row = record(value, path), entries = array(row.entries, countEntry, path, 100)
  const result = { id: uuid(row.id, path), revision: integer(row.revision, path), state: oneOf(row.state, COUNT_STATES, path), locationId: uuid(row.locationId, path),
    ...draftExpiryFields(row.draftExpiry, row.state === 'EXPIRED'),
    partialLocation: row.partialLocation, roundRevision: nullable(row.roundRevision, integer, path), entries }
  if (result.partialLocation !== true || !entries.length || new Set(entries.map(row => row.balanceId)).size !== entries.length ||
    (result.roundRevision !== null && result.roundRevision > result.revision) || (result.state === 'COUNTING' && result.roundRevision === null)) throw new WarehouseDataError(path)
  return { ...result, partialLocation: true as const }
}
export type WarehouseCount = ReturnType<typeof countView>
export function countFact(value: unknown, path = 'observation') {
  const row = record(value, path)
  return { id: uuid(row.id, path), balanceId: uuid(row.balanceId, path), counterId: uuid(row.counterId, path), roundRevision: integer(row.roundRevision, path),
    quantityBase: decimal(row.quantityBase, path), baseUnit: baseUnit(row.baseUnit, path), reason: text(row.reason, path), documentReference: text(row.documentReference, path) }
}
export type CountFact = ReturnType<typeof countFact>
function comparison(value: unknown, path = 'comparison') {
  const row = record(value, path)
  return { balanceId: uuid(row.balanceId, path), counterId: uuid(row.counterId, path), bookQuantityBase: decimal(row.bookQuantityBase, path), quantityBase: decimal(row.quantityBase, path),
    baseUnit: baseUnit(row.baseUnit, path), observedDimensionRevision: integer(row.observedDimensionRevision, path) }
}
export function countReview(value: unknown, path = 'review') {
  const row = record(value, path), count = countView(row.count, path), observations = array(row.observations, comparison, path, 100)
  if (!['SUBMITTED', 'APPROVED', 'POSTED'].includes(count.state) || observations.length !== count.entries.length ||
    new Set(observations.map(row => row.balanceId)).size !== observations.length || observations.some(observation => !count.entries.some(entry =>
      entry.balanceId === observation.balanceId && entry.counterId === observation.counterId && entry.baseUnit === observation.baseUnit))) throw new WarehouseDataError(path)
  return { count, observations }
}
export function countPerson(value: unknown, path = 'person') {
  const row = record(value, path)
  return { id: uuid(row.id, path), name: nullable(row.name, text, path) }
}
export type CountPerson = ReturnType<typeof countPerson>
function countLocation(value: unknown, path = 'location') {
  const row = record(value, path)
  return { id: uuid(row.id, path), code: text(row.code, path), name: nullable(row.name, text, path) }
}
function countItem(value: unknown, path = 'item') {
  const row = record(value, path)
  return { skuId: uuid(row.skuId, path), code: text(row.code, path), name: text(row.name, path), tracking: oneOf(row.tracking, TRACKING, path),
    serial: nullable(row.serial, text, path), lotCode: nullable(row.lotCode, text, path) }
}
function custody(row: Record<string, unknown>, path: string) {
  return { custodianId: uuid(row.custodianId, path), custodianKind: oneOf(row.custodianKind, CUSTODIAN_KINDS, path),
    condition: oneOf(row.condition, CONDITIONS, path), legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path) }
}
export function countPosition(value: unknown, path = 'position') {
  const row = record(value, path)
  return { id: uuid(row.id, path), stockIdentityId: uuid(row.stockIdentityId, path), item: countItem(row.item, path), baseUnit: baseUnit(row.baseUnit, path),
    location: countLocation(row.location, path), ...custody(row, path), status: oneOf(row.status, STOCK_STATES, path) }
}
export type CountPosition = ReturnType<typeof countPosition>
function countReferences(value: unknown, count: WarehouseCount, path: string) {
  const row = record(value, path)
  const result = { code: text(row.code, path), reason: text(row.reason, path), createdAt: timestamp(row.createdAt, path), location: countLocation(row.location, path),
    requester: countPerson(row.requester, path), counters: array(row.counters, countPerson, path, 100), lines: array(row.lines, (value, path = 'line') => {
      const line = record(value, path)
      return { balanceId: uuid(line.balanceId, path), item: countItem(line.item, path), ...custody(line, path) }
    }, path, 100) }
  if (result.location.id !== count.locationId || result.lines.length !== count.entries.length || new Set(result.lines.map(line => line.balanceId)).size !== result.lines.length ||
    new Set(result.counters.map(person => person.id)).size !== result.counters.length || count.entries.some(entry =>
      !result.lines.some(line => line.balanceId === entry.balanceId && line.item.skuId === entry.skuId) || !result.counters.some(person => person.id === entry.counterId))) throw new WarehouseDataError(path)
  return result
}
export function countDetails(value: unknown, path = 'details') {
  const row = record(value, path), count = countView(row.count, path)
  return { count, references: countReferences(row.references, count, path) }
}
export type CountDetails = ReturnType<typeof countDetails>
export function countDraftEdit(value: unknown, path = 'draft') {
  const row = record(value, path), details = countDetails(row.details, path)
  const positions = array(row.positions, countPosition, path, 100)
  const eligibleAssignedCounterIds = array(row.eligibleAssignedCounterIds, uuid, path, 100)
  if (details.count.state !== 'DRAFT' || new Set(positions.map(position => position.id)).size !== positions.length ||
    positions.some(position => position.location.id !== details.count.locationId || !details.count.entries.some(entry =>
      entry.balanceId === position.id && entry.stockIdentityId === position.stockIdentityId && entry.skuId === position.item.skuId && entry.baseUnit === position.baseUnit)) ||
    new Set(eligibleAssignedCounterIds).size !== eligibleAssignedCounterIds.length ||
    eligibleAssignedCounterIds.some(id => !details.count.entries.some(entry => entry.counterId === id))) throw new WarehouseDataError(path)
  return { details, positions, eligibleAssignedCounterIds }
}
export function countReviewDetails(value: unknown, path = 'details') {
  const row = record(value, path), review = countReview(row.review, path)
  return { review, references: countReferences(row.references, review.count, path) }
}
export function countHistoryEntry(value: unknown, path = 'history') {
  const row = record(value, path)
  return { fact: countFact(row.fact, path), recordedAt: timestamp(row.recordedAt, path) }
}
export interface CountFilter { page?: number; size?: number; state?: WarehouseCount['state']; locationId?: string; skuId?: string; serial?: string; query?: string; from?: string; until?: string }
const root = '/api/v1/warehouse/counts'
export const countWorkbench = (filter: CountFilter = {}) => query(`${root}/workbench${parameters({ ...filter })}`, pageOf(countDetails))
export const getCountDetails = (id: string) => query(`${root}/${uuid(id)}/details`, countDetails)
export const getCountDraft = (id: string) => query(`${root}/${uuid(id)}/draft`, countDraftEdit)
export const getCountReviewDetails = (id: string) => query(`${root}/${uuid(id)}/review/details`, countReviewDetails)
export const countPositions = (filter: Pick<CountFilter, 'locationId' | 'skuId' | 'serial' | 'query' | 'page' | 'size'>) => query(`${root}/positions${parameters({ ...filter })}`, pageOf(countPosition))
export const countCounters = (locationId: string, search: string, page: number) => query(`${root}/locations/${uuid(locationId)}/counters${parameters({ query: search.trim() || undefined, page })}`, pageOf(countPerson))
export const countHistory = (id: string, page = 0, size = 25) => query(`${root}/${uuid(id)}/history/page${parameters({ page, size })}`, pageOf(countHistoryEntry))
export const listCounts = (filter: { page?: number; size?: number } = {}) => query(`${root}${parameters({ ...filter })}`, pageOf(countView))
export const getCount = (id: string) => query(`${root}/${uuid(id)}`, countView)
export const getCountReview = (id: string) => query(`${root}/${uuid(id)}/review`, countReview)
export const createCount = (input: CountDraft) => command(root, 'POST', input, countView)
export const updateCount = (id: string, expectedRevision: number, draft: CountDraft) => command(`${root}/${uuid(id)}`, 'PUT', { expectedRevision, draft }, countView)
export const startCount = (id: string, expectedRevision: number) => command(`${root}/${uuid(id)}/start`, 'POST', { expectedRevision }, countView)
export const observeCount = (id: string, input: CountObservation) => command(`${root}/${uuid(id)}/observe`, 'POST', input, countView)
export const submitCount = (id: string, expectedRevision: number) => command(`${root}/${uuid(id)}/submit`, 'POST', { expectedRevision }, countView)
export const recount = (id: string, expectedRevision: number) => command(`${root}/${uuid(id)}/recount`, 'POST', { expectedRevision }, countView)
