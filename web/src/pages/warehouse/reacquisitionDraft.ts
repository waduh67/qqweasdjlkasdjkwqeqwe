import type { WorkOrderSignature } from '@/api/warehouse/evidence'
import type { ReacquisitionInput, ReturnDetails } from '@/api/warehouse/returns'
import { completedCustomerRepairInspection } from './returnDraft'

export function readyForReacquisition(details: ReturnDetails) {
  const view = details.returnCase
  return view.origin === 'ASSET_REMOVAL' && view.state === 'RECEIVED_IN_INSPECTION' && view.legalOwner === 'CUSTOMER' &&
    details.references.assetOrigin?.legalOwner === 'CUSTOMER' && details.references.item.tracking === 'SERIAL' && view.quantityBase === '1' && view.baseUnit === 'EA' &&
    !details.references.rmaHandoverId && (!view.repair || completedCustomerRepairInspection(details))
}
export function buildReacquisition(details: ReturnDetails, signature: WorkOrderSignature, reason: string, titleTransferReference: string, confirmed: boolean): ReacquisitionInput {
  if (!readyForReacquisition(details)) throw new Error('Alih kepemilikan memerlukan perangkat pelanggan di karantina tanpa penugasan atau servis terbuka.')
  if (signature.workOrderId !== details.references.assetOrigin?.workOrderId) throw new Error('Gunakan tanda tangan yang tersimpan pada WO pemasangan asal.')
  if (!confirmed) throw new Error('Periksa bukti dan konfirmasikan persetujuan alih kepemilikan pelanggan.')
  if ([reason, titleTransferReference].some(value => !value.trim() || value.trim().length > 500)) throw new Error('Isi alasan dan referensi alih kepemilikan, masing-masing maksimal 500 karakter.')
  return { expectedRevision: details.returnCase.revision, reason: reason.trim(), titleTransferReference: titleTransferReference.trim(), evidenceId: signature.revisionId }
}
