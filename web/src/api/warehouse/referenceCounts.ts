import { array, decimal, digest, integer, nullable, oneOf, pageOf, record, text, timestamp, uuid, WarehouseDataError } from './codec'
import { referenceCommand } from './reference'
import { parameters, query } from './transport'

export function countLocation(value: unknown) {
  const row = record(value)
  return { id: uuid(row.id), code: text(row.code), name: text(row.name), kind: oneOf(row.kind, ['WAREHOUSE', 'BIN', 'TECHNICIAN']), technicianName: nullable(row.technicianName, text, 'technicianName') }
}
export type CountLocation = ReturnType<typeof countLocation>
export function countSnapshot(value: unknown) {
  const row = record(value)
  const snapshot = { id: uuid(row.id), skuId: uuid(row.skuId), skuName: text(row.skuName), locationId: uuid(row.locationId), locationName: text(row.locationName),
    baseUnit: oneOf(row.baseUnit, ['EA', 'MM']), tracking: oneOf(row.tracking, ['BULK', 'LOT', 'SERIAL']), bookBase: decimal(row.bookBase), snapshotHash: digest(row.snapshotHash), loadedAt: timestamp(row.loadedAt),
    positions: array(row.positions, value => {
      const position = record(value), dimension = record(position.dimension)
      return { balanceId: uuid(position.balanceId), stockIdentityId: uuid(dimension.stockIdentityId), skuId: uuid(dimension.skuId), locationId: uuid(dimension.locationId),
        quantityBase: decimal(position.quantityBase), status: oneOf(position.status, ['AVAILABLE', 'ISSUED']), balanceRevision: integer(position.balanceRevision), pieceRevision: integer(position.pieceRevision),
        serial: nullable(position.serial, text, 'serial'), mac: nullable(position.mac, text, 'mac') }
    }, 'positions', Number.MAX_SAFE_INTEGER) }
  if (snapshot.tracking === 'SERIAL' && snapshot.baseUnit !== 'EA' || new Set(snapshot.positions.map(item => item.balanceId)).size !== snapshot.positions.length ||
    snapshot.positions.some(item => item.skuId !== snapshot.skuId || item.locationId !== snapshot.locationId || BigInt(item.quantityBase) <= 0n || snapshot.tracking === 'SERIAL' && (!item.serial || item.quantityBase !== '1')) ||
    snapshot.positions.reduce((sum, item) => sum + BigInt(item.quantityBase), 0n) !== BigInt(snapshot.bookBase)) throw new WarehouseDataError('count.snapshot')
  return snapshot
}
export type CountSnapshot = ReturnType<typeof countSnapshot>
export function referenceCount(value: unknown) {
  const row = record(value), snapshot = countSnapshot(row.snapshot)
  const count = { id: uuid(row.id), snapshot, physicalBase: decimal(row.physicalBase), differenceBase: decimal(row.differenceBase, 'differenceBase', true),
    serials: array(row.serials, value => { const item = record(value); return { serial: text(item.serial), mac: nullable(item.mac, text, 'mac') } }, 'serials', 500),
    reason: text(row.reason), actorId: uuid(row.actorId), actorName: text(row.actorName), recordedAt: timestamp(row.recordedAt), movementIds: array(row.movementIds, uuid) }
  if (BigInt(count.physicalBase) - BigInt(snapshot.bookBase) !== BigInt(count.differenceBase) ||
    (snapshot.tracking === 'SERIAL' ? BigInt(count.serials.length) !== BigInt(count.physicalBase) : count.serials.length !== 0) ||
    new Set(count.serials.map(item => item.serial.trim().toUpperCase())).size !== count.serials.length) throw new WarehouseDataError('count.audit')
  return count
}
export type ReferenceCount = ReturnType<typeof referenceCount>
export interface CountInput { readonly snapshotId: string; readonly physicalBase: string; readonly serials: readonly { readonly serial: string; readonly mac?: string }[]; readonly reason: string }
export const listCountLocations = (search: string, page: number) => query('/api/v2/warehouse/counts/locations' + parameters({ search, page }), pageOf(countLocation))
export const loadCountSnapshot = (input: { readonly skuId: string; readonly locationId: string }) => referenceCommand('/api/v2/warehouse/counts/snapshot', 'POST', input, countSnapshot)
export const saveReferenceCount = (input: CountInput) => referenceCommand('/api/v2/warehouse/counts', 'POST', input, referenceCount)
export const listReferenceCounts = (page: number) => query('/api/v2/warehouse/counts' + parameters({ page }), pageOf(referenceCount))
export const getReferenceCount = (id: string) => query('/api/v2/warehouse/counts/' + uuid(id), referenceCount)
