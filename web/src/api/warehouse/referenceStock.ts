import { nullable, oneOf, pageOf, plainText, record, text, timestamp, uuid, decimal } from './codec'
import { referencePosition } from './reference'
import { parameters, query } from './transport'

const root = '/api/v2/warehouse'
export interface ReferencePositionFilter {
  page?: number; size?: number; search?: string; locationId?: string;
  holderKind?: 'WAREHOUSE' | 'TECHNICIAN' | 'VEHICLE' | 'CUSTOMER'; availableOnly?: boolean;
}
export const listReferencePositions = (skuId: string, filter: ReferencePositionFilter = {}) =>
  query(`${root}/stock/${uuid(skuId)}/positions${parameters({ ...filter, availableOnly: filter.availableOnly === undefined ? undefined : String(filter.availableOnly) })}`, pageOf(referencePosition))

export function referenceHistory(value: unknown) {
  const row = record(value)
  return { id: uuid(row.id), operationId: uuid(row.operationId), documentId: uuid(row.documentId), kind: text(row.kind), recordedAt: timestamp(row.recordedAt),
    actorName: text(row.actorName), notes: plainText(row.notes), locationId: uuid(row.locationId), locationName: text(row.locationName),
    holderName: text(row.holderName), direction: oneOf(row.direction, ['IN', 'OUT']), quantityBase: decimal(row.quantityBase),
    baseUnit: oneOf(row.baseUnit, ['EA', 'MM']), serial: nullable(row.serial, text, 'serial'), mac: nullable(row.mac, text, 'mac') }
}
export const listReferenceHistory = (skuId: string, page = 0) =>
  query(`${root}/stock/${uuid(skuId)}/history${parameters({ page })}`, pageOf(referenceHistory))
