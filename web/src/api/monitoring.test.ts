import { afterEach, describe, expect, it, vi } from 'vitest'
import { InvalidManualOltPollResponseError, parseManualOltPollResult, pollOltNow } from './monitoring'

const result = {
  oltId: 'olt-a',
  oltCode: 'OLT-01',
  reachable: true,
  readingCount: 32,
  failureReason: null,
  checkedAt: '2026-09-11T03:04:05Z',
}

afterEach(() => vi.unstubAllGlobals())

describe('parseManualOltPollResult', () => {
  it('accepts exactly the six allowed fields', () => {
    expect(parseManualOltPollResult(result)).toEqual(result)
  })

  it('rejects an extra seventh field', () => {
    expect(() => parseManualOltPollResult({
      ...result,
      snmpCommunity: 'frontend-exact-parser-sentinel',
    })).toThrow(InvalidManualOltPollResponseError)
  })

  it('rejects an extra enumerable Symbol field', () => {
    const response = { ...result }
    Object.defineProperty(response, Symbol('metadata'), { enumerable: true, value: 'unexpected' })

    expect(() => parseManualOltPollResult(response)).toThrow(InvalidManualOltPollResponseError)
  })

  it.each([
    '2024-02-29T23:59:59Z',
    '2026-09-11T10:04:05+07:00',
    '2026-09-11T03:04:05.123456789Z',
  ])('preserves supported backend timestamp %s', (checkedAt) => {
    expect(parseManualOltPollResult({ ...result, checkedAt }).checkedAt).toBe(checkedAt)
  })

  it.each([
    '2026-02-31T08:00:00Z',
    '2025-02-29T08:00:00+07:00',
    '2026-04-31T08:00:00.123456789Z',
  ])('rejects impossible calendar date %s', (checkedAt) => {
    expect(() => parseManualOltPollResult({ ...result, checkedAt })).toThrow(InvalidManualOltPollResponseError)
  })

  it.each([
    null,
    [],
    {},
    { ...result, oltId: 7 },
    { ...result, oltCode: '' },
    { ...result, reachable: 'true' },
    { ...result, readingCount: -1 },
    { ...result, readingCount: 1.5 },
    { ...result, failureReason: 42 },
    { ...result, checkedAt: 'not-a-timestamp' },
  ])('rejects malformed response %#', (value: unknown) => {
    expect(() => parseManualOltPollResult(value)).toThrow(InvalidManualOltPollResponseError)
  })
})

describe('pollOltNow', () => {
  it('posts to the encoded OLT endpoint and parses the response', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json({ ...result, oltId: 'olt/a b' }))
    vi.stubGlobal('fetch', fetch)

    await expect(pollOltNow('olt/a b')).resolves.toEqual({ ...result, oltId: 'olt/a b' })
    expect(fetch).toHaveBeenCalledWith(
      '/api/monitoring/olts/olt%2Fa%20b/poll',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('rejects a response belonging to another OLT', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json({ ...result, oltId: 'olt-b' })))

    await expect(pollOltNow('olt-a')).rejects.toThrow(InvalidManualOltPollResponseError)
  })
})
