import { array, boolean, decimal, digest, integer, nullable, oneOf, pageOf, plainText, record, text, timestamp, uuid, type Decoder } from './codec'
import { location, sku, supplier } from './models'
import { captureCommandSession, command, parameters, query, uploadCommand, type WarehouseCommand } from './transport'

const root = '/api/v2/warehouse'
export function workflow(value: unknown) {
  const row = record(value), snapshot = record(row.snapshot)
  return { tenantId: uuid(snapshot.tenantId), epoch: integer(snapshot.epoch),
    state: oneOf(snapshot.state, ['LEGACY', 'VALIDATING', 'ENFORCED']),
    workflow: oneOf(snapshot.workflow, ['LEGACY', 'DRAINING', 'REFERENCE']), owner: boolean(row.owner) }
}
export const readWorkflow = () => query(`${root}/workflow`, workflow)
export const referenceSkus = (search = '', page = 0) => query(`${root}/skus${parameters({ search, page })}`, pageOf(sku))
export const referenceLocations = (search = '', page = 0) => query(`${root}/locations${parameters({ search, page })}`, pageOf(location))
export const referenceSuppliers = (search = '', page = 0) => query(`${root}/suppliers${parameters({ search, page })}`, pageOf(supplier))

export function referencePosition(value: unknown) {
  const row = record(value)
  return { stockIdentityId: uuid(row.stockIdentityId), skuId: uuid(row.skuId), skuCode: text(row.skuCode), skuName: text(row.skuName),
    tracking: oneOf(row.tracking, ['BULK', 'LOT', 'SERIAL']), baseUnit: oneOf(row.baseUnit, ['EA', 'MM']), quantityBase: decimal(row.quantityBase),
    locationId: uuid(row.locationId), locationName: text(row.locationName), holderId: uuid(row.holderId), holderName: text(row.holderName), holderEmail: nullable(row.holderEmail, text, 'holderEmail'),
    holderKind: oneOf(row.holderKind, ['WAREHOUSE', 'TECHNICIAN', 'VEHICLE', 'CUSTOMER']), status: text(row.status),
    serial: nullable(row.serial, text, 'serial'), mac: nullable(row.mac, text, 'mac'), revision: integer(row.revision) }
}
export type ReferencePosition = ReturnType<typeof referencePosition>
export const ownMaterials = (search = '', page = 0, size = 25) => query(`${root}/my-materials${parameters({ search, page, size })}`, pageOf(referencePosition))
export function referenceStock(value: unknown) {
  const row = record(value)
  return { sku: sku(row.sku), positions: array(row.positions, referencePosition), warehouses: array(row.warehouses, value => {
    const item = record(value)
    return { warehouseId: uuid(item.warehouseId), warehouseName: text(item.warehouseName), quantityBase: decimal(item.quantityBase) }
  }) }
}
export const getReferenceStock = (id: string) => query(`${root}/stock/${uuid(id)}?includePositions=false`, referenceStock)

