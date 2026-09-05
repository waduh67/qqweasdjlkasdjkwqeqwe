import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as provisioningApi from '@/api/provisioning'
import { useProvisioningExecution } from './useProvisioningExecution'

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('useProvisioningExecution', () => {
  it('melacak status nonterminal lalu berhenti pada status terminal', async () => {
    const get = vi.spyOn(provisioningApi, 'getProvisioningExecution')
      .mockResolvedValueOnce({ id: 'exec-1', planId: 'plan-1', revision: 1, status: 'QUEUED' })
      .mockResolvedValueOnce({ id: 'exec-1', planId: 'plan-1', revision: 2, status: 'RUNNING' })
      .mockResolvedValueOnce({ id: 'exec-1', planId: 'plan-1', revision: 3, status: 'VERIFYING' })
      .mockResolvedValueOnce({ id: 'exec-1', planId: 'plan-1', revision: 4, status: 'SUCCEEDED' })
    const { result } = renderHook(() => useProvisioningExecution('exec-1'))

    await act(() => vi.advanceTimersByTimeAsync(0))
    await act(() => vi.advanceTimersByTimeAsync(500))
    await act(() => vi.advanceTimersByTimeAsync(1_000))
    await act(() => vi.advanceTimersByTimeAsync(2_000))

    expect(result.current.execution?.status).toBe('SUCCEEDED')
    expect(result.current.history.map((item) => item.status)).toEqual([
      'QUEUED', 'RUNNING', 'VERIFYING', 'SUCCEEDED',
    ])
    await act(() => vi.advanceTimersByTimeAsync(10_000))
    expect(get).toHaveBeenCalledTimes(4)
  })

  it('membatalkan request aktif dan timer saat unmount', async () => {
    let signal: AbortSignal | undefined
    vi.spyOn(provisioningApi, 'getProvisioningExecution').mockImplementation((_id, requestSignal) => {
      signal = requestSignal
      return new Promise(() => {})
    })
    const { unmount } = renderHook(() => useProvisioningExecution('exec-1'))

    await act(() => vi.advanceTimersByTimeAsync(0))
    expect(signal).toBeDefined()
    unmount()

    expect(signal?.aborted).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('melanjutkan polling setelah kegagalan transien', async () => {
    const get = vi.spyOn(provisioningApi, 'getProvisioningExecution')
      .mockRejectedValueOnce(new Error('temporarily unavailable'))
      .mockResolvedValueOnce({ id: 'exec-1', planId: 'plan-1', revision: 2, status: 'SUCCEEDED' })
    const { result } = renderHook(() => useProvisioningExecution('exec-1'))

    await act(() => vi.advanceTimersByTimeAsync(0))
    expect(result.current.error).toBeInstanceOf(Error)
    await act(() => vi.advanceTimersByTimeAsync(500))

    expect(get).toHaveBeenCalledTimes(2)
    expect(result.current.execution?.status).toBe('SUCCEEDED')
    expect(result.current.error).toBeNull()
  })

  it('membersihkan status lama saat execution id berubah', async () => {
    vi.spyOn(provisioningApi, 'getProvisioningExecution')
      .mockResolvedValueOnce({ id: 'exec-1', planId: 'plan-1', revision: 1, status: 'SUCCEEDED' })
      .mockResolvedValueOnce({ id: 'exec-2', planId: 'plan-2', revision: 1, status: 'QUEUED' })
    const { result, rerender } = renderHook(({ id }) => useProvisioningExecution(id), {
      initialProps: { id: 'exec-1' as string | null },
    })
    await act(() => vi.advanceTimersByTimeAsync(0))

    rerender({ id: 'exec-2' })
    await act(() => vi.advanceTimersByTimeAsync(0))

    expect(result.current.execution?.id).toBe('exec-2')
    expect(result.current.history.map((item) => item.id)).toEqual(['exec-2'])
  })
})
