import { timestamp } from './approvals'
import { array, boolean, decimal, integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { baseUnit } from './materialModels'
import { CONDITIONS, LEGAL_OWNERS, TRACKING } from './models'
import type { ReceiptCost } from './receipts'
import { command, parameters, query } from './transport'

export const RETURN_STATES = ['DRAFT', 'DISPATCHED', 'RECEIVED_IN_INSPECTION', 'ACCEPTED', 'REPAIR', 'SUPPLIER_RETURN', 'SCRAP', 'LOST'] as const
export const RETURN_ORIGINS = ['MATERIAL_RESIDUAL', 'ASSET_REMOVAL'] as const
export const REPAIR_RESULTS = ['REPAIRED', 'UNREPAIRED'] as const
export interface ReturnFilter {
  page?: number; size?: number; origin?: typeof RETURN_ORIGINS[number]; state?: typeof RETURN_STATES[number];
  locationId?: string; skuId?: string; stockIdentityId?: string; owner?: typeof LEGAL_OWNERS[number]; serial?: string; query?: string; from?: string; until?: string;
}
export interface ReturnIntake { origin: typeof RETURN_ORIGINS[number]; sourceDocumentId: string; quarantineLocationId: string; evidenceReference: string }
export interface ReturnInspection {
  expectedRevision: number; measuredQuantityBase: string; condition: typeof CONDITIONS[number]; destinationLocationId: string;
  evidenceReference: string; resetConfirmed: boolean; observedSerial?: string | null; resetEvidenceReference?: string | null;
}
export interface RepairDispatch { expectedRevision: number; vendorId: string; repairLocationId: string; vendorReference: string; evidenceReference: string; observedSerial: string }
export interface RepairReceipt { expectedRevision: number; observedSerial: string; quarantineLocationId: string; result: typeof REPAIR_RESULTS[number]; vendorReference: string; evidenceReference: string }
export interface ReplacementInput { expectedRevision: number; externalReference: string; sourceLocationId: string; inspectionLocationId: string; skuId: string; serial: string; evidenceReference: string; mac?: string | null; cost?: ReceiptCost | null }
export interface ReacquisitionInput { expectedRevision: number; reason: string; titleTransferReference: string; evidenceId: string }
export interface RmaDispatch { expectedRevision: number; workOrderId: string; workOrderRevision: number; technicianId: string; transitLocationId: string; technicianLocationId: string; observedSerial: string; evidenceReference: string }

function inspection(value: unknown, path = 'inspection') {
  const row = record(value, path)
  return { expectedRevision: integer(row.expectedRevision, path), measuredQuantityBase: decimal(row.measuredQuantityBase, path),
    condition: oneOf(row.condition, CONDITIONS, path), destinationLocationId: uuid(row.destinationLocationId, path), evidenceReference: text(row.evidenceReference, path),
    resetConfirmed: boolean(row.resetConfirmed, path), observedSerial: nullable(row.observedSerial, text, path), resetEvidenceReference: nullable(row.resetEvidenceReference, text, path) }
}
function repair(value: unknown, path = 'repair') {
  const row = record(value, path)
  const result = { id: uuid(row.id, path), vendorId: uuid(row.vendorId, path), vendorReference: text(row.vendorReference, path), repairLocationId: uuid(row.repairLocationId, path),
    dispatchRevision: integer(row.dispatchRevision, path), returnedRevision: nullable(row.returnedRevision, integer, path),
    result: nullable(row.result, (v, p) => oneOf(v, REPAIR_RESULTS, p), path), receiptReference: nullable(row.receiptReference, text, path) }
  if (result.dispatchRevision < 1 || (result.returnedRevision !== null && (result.returnedRevision <= result.dispatchRevision || !result.result || !result.receiptReference))) throw new WarehouseDataError(path)
  return result
}
export function returnView(value: unknown, path = 'return') {
  const row = record(value, path)
  const result = { id: uuid(row.id, path), revision: integer(row.revision, path), state: oneOf(row.state, RETURN_STATES, path), origin: oneOf(row.origin, RETURN_ORIGINS, path),
    sourceDocumentId: uuid(row.sourceDocumentId, path), stockIdentityId: uuid(row.stockIdentityId, path), skuId: uuid(row.skuId, path), lotId: nullable(row.lotId, uuid, path),
    baseUnit: baseUnit(row.baseUnit, path), quantityBase: decimal(row.quantityBase, path), locationId: uuid(row.locationId, path), condition: oneOf(row.condition, CONDITIONS, path),
    legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path), receivedBy: uuid(row.receivedBy, path), recordedAt: timestamp(row.recordedAt, path),
    inspection: nullable(row.inspection, inspection, path), repair: nullable(row.repair, repair, path) }
  if (BigInt(result.quantityBase) <= 0n || (result.inspection && (result.inspection.measuredQuantityBase !== result.quantityBase || result.inspection.expectedRevision >= result.revision)) ||
    (result.repair && (result.repair.dispatchRevision > result.revision || (result.repair.returnedRevision !== null && result.repair.returnedRevision > result.revision))) ||
    (result.state === 'REPAIR' && (!result.repair || result.repair.returnedRevision !== null))) throw new WarehouseDataError(path)
  return result
}
export type WarehouseReturn = ReturnType<typeof returnView>
function namedRef(value: unknown, path = 'reference') {
  const row = record(value, path)
  return { id: uuid(row.id, path), code: text(row.code, path), name: nullable(row.name, text, path) }
}
function itemRef(value: unknown, path = 'item') {
  const row = record(value, path)
  return { ...namedRef(row, path), name: text(row.name, path), tracking: oneOf(row.tracking, TRACKING, path), serial: nullable(row.serial, text, path), lotCode: nullable(row.lotCode, text, path) }
}
function assetOrigin(value: unknown, path = 'assetOrigin') {
  const row = record(value, path)
  return { assignmentId: uuid(row.assignmentId, path), customerId: uuid(row.customerId, path), workOrderId: uuid(row.workOrderId, path) }
}
export function returnDetails(value: unknown, path = 'details') {
  const row = record(value, path), returnCase = returnView(row.returnCase, path), refs = record(row.references, path)
  const references = { code: text(refs.code, path), sourceCode: text(refs.sourceCode, path), workOrderId: nullable(refs.workOrderId, uuid, path), workOrderCode: nullable(refs.workOrderCode, text, path),
    item: itemRef(refs.item, path), locations: array(refs.locations, namedRef, path, 3), receivedByName: nullable(refs.receivedByName, text, path),
    vendor: nullable(refs.vendor, namedRef, path), rmaHandoverId: nullable(refs.rmaHandoverId, uuid, path), assetOrigin: nullable(refs.assetOrigin, assetOrigin, path) }
  if (references.item.id !== returnCase.skuId || !references.locations.some(row => row.id === returnCase.locationId) ||
    new Set(references.locations.map(row => row.id)).size !== references.locations.length ||
    (references.item.tracking === 'SERIAL' && (!references.item.serial || returnCase.baseUnit !== 'EA' || returnCase.quantityBase !== '1')) ||
    (returnCase.repair && (references.vendor?.id !== returnCase.repair.vendorId || !references.locations.some(row => row.id === returnCase.repair?.repairLocationId)))) throw new WarehouseDataError(path)
  return { returnCase, references }
}
export type ReturnDetails = ReturnType<typeof returnDetails>
export function returnSource(value: unknown, path = 'source') {
  const row = record(value, path)
  const result = { sourceDocumentId: uuid(row.sourceDocumentId, path), origin: oneOf(row.origin, RETURN_ORIGINS, path), code: text(row.code, path), recordedAt: timestamp(row.recordedAt, path),
    workOrderId: nullable(row.workOrderId, uuid, path), workOrderCode: nullable(row.workOrderCode, text, path), stockIdentityId: uuid(row.stockIdentityId, path), lotId: nullable(row.lotId, uuid, path),
    item: itemRef(row.item, path), quantityBase: decimal(row.quantityBase, path), baseUnit: baseUnit(row.baseUnit, path), legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path),
    location: namedRef(row.location, path), quarantineLocationId: nullable(row.quarantineLocationId, uuid, path) }
  if (BigInt(result.quantityBase) <= 0n || (result.item.tracking === 'SERIAL' && (!result.item.serial || result.quantityBase !== '1' || result.baseUnit !== 'EA')) ||
    (result.origin === 'MATERIAL_RESIDUAL' && (result.quarantineLocationId !== result.location.id || result.legalOwner !== 'ISP')) ||
    (result.origin === 'ASSET_REMOVAL' && (result.item.tracking !== 'SERIAL' || result.quarantineLocationId !== null))) throw new WarehouseDataError(path)
  return result
}
export type ReturnSource = ReturnType<typeof returnSource>
function replacement(value: unknown, path = 'replacement') {
  const row = record(value, path)
  return { id: uuid(row.id, path), returnId: uuid(row.returnId, path), repairCaseId: uuid(row.repairCaseId, path), receiptId: uuid(row.receiptId, path),
    originalAssetId: uuid(row.originalAssetId, path), legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path), replacementAssetId: nullable(row.replacementAssetId, uuid, path) }
}
export type SupplierReplacement = ReturnType<typeof replacement>
function reacquisition(value: unknown, path = 'reacquisition') {
  const row = record(value, path)
  return { documentId: uuid(row.documentId, path), returnId: uuid(row.returnId, path), revision: integer(row.revision, path) }
}
export function rmaHandover(value: unknown, path = 'rma') {
  const row = record(value, path)
  return { id: uuid(row.id, path), returnId: uuid(row.returnId, path), repairCaseId: uuid(row.repairCaseId, path), originalAssignmentId: uuid(row.originalAssignmentId, path),
    customerId: uuid(row.customerId, path), workOrderId: uuid(row.workOrderId, path), workOrderRevision: integer(row.workOrderRevision, path), technicianId: uuid(row.technicianId, path),
    stockIdentityId: uuid(row.stockIdentityId, path), skuId: uuid(row.skuId, path), serial: text(row.serial, path), sourceLocationId: uuid(row.sourceLocationId, path),
    transitLocationId: uuid(row.transitLocationId, path), technicianLocationId: uuid(row.technicianLocationId, path), legalOwner: oneOf(row.legalOwner, ['CUSTOMER'], path),
    revision: integer(row.revision, path), state: oneOf(row.state, ['DRAFT', 'DISPATCHED', 'RECEIVED'], path), locationId: uuid(row.locationId, path),
    createdBy: uuid(row.createdBy, path), recordedAt: timestamp(row.recordedAt, path) }
}
export type CustomerRmaHandover = ReturnType<typeof rmaHandover>

