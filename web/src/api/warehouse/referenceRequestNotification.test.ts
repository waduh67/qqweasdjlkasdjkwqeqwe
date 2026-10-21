import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '../client'
import { referenceCommand } from './reference'
import { integer, record } from './codec'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('shares a request send, notifies only after success, and keeps session checks synchronous', async () => {
  tokenStore.clear()
  const fetch = vi.fn().mockRejectedValueOnce(new TypeError('Lost response'))
    .mockResolvedValueOnce(new Response('{"count":1}', { headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetch)
  const changed = vi.fn()
  window.addEventListener('warehouse:requests-changed', changed)
  try {
    const operation = referenceCommand('/api/v2/warehouse/requests', 'POST', { reason: 'Kebutuhan lapangan' }, value => integer(record(value).count))
    await expect(operation.execute()).rejects.toThrow('Lost response')
    expect(changed).not.toHaveBeenCalled()
    const first = operation.execute(), second = operation.execute()
    expect(first).toBe(second)
    await expect(first).resolves.toBe(1)
    expect(changed).toHaveBeenCalledTimes(1)
    expect(fetch).toHaveBeenCalledTimes(2)
    tokenStore.clear()
    expect(() => operation.execute()).toThrow('Sesi berubah')
    expect(fetch).toHaveBeenCalledTimes(2)
  } finally { window.removeEventListener('warehouse:requests-changed', changed) }
})
