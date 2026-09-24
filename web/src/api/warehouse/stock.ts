import { array, boolean, decimal, integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { timestamp } from './approvals'
import { CONDITIONS, LEGAL_OWNERS, STOCK_STATES, TRACKING, quantity } from './models'
import { parameters, query } from './transport'

export const CUSTODIAN_KINDS = ['WAREHOUSE', 'VEHICLE', 'TECHNICIAN', 'CUSTOMER', 'REPAIR', 'TRANSIT', 'LOST', 'DISPOSED'] as const
export const ADMISSIONS = ['VERIFIED', 'LEGACY_UNRESOLVED'] as const
export const ASSET_STATES = [...STOCK_STATES, 'INSTALLED', 'PROVISIONAL'] as const
export interface StockFilter { page?: number; size?: number; sort?: 'name' | 'createdAt' | 'id'; direction?: 'asc' | 'desc'; skuId?: string; serial?: string; locationId?: string; status?: string; condition?: string; owner?: string; from?: string; until?: string }
export const STOCK_BUCKETS = ['AVAILABLE', 'RESERVED', 'PICKED', 'TECHNICIAN', 'TRANSIT', 'INSTALLED', 'QUARANTINE'] as const
export interface PositionFilter extends StockFilter { bucket?: typeof STOCK_BUCKETS[number] }

export function stockCost(value: unknown, path = 'cost') {
  const row = record(value, path), state = oneOf(row.state, ['KNOWN', 'UNKNOWN'], path)
  if (state === 'UNKNOWN') {
    if ([row.totalMinor, row.costBasisQuantityBase, row.currency].some(v => v !== null && v !== undefined)) throw new WarehouseDataError(path)
    return { state } as const
  }
  const totalMinor = decimal(row.totalMinor, path), costBasisQuantityBase = decimal(row.costBasisQuantityBase, path), currency = text(row.currency, path)
  if (BigInt(costBasisQuantityBase) <= 0n || !/^[A-Z]{3}$/.test(currency)) throw new WarehouseDataError(path)
  return { state, totalMinor, costBasisQuantityBase, currency } as const
}
export type StockCost = ReturnType<typeof stockCost>
function origin(value: unknown, path = 'origin') {
  const row = record(value, path)
  return { documentId: uuid(row.documentId, path), documentCode: text(row.documentCode, path), kind: text(row.kind, path), lineId: uuid(row.lineId, path),
    customerLabelSnapshot: nullable(row.customerLabelSnapshot, text, path), workOrderCodeSnapshot: nullable(row.workOrderCodeSnapshot, text, path) }
}
export type StockOrigin = ReturnType<typeof origin>
export function position(value: unknown, path = 'position') {
  const row = record(value, path)
  const physical = quantity(row.physical, path), reservedUnpicked = quantity(row.reservedUnpicked, path), reservedPicked = quantity(row.reservedPicked, path), available = quantity(row.available, path)
  if ([reservedUnpicked, reservedPicked, available].some(q => q.baseUnit !== physical.baseUnit)) throw new WarehouseDataError(path)
  return { id: uuid(row.id, path), skuId: uuid(row.skuId, path), skuCode: text(row.skuCode, path), name: text(row.name, path), tracking: oneOf(row.tracking, TRACKING, path),
    stockIdentityId: uuid(row.stockIdentityId, path), lotId: nullable(row.lotId, uuid, path), serial: nullable(row.serial, text, path),
    locationId: uuid(row.locationId, path), locationName: nullable(row.locationName, text, path), custodianId: uuid(row.custodianId, path), custodianKind: oneOf(row.custodianKind, CUSTODIAN_KINDS, path),
    condition: oneOf(row.condition, CONDITIONS, path), legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path), status: oneOf(row.status, STOCK_STATES, path),
    physical, reservedUnpicked, reservedPicked, available }
}
export type StockPosition = ReturnType<typeof position>
export function stockAsset(value: unknown, path = 'asset') {
  const row = record(value, path), admission = oneOf(row.admission, ADMISSIONS, path), rawQuantity = record(row.quantity, path)
  const amount = admission === 'LEGACY_UNRESOLVED' && rawQuantity.baseUnit === null ? null : quantity(rawQuantity, path)
  return { id: uuid(row.id, path), assetId: uuid(row.assetId, path), skuId: nullable(row.skuId, uuid, path), skuCode: nullable(row.skuCode, text, path), name: nullable(row.name, text, path),
    serial: text(row.serial, path), mac: nullable(row.mac, text, path), status: oneOf(row.status, ASSET_STATES, path), condition: oneOf(row.condition, CONDITIONS, path), legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path),
    locationId: uuid(row.locationId, path), locationName: nullable(row.locationName, text, path), custodianId: uuid(row.custodianId, path), custodianKind: oneOf(row.custodianKind, CUSTODIAN_KINDS, path),
    quantity: amount, rawQuantityBase: nullable(rawQuantity.quantityBase, decimal, path), admission, installedOnuId: nullable(row.installedOnuId, uuid, path), origin: nullable(row.origin, origin, path),
    cost: nullable(row.cost, stockCost, path) }
}
export type StockAsset = ReturnType<typeof stockAsset>
export function stockLot(value: unknown, path = 'lot') {
  const row = record(value, path)
  return { id: uuid(row.id, path), skuId: uuid(row.skuId, path), code: text(row.code, path), name: text(row.name, path), received: quantity(row.received, path),
    receivedAt: timestamp(row.receivedAt, path), admission: oneOf(row.admission, ADMISSIONS, path), origin: nullable(row.origin, origin, path), cost: nullable(row.cost, stockCost, path) }
}
export type StockLot = ReturnType<typeof stockLot>
export function lotDetail(value: unknown, path = 'lot') {
  const row = record(record(value, path).conservation, `${path}.conservation`)
  return { ...stockLot(value, path), conservation: { consistent: boolean(row.consistent, path), physicalQuantityBase: decimal(row.physicalQuantityBase, path), rootQuantityBase: decimal(row.rootQuantityBase, path),
    activeQuantityBase: decimal(row.activeQuantityBase, path), terminalQuantityBase: decimal(row.terminalQuantityBase, path), rootCount: integer(row.rootCount, path), splitCount: integer(row.splitCount, path) } }
}
export type StockLotDetail = ReturnType<typeof lotDetail>
export function stockSegment(value: unknown, path = 'segment') {
  const row = record(value, path), children = array(row.children, uuid, path, 100), childCount = integer(row.childCount, path)
  if (children.length > childCount) throw new WarehouseDataError(path)
  return { id: uuid(row.id, path), stockIdentityId: uuid(row.stockIdentityId, path), lotId: uuid(row.lotId, path), parentSegmentId: nullable(row.parentSegmentId, uuid, path),
    kind: oneOf(row.kind, ['SERIAL', 'REEL', 'CUT', 'REMNANT', 'BULK'], path), state: oneOf(row.state, ['ACTIVE', 'SPLIT', 'RETIRED'], path), quantity: quantity(row.quantity, path),
    createdAt: timestamp(row.createdAt, path), origin: nullable(row.origin, origin, path), children, childCount, conserved: boolean(row.conserved, path) }
}
export type StockSegment = ReturnType<typeof stockSegment>
export function unknownStock(value: unknown, path = 'unknownStock') {
  const row = record(value, path)
  if (row.available !== false) throw new WarehouseDataError(path)
  return { id: uuid(row.id, path), source: oneOf(row.source, ['BALANCE', 'ASSET'], path), skuId: nullable(row.skuId, uuid, path), name: nullable(row.name, text, path), locationId: uuid(row.locationId, path),
    status: oneOf(row.status, ASSET_STATES, path), rawQuantity: nullable(row.rawQuantity, text, path), quantityBase: nullable(row.quantityBase, decimal, path), baseUnit: nullable(row.baseUnit, (v, p) => oneOf(v, ['EA', 'MM'], p), path),
    legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path), admission: oneOf(row.admission, ADMISSIONS, path), serial: nullable(row.serial, text, path), available: false as const, reason: text(row.reason, path) }
}
export type UnknownStock = ReturnType<typeof unknownStock>

