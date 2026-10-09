import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { tokenStore } from '../client'
import { workAreas, workCustomers } from './referenceWorkManagement'
import { deleteWorkType } from './referenceWorkTypes'
import { WarehouseDataError } from './codec'

const id = '00000000-0000-4000-8000-000000000001'
const area = { id, name: 'Area Utara', code: 'UTARA' }
const type = { id, revision: 3, name: 'Periksa', workType: 'PREVENTIVE', materialRequired: false, photoSlots: ['Hasil'], active: true, deleted: false } as const
const response = (body: unknown) => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
let fetchMock: ReturnType<typeof vi.fn>
beforeEach(() => { tokenStore.clear(); fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock) })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('reads the scoped second area page without treating it as the whole directory', async () => {
  fetchMock.mockResolvedValue(response({ content: [area], page: 1, size: 25, totalElements: 26, totalPages: 2 }))
  expect(await workAreas('Utara & Barat', 1)).toEqual({ items: [area], page: 1, size: 25, totalElements: 26 })
  expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v2/work-orders/areas?query=Utara+%26+Barat&page=1&size=25')
})
it('fails visibly when choice counts disagree with the page metadata', async () => {
  fetchMock.mockResolvedValue(response({ content: [area], page: 0, size: 25, totalElements: 26, totalPages: 1 }))
  await expect(workAreas('', 0)).rejects.toBeInstanceOf(WarehouseDataError)
})
it('parses optional customer areas and rejects an unknown customer status', async () => {
  fetchMock.mockResolvedValueOnce(response({ content: [{ ...area, status: 'PROSPECT', areaId: null }], page: 0, size: 25, totalElements: 1, totalPages: 1 }))
  expect((await workCustomers('', 0)).items[0]?.areaId).toBeNull()
  fetchMock.mockResolvedValueOnce(response({ content: [{ ...area, status: 'UNKNOWN' }], page: 0, size: 25, totalElements: 1, totalPages: 1 }))
  await expect(workCustomers('', 0)).rejects.toBeInstanceOf(WarehouseDataError)
})
it('retries an uncertain deletion with identical revision, method and key, then refuses a new session', async () => {
  const operation = deleteWorkType({ ...type, photoSlots: [...type.photoSlots] })
  fetchMock.mockRejectedValueOnce(new TypeError('lost response')).mockResolvedValueOnce(response({ ...type, revision: 4, active: false, deleted: true }))
  await expect(operation.execute()).rejects.toThrow('lost response')
  const first = operation.execute(), second = operation.execute()
  expect(first).toBe(second)
  await expect(first).resolves.toMatchObject({ revision: 4, deleted: true })
  expect(fetchMock).toHaveBeenCalledTimes(2)
  for (const [path, options] of fetchMock.mock.calls) {
    expect(path).toBe('/api/v2/work-orders/types/' + id + '?expectedRevision=3')
    expect(options).toMatchObject({ method: 'DELETE', body: '{"expectedRevision":3}' })
    expect(options.headers.get('Idempotency-Key')).toBe(operation.key)
  }
  tokenStore.clear()
  expect(() => operation.execute()).toThrow('Sesi berubah')
  expect(fetchMock).toHaveBeenCalledTimes(2)
})
