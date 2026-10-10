import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { b2b } from './b2b'
import { tokenStore } from './client'

const id = '11111111-1111-4111-8111-111111111111'
const saved = { id, clientId: id, clientName: 'Kantor', visitDate: '2026-10-10', counted: true, notes: 'Periksa koneksi', reporterName: 'NE', createdAt: '2026-10-10T08:00:00Z', photos: [{ id, contentType: 'image/png', sizeBytes: 3 }] }
let fetchMock: ReturnType<typeof vi.fn>
beforeEach(() => { tokenStore.clear(); fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock) })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('retains visit notes photos and operation key after an uncertain submission', async () => {
  const files = [new File(['png'], 'proof.png', { type: 'image/png' })]
  const operation = b2b.report(id, 'Periksa koneksi', files)
  fetchMock.mockRejectedValueOnce(new TypeError('connection lost')).mockResolvedValueOnce(new Response(JSON.stringify(saved)))

  await expect(operation.execute()).rejects.toThrow('connection lost')
  files.splice(0, 1, new File(['changed'], 'new.png', { type: 'image/png' }))
  await expect(operation.execute()).resolves.toEqual(saved)

  expect(fetchMock).toHaveBeenCalledTimes(2)
  for (const call of fetchMock.mock.calls) {
    const init: RequestInit = call[1]
    expect(new Headers(init.headers).get('Idempotency-Key')).toBe(operation.key)
    expect(init.body).toBeInstanceOf(FormData)
    if (!(init.body instanceof FormData)) throw new Error('Expected multipart')
    expect(init.body.get('notes')).toBe('Periksa koneksi')
    const photo = init.body.get('photos')
    expect(photo).toBeInstanceOf(File)
    if (!(photo instanceof File)) throw new Error('Expected proof file')
    expect(photo.name).toBe('proof.png'); expect(photo.size).toBe(3)
  }
})

it('coalesces simultaneous submissions of one captured visit', async () => {
  fetchMock.mockResolvedValue(new Response(JSON.stringify(saved)))
  const operation = b2b.report(id, 'Periksa koneksi', [new File(['png'], 'proof.png', { type: 'image/png' })])

  const first = operation.execute(), second = operation.execute()
  await expect(first).resolves.toEqual(saved)

  expect(first).toBe(second); expect(fetchMock).toHaveBeenCalledTimes(1)
})

it('rejects a captured visit when another account logs in', () => {
  tokenStore.setAccessToken('NE')
  const operation = b2b.report(id, 'Periksa koneksi', [new File(['png'], 'proof.png', { type: 'image/png' })])

  tokenStore.clear(); tokenStore.setAccessToken('other-account')

  expect(() => operation.execute()).toThrow('Sesi berubah'); expect(fetchMock).not.toHaveBeenCalled()
})

it('rejects malformed reports instead of displaying empty success', async () => {
  fetchMock.mockResolvedValue(new Response(JSON.stringify({ content: [{ clientId: id }], page: 0, size: 20, totalElements: 1 })))

  await expect(b2b.reports('2026-10', '', 0)).rejects.toThrow()
})
