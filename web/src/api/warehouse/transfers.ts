import { timestamp } from './approvals'
import { array, boolean, decimal, integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { baseUnit } from './materialModels'
import { CONDITIONS, LEGAL_OWNERS } from './models'
import type { BaseUnit } from './quantity'
import { command, parameters, query } from './transport'

export const TRANSFER_STATES = ['DRAFT', 'DISPATCHED', 'PART_RECEIVED', 'RECEIVED', 'DISCREPANCY'] as const
export type TransferState = typeof TRANSFER_STATES[number]
export interface TransferFilter { page?: number; size?: number; state?: TransferState; locationId?: string; skuId?: string; serial?: string; query?: string; from?: string; until?: string }
export interface TransferDraft {
  sourceLocationId: string; destinationLocationId: string; transitLocationId: string; receiverId: string; reason: string;
  lines: { stockIdentityId: string; quantityBase: string; baseUnit: BaseUnit; sourceBalanceId: string }[];
}
export interface TransferReceipt { expectedRevision: number; evidenceReference: string; lines: { lineId: string; quantityBase: string; baseUnit: BaseUnit }[] }
export interface TransferDiscrepancy { expectedRevision: number; action: 'LOST' | 'REJECTED'; destinationLocationId: string; reason: string; evidenceReference: string }

function transferLine(value: unknown, path = 'line') {
  const row = record(value, path)
  return { id: uuid(row.id, path), skuId: uuid(row.skuId, path), stockIdentityId: uuid(row.stockIdentityId, path), baseUnit: baseUnit(row.baseUnit, path),
    quantityBase: decimal(row.quantityBase, path), receivedBase: decimal(row.receivedBase, path), inTransitBase: decimal(row.inTransitBase, path), resolvedBase: decimal(row.resolvedBase ?? '0', path),
    remainingIdentityId: nullable(row.remainingIdentityId, uuid, path), condition: oneOf(row.condition, CONDITIONS, path), legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path) }
}
export type TransferLine = ReturnType<typeof transferLine>
export function transferView(value: unknown, path = 'transfer') {
  const row = record(value, path), state = oneOf(row.state, TRANSFER_STATES, path), lines = array(row.lines, transferLine, path, 100)
  if (!lines.length || new Set(lines.map(line => line.id)).size !== lines.length) throw new WarehouseDataError(path)
  for (const line of lines) {
    const requested = BigInt(line.quantityBase), received = BigInt(line.receivedBase), transit = BigInt(line.inTransitBase), resolved = BigInt(line.resolvedBase)
    if (requested <= 0n || (state === 'DRAFT' ? received + transit + resolved !== 0n : received + transit + resolved !== requested) ||
      (transit > 0n && !line.remainingIdentityId)) throw new WarehouseDataError(path)
  }
  const sourceLocationId = uuid(row.sourceLocationId, path), destinationLocationId = uuid(row.destinationLocationId, path), transitLocationId = uuid(row.transitLocationId, path)
  if (new Set([sourceLocationId, destinationLocationId, transitLocationId]).size !== 3) throw new WarehouseDataError(path)
  return { id: uuid(row.id, path), code: text(row.code, path), revision: integer(row.revision, path), state, sourceLocationId, destinationLocationId, transitLocationId,
    senderId: uuid(row.senderId, path), receiverId: uuid(row.receiverId, path), reason: text(row.reason, path), recordedAt: timestamp(row.recordedAt, path), lines,
    resolutionDocumentId: nullable(row.resolutionDocumentId, uuid, path) }
}
export type WarehouseTransfer = ReturnType<typeof transferView>
function locationRef(value: unknown, path = 'location') {
  const row = record(value, path)
  return { id: uuid(row.id, path), code: text(row.code, path), name: nullable(row.name, text, path) }
}
function personRef(value: unknown, path = 'person') {
  const row = record(value, path)
  return { id: uuid(row.id, path), name: nullable(row.name, text, path) }
}
function lineRef(value: unknown, path = 'line') {
  const row = record(value, path)
  return { lineId: uuid(row.lineId, path), skuCode: nullable(row.skuCode, text, path), skuName: nullable(row.skuName, text, path), serial: nullable(row.serial, text, path), lotCode: nullable(row.lotCode, text, path) }
}
export function transferDetails(value: unknown, path = 'details') {
  const row = record(value, path), transfer = transferView(row.transfer, path), refs = record(row.references, path)
  const locations = array(refs.locations, locationRef, path, 3), people = array(refs.people, personRef, path, 2), lines = array(refs.lines, lineRef, path, 100)
  if (new Set(locations.map(row => row.id)).size !== 3 || [transfer.sourceLocationId, transfer.destinationLocationId, transfer.transitLocationId].some(id => !locations.some(row => row.id === id)) ||
    new Set(people.map(row => row.id)).size !== people.length || [transfer.senderId, transfer.receiverId].some(id => !people.some(row => row.id === id)) ||
    lines.length !== transfer.lines.length || new Set(lines.map(row => row.lineId)).size !== lines.length || lines.some(row => !transfer.lines.some(line => line.id === row.lineId))) throw new WarehouseDataError(path)
  return { transfer, references: { locations, people, lines } }
}
export type TransferDetails = ReturnType<typeof transferDetails>

export function transferRecovery(value: unknown, path = 'recovery') {
  const row = record(value, path), canReport = boolean(row.canReport, path)
  const block = nullable(row.block, text, path), resolutionDocumentId = nullable(row.resolutionDocumentId, uuid, path)
  if (canReport !== (block === null) || (canReport && resolutionDocumentId === null)) throw new WarehouseDataError(path)
  return { transferId: uuid(row.transferId, path), revision: integer(row.revision, path), resolutionDocumentId, canReport, block }
}

const root = '/api/v1/warehouse/transfers'
export const listTransfers = (filter: TransferFilter = {}) => query(`${root}${parameters({ ...filter })}`, pageOf(transferDetails))
export const getTransfer = (id: string) => query(`${root}/${uuid(id)}/details`, transferDetails)
export const getTransferRecovery = (id: string) => query(`${root}/${uuid(id)}/discrepancy/recovery`, transferRecovery)
export const transferHistory = (id: string, page = 0) => query(`${root}/${uuid(id)}/history/page${parameters({ page, size: 25 })}`, pageOf(transferView))
export const createTransfer = (input: TransferDraft) => command(root, 'POST', input, transferView)
export const dispatchTransfer = (id: string, expectedRevision: number) => command(`${root}/${uuid(id)}/dispatch`, 'POST', { expectedRevision }, transferView)
export const receiveTransfer = (id: string, input: TransferReceipt) => command(`${root}/${uuid(id)}/receive`, 'POST', input, transferView)
export const reportTransferDiscrepancy = (id: string, input: TransferDiscrepancy) => command(`${root}/${uuid(id)}/discrepancy`, 'POST', input, transferView)