export function stockEvent(value: unknown, path = 'event') {
  const row = record(value, path), kind = oneOf(row.kind, ['MOVEMENT_LEG', 'INSPECTION', 'RESERVATION', 'MATERIAL_FACT'], path)
  const common = { id: text(row.id, path), recordedAt: timestamp(row.recordedAt, path), stockIdentityId: uuid(row.stockIdentityId, path) }
  if (kind !== 'RESERVATION') uuid(common.id, path)
  switch (kind) {
    case 'MOVEMENT_LEG': return { ...common, kind, postingId: uuid(row.postingId, path), movementKind: text(row.movementKind, path), documentId: uuid(row.documentId, path), documentCode: text(row.documentCode, path), documentRevision: integer(row.documentRevision, path),
      lineId: nullable(row.lineId, uuid, path), operationId: uuid(row.operationId, path), compensatesPostingId: nullable(row.compensatesPostingId, uuid, path), direction: oneOf(row.direction, ['IN', 'OUT'], path),
      locationId: uuid(row.locationId, path), currentLocationName: nullable(row.currentLocationName, text, path), custodianId: uuid(row.custodianId, path), custodianKind: oneOf(row.custodianKind, CUSTODIAN_KINDS, path),
      condition: oneOf(row.condition, CONDITIONS, path), legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path), status: oneOf(row.status, STOCK_STATES, path), quantity: quantity(row.quantity, path),
      customerLabelSnapshot: nullable(row.customerLabelSnapshot, text, path), workOrderCodeSnapshot: nullable(row.workOrderCodeSnapshot, text, path) }
    case 'INSPECTION': return { ...common, kind, inspectionId: uuid(row.inspectionId, path), operationId: uuid(row.operationId, path), documentId: uuid(row.documentId, path), lineId: uuid(row.lineId, path),
      sourceStockIdentityId: uuid(row.sourceStockIdentityId, path), disposition: oneOf(row.disposition, ['ACCEPTED', 'QUARANTINE', 'SUPPLIER_RETURN'], path), quantity: quantity(row.quantity, path) }
    case 'RESERVATION': {
      const eventId = uuid(row.eventId, path), reservationId = uuid(row.reservationId, path)
      if (common.id !== `${eventId}:${reservationId}`) throw new WarehouseDataError(path)
      return { ...common, kind, eventId, eventKind: text(row.eventKind, path), reservationId, documentId: uuid(row.documentId, path), documentRevision: integer(row.documentRevision, path), operationId: uuid(row.operationId, path),
        reservedUnpickedBase: decimal(row.reservedUnpickedBase, path), reservedPickedBase: decimal(row.reservedPickedBase, path), baseUnit: oneOf(row.baseUnit, ['EA', 'MM'], path), state: text(row.state, path) }
    }
    case 'MATERIAL_FACT': return { ...common, kind, postingId: uuid(row.postingId, path), installed: boolean(row.installed, path), returned: boolean(row.returned, path), useRevision: integer(row.useRevision, path), compensationId: nullable(row.compensationId, uuid, path), quantity: quantity(row.quantity, path) }
  }
}
export type StockEvent = ReturnType<typeof stockEvent>
const root = '/api/v1/warehouse'
export const listPositions = (filter: PositionFilter = {}) => query(`${root}/stock/positions${parameters({ ...filter })}`, pageOf(position))
export const getPosition = (id: string) => query(`${root}/stock/positions/${uuid(id)}`, position)
export const listAssets = (filter: StockFilter = {}) => query(`${root}/assets${parameters({ ...filter })}`, pageOf(stockAsset))
export const getStockAsset = (id: string) => query(`${root}/assets/${uuid(id)}`, stockAsset)
export const listLots = (filter: StockFilter = {}) => query(`${root}/lots${parameters({ ...filter })}`, pageOf(stockLot))
export const getLot = (id: string) => query(`${root}/lots/${uuid(id)}`, lotDetail)
export const listSegments = (lotId: string, filter: StockFilter = {}) => query(`${root}/lots/${uuid(lotId)}/segments${parameters({ ...filter })}`, pageOf(stockSegment))
export const getSegment = (lotId: string, segmentId: string) => query(`${root}/lots/${uuid(lotId)}/segments/${uuid(segmentId)}`, stockSegment)
export const listUnknownStock = (filter: StockFilter = {}) => query(`${root}/stock/unknown${parameters({ ...filter })}`, pageOf(unknownStock))
export const stockHistory = (resource: 'assets' | 'lots' | 'stock/positions', id: string, filter: StockFilter = {}) => query(`${root}/${resource}/${uuid(id)}/history${parameters({ ...filter })}`, pageOf(stockEvent))