const root = '/api/v1/warehouse/returns'
export const listReturns = (filter: ReturnFilter = {}) => query(`${root}/workbench${parameters({ ...filter })}`, pageOf(returnDetails))
export const getReturn = (id: string) => query(`${root}/${uuid(id)}/details`, returnDetails)
export const listReturnSources = (filter: Omit<ReturnFilter, 'state'> = {}) => query(`${root}/sources${parameters({ ...filter })}`, pageOf(returnSource))
export const returnHistory = (id: string, page = 0) => query(`${root}/${uuid(id)}/history/page${parameters({ page, size: 25 })}`, pageOf(returnView))
export const receiveReturn = (input: ReturnIntake) => command(root, 'POST', input, returnView)
export const inspectReturn = (id: string, input: ReturnInspection) => command(`${root}/${uuid(id)}/inspect`, 'POST', input, returnView)
export const dispatchRepair = (id: string, input: RepairDispatch) => command(`${root}/${uuid(id)}/repair-dispatch`, 'POST', input, returnView)
export const receiveRepair = (id: string, input: RepairReceipt) => command(`${root}/${uuid(id)}/repair-receive`, 'POST', input, returnView)
export const listReplacements = (id: string, page = 0) => query(`${root}/${uuid(id)}/replacement-receipts${parameters({ page, size: 25 })}`, value => array(value, replacement, 'replacements', 25))
export const requestReplacement = (id: string, input: ReplacementInput) => command(`${root}/${uuid(id)}/replacement-receipts`, 'POST', input, replacement)
export const requestReacquisition = (id: string, input: ReacquisitionInput) => command(`${root}/${uuid(id)}/reacquisition`, 'POST', input, reacquisition)
export const dispatchRma = (id: string, input: RmaDispatch) => command(`${root}/${uuid(id)}/rma-handover`, 'POST', input, rmaHandover)
export const getRmaHandover = (id: string) => query(`/api/v1/warehouse/rma-handovers/${uuid(id)}`, rmaHandover)
