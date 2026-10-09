import { describe, expect, it } from 'vitest'
import type { ReferencePhoto, ReferencePosition, ReferenceWorkDetail } from '@/api/warehouse/reference'
import { assertCompletionEvidence, completionMaterials, ReferenceDraftError } from './referenceCompletionDraft'

const source: ReferencePosition = { stockIdentityId: 'stock', skuId: 'sku', skuCode: 'DROP', skuName: 'Drop cable', tracking: 'BULK', baseUnit: 'MM', quantityBase: '9007199254740993', locationId: 'location', locationName: 'Lapangan', holderId: 'tech', holderName: 'Teknisi', holderKind: 'TECHNICIAN', status: 'ISSUED', serial: null, mac: null, revision: 0 }
const detail: ReferenceWorkDetail = { workOrder: { id: 'work', code: 'WO-1', revision: 4, type: { id: 'type', revision: 0, name: 'Pasang', workType: 'PSB', materialRequired: true, photoSlots: ['Lokasi', 'Hasil'], active: true, deleted: false }, title: 'Pasang pelanggan', description: '', priority: 'NORMAL', customerId: null, technicianId: 'tech', technicianName: 'Teknisi', areaId: 'area', scheduledAt: null, state: 'PENDING', assignmentGeneration: 2, lastActivityAt: '2026-10-08T00:00:00Z', blockedReason: null, createdAt: '2026-10-08T00:00:00Z' }, overdue: false, overdueAt: '2026-10-11T00:00:00Z', completion: null, timeline: [] }
const photo: ReferencePhoto = { id: 'photo', slot: 'Lokasi', assignmentGeneration: 2, uploadedBy: 'tech', uploadedByName: 'Teknisi', contentType: 'image/png', sizeBytes: 68, sha256: 'a'.repeat(64), receivedAt: '2026-10-08T00:00:00Z', current: true }

describe('reference completion draft', () => {
  it('preserves exact millimetres above the floating point integer limit', () => {
    expect(completionMaterials([{ key: '1', source, quantity: '9007199254740.993' }], true)).toEqual([{ stockIdentityId: 'stock', quantityBase: '9007199254740993' }])
  })
  it('rejects one millimetre above current custody', () => {
    expect(() => completionMaterials([{ key: '1', source, quantity: '9007199254740.994' }], true)).toThrow(ReferenceDraftError)
  })
  it.each(['0', '-1', '1e3', '0.0001']) ('rejects invalid material amount %s', quantity => {
    expect(() => completionMaterials([{ key: '1', source, quantity }], true)).toThrow(ReferenceDraftError)
  })
  it('rejects duplicate stock identities across separate rows', () => {
    expect(() => completionMaterials([{ key: '1', source, quantity: '1' }, { key: '2', source, quantity: '2' }], true)).toThrow('Satu barang hanya boleh dipilih sekali')
  })
  it('requires complete selected rows and material for mandatory types', () => {
    expect(() => completionMaterials([], true)).toThrow(ReferenceDraftError)
    expect(() => completionMaterials([{ key: '1', source: null, quantity: '1' }], false)).toThrow(ReferenceDraftError)
    expect(completionMaterials([], false)).toEqual([])
  })
  it('requires exactly one unit for serialized devices', () => {
    const device = { ...source, baseUnit: 'EA', tracking: 'SERIAL', quantityBase: '2', serial: 'ONT-1' } as const
    expect(() => completionMaterials([{ key: '1', source: device, quantity: '2' }], true)).toThrow('Perangkat berserial harus dicatat satu unit')
    expect(completionMaterials([{ key: '1', source: device, quantity: '1' }], true)[0]?.quantityBase).toBe('1')
  })
  it('rejects prior assignment and superseded evidence despite matching slot names', () => {
    expect(() => assertCompletionEvidence(detail, [photo, { ...photo, id: 'old', slot: 'Hasil', assignmentGeneration: 1 }])).toThrow('Hasil')
    expect(() => assertCompletionEvidence(detail, [photo, { ...photo, id: 'old', slot: 'Hasil', current: false }])).toThrow('Hasil')
    expect(() => assertCompletionEvidence(detail, [photo, { ...photo, id: 'new', slot: 'Hasil' }])).not.toThrow()
  })
})
