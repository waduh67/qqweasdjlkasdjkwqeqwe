import { afterEach, describe, expect, it, vi } from 'vitest'
import { InvalidOltOnusResponseError, parseOltOnus, readOltOnus } from './oltOnus'
import { deviceOnu, oltOnusSnapshot, unsupportedOnu } from './oltOnus.test-support'

afterEach(() => vi.unstubAllGlobals())

describe('parseOltOnus', () => {
  it('parses a device inventory without customer data and preserves device-clock strings', () => {
    const snapshot = oltOnusSnapshot({ onus: [deviceOnu, unsupportedOnu], warnings: ['Partial read'] })
    expect(parseOltOnus(snapshot)).toEqual(snapshot)
    expect(parseOltOnus(snapshot).onus[0]?.lastDownTime).toBe('31/12/2000 23:59:59')
  })

  it.each(['ONLINE', 'OFFLINE', 'LOS', 'UNKNOWN', null])('accepts running state %s unchanged', (runningState) => {
    const parsed = parseOltOnus(oltOnusSnapshot({ onus: [] }))
    expect(parseOltOnus({ ...parsed, onus: [{ ...deviceOnu, runningState }] }).onus[0]?.runningState).toBe(runningState)
  })

  it('preserves nullable source and zero receive power', () => {
    const snapshot = oltOnusSnapshot({ systemDescription: null, onus: [{ ...deviceOnu, rxPowerDbm: 0 }] })
    expect(parseOltOnus(snapshot)).toEqual(snapshot)
  })

  it.each([
    null, [], {},
    { ...oltOnusSnapshot(), oltId: 7 },
    { ...oltOnusSnapshot(), systemDescription: undefined },
    { ...oltOnusSnapshot(), readAt: 'not an ISO timestamp' },
    { ...oltOnusSnapshot(), onus: null },
    { ...oltOnusSnapshot(), warnings: [null] },
    ...[
      { index: 1 }, { ontId: undefined }, { serialNumber: null }, { name: 12 },
      { runningState: 'UP' }, { rxPowerDbm: '-20' }, { rxPowerDbm: Infinity },
      { rxPowerDbm: NaN }, { lastUpTime: 100 }, { lastDownCause: {} },
    ].map((override) => ({ ...oltOnusSnapshot(), onus: [{ ...deviceOnu, ...override }] })),
  ])('rejects malformed response %#', (value: unknown) => {
    expect(() => parseOltOnus(value)).toThrow(InvalidOltOnusResponseError)
  })
})

describe('readOltOnus', () => {
  it('uses the existing GET client with encoded OLT id and abort signal', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json(oltOnusSnapshot({ oltId: 'olt/a b' })))
    vi.stubGlobal('fetch', fetch)
    const controller = new AbortController()
    expect((await readOltOnus('olt/a b', controller.signal)).oltId).toBe('olt/a b')
    expect(fetch).toHaveBeenCalledWith('/api/monitoring/olts/olt%2Fa%20b/onus', expect.objectContaining({ method: 'GET', signal: controller.signal }))
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('rejects snapshots belonging to a different OLT', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json(oltOnusSnapshot({ oltId: 'olt-b' }))))
    await expect(readOltOnus('olt-a')).rejects.toThrow(InvalidOltOnusResponseError)
  })

  it('surfaces backend errors without retrying', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json({ detail: 'OLT tidak merespons' }, { status: 502 }))
    vi.stubGlobal('fetch', fetch)
    await expect(readOltOnus('olt-a')).rejects.toThrow('OLT tidak merespons')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
