import { api } from '../client'
import { timestamp } from './approvals'
import { integer, nullable, oneOf, record, text, uuid, WarehouseDataError } from './codec'
import { query } from './transport'

export function workOrderSignature(value: unknown, path = 'signature') {
  const row = record(value, path)
  const result = { revisionId: uuid(row.revisionId, path), workOrderId: uuid(row.workOrderId, path), signerName: text(row.signerName, path),
    contentType: oneOf(row.contentType, ['image/png', 'image/jpeg', 'image/gif', 'image/webp'], path), sizeBytes: integer(row.sizeBytes, path),
    signedBy: uuid(row.signedBy, path), signedByName: nullable(row.signedByName, text, path), signedAt: timestamp(row.signedAt, path), createdAt: timestamp(row.createdAt, path) }
  if (result.sizeBytes === 0) throw new WarehouseDataError(path)
  return result
}
export type WorkOrderSignature = ReturnType<typeof workOrderSignature>
export const getWorkOrderSignature = (workOrderId: string) => query(`/api/work-orders/${uuid(workOrderId)}/signature`, value => {
  const signature = nullable(value, workOrderSignature, 'signature')
  if (signature && signature.workOrderId !== workOrderId) throw new WarehouseDataError('signature.workOrderId')
  return signature
})

/** The existing content endpoint serves the current revision; reject a replaced signature. */
export async function getWorkOrderSignatureFile(signature: WorkOrderSignature): Promise<Blob> {
  const check = async () => {
    const current = await getWorkOrderSignature(signature.workOrderId)
    if (current?.revisionId !== signature.revisionId) throw new Error('Tanda tangan telah berubah. Muat ulang bukti sebelum melanjutkan.')
  }
  await check()
  const blob = await api.blob(`/api/work-orders/${uuid(signature.workOrderId)}/signature/content`)
  await check()
  if (blob.size !== signature.sizeBytes || blob.type !== signature.contentType) throw new WarehouseDataError('signature.content')
  return blob
}
