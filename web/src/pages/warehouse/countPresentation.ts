import type { CountDetails, CountPerson, CountPosition, WarehouseCount } from '@/api/warehouse/counts'

export const countStateLabels: Record<WarehouseCount['state'], string> = { EXPIRED: 'Kedaluwarsa', DRAFT: 'Draf', COUNTING: 'Sedang dihitung', SUBMITTED: 'Diajukan', RECOUNT_REQUIRED: 'Perlu hitung ulang', APPROVED: 'Disetujui', POSTED: 'Dibukukan' }
export const countPersonLabel = (person: CountPerson) => person.name ?? `Petugas ${person.id}`
export const countItemLabel = (item: CountPosition['item']) => `${item.name} · ${item.code}${item.serial ? ` · ${item.serial}` : item.lotCode ? ` · ${item.lotCode}` : ''}`
export function countLineLabel(details: Pick<CountDetails, 'references'>, balanceId: string) {
  const line = details.references.lines.find(line => line.balanceId === balanceId)
  return line ? countItemLabel(line.item) : `Posisi ${balanceId}`
}
export function countCounterLabel(details: Pick<CountDetails, 'references'>, counterId: string) {
  const person = details.references.counters.find(person => person.id === counterId)
  return person ? countPersonLabel(person) : `Petugas ${counterId}`
}
