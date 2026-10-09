import { expect, it } from 'vitest'
import { typeDetails } from './referenceTypeDraft'
import { WorkDraftError } from './referenceWorkDraft'

const draft = { name: '  Periksa kabel  ', workType: 'REPAIR', slots: ' Kedatangan \r\n Hasil ukur ', materialRequired: false, active: true, expectedRevision: 3 } as const
it('normalizes named photo slots while retaining the saved revision and requirements', () => {
  expect(typeDetails(draft)).toEqual({ name: 'Periksa kabel', workType: 'REPAIR', photoSlots: ['Kedatangan', 'Hasil ukur'], materialRequired: false, active: true, expectedRevision: 3 })
})
it.each(['', 'Foto\n', 'Foto\nfoto', 'x'.repeat(101), Array.from({ length: 13 }, (_, index) => 'Foto ' + index).join('\n'), '<img>', 'Foto\tbaru'])('rejects invalid required slots %s before review', slots => {
  expect(() => typeDetails({ ...draft, slots })).toThrow(WorkDraftError)
})
it.each(['', 'x'.repeat(201), '<b>Jenis</b>', 'Nama\u0000'])('rejects invalid type name %s', name => {
  expect(() => typeDetails({ ...draft, name })).toThrow(WorkDraftError)
})
