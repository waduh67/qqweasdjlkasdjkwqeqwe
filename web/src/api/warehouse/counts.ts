import { array, decimal, integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { baseUnit } from './materialModels'
import { command, parameters, query } from './transport'

export const COUNT_STATES = ['DRAFT', 'COUNTING', 'SUBMITTED', 'RECOUNT_REQUIRED', 'APPROVED', 'POSTED'] as const
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
const root = '/api/v1/warehouse/counts'
export const listCounts = (filter: { page?: number; size?: number } = {}) => query(`${root}${parameters({ ...filter })}`, pageOf(countView))
export const getCount = (id: string) => query(`${root}/${uuid(id)}`, countView)
export const getCountReview = (id: string) => query(`${root}/${uuid(id)}/review`, countReview)
export const createCount = (input: CountDraft) => command(root, 'POST', input, countView)
export const startCount = (id: string, expectedRevision: number) => command(`${root}/${uuid(id)}/start`, 'POST', { expectedRevision }, countView)
export const observeCount = (id: string, input: CountObservation) => command(`${root}/${uuid(id)}/observe`, 'POST', input, countView)
export const submitCount = (id: string, expectedRevision: number) => command(`${root}/${uuid(id)}/submit`, 'POST', { expectedRevision }, countView)
export const recount = (id: string, expectedRevision: number) => command(`${root}/${uuid(id)}/recount`, 'POST', { expectedRevision }, countView)