export function workType(value: unknown) {
  const row = record(value)
  return { id: uuid(row.id), revision: integer(row.revision), name: text(row.name),
    workType: oneOf(row.workType, ['PSB', 'REPAIR', 'MIGRATION', 'DISMANTLE', 'PREVENTIVE']),
    materialRequired: boolean(row.materialRequired), photoSlots: array(row.photoSlots, text, 'photoSlots', 12),
    active: boolean(row.active), deleted: boolean(row.deleted) }
}
export const WORK_STATES = ['PENDING', 'BLOCKED', 'COMPLETED', 'CANCELLED'] as const
export function referenceWorkOrder(value: unknown) {
  const row = record(value)
  return { id: uuid(row.id), code: text(row.code), revision: integer(row.revision), type: workType(row.type), title: text(row.title),
    description: plainText(row.description), priority: oneOf(row.priority, ['LOW', 'NORMAL', 'HIGH', 'URGENT']),
    customerId: nullable(row.customerId, uuid, 'customerId'), technicianId: uuid(row.technicianId), technicianName: text(row.technicianName),
    areaId: uuid(row.areaId), scheduledAt: nullable(row.scheduledAt, timestamp, 'scheduledAt'), state: oneOf(row.state, WORK_STATES),
    assignmentGeneration: integer(row.assignmentGeneration), lastActivityAt: timestamp(row.lastActivityAt),
    blockedReason: nullable(row.blockedReason, plainText, 'blockedReason'), createdAt: timestamp(row.createdAt) }
}
export type ReferenceWorkOrder = ReturnType<typeof referenceWorkOrder>
export function referencePhoto(value: unknown) {
  const row = record(value)
  return { id: uuid(row.id), slot: text(row.slot), assignmentGeneration: integer(row.assignmentGeneration),
    uploadedBy: uuid(row.uploadedBy), uploadedByName: text(row.uploadedByName), contentType: text(row.contentType),
    sizeBytes: integer(row.sizeBytes), sha256: digest(row.sha256), receivedAt: timestamp(row.receivedAt), current: boolean(row.current) }
}
export type ReferencePhoto = ReturnType<typeof referencePhoto>
export function referenceCompletion(value: unknown) {
  const row = record(value)
  return { workOrderId: uuid(row.workOrderId), revision: integer(row.revision), assignmentGeneration: integer(row.assignmentGeneration),
    technicianId: uuid(row.technicianId), notes: plainText(row.notes), completedAt: timestamp(row.completedAt),
    documentId: nullable(row.documentId, uuid, 'documentId'), photos: array(row.photos, value => {
      const photo = record(value)
      return { id: uuid(photo.id), slot: text(photo.slot), sha256: digest(photo.sha256), sizeBytes: integer(photo.sizeBytes), contentType: text(photo.contentType) }
    }), materials: array(row.materials, value => {
      const material = record(value)
      return { lineId: uuid(material.lineId), stockIdentityId: uuid(material.stockIdentityId), consumedIdentityId: uuid(material.consumedIdentityId),
        skuId: uuid(material.skuId), skuName: text(material.skuName), quantityBase: decimal(material.quantityBase),
        baseUnit: oneOf(material.baseUnit, ['EA', 'MM']), tracking: oneOf(material.tracking, ['BULK', 'LOT', 'SERIAL']),
        serial: nullable(material.serial, text, 'serial'), mac: nullable(material.mac, text, 'mac') }
    }) }
}
export function referenceWorkDetail(value: unknown) {
  const row = record(value)
  return { workOrder: referenceWorkOrder(row.workOrder), overdue: boolean(row.overdue), overdueAt: timestamp(row.overdueAt),
    completion: nullable(row.completion, referenceCompletion, 'completion'), timeline: array(row.timeline, value => {
      const event = record(value)
      return { id: uuid(event.id), revision: integer(event.revision), action: text(event.action), actorName: text(event.actorName),
        notes: plainText(event.notes), recordedAt: timestamp(event.recordedAt) }
    }) }
}
export type ReferenceWorkDetail = ReturnType<typeof referenceWorkDetail>
export const listReferenceWork = (search = '', page = 0, state?: typeof WORK_STATES[number]) =>
  query(`/api/v2/work-orders${parameters({ search, page, state })}`, pageOf(referenceWorkOrder))
export const getReferenceWork = (id: string) => query(`/api/v2/work-orders/${uuid(id)}`, referenceWorkDetail)
export const referenceTypes = () => query('/api/v2/work-orders/types', value => array(value, workType))
export const referencePhotos = (id: string) => query(`/api/v2/work-orders/${uuid(id)}/evidence`, value => array(value, referencePhoto))

function sessionCommand<T>(captured: WarehouseCommand<T>): WarehouseCommand<T> {
  const check = captureCommandSession()
  return Object.freeze({ ...captured, execute() { check(); return captured.execute() } })
}
export function referenceCommand<T>(path: string, method: 'POST' | 'PUT', input: unknown, decode: Decoder<T>): WarehouseCommand<T> {
  return sessionCommand(command(path, method, input, decode))
}
export const referenceProgress = (id: string, revision: number, state: 'PENDING' | 'BLOCKED', notes: string) =>
  sessionCommand(command(`/api/v2/work-orders/${uuid(id)}/progress`, 'POST', { expectedRevision: revision, state, notes }, referenceWorkOrder))
export const completeReferenceWork = (id: string, revision: number, notes: string, materials: readonly { stockIdentityId: string; quantityBase: string }[]) =>
  sessionCommand(command(`/api/v2/work-orders/${uuid(id)}/complete`, 'POST', { expectedRevision: revision, notes, materials }, referenceWorkOrder))
export const uploadReferencePhoto = (id: string, revision: number, slot: string, file: File) =>
  sessionCommand(uploadCommand(`/api/v2/work-orders/${uuid(id)}/evidence`, revision, file, referenceWorkOrder, crypto.randomUUID(), slot))
