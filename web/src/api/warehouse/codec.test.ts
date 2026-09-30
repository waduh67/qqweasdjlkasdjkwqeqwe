import { describe, expect, it } from 'vitest'
import { pageOf, WarehouseDataError } from './codec'
import { quantity, sku, stockRow } from './models'

const id = 'c66801d0-2194-4edb-a080-bc3f7e2c8089'
const validSku = { id, revision: 0, state: 'ACTIVE', code: 'CABLE', name: 'Kabel drop', tracking: 'LOT', baseUnit: 'MM',
  allowedOwnershipModes: ['LOAN'], inspectionRequired: true, minimumQuantityBase: '0', category: null, model: null }
const cable = { quantityBase: '917500', baseUnit: 'MM', displayQuantity: '917.500', displayUnit: 'M' }

describe('warehouse response contracts', () => {
  it('reads exact typed master and quantity data while allowing additive fields', () => {
    expect(sku({ ...validSku, future: 'value' })).toEqual(validSku)
    expect(quantity(cable)).toEqual(cable)
  })

  it.each([null, [], {}, { ...validSku, revision: '0' }, { ...validSku, revision: 9007199254740992 },
    { ...validSku, baseUnit: 'METRE' }, { ...validSku, tracking: 'SERIAL' }, { ...validSku, minimumQuantityBase: 0 },
    { ...validSku, inspectionRequired: 'true' }, { ...validSku, id: 'invalid' }, { ...validSku, allowedOwnershipModes: [] },
    { ...validSku, allowedOwnershipModes: ['LOAN', 'LOAN'] }])('rejects a malformed master %j', (input) => {
    expect(() => sku(input)).toThrow(WarehouseDataError)
  })

  it.each([{ ...cable, quantityBase: 917500 }, { ...cable, quantityBase: '917.5' }, { ...cable, displayQuantity: '917' },
    { ...cable, displayUnit: 'EA' }, { ...cable, baseUnit: null }])('rejects inconsistent quantities %j', (input) => {
    expect(() => quantity(input)).toThrow(WarehouseDataError)
  })

  it('never converts malformed or inconsistent pages to an empty result', () => {
    const decode = pageOf(sku)
    expect(decode({ items: [], page: 3, size: 25, totalElements: 0 }).items).toEqual([])
    for (const input of [[], { items: null, page: 0, size: 25, totalElements: 0 },
      { items: [validSku], page: 0, size: 25, totalElements: 0 },
      { items: [], page: 0, size: 101, totalElements: 0 }]) expect(() => decode(input)).toThrow(WarehouseDataError)
  })

  it('requires consistent units across physical and reservation buckets', () => {
    const body = { id, skuId: id, skuCode: 'CABLE', name: 'Kabel', tracking: 'LOT', physical: cable,
      reservedUnpicked: cable, reservedPicked: cable, available: cable,
      statusBuckets: { AVAILABLE: '917500' }, conditionBuckets: { SERVICEABLE: '917500' }, ownerBuckets: { ISP: '917500' } }
    expect(stockRow(body).physical.quantityBase).toBe('917500')
    expect(() => stockRow({ ...body, available: { quantityBase: '9', baseUnit: 'EA', displayQuantity: '9', displayUnit: 'EA' } })).toThrow(WarehouseDataError)
    expect(() => stockRow({ ...body, statusBuckets: { MISSING_STATE: '9' } })).toThrow(WarehouseDataError)
  })
})
