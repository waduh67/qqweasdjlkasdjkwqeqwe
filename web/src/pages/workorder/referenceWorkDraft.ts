import type { WorkDetailsInput } from '@/api/warehouse/referenceWorkManagement'
import type { ReferenceWorkOrder } from '@/api/warehouse/reference'
import { oneOf, plainText, record, text, uuid } from '@/api/warehouse/codec'

export class WorkDraftError extends Error { readonly name = 'WorkDraftError' }
export type WorkSeed = { readonly title: string; readonly description: string; readonly customerId: string; readonly type: 'PSB' | 'REPAIR' }
export function workSeed(value: unknown): WorkSeed | null {
  if (value == null) return null
  const state = record(value)
  if (state.woDraft === undefined) return null
  const seed = record(state.woDraft)
  return { ...workText(text(seed.title), plainText(seed.description)), customerId: uuid(seed.customerId), type: oneOf(seed.type, ['PSB', 'REPAIR']) }
}
export type WorkDraft = { readonly title: string; readonly description: string; readonly priority: ReferenceWorkOrder['priority']; readonly schedule: string }
export const PRIORITY_LABELS = { LOW: 'Rendah', NORMAL: 'Normal', HIGH: 'Tinggi', URGENT: 'Mendesak' } as const
export function localSchedule(instant: string | null): string {
  if (!instant) return ''
  const date = new Date(instant), pad = (value: number, width = 2) => String(value).padStart(width, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`
}
function scheduledAt(value: string, previous?: string | null): string | null {
  if (!value) return null
  if (previous && value === localSchedule(previous)) return previous
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,3})?)?$/.test(value)) throw new WorkDraftError('Isi jadwal berupa tanggal dan waktu setempat.')
  const date = new Date(value)
  if (!Number.isFinite(date.getTime()) || localSchedule(date.toISOString()).slice(0, value.length) !== value) throw new WorkDraftError('Tanggal atau waktu jadwal tidak valid.')
  return date.toISOString()
}
function workText(value: string, description: string) {
  const title = value.trim()
  if (!title || title.length > 200) throw new WorkDraftError('Isi judul pekerjaan, maksimal 200 karakter.')
  if (description.length > 2000) throw new WorkDraftError('Instruksi maksimal 2.000 karakter.')
  if (/[<>\u0000-\u0009\u000b-\u001f\u007f-\u009f]/.test(title + description)) throw new WorkDraftError('Gunakan teks biasa tanpa HTML atau karakter kontrol.')
  return { title, description }
}
export function workDetails(draft: WorkDraft, references: { readonly areaId: string | null; readonly customerId: string | null; readonly previousSchedule?: string | null }): WorkDetailsInput {
  if (!references.areaId) throw new WorkDraftError('Pilih area pekerjaan.')
  return { ...workText(draft.title, draft.description), priority: draft.priority, areaId: references.areaId, customerId: references.customerId, scheduledAt: scheduledAt(draft.schedule, references.previousSchedule) }
}
