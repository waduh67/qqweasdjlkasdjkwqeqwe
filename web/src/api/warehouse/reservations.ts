import { timestamp } from './approvals'
import { array, decimal, integer, nullable, oneOf, record, text, uuid, WarehouseDataError } from './codec'
import { baseUnit, DEMAND_STATES } from './materialModels'
import { query } from './transport'

function demandSupply(value: unknown, path = 'supply') {
  const row = record(value, path)
  const requestedBase = decimal(row.requestedBase, path), reservedUnpickedBase = decimal(row.reservedUnpickedBase, path), reservedPickedBase = decimal(row.reservedPickedBase, path), issuedBase = decimal(row.issuedBase, path), backorderBase = decimal(row.backorderBase, path), totalReservedBase = decimal(row.totalReservedBase, path)
  if (BigInt(requestedBase) !== BigInt(reservedUnpickedBase) + BigInt(reservedPickedBase) + BigInt(issuedBase) + BigInt(backorderBase) || BigInt(totalReservedBase) !== BigInt(reservedUnpickedBase) + BigInt(reservedPickedBase)) throw new WarehouseDataError(path)
  return { requestedBase, reservedUnpickedBase, reservedPickedBase, issuedBase, backorderBase, totalReservedBase, baseUnit: baseUnit(row.baseUnit, path), demandState: oneOf(row.demandState, DEMAND_STATES, path) }
}
export function reservationAllocation(value: unknown, path = 'allocation') {
  const row = record(value, path)
  return { id: uuid(row.allocationId, path), reservationId: uuid(row.reservationId, path), reservationRevision: integer(row.reservationRevision, path),
    documentId: uuid(row.documentId, path), documentRevision: integer(row.documentRevision, path), demandLineId: uuid(row.demandLineId, path), planLineId: uuid(row.planLineId, path), planRevision: integer(row.planRevision, path), workOrderId: uuid(row.workOrderId, path),
    stockIdentityId: uuid(row.stockIdentityId, path), lotId: nullable(row.lotId, uuid, path), skuId: uuid(row.skuId, path), locationId: uuid(row.locationId, path), stockRevision: integer(row.stockRevision, path),
    reservedUnpickedBase: decimal(row.reservedUnpickedBase, path), reservedPickedBase: decimal(row.reservedPickedBase, path), baseUnit: baseUnit(row.baseUnit, path), state: text(row.state, path), expiresAt: timestamp(row.expiresAt, path),
    skuCode: nullable(row.skuCode, text, path), skuName: nullable(row.skuName, text, path), serial: nullable(row.serial, text, path), lotCode: nullable(row.lotCode, text, path), locationName: nullable(row.locationName, text, path),
    demandSupply: demandSupply(row.demandSupply, path) }
}
export type ReservationAllocation = ReturnType<typeof reservationAllocation>
// This legacy endpoint returns an unpaged list, including historical released links.
// Keep every returned row; never treat the codec's normal page cap as a server limit.
export const listAllocations = (workOrderId: string) => query(`/api/v1/warehouse/material-requests/allocations/${uuid(workOrderId)}`, (value, path) => array(value, reservationAllocation, path, Number.MAX_SAFE_INTEGER))
