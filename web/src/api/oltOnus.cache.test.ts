import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearOltOnusCache, getCachedOltOnus, invalidateOltOnusCache, readCachedOltOnus } from './oltOnus'
import { deferred, oltOnusSnapshot, unsupportedOnu } from './oltOnus.test-support'

afterEach(() => {
  clearOltOnusCache()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('ONU inventory session cache', () => {
  it('reuses a successful snapshot until exactly 15 minutes after receiving it', async () => {
    let now = 1_000_000
    vi.spyOn(Date, 'now').mockImplementation(() => now)
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(async () => Response.json(oltOnusSnapshot()))
    vi.stubGlobal('fetch', fetch)
    const first = await readCachedOltOnus('olt-a')
    now += 15 * 60 * 1000 - 1
    expect(await readCachedOltOnus('olt-a')).toBe(first)
    expect(fetch).toHaveBeenCalledTimes(1)
    now += 1
    expect(getCachedOltOnus('olt-a')).toBeUndefined()
    expect(await readCachedOltOnus('olt-a')).not.toBe(first)
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('shares one in-flight request, including a Refresh while a read is pending', async () => {
    const pending = deferred<Response>()
    const fetch = vi.fn<typeof globalThis.fetch>().mockReturnValue(pending.promise)
    vi.stubGlobal('fetch', fetch)
    const first = readCachedOltOnus('olt-a')
    expect(readCachedOltOnus('olt-a')).toBe(first)
    expect(readCachedOltOnus('olt-a', true)).toBe(first)
    pending.resolve(Response.json(oltOnusSnapshot()))
    expect(await first).toEqual(oltOnusSnapshot())
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('separates OLT snapshots', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot()))
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot({ oltId: 'olt-b', onus: [unsupportedOnu] })))
    vi.stubGlobal('fetch', fetch)
    const a = await readCachedOltOnus('olt-a')
    const b = await readCachedOltOnus('olt-b')
    expect(await readCachedOltOnus('olt-a')).toBe(a)
    expect(await readCachedOltOnus('olt-b')).toBe(b)
    expect(a.onus).not.toEqual(b.onus)
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('Refresh bypasses a valid cache and replaces it', async () => {
    const next = oltOnusSnapshot({ onus: [unsupportedOnu], readAt: '2026-09-08T04:10:00Z' })
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot()))
      .mockResolvedValueOnce(Response.json(next))
    vi.stubGlobal('fetch', fetch)
    await readCachedOltOnus('olt-a')
    expect(await readCachedOltOnus('olt-a', true)).toEqual(next)
    expect(await readCachedOltOnus('olt-a')).toEqual(next)
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('evicts the previous snapshot when Refresh fails and allows a later retry', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot()))
      .mockResolvedValueOnce(Response.json({ detail: 'SNMP read failed' }, { status: 502 }))
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot()))
    vi.stubGlobal('fetch', fetch)
    await readCachedOltOnus('olt-a')
    await expect(readCachedOltOnus('olt-a', true)).rejects.toThrow('SNMP read failed')
    expect(getCachedOltOnus('olt-a')).toBeUndefined()
    await expect(readCachedOltOnus('olt-a')).resolves.toEqual(oltOnusSnapshot())
    expect(fetch).toHaveBeenCalledTimes(3)
  })

  it('does not cache a response for the wrong OLT', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot({ oltId: 'olt-b' })))
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot()))
    vi.stubGlobal('fetch', fetch)
    await expect(readCachedOltOnus('olt-a')).rejects.toThrow('Respons ONU di OLT tidak sesuai kontrak')
    expect(getCachedOltOnus('olt-a')).toBeUndefined()
    await expect(readCachedOltOnus('olt-a')).resolves.toEqual(oltOnusSnapshot())
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('caches a successful empty snapshot without repeatedly reading the device', async () => {
    const empty = oltOnusSnapshot({ onus: [], warnings: ['Empty serial table'] })
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json(empty))
    vi.stubGlobal('fetch', fetch)
    await readCachedOltOnus('olt-a')
    expect(await readCachedOltOnus('olt-a')).toEqual(empty)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('clears snapshots between sessions and ignores an earlier session response', async () => {
    const previous = deferred<Response>()
    const current = deferred<Response>()
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockReturnValueOnce(previous.promise)
      .mockReturnValueOnce(current.promise)
    vi.stubGlobal('fetch', fetch)
    const oldRead = readCachedOltOnus('olt-a')
    clearOltOnusCache()
    const newRead = readCachedOltOnus('olt-a')
    previous.resolve(Response.json(oltOnusSnapshot()))
    await oldRead
    expect(getCachedOltOnus('olt-a')).toBeUndefined()
    expect(readCachedOltOnus('olt-a')).toBe(newRead)
    const next = oltOnusSnapshot({ onus: [unsupportedOnu] })
    current.resolve(Response.json(next))
    expect(await newRead).toEqual(next)
    expect(getCachedOltOnus('olt-a')).toEqual(next)
    clearOltOnusCache()
    expect(getCachedOltOnus('olt-a')).toBeUndefined()
  })

  it('invalidates only one OLT and never lets its stale in-flight response repopulate', async () => {
    const oldA = deferred<Response>()
    const newA = deferred<Response>()
    const b = oltOnusSnapshot({ oltId: 'olt-b', oltCode: 'OLT-B', onus: [unsupportedOnu] })
    const freshA = oltOnusSnapshot({ onus: [unsupportedOnu], readAt: '2026-09-11T04:10:00Z' })
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockReturnValueOnce(oldA.promise)
      .mockResolvedValueOnce(Response.json(b))
      .mockReturnValueOnce(newA.promise)
    vi.stubGlobal('fetch', fetch)

    const staleRead = readCachedOltOnus('olt-a')
    await expect(readCachedOltOnus('olt-b')).resolves.toEqual(b)
    invalidateOltOnusCache('olt-a')
    const freshRead = readCachedOltOnus('olt-a')

    newA.resolve(Response.json(freshA))
    await expect(freshRead).resolves.toEqual(freshA)
    oldA.resolve(Response.json(oltOnusSnapshot()))
    await expect(staleRead).resolves.toEqual(oltOnusSnapshot())

    expect(getCachedOltOnus('olt-a')).toEqual(freshA)
    expect(getCachedOltOnus('olt-b')).toEqual(b)
    expect(fetch).toHaveBeenCalledTimes(3)
  })
})
