import { StrictMode } from 'react'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { deferred, deviceOnu, oltOnusSnapshot, unsupportedOnu } from '@/api/oltOnus.test-support'
import { OltDeviceOnus } from './OltDeviceOnus'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('OltDeviceOnus snapshot lifecycle', () => {
  it('reads once even in StrictMode, disables Refresh while loading, and never polls', async () => {
    const pending = deferred<Response>()
    const fetch = vi.fn<typeof globalThis.fetch>().mockReturnValue(pending.promise)
    vi.stubGlobal('fetch', fetch)
    render(<StrictMode><OltDeviceOnus oltId="olt-a" /></StrictMode>)
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('button', { name: 'Refresh' }).hasAttribute('disabled')).toBe(true)
    expect(screen.queryByText('Tidak ditemukan pada hasil baca ini')).toBeNull()
    await act(async () => pending.resolve(Response.json(oltOnusSnapshot())))
    expect(screen.getByRole('button', { name: 'Refresh' }).hasAttribute('disabled')).toBe(false)
    vi.useFakeTimers()
    await act(async () => { vi.advanceTimersByTime(300_000) })
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('shows an initial error distinct from empty and rereads only after manual Refresh', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(Response.json({ detail: 'OLT tidak merespons' }, { status: 502 }))
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot()))
    vi.stubGlobal('fetch', fetch)
    const user = userEvent.setup()
    render(<OltDeviceOnus oltId="olt-a" />)
    expect((await screen.findByRole('alert')).textContent).toContain('OLT tidak merespons')
    expect(screen.queryByText('Tidak ditemukan pada hasil baca ini')).toBeNull()
    expect(fetch).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText('HWTC00112233')).toBeDefined()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('clears old rows and their timestamp when Refresh fails, then recovers manually', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot()))
      .mockResolvedValueOnce(Response.json({ detail: 'Pembacaan gagal' }, { status: 503 }))
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot({ onus: [unsupportedOnu], readAt: '2026-09-08T04:10:00Z' })))
    vi.stubGlobal('fetch', fetch)
    const user = userEvent.setup()
    render(<OltDeviceOnus oltId="olt-a" />)
    await screen.findByText('HWTC00112233')
    await user.click(screen.getByRole('button', { name: 'Refresh' }))
    expect((await screen.findByRole('alert')).textContent).toContain('Pembacaan gagal')
    expect(screen.queryByText('HWTC00112233')).toBeNull()
    expect(screen.queryByText('2026-09-08T04:05:06Z')).toBeNull()
    expect(screen.queryByText('Tidak ditemukan pada hasil baca ini')).toBeNull()
    expect(fetch).toHaveBeenCalledTimes(2)
    await user.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText('ZTEG44556677')).toBeDefined()
    expect(screen.getByText('2026-09-08T04:10:00Z')).toBeDefined()
    expect(fetch).toHaveBeenCalledTimes(3)
  })

  it('aborts on OLT change and ignores late results even when fetch does not honor abort', async () => {
    const oldRead = deferred<Response>()
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockReturnValueOnce(oldRead.promise)
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot({ oltId: 'olt-b', oltCode: 'OLT-B', onus: [unsupportedOnu] })))
    vi.stubGlobal('fetch', fetch)
    const { rerender } = render(<OltDeviceOnus oltId="olt-a" />)
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
    const oldSignal = fetch.mock.calls[0]?.[1]?.signal
    rerender(<OltDeviceOnus oltId="olt-b" />)
    expect(oldSignal?.aborted).toBe(true)
    await screen.findByText('ZTEG44556677')
    await act(async () => oldRead.resolve(Response.json(oltOnusSnapshot())))
    expect(screen.queryByText('HWTC00112233')).toBeNull()
    expect(screen.getByText(/Sumber: OLT-B/)).toBeDefined()
    expect(fetch.mock.calls.map(([path]) => path)).toEqual([
      '/api/monitoring/olts/olt-a/onus', '/api/monitoring/olts/olt-b/onus',
    ])
  })

  it('resets a previous OLT snapshot and its search immediately when OLT changes', async () => {
    const nextRead = deferred<Response>()
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot()))
      .mockReturnValueOnce(nextRead.promise)
    vi.stubGlobal('fetch', fetch)
    const user = userEvent.setup()
    const { rerender } = render(<OltDeviceOnus oltId="olt-a" />)
    await screen.findByText('HWTC00112233')
    await user.type(screen.getByRole('searchbox'), 'hwtc')
    rerender(<OltDeviceOnus oltId="olt-b" />)
    expect(screen.queryByText('HWTC00112233')).toBeNull()
    expect(screen.queryByText('2026-09-08T04:05:06Z')).toBeNull()
    await act(async () => nextRead.resolve(Response.json(oltOnusSnapshot({ oltId: 'olt-b', onus: [unsupportedOnu] }))))
    expect(screen.getByText('ZTEG44556677')).toBeDefined()
    expect(screen.getByRole('searchbox').getAttribute('value')).toBe('')
  })

  it('aborts on unmount and safely ignores late rejection', async () => {
    const pending = deferred<Response>()
    const fetch = vi.fn<typeof globalThis.fetch>().mockReturnValue(pending.promise)
    vi.stubGlobal('fetch', fetch)
    const { unmount } = render(<OltDeviceOnus oltId="olt-a" />)
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
    const signal = fetch.mock.calls[0]?.[1]?.signal
    unmount()
    expect(signal?.aborted).toBe(true)
    await act(async () => pending.reject(new Error('late failure')))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('keeps local search across manual refresh while replacing snapshot values', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot()))
      .mockResolvedValueOnce(Response.json(oltOnusSnapshot({ onus: [{ ...deviceOnu, runningState: 'LOS' }] })))
    vi.stubGlobal('fetch', fetch)
    const user = userEvent.setup()
    render(<OltDeviceOnus oltId="olt-a" />)
    await screen.findByText('HWTC00112233')
    await user.type(screen.getByRole('searchbox'), 'hwtc')
    await user.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText('LOS')).toBeDefined()
    expect(screen.getByRole('searchbox').getAttribute('value')).toBe('hwtc')
    expect(screen.queryByText('ONLINE')).toBeNull()
  })
})
