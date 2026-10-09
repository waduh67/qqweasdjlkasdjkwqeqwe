import { afterEach, expect, it, vi } from 'vitest'
import { localSchedule, workDetails, workSeed, WorkDraftError, type WorkDraft } from './referenceWorkDraft'

const draft: WorkDraft = { title: '  Perbaikan kabel pelanggan  ', description: 'Periksa sambungan\nCatat hasil pengukuran.', priority: 'HIGH', schedule: '' }
const references = { areaId: 'area-1', customerId: 'customer-1' }
afterEach(() => vi.unstubAllEnvs())

it('converts an edited local schedule to an instant in the operator timezone', () => {
  vi.stubEnv('TZ', 'Asia/Bangkok')
  expect(workDetails({ ...draft, schedule: '2026-10-09T16:30' }, references).scheduledAt).toBe('2026-10-09T09:30:00.000Z')
  vi.stubEnv('TZ', 'America/New_York')
  expect(workDetails({ ...draft, schedule: '2026-10-09T16:30:04.12' }, references).scheduledAt).toBe('2026-10-09T20:30:04.120Z')
})

it('preserves the precise original instant when the displayed schedule is unchanged', () => {
  const previousSchedule = '2026-10-09T09:30:04.123456Z'
  expect(workDetails({ ...draft, schedule: localSchedule(previousSchedule) }, { ...references, previousSchedule }).scheduledAt).toBe(previousSchedule)
  expect(workDetails(draft, { ...references, previousSchedule }).scheduledAt).toBeNull()
})

it.each(['2026-02-30T16:30', '2026-13-01T16:30', '2026-10-09T24:01', '2026-10-09T16:60', '2026-10-09', '2026-10-09T16:30Z'])('rejects invalid local schedule %s before review', schedule => {
  expect(() => workDetails({ ...draft, schedule }, references)).toThrow(WorkDraftError)
})

it('keeps the selected customer and plain instructions while trimming the title', () => {
  expect(workDetails(draft, references)).toEqual({ ...references, title: 'Perbaikan kabel pelanggan', description: draft.description, priority: 'HIGH', scheduledAt: null })
  expect(() => workDetails(draft, { ...references, areaId: null })).toThrow('Pilih area')
  for (const title of [' ', 'x'.repeat(201), '<b>Perbaikan</b>', 'Judul\u0000']) expect(() => workDetails({ ...draft, title }, references)).toThrow(WorkDraftError)
  for (const description of ['x'.repeat(2001), '<img>', 'Catatan\tbaru']) expect(() => workDetails({ ...draft, description }, references)).toThrow(WorkDraftError)
})

it('accepts a map draft only after parsing its customer, type and plain instructions', () => {
  const seed = { customerId: '00000000-0000-4000-8000-000000000001', title: 'Periksa sambungan', description: 'Redaman tinggi', type: 'REPAIR', customerName: 'Pelanggan' }
  expect(workSeed({ woDraft: seed })).toEqual({ customerId: seed.customerId, title: seed.title, description: seed.description, type: 'REPAIR' })
  expect(workSeed(null)).toBeNull(); expect(workSeed({ focus: 'map' })).toBeNull()
  for (const patch of [{ customerId: 'bad' }, { type: 'UNKNOWN' }, { title: '<img>' }, { description: 'x'.repeat(2001) }]) expect(() => workSeed({ woDraft: { ...seed, ...patch } })).toThrow()
})
