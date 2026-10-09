import { integer, uuid } from './codec'
import { referenceCommand, workType } from './reference'
import { parameters } from './transport'

export const WORK_KINDS = ['PSB', 'REPAIR', 'MIGRATION', 'DISMANTLE', 'PREVENTIVE'] as const
export const WORK_KIND_LABELS = { PSB: 'Pasang baru', REPAIR: 'Perbaikan', MIGRATION: 'Migrasi', DISMANTLE: 'Bongkar', PREVENTIVE: 'Pemeliharaan' } as const
export type WorkType = ReturnType<typeof workType>
export type WorkTypeInput = { readonly name: string; readonly workType: WorkType['workType']; readonly materialRequired: boolean; readonly photoSlots: readonly string[]; readonly active: boolean; readonly expectedRevision: number }
const root = '/api/v2/work-orders/types'
export const saveWorkType = (id: string | null, input: WorkTypeInput) => referenceCommand(id ? root + '/' + uuid(id) : root, id ? 'PUT' : 'POST', input, workType)
export const deleteWorkType = (type: WorkType) => referenceCommand(root + '/' + uuid(type.id) + parameters({ expectedRevision: integer(type.revision) }), 'DELETE', { expectedRevision: type.revision }, workType)
