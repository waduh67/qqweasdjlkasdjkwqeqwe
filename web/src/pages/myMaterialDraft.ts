import type { MaterialCustody } from '@/api/warehouse/materialExecution'
import type { MaterialReceiptInput, MaterialReturnInput, MyMaterialContext, MyMaterialIssue } from '@/api/warehouse/myMaterials'
import { quantityFromInput } from '@/api/warehouse/quantity'
import { sameSerialIdentity } from '@/api/warehouse/serialIdentity'

function evidence(value: string) {
  if (!value.trim() || value.trim().length > 500) throw new Error('Isi referensi bukti, maksimal 500 karakter.')
  return value.trim()
}
export function myReceiptInput(context: MyMaterialContext, issue: MyMaterialIssue, actor: string, lineId: string,
  accepted: string, missing: string, rejected: string, reason: string, reference: string, observedSerial: string | null): MaterialReceiptInput {
  if (!context.currentAssignee || !context.active || issue.receiver.id !== actor || issue.workOrderId !== context.id) throw new Error('Penerimaan hanya untuk penerima yang masih ditugaskan pada WO aktif.')
  if (issue.workOrderRevision !== context.workOrderRevision) throw new Error('Revisi WO berubah sejak pengiriman. Minta petugas memeriksa pengiriman sebelum menerima.')
  const source = issue.lines.find(line => line.id === lineId && line.remainingBase !== '0')
  if (!source || issue.state === 'RECEIVED') throw new Error('Pilih barang yang masih menunggu penerimaan.')
  const acceptedBase = quantityFromInput(accepted, source.baseUnit), missingBase = quantityFromInput(missing, source.baseUnit, true), rejectedBase = quantityFromInput(rejected, source.baseUnit, true)
  if (BigInt(acceptedBase) + BigInt(missingBase) + BigInt(rejectedBase) > BigInt(source.remainingBase)) throw new Error('Jumlah diterima, kurang, dan ditolak melebihi sisa pengiriman.')
  if (reason.trim().length > 1000 || ((missingBase !== '0' || rejectedBase !== '0') && !reason.trim())) throw new Error('Isi alasan selisih, maksimal 1000 karakter.')
  if (source.serial && !sameSerialIdentity(source.serial, observedSerial)) throw new Error('Pindai atau ketik serial perangkat yang benar-benar diterima.')
  return { issueId: issue.id, expectedRevision: issue.revision, workOrderRevision: context.workOrderRevision, evidenceReference: evidence(reference),
    lines: [{ issueLineId: source.id, stockIdentityId: source.stockIdentityId, baseUnit: source.baseUnit, acceptedBase, missingBase, rejectedBase,
      reason: reason.trim() || undefined, serial: source.serial ?? undefined }] }
}
export function myReturnInput(context: MyMaterialContext, source: MaterialCustody | null, quantity: string, target: string | null, reason: string, reference: string, observedSerial: string | null = null): MaterialReturnInput {
  if (!source) throw new Error('Pilih barang yang masih di tangan Anda.')
  if (source.sku.tracking === 'SERIAL' && !sameSerialIdentity(source.serial, observedSerial)) throw new Error('Pindai atau ketik serial perangkat yang akan dikembalikan.')
  if (!target || !reason.trim() || reason.trim().length > 1000) throw new Error('Pilih karantina tujuan dan isi alasan pengembalian, maksimal 1000 karakter.')
  const quantityBase = quantityFromInput(quantity, source.baseUnit)
  if (BigInt(quantityBase) > BigInt(source.quantityBase)) throw new Error('Jumlah pengembalian melebihi sisa di tangan Anda.')
  return { workOrderRevision: context.workOrderRevision, receiptId: source.receiptId, issueLineId: source.issueLineId, stockIdentityId: source.id,
    quantityBase, baseUnit: source.baseUnit, targetLocationId: target, reason: reason.trim(), evidenceReference: evidence(reference), usageId: source.sourceUsageId ?? undefined }
}
