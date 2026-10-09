import type { WorkTypeInput } from '@/api/warehouse/referenceWorkTypes'
import { WorkDraftError } from './referenceWorkDraft'

export function typeDetails(draft: Omit<WorkTypeInput, 'photoSlots'> & { readonly slots: string }): WorkTypeInput {
  const name = draft.name.trim(), photoSlots = draft.slots.split(/\r?\n/).map(slot => slot.trim())
  if (!name || name.length > 200) throw new WorkDraftError('Isi nama jenis, maksimal 200 karakter.')
  if (photoSlots.length < 1 || photoSlots.length > 12 || photoSlots.some(slot => !slot || slot.length > 100)) throw new WorkDraftError('Isi 1–12 foto wajib, satu nama per baris, maksimal 100 karakter per nama.')
  if (new Set(photoSlots.map(slot => slot.toLocaleLowerCase('id'))).size !== photoSlots.length) throw new WorkDraftError('Setiap nama foto wajib harus berbeda.')
  if ([name, ...photoSlots].some(value => /[<>]/.test(value) || Array.from(value).some(character => { const code = character.charCodeAt(0); return code < 32 || (code >= 127 && code <= 159) }))) throw new WorkDraftError('Gunakan nama berupa teks biasa tanpa HTML atau karakter kontrol.')
  return { name, photoSlots, workType: draft.workType, materialRequired: draft.materialRequired, active: draft.active, expectedRevision: draft.expectedRevision }
}
