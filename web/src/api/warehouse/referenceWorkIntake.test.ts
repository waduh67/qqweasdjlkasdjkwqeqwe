import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError, tokenStore } from '../client'
import { dispatchWorkIntake, listWorkIntake, loadReferenceWorkDocument } from './referenceWorkIntake'

const id = '00000000-0000-4000-8000-000000000001'
const now = '2026-10-09T10:00:00Z'
const type = { id, revision: 0, name: 'Pemasangan', workType: 'PSB', photoSlots: ['Bukti'], materialRequired: false, active: true, deleted: false }
const work = { id, code: 'WO-1', revision: 0, type, title: 'Pasang layanan', description: '', priority: 'NORMAL', customerId: id,
  areaId: id, technicianId: id, technicianName: 'Teknisi', scheduledAt: null, state: 'PENDING', assignmentGeneration: 1,
  lastActivityAt: now, blockedReason: null, createdAt: now }
const detail = { workOrder: work, overdue: false, overdueAt: now, customerLocked: true, completion: null, timeline: [] }
const intake = { id, code: 'WO-1', source: 'PSB', sourceId: id, type: 'PSB', title: 'Pasang layanan', description: '', priority: 'NORMAL',
  customerId: id, areaId: id, scheduledAt: null, createdAt: now, dispatched: false }
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
let fetchMock: ReturnType<typeof vi.fn>
beforeEach(() => { tokenStore.clear(); fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock) })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('opens an operator intake on assigned-detail 404 without requesting photo evidence', async () => {
  fetchMock.mockResolvedValueOnce(response({ detail: 'Missing' }, 404)).mockResolvedValueOnce(response(intake))
  expect(await loadReferenceWorkDocument(id, false)).toEqual({ kind: 'intake', intake })
  expect(fetchMock.mock.calls.map(call => call[0])).toEqual(['/api/v2/work-orders/' + id, '/api/v2/work-orders/intake/' + id])
})
it.each([403, 500])('retains assigned-detail failure %i without falling back to the queue', async status => {
  fetchMock.mockResolvedValue(response({ detail: 'Unavailable' }, status))
  await expect(loadReferenceWorkDocument(id, false)).rejects.toMatchObject({ status })
  expect(fetchMock).toHaveBeenCalledTimes(1)
})
it('keeps a technician 404 private without reading operator intake', async () => {
  fetchMock.mockResolvedValue(response({ detail: 'Missing' }, 404))
  await expect(loadReferenceWorkDocument(id, true)).rejects.toBeInstanceOf(ApiError)
  expect(fetchMock).toHaveBeenCalledTimes(1)
})
it('reloads the assigned document when dispatch wins between the detail and intake reads', async () => {
  fetchMock.mockResolvedValueOnce(response({}, 404)).mockResolvedValueOnce(response({ ...intake, dispatched: true }))
    .mockResolvedValueOnce(response(detail)).mockResolvedValueOnce(response([]))
  expect(await loadReferenceWorkDocument(id, false)).toEqual({ kind: 'assigned', detail, photos: [] })
  expect(fetchMock.mock.calls.map(call => call[0])).toEqual(['/api/v2/work-orders/' + id, '/api/v2/work-orders/intake/' + id, '/api/v2/work-orders/' + id, '/api/v2/work-orders/' + id + '/evidence'])
})
it('reads searched queue pages with their source labels and metadata', async () => {
  fetchMock.mockResolvedValue(response({ items: [intake], page: 1, size: 25, totalElements: 26 }))
  expect(await listWorkIntake('PSB & Utara', 1)).toMatchObject({ items: [intake], page: 1, totalElements: 26 })
  expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v2/work-orders/intake?search=PSB+%26+Utara&page=1&size=25')
})
it('retries dispatch with identical bytes and key, shares an in-flight send, and refuses a new session', async () => {
  const input = { typeId: id, technicianId: id, areaId: id, scheduledAt: null }
  const operation = dispatchWorkIntake(id, input)
  fetchMock.mockRejectedValueOnce(new TypeError('Lost response')).mockResolvedValueOnce(response(work))
  await expect(operation.execute()).rejects.toThrow('Lost response')
  const first = operation.execute(), second = operation.execute()
  expect(first).toBe(second)
  await expect(first).resolves.toEqual(work)
  expect(fetchMock).toHaveBeenCalledTimes(2)
  for (const [path, options] of fetchMock.mock.calls) {
    expect(path).toBe('/api/v2/work-orders/intake/' + id + '/dispatch')
    expect(options).toMatchObject({ method: 'POST', body: JSON.stringify(input) })
    expect(options.headers.get('Idempotency-Key')).toBe(operation.key)
  }
  tokenStore.clear()
  expect(() => operation.execute()).toThrow('Sesi berubah')
  expect(fetchMock).toHaveBeenCalledTimes(2)
})
