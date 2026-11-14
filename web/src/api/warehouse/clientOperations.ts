import { array, boolean, decimal, integer, nullable, oneOf, pageOf, plainText, record, text, timestamp, uuid, type Decoder, WarehouseDataError } from './codec'
import { referenceCommand } from './reference'
import { parameters, query } from './transport'

const root = '/api/v2/warehouse'
const unit = (v: unknown) => oneOf(v, ['EA', 'MM'])
function base(value: unknown) {
  const r = record(value)
  return { id: uuid(r.id), revision: integer(r.revision), warehouseId: uuid(r.warehouseId), warehouseName: text(r.warehouseName), state: text(r.state), createdAt: timestamp(r.createdAt), updatedAt: timestamp(r.updatedAt) }
}
function quantities(total: string, done: string) {
  if (BigInt(total) <= 0n || BigInt(done) > BigInt(total)) throw new WarehouseDataError('client quantities')
}
export function purchase(value: unknown) {
  const r = record(value)
  return { ...base(r), state: oneOf(r.state, ['ORDERED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED']), supplierId: uuid(r.supplierId), supplierName: text(r.supplierName), purchaseDate: text(r.purchaseDate), reference: text(r.reference), requestId: nullable(r.requestId, uuid, 'requestId'), notes: plainText(r.notes),
    lines: array(r.lines, v => { const l = record(v), quantityBase = decimal(l.quantityBase), receivedBase = decimal(l.receivedBase); quantities(quantityBase, receivedBase)
      return { id: uuid(l.id), skuId: uuid(l.skuId), skuName: text(l.skuName), baseUnit: unit(l.baseUnit), quantityBase, receivedBase, requestLineId: nullable(l.requestLineId, uuid, 'requestLineId'), unitPriceMinor: nullable(l.unitPriceMinor, decimal, 'unitPriceMinor') }
    }, 'lines', 100) }
}
export function loan(value: unknown) {
  const r = record(value)
  return { ...base(r), state: oneOf(r.state, ['SUBMITTED', 'MANAGER_REVIEW', 'APPROVED', 'REJECTED', 'ACTIVE', 'PARTIALLY_RETURNED', 'RETURNED']), borrowerName: text(r.borrowerName), contact: plainText(r.contact), loanDate: text(r.loanDate), dueDate: nullable(r.dueDate, text, 'dueDate'), notes: plainText(r.notes), requiresManagerApproval: boolean(r.requiresManagerApproval), externalLocationId: nullable(r.externalLocationId, uuid, 'externalLocationId'), issueEvidence: plainText(r.issueEvidence), issuedBy: plainText(r.issuedBy), policyRevision: integer(r.policyRevision),
    lines: array(r.lines, v => { const l = record(v), quantityBase = decimal(l.quantityBase), approvedBase = decimal(l.approvedBase); quantities(quantityBase, approvedBase)
      return { id: uuid(l.id), skuId: uuid(l.skuId), skuName: text(l.skuName), baseUnit: unit(l.baseUnit), tracking: oneOf(l.tracking, ['SERIAL', 'BULK', 'LOT']), quantityBase, approvedBase,
        stocks: array(l.stocks, v => { const s = record(v), quantityBase = decimal(s.quantityBase), returnedBase = decimal(s.returnedBase); quantities(quantityBase, returnedBase)
          return { stockIdentityId: uuid(s.stockIdentityId), quantityBase, returnedBase, serial: nullable(s.serial, text, 'serial') } }, 'stocks', 100) }
    }, 'lines', 100) }
}
export function warranty(value: unknown) {
  const r = record(value)
  return { ...base(r), state: oneOf(r.state, ['MASUK', 'DIKIRIM', 'SELESAI']), loanId: uuid(r.loanId), borrowerName: text(r.borrowerName), skuId: uuid(r.skuId), skuName: text(r.skuName), baseUnit: unit(r.baseUnit), tracking: oneOf(r.tracking, ['SERIAL', 'BULK', 'LOT']), stockIdentityId: uuid(r.stockIdentityId), quantityBase: decimal(r.quantityBase), serial: nullable(r.serial, text, 'serial'), locationId: uuid(r.locationId), result: nullable(r.result, text, 'result'), supplierId: nullable(r.supplierId, uuid, 'supplierId'), supplierName: nullable(r.supplierName, text, 'supplierName'), vendorReference: plainText(r.vendorReference), evidence: text(r.evidence), sentAt: nullable(r.sentAt, timestamp, 'sentAt'), returnedAt: nullable(r.returnedAt, timestamp, 'returnedAt'), replacementAssetId: nullable(r.replacementAssetId, uuid, 'replacementAssetId') }
}
export type Purchase = ReturnType<typeof purchase>
export type Loan = ReturnType<typeof loan>
export type Warranty = ReturnType<typeof warranty>
export const getClientOperationsOverview = () => query(root + '/client-operations/overview', v => { const r = record(v); return { purchasesPending: nullable(r.purchasesPending, integer, 'purchasesPending'), loansAwaitingAdmin: nullable(r.loansAwaitingAdmin, integer, 'loansAwaitingAdmin'), loansAwaitingManager: nullable(r.loansAwaitingManager, integer, 'loansAwaitingManager'), activeLoans: nullable(r.activeLoans, integer, 'activeLoans'), openWarranties: nullable(r.openWarranties, integer, 'openWarranties') } })
function detail<T>(decode: Decoder<T>) { return (value: unknown) => { const r = record(value); return { document: decode(r.document), timeline: array(r.timeline, v => { const e = record(v); return { id: uuid(e.id), revision: integer(e.revision), action: text(e.action), actorName: text(e.actorName), notes: plainText(e.notes), movementIds: array(e.movementIds, uuid), recordedAt: timestamp(e.recordedAt) } }) } } }
export type ClientDetail<T> = ReturnType<ReturnType<typeof detail<T>>>
export const listPurchases = (search = '', page = 0) => query(root + '/purchases' + parameters({ search, page }), pageOf(purchase))
export const listLoans = (search = '', page = 0) => query(root + '/loans' + parameters({ search, page }), pageOf(loan))
export const listWarranties = (search = '', page = 0) => query(root + '/warranties' + parameters({ search, page }), pageOf(warranty))
export const getPurchase = (id: string) => query(root + '/purchases/' + uuid(id), detail(purchase))
export const getLoan = (id: string) => query(root + '/loans/' + uuid(id), detail(loan))
export const getLoanIssue = (id: string) => query(root + '/loans/' + uuid(id) + '/issue-snapshot', loan)
export const getWarranty = (id: string) => query(root + '/warranties/' + uuid(id), detail(warranty))
export interface ItemInput { skuId: string; baseUnit: 'EA' | 'MM'; quantityBase: string }
export interface PurchaseInput { warehouseId: string; supplierId: string; purchaseDate: string; reference: string; notes: string; requestId?: string; lines: (ItemInput & { requestLineId?: string; unitPriceMinor?: string })[] }
export interface LoanInput { warehouseId: string; borrowerName: string; contact: string; loanDate: string; dueDate: string | null; notes: string; lines: ItemInput[] }
export const createPurchase = (input: PurchaseInput) => referenceCommand(root + '/purchases', 'POST', input, purchase)
export const createLoan = (input: LoanInput) => referenceCommand(root + '/loans', 'POST', input, loan)
export const purchaseReceive = (row: Purchase, input: { lineId: string; quantityBase: string; serials: { serial: string; mac?: string }[]; lotCode?: string; notes: string }) => referenceCommand(root + '/purchases/' + row.id + '/receipts', 'POST', { ...input, expectedRevision: row.revision }, purchase)
export const purchaseCancel = (row: Purchase, notes: string) => referenceCommand(root + '/purchases/' + row.id + '/cancel', 'POST', { expectedRevision: row.revision, notes }, purchase)
export const loanReview = (row: Loan, lines: { lineId: string; approvedBase: string }[], notes: string) => referenceCommand(root + '/loans/' + row.id + '/review', 'POST', { expectedRevision: row.revision, lines, notes }, loan)
export const loanDecision = (row: Loan, approved: boolean, reason: string) => referenceCommand(root + '/loans/' + row.id + '/decision', 'POST', { expectedRevision: row.revision, approved, reason }, loan)
export const loanIssue = (row: Loan, lines: { lineId: string; stocks: { stockIdentityId: string; quantityBase: string }[] }[], evidence: string) => referenceCommand(root + '/loans/' + row.id + '/issue', 'POST', { expectedRevision: row.revision, lines, evidence }, loan)
export const loanReturn = (row: Loan, lines: { lineId: string; stockIdentityId: string; quantityBase: string; condition: 'SERVICEABLE' | 'DAMAGED'; observedSerial?: string }[], evidence: string) => referenceCommand(root + '/loans/' + row.id + '/returns', 'POST', { expectedRevision: row.revision, lines, evidence }, loan)
export const warrantyDispatch = (row: Warranty, input: { supplierId: string; vendorReference: string; evidence: string }) => referenceCommand(root + '/warranties/' + row.id + '/dispatch', 'POST', { ...input, expectedRevision: row.revision }, warranty)
export const warrantyReceive = (row: Warranty, input: { result: string; evidence: string; observedSerial?: string; replacementSerial?: string; replacementMac?: string }) => referenceCommand(root + '/warranties/' + row.id + '/receive', 'POST', { ...input, expectedRevision: row.revision }, warranty)
export const warrantyInspect = (row: Warranty, input: { serviceable: boolean; resetConfirmed: boolean; evidence: string }) => referenceCommand(root + '/warranties/' + row.id + '/inspect', 'POST', { ...input, expectedRevision: row.revision }, warranty)
export const CLIENT_LABELS: Record<string, string> = { ORDERED: 'Dipesan', PARTIALLY_RECEIVED: 'Diterima sebagian', RECEIVED: 'Diterima lengkap', CANCELLED: 'Sisa dibatalkan', SUBMITTED: 'Menunggu Admin', MANAGER_REVIEW: 'Menunggu Manager', APPROVED: 'Disetujui', REJECTED: 'Ditolak', ACTIVE: 'Dipinjam', PARTIALLY_RETURNED: 'Kembali sebagian', RETURNED: 'Kembali lengkap', MASUK: 'Karantina', DIKIRIM: 'Di vendor', SELESAI: 'Selesai' }
