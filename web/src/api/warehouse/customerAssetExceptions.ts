import { timestamp } from './approvals'
import { boolean, integer, nullable, oneOf, record, text, uuid, WarehouseDataError } from './codec'
import type { AssetHistory } from './customerAssets'
import type { WarehouseLocation } from './models'
import { command, query } from './transport'

export type AssetExceptionKind = 'loss' | 'title'
export interface AssetExceptionProposal { documentId: string; label: string }
export function assetExceptionContext(value: unknown, path = 'assetException') {
  const r = record(value, path), o = record(r.ownership, path), w = record(r.workOrder, path)
  return {
    ownership: { assignmentId: uuid(o.assignmentId, path), assetId: uuid(o.assetId, path), customerId: uuid(o.customerId, path), workOrderId: uuid(o.workOrderId, path),
      ownershipMode: oneOf(o.ownershipMode, ['LOAN', 'SALE'], path), legalOwner: oneOf(o.legalOwner, ['ISP', 'CUSTOMER'], path),
      assignmentRevision: integer(o.assignmentRevision, path), titleRevision: integer(o.titleRevision, path), handoverId: uuid(o.handoverId, path) },
    customerLabel: text(r.customerLabel, path),
    workOrder: { id: uuid(w.id, path), code: text(w.code, path), revision: integer(w.revision, path), signature: nullable(w.signature, (value, path = 'signature') => {
      const s = record(value, path); return { id: uuid(s.id, path), signerName: text(s.signerName, path), recordedAt: timestamp(s.recordedAt, path) }
    }, path) },
    canRequestTitleCorrection: boolean(r.canRequestTitleCorrection, path), canRequestLoss: boolean(r.canRequestLoss, path),
  }
}
export type AssetExceptionContext = ReturnType<typeof assetExceptionContext>
export const getAssetExceptionContext = (row: AssetHistory) => query(`/api/customers/${uuid(row.asset.customerId)}/assets/${uuid(row.asset.id)}/exceptions/context`, value => {
  const context = assetExceptionContext(value), o = context.ownership, a = row.asset
  if (a.endedAt || a.handoverState !== 'ACCEPTED' || a.provenance === 'UNKNOWN' || a.positionStatus !== 'CUSTOMER_INSTALLED' ||
    o.assignmentId !== a.id || o.assetId !== a.assetId || o.customerId !== a.customerId || o.workOrderId !== a.workOrderId || context.workOrder.id !== a.workOrderId ||
    o.assignmentRevision !== a.revision || o.titleRevision !== a.titleRevision || o.ownershipMode !== a.ownershipMode || o.legalOwner !== a.legalOwner)
    throw new Error('Aset atau kepemilikan berubah. Muat ulang riwayat pelanggan sebelum membuat pengajuan.')
  return context
})

export function proposeAssetException(kind: AssetExceptionKind, context: AssetExceptionContext, reason: string, destination: WarehouseLocation | null) {
  const o = structuredClone(context.ownership), signature = context.workOrder.signature
  reason = reason.trim()
  if (!signature || !reason || reason.length > (kind === 'title' ? 500 : 1000)) throw new Error('Lengkapi alasan dan bukti tanda tangan WO asal.')
  if (kind === 'title') {
    if (!context.canRequestTitleCorrection) throw new Error('Koreksi kepemilikan belum memenuhi syarat.')
    const targetOwner = o.legalOwner === 'ISP' ? 'CUSTOMER' : 'ISP'
    return command('/api/v1/warehouse/asset-title-corrections', 'POST', { assignmentId: o.assignmentId, sourceHandoverId: o.handoverId,
      expectedAssignmentRevision: o.assignmentRevision, expectedTitleRevision: o.titleRevision, targetOwner, reason, evidenceId: signature.id }, (value): AssetExceptionProposal => {
      const r = record(value)
      if (r.assignmentId !== o.assignmentId || r.sourceTitleRevision !== o.titleRevision || r.targetOwner !== targetOwner) throw new WarehouseDataError('titleCorrection')
      return { documentId: uuid(r.documentId), label: 'Usulan koreksi kepemilikan' }
    })
  }
  if (!context.canRequestLoss || o.ownershipMode !== 'LOAN' || o.legalOwner !== 'ISP' || !destination || destination.state !== 'ACTIVE' || destination.kind !== 'LOST' || destination.issueEligible)
    throw new Error('Pengajuan kehilangan memerlukan perangkat pinjam pakai milik ISP dan lokasi kehilangan yang aktif.')
  const destinationId = destination.id
  return command('/api/v1/warehouse/asset-losses', 'POST', { assignmentId: o.assignmentId, sourceHandoverId: o.handoverId,
    expectedRevision: o.assignmentRevision, expectedTitleRevision: o.titleRevision, expectedWorkOrderRevision: context.workOrder.revision,
    destinationLocationId: destinationId, reason, evidenceId: signature.id }, (value): AssetExceptionProposal => {
    const r = record(value)
    if (r.assignmentId !== o.assignmentId || r.sourceHandoverId !== o.handoverId || r.stockIdentityId !== o.assetId || r.destinationLocationId !== destinationId ||
      r.evidenceId !== signature.id || r.quantityBase !== '1' || r.baseUnit !== 'EA' || r.revision !== 0 || r.state !== 'DRAFT') throw new WarehouseDataError('assetLoss')
    return { documentId: uuid(r.id), label: text(r.code) }
  })
}
