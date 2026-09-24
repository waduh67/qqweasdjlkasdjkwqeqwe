import { array, boolean, decimal, integer, nullable, oneOf, record, text, uuid, WarehouseDataError } from './codec'
import { formatBaseQuantity, type BaseUnit } from './quantity'

export const TRACKING = ['SERIAL', 'LOT', 'BULK'] as const
export const MASTER_STATES = ['ACTIVE', 'ARCHIVED'] as const
export const OWNERSHIP = ['LOAN', 'SALE'] as const
export const LOCATION_KINDS = ['WAREHOUSE', 'BIN', 'VEHICLE', 'TECHNICIAN', 'CUSTOMER_SITE', 'QUARANTINE', 'LOST', 'DISPOSED', 'TRANSIT'] as const
export const STOCK_STATES = ['AVAILABLE', 'RESERVED', 'PICKED', 'ISSUED', 'IN_TRANSIT', 'CONSUMED', 'RETURNED', 'QUARANTINE', 'LOST', 'DISPOSED', 'RECEIPT_SOURCE', 'CUSTOMER_INSTALLED'] as const
export const CONDITIONS = ['SERVICEABLE', 'QUARANTINE', 'DAMAGED', 'SCRAP'] as const
export const LEGAL_OWNERS = ['ISP', 'CUSTOMER', 'UNKNOWN'] as const

export interface Quantity { quantityBase: string; baseUnit: BaseUnit; displayQuantity: string; displayUnit: 'M' | 'EA' }
export function quantity(value: unknown, path = 'quantity'): Quantity {
  const row = record(value, path)
  const quantityBase = decimal(row.quantityBase, `${path}.quantityBase`)
  const baseUnit = oneOf(row.baseUnit, ['EA', 'MM'], `${path}.baseUnit`)
  const displayQuantity = text(row.displayQuantity, `${path}.displayQuantity`)
  const displayUnit = oneOf(row.displayUnit, ['M', 'EA'], `${path}.displayUnit`)
  if (displayQuantity !== formatBaseQuantity(quantityBase, baseUnit, '.') || displayUnit !== (baseUnit === 'MM' ? 'M' : 'EA')) throw new WarehouseDataError(path)
  return { quantityBase, baseUnit, displayQuantity, displayUnit }
}

function master(value: unknown, path: string) {
  const row = record(value, path)
  return { row, id: uuid(row.id, `${path}.id`), revision: integer(row.revision, `${path}.revision`),
    state: oneOf(row.state, MASTER_STATES, `${path}.state`), code: text(row.code, `${path}.code`) }
}

export function sku(value: unknown, path = 'sku') {
  const { row, ...base } = master(value, path)
  const tracking = oneOf(row.tracking, TRACKING, `${path}.tracking`)
  const baseUnit = oneOf(row.baseUnit, ['EA', 'MM'], `${path}.baseUnit`)
  if (tracking === 'SERIAL' && baseUnit !== 'EA') throw new WarehouseDataError(path)
  const allowedOwnershipModes = array(row.allowedOwnershipModes, (v, p) => oneOf(v, OWNERSHIP, p), `${path}.allowedOwnershipModes`, 2)
  if (allowedOwnershipModes.length === 0 || new Set(allowedOwnershipModes).size !== allowedOwnershipModes.length) throw new WarehouseDataError(`${path}.allowedOwnershipModes`)
  return { ...base, name: text(row.name, `${path}.name`), tracking, baseUnit,
    category: nullable(row.category, text, `${path}.category`), model: nullable(row.model, text, `${path}.model`),
    allowedOwnershipModes,
    inspectionRequired: boolean(row.inspectionRequired, `${path}.inspectionRequired`), minimumQuantityBase: decimal(row.minimumQuantityBase, `${path}.minimumQuantityBase`) }
}
export type WarehouseSku = ReturnType<typeof sku>

export function location(value: unknown, path = 'location') {
  const { row, ...base } = master(value, path)
  return { ...base, name: nullable(row.name, text, `${path}.name`), kind: oneOf(row.kind, LOCATION_KINDS, `${path}.kind`),
    parentLocationId: nullable(row.parentLocationId, uuid, `${path}.parentLocationId`), siteId: nullable(row.siteId, uuid, `${path}.siteId`),
    areaId: nullable(row.areaId, uuid, `${path}.areaId`), custodianId: nullable(row.custodianId, uuid, `${path}.custodianId`),
    issueEligible: boolean(row.issueEligible, `${path}.issueEligible`) }
}
export type WarehouseLocation = ReturnType<typeof location>

export function supplier(value: unknown, path = 'supplier') {
  const { row, ...base } = master(value, path)
  return { ...base, name: text(row.name, `${path}.name`), contactReference: nullable(row.contactReference, text, `${path}.contactReference`) }
}
export type WarehouseSupplier = ReturnType<typeof supplier>

export function stockRow(value: unknown, path = 'stock') {
  const row = record(value, path)
  const physical = quantity(row.physical, `${path}.physical`)
  const reservedUnpicked = quantity(row.reservedUnpicked, `${path}.reservedUnpicked`)
  const reservedPicked = quantity(row.reservedPicked, `${path}.reservedPicked`)
  const available = quantity(row.available, `${path}.available`)
  if ([reservedUnpicked, reservedPicked, available].some(q => q.baseUnit !== physical.baseUnit)) throw new WarehouseDataError(path)
  const bucket = (value: unknown, field: string, keys: readonly string[]) => Object.fromEntries(Object.entries(record(value, field))
    .map(([key, amount]) => [oneOf(key, keys, field), decimal(amount, `${field}.${key}`)]))
  return { id: uuid(row.id, `${path}.id`), skuId: uuid(row.skuId, `${path}.skuId`), skuCode: text(row.skuCode, `${path}.skuCode`),
    name: text(row.name, `${path}.name`), tracking: oneOf(row.tracking, TRACKING, `${path}.tracking`), physical, reservedUnpicked, reservedPicked, available,
    statusBuckets: bucket(row.statusBuckets, `${path}.statusBuckets`, STOCK_STATES), conditionBuckets: bucket(row.conditionBuckets, `${path}.conditionBuckets`, CONDITIONS), ownerBuckets: bucket(row.ownerBuckets, `${path}.ownerBuckets`, LEGAL_OWNERS) }
}
export type WarehouseStock = ReturnType<typeof stockRow>

export function identityLookup(value: unknown, path = 'lookup') {
  const row = record(value, path)
  return { assetId: uuid(row.assetId, `${path}.assetId`), skuId: nullable(row.skuId, uuid, `${path}.skuId`), serial: text(row.serial, `${path}.serial`),
    mac: nullable(row.mac, text, `${path}.mac`), locationId: uuid(row.locationId, `${path}.locationId`), legacyUnresolved: boolean(row.legacyUnresolved, `${path}.legacyUnresolved`) }
}
export type WarehouseIdentity = ReturnType<typeof identityLookup>
