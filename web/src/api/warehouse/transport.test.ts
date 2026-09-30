import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, tokenStore } from '../client'
import { integer, record, WarehouseDataError } from './codec'
import { command, query } from './transport'

const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const decode = (value: unknown) => integer(record(value).revision)
let fetchMock: ReturnType<typeof vi.fn>
beforeEach(() => { tokenStore.clear(); fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock) })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

describe('warehouse immutable command retry', () => {
  it('keeps original body and key after an ambiguous network failure even if the input object changes', async () => {
    const input = { expectedRevision: 1, quantityBase: '17500' }
    const operation = command('/api/v1/warehouse/returns/id/inspect', 'POST', input, decode)
    fetchMock.mockRejectedValueOnce(new TypeError('network lost')).mockResolvedValueOnce(response({ revision: 2 }))
    await expect(operation.execute()).rejects.toThrow('network lost')
    input.quantityBase = '99999'
    await expect(operation.execute()).resolves.toBe(2)
    for (const [, init] of fetchMock.mock.calls) {
      expect((init as RequestInit).body).toBe('{"expectedRevision":1,"quantityBase":"17500"}')
      expect(((init as RequestInit).headers as Headers).get('Idempotency-Key')).toBe(operation.key)
    }
  })

  it('retains the same command key while a401 refresh rotates only authentication', async () => {
    tokenStore.setAccessToken('old-access'); tokenStore.setRefreshToken('old-refresh')
    const operation = command('/api/v1/warehouse/skus', 'POST', { name: 'Kabel' }, decode)
    fetchMock.mockResolvedValueOnce(response({}, 401)).mockResolvedValueOnce(response({ accessToken: 'new-access', refreshToken: 'new-refresh' }))
      .mockResolvedValueOnce(response({ revision: 0 }))
    await expect(operation.execute()).resolves.toBe(0)
    expect(fetchMock.mock.calls.map(call => call[0])).toEqual(['/api/v1/warehouse/skus', '/api/auth/refresh', '/api/v1/warehouse/skus'])
    const first = fetchMock.mock.calls[0][1] as RequestInit
    const retry = fetchMock.mock.calls[2][1] as RequestInit
    expect(retry.body).toBe(first.body)
    expect((retry.headers as Headers).get('Idempotency-Key')).toBe((first.headers as Headers).get('Idempotency-Key'))
    expect((retry.headers as Headers).get('Authorization')).toBe('Bearer new-access')
  })

  it('coalesces double submission and propagates a conflict without silently changing the key', async () => {
    const operation = command('/api/v1/warehouse/skus/id', 'PUT', { expectedRevision: 0 }, decode)
    fetchMock.mockResolvedValue(response({ code: 'STALE_REVISION', message: 'Changed' }, 409))
    const first = operation.execute()
    const second = operation.execute()
    expect(first).toBe(second)
    await expect(first).rejects.toMatchObject({ status: 409, code: 'STALE_REVISION' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await expect(operation.execute()).rejects.toBeInstanceOf(ApiError)
    expect((fetchMock.mock.calls[1][1].headers as Headers).get('Idempotency-Key')).toBe(operation.key)
  })

  it('rejects malformed success responses and never returns a synthetic empty result', async () => {
    fetchMock.mockResolvedValue(response({ revision: 'zero' }))
    await expect(query('/api/v1/warehouse/skus/id', decode)).rejects.toBeInstanceOf(WarehouseDataError)
  })
})
