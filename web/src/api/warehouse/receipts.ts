import { api } from '../client'
import { array, boolean, decimal, integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { timestamp } from './approvals'
import { CONDITIONS, LEGAL_OWNERS, STOCK_STATES, TRACKING } from './models'
import type { BaseUnit } from './quantity'
import { command, parameters, query, uploadCommand } from './transport'

export const RECEIPT_STATES = ['DRAFT', 'RECEIVED_IN_INSPECTION', 'PUTAWAY', 'CLOSED'] as const
export interface ReceiptSerialInput { serial: string; mac?: string | null }
export interface ReceiptConversion { numerator: string; denominator: string; packageQuantity: string }
export interface ReceiptCost { totalMinor: string; currency: string }
export interface ReceiptDraftLineInput { skuId: string; quantityBase: string; serials?: ReceiptSerialInput[]; lotCode?: string | null; conversion?: ReceiptConversion | null; cost?: ReceiptCost | null }
export interface ReceiptDraftInput { supplierId: string; externalReference: string; sourceLocationId: string; inspectionLocationId: string; lines: ReceiptDraftLineInput[]; expectedRevision?: number }
export interface ReceiptInspectionInput { lineId: string; stockIdentityId: string; baseUnit: BaseUnit; acceptedBase: string; rejectedBase: string; evidenceId: string; reason: string; rejectedDisposition: 'QUARANTINE' | 'SUPPLIER_RETURN' }
export interface ReceiptPutawayInput { lineId: string; stockIdentityId: string; quantityBase: string; baseUnit: BaseUnit }

function conversion(value: unknown, path = 'conversion'): ReceiptConversion {
  const row = record(value, path)
  const result = { numerator: decimal(row.numerator, path), denominator: decimal(row.denominator, path), packageQuantity: decimal(row.packageQuantity, path) }
  if (Object.values(result).some(v => BigInt(v) <= 0n)) throw new WarehouseDataError(path)
  return result
}
function cost(value: unknown, path = 'cost') {
  const row = record(value, path), currency = text(row.currency, path), basis = decimal(row.costBasisQuantityBase, path)
  if (!/^[A-Z]{3}$/.test(currency) || BigInt(basis) <= 0n) throw new WarehouseDataError(path)
  return { totalMinor: decimal(row.totalMinor, path), currency, costBasisQuantityBase: basis }
}
function piece(value: unknown, path = 'piece') {
  const row = record(value, path)
  return { stockIdentityId: uuid(row.stockIdentityId, path), lotId: nullable(row.lotId, uuid, path), quantityBase: decimal(row.quantityBase, path),
    revision: integer(row.revision, path), disposition: nullable(row.disposition, (v, p) => oneOf(v, ['ACCEPTED', 'QUARANTINE', 'SUPPLIER_RETURN'], p), path),
    locationId: uuid(row.locationId, path), condition: oneOf(row.condition, CONDITIONS, path), legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path),
    status: oneOf(row.status, STOCK_STATES, path), custodianId: uuid(row.custodianId, path), custodianKind: oneOf(row.custodianKind, ['WAREHOUSE', 'VEHICLE', 'TECHNICIAN', 'CUSTOMER', 'REPAIR', 'TRANSIT', 'LOST', 'DISPOSED'], path) }
}
function line(value: unknown, path = 'line') {
  const row = record(value, path)
  const result = { id: uuid(row.id, path), inputLineNumber: integer(row.inputLineNumber, path), skuId: uuid(row.skuId, path), skuCode: text(row.skuCode, path), skuName: text(row.skuName, path),
    tracking: oneOf(row.tracking, TRACKING, path), baseUnit: oneOf(row.baseUnit, ['EA', 'MM'], path), quantityBase: decimal(row.quantityBase, path),
    serial: nullable(row.serial, text, path), mac: nullable(row.mac, text, path), lotCode: nullable(row.lotCode, text, path), inspectionRequired: boolean(row.inspectionRequired, path),
    conversion: nullable(row.conversion, conversion, path), cost: nullable(row.cost, cost, path), pieces: array(row.pieces, piece, path, 10000),
    acceptedBase: decimal(row.acceptedBase, path), rejectedBase: decimal(row.rejectedBase, path), putawayBase: decimal(row.putawayBase, path) }
  if (result.inputLineNumber < 1 || BigInt(result.quantityBase) <= 0n || (result.tracking === 'SERIAL' && (result.baseUnit !== 'EA' || result.quantityBase !== '1' || !result.serial))) throw new WarehouseDataError(path)
  return result
}
function inspection(value: unknown, path = 'inspection') {
  const row = record(value, path)
  return { id: uuid(row.id, path), lineId: uuid(row.lineId, path), acceptedBase: decimal(row.acceptedBase, path), rejectedBase: decimal(row.rejectedBase, path), baseUnit: oneOf(row.baseUnit, ['EA', 'MM'], path),
    evidenceId: uuid(row.evidenceId, path), reason: text(row.reason, path), disposition: text(row.disposition, path), operationId: uuid(row.operationId, path) }
}
export function receipt(value: unknown, path = 'receipt') {
  const row = record(value, path)
  return { id: uuid(row.id, path), revision: integer(row.revision, path), state: oneOf(row.state, RECEIPT_STATES, path), createdAt: timestamp(row.createdAt, path),
    supplierId: uuid(row.supplierId, path), supplierName: text(row.supplierName, path), externalReference: text(row.externalReference, path),
    sourceLocationId: uuid(row.sourceLocationId, path), inspectionLocationId: uuid(row.inspectionLocationId, path),
    // Original replay responses may predate the additive snapshot-name fields. Always reload after a command.
    sourceLocationName: nullable(row.sourceLocationName, text, path), inspectionLocationName: nullable(row.inspectionLocationName, text, path),
    costVisible: row.costVisible === undefined ? false : boolean(row.costVisible, path),
    lines: array(row.lines, line, path, 500), inspections: array(row.inspections, inspection, path, 10000) }
}
export type WarehouseReceipt = ReturnType<typeof receipt>
export type ReceiptLine = WarehouseReceipt['lines'][number]
export type ReceiptPiece = ReceiptLine['pieces'][number]
function history(value: unknown, path = 'history') {
  const row = record(value, path)
  return { operationId: uuid(row.operationId, path), revision: integer(row.revision, path), action: text(row.action, path), recordedAt: timestamp(row.recordedAt, path) }
}
export function receiptEvidence(value: unknown, path = 'evidence') {
  const row = record(value, path), sizeBytes = integer(row.sizeBytes, path), sha256 = text(row.sha256, path)
  if (sizeBytes < 1 || sizeBytes > 15728640 || !/^[a-f0-9]{64}$/.test(sha256)) throw new WarehouseDataError(path)
  return { id: uuid(row.id, path), documentId: uuid(row.documentId, path), contentType: oneOf(row.contentType, ['image/png', 'image/jpeg', 'application/pdf'], path), sizeBytes, sha256 }
}
function evidenceItem(value: unknown, path = 'evidence') {
  const row = record(value, path)
  return { ...receiptEvidence(value, path), createdAt: timestamp(row.createdAt, path), matchesCurrentIntake: boolean(row.matchesCurrentIntake, path) }
}
export type ReceiptEvidence = ReturnType<typeof evidenceItem>
export function receiptTransition(value: unknown, path = 'receiptTransition') {
  const row = record(value, path)
  return { id: uuid(row.id, path), revision: integer(row.revision, path), state: oneOf(row.state, RECEIPT_STATES, path), operationId: uuid(row.operationId, path) }
}
export type ReceiptTransition = ReturnType<typeof receiptTransition>
const root = '/api/v1/warehouse/receipts'
export const listReceipts = (filter: { page?: number; size?: number; status?: WarehouseReceipt['state']; serial?: string; skuId?: string; locationId?: string; from?: string; until?: string } = {}) => query(`${root}${parameters({ ...filter })}`, pageOf(receipt))
export const getReceipt = (id: string) => query(`${root}/${uuid(id)}`, receipt)
export const getReceiptHistory = (id: string) => query(`${root}/${uuid(id)}/history`, value => array(value, history, 'history', 10000))
export const listReceiptEvidence = (id: string, page = 0) => query(`${root}/${uuid(id)}/attachments${parameters({ page, size: 25 })}`, pageOf(evidenceItem))
export const saveReceipt = (input: ReceiptDraftInput, id?: string) => command(`${root}${id ? `/${uuid(id)}` : ''}`, id ? 'PUT' : 'POST', input, receipt)
export const receiveReceipt = (id: string, revision: number) => command(`${root}/${uuid(id)}/receive`, 'POST', { expectedRevision: revision }, receiptTransition)
export const inspectReceipt = (id: string, revision: number, lines: ReceiptInspectionInput[]) => command(`${root}/${uuid(id)}/inspect`, 'POST', { expectedRevision: revision, lines }, receiptTransition)
export const putawayReceipt = (id: string, revision: number, destinationLocationId: string, lines: ReceiptPutawayInput[]) => command(`${root}/${uuid(id)}/putaway`, 'POST', { expectedRevision: revision, destinationLocationId, lines }, receiptTransition)
export const attachReceipt = (id: string, revision: number, file: File) => uploadCommand(`${root}/${uuid(id)}/attachments`, revision, file, receiptEvidence)
export const downloadReceiptEvidence = (id: string, evidenceId: string) => api.blob(`${root}/${uuid(id)}/attachments/${uuid(evidenceId)}`)
