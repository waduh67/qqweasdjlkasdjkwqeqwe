import { currentMonth } from '@/pages/b2b/shared'

export class FieldFilterError extends Error {}
export function monthError(month: string, futureAllowed = false) {
  if (!/^(20[0-9]{2}|2100)-(0[1-9]|1[0-2])$/.test(month)) return 'Pilih bulan yang valid (2000–2100).'
  return !futureAllowed && month > currentMonth() ? 'Bulan mendatang belum dapat dihitung.' : ''
}
export function operationalDate() {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Jakarta', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  return [parts.find(p => p.type === 'year')?.value, parts.find(p => p.type === 'month')?.value, parts.find(p => p.type === 'day')?.value].join('-')
}
export function dateError(date: string) {
  if (!/^(20[0-9]{2}|2100)-[0-9]{2}-[0-9]{2}$/.test(date)) return 'Pilih tanggal yang valid.'
  const parsed = new Date(date + 'T12:00:00Z')
  return !Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== date ? 'Pilih tanggal yang valid.' : ''
}
