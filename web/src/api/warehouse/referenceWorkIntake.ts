import { ApiError } from '../client'
import { boolean, nullable, oneOf, pageOf, plainText, record, text, timestamp, uuid } from './codec'
import { getReferenceWork, referenceCommand, referencePhotos, referenceWorkOrder, type ReferenceWorkDetail } from './reference'
import { parameters, query } from './transport'

const root = '/api/v2/work-orders/intake'
export const WORK_SOURCE_LABELS = { PSB: 'Pasang baru', HELPDESK: 'Keluhan pelanggan', PREVENTIVE: 'Pemeliharaan preventif' } as const
export function referenceWorkIntake(value: unknown) {
  const row = record(value)
  return { id: uuid(row.id), code: text(row.code), source: oneOf(row.source, ['PSB', 'HELPDESK', 'PREVENTIVE']),
    sourceId: uuid(row.sourceId), type: oneOf(row.type, ['PSB', 'REPAIR', 'PREVENTIVE']), title: text(row.title),
    description: plainText(row.description), priority: oneOf(row.priority, ['LOW', 'NORMAL', 'HIGH', 'URGENT']),
    customerId: uuid(row.customerId), areaId: nullable(row.areaId, uuid, 'areaId'),
    scheduledAt: nullable(row.scheduledAt, timestamp, 'scheduledAt'), createdAt: timestamp(row.createdAt), dispatched: boolean(row.dispatched) }
}
export type ReferenceWorkIntake = ReturnType<typeof referenceWorkIntake>
export const listWorkIntake = (search: string, page: number) => query(root + parameters({ search, page, size: 25 }), pageOf(referenceWorkIntake))
export const getWorkIntake = (id: string) => query(root + '/' + uuid(id), referenceWorkIntake)
type WorkDocument = { readonly kind: 'assigned'; readonly detail: ReferenceWorkDetail; readonly photos: Awaited<ReturnType<typeof referencePhotos>> }
  | { readonly kind: 'intake'; readonly intake: ReferenceWorkIntake }
export async function loadReferenceWorkDocument(id: string, field: boolean): Promise<WorkDocument> {
  let detail: ReferenceWorkDetail
  try { detail = await getReferenceWork(id) }
  catch (error) {
    if (error instanceof ApiError && error.status === 404 && !field) {
      const intake = await getWorkIntake(id)
      if (!intake.dispatched) return { kind: 'intake', intake }
      detail = await getReferenceWork(id)
    } else throw error
  }
  return { kind: 'assigned', detail, photos: await referencePhotos(id) }
}
export type WorkDispatchInput = { readonly typeId: string; readonly technicianId: string; readonly areaId: string; readonly scheduledAt: string | null }
export const dispatchWorkIntake = (id: string, input: WorkDispatchInput) => referenceCommand(root + '/' + uuid(id) + '/dispatch', 'POST', input, referenceWorkOrder)
