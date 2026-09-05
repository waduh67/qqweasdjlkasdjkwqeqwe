import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import * as provisioningApi from '@/api/provisioning'
import { useProvisioningApply, useProvisioningDraft, useProvisioningIntents } from './useProvisioning'

afterEach(() => vi.restoreAllMocks())

describe('useProvisioningDraft', () => {
  it('memisahkan draf lokal dari revisi plan server', async () => {
    vi.spyOn(provisioningApi, 'previewProvisioning').mockResolvedValue({
      plan: {
        id: 'plan-1', tenantId: 'tenant-1', intentId: 'intent-1', revision: 8,
        status: 'VALIDATED', contentHash: 'hash', preconditionHash: 'precondition', steps: [],
      },
      decision: { allowed: true, code: 'ALLOWED', warnings: [], evidenceIds: [] },
    })
    const { result } = renderHook(() => useProvisioningDraft({ name: 'Awal' }))

    act(() => result.current.setDraft({ name: 'Suntingan lokal' }))
    await act(() => result.current.previewPlan('plan-1', 'DRY_RUN'))

    expect(result.current.draft).toEqual({ name: 'Suntingan lokal' })
    expect(result.current.serverPlanRevision).toBe(8)
  })

  it('membatalkan preview lama ketika preview baru dimulai', async () => {
    const signals: AbortSignal[] = []
    vi.spyOn(provisioningApi, 'previewProvisioning').mockImplementation((_id, _mode, signal) => {
      if (signal) signals.push(signal)
      return new Promise(() => {})
    })
    const { result, unmount } = renderHook(() => useProvisioningDraft({ name: 'Draf' }))

    act(() => { void result.current.previewPlan('plan-1', 'DRY_RUN') })
    act(() => { void result.current.previewPlan('plan-2', 'SIMULATOR') })

    expect(signals[0]?.aborted).toBe(true)
    expect(signals[1]?.aborted).toBe(false)
    unmount()
    expect(signals[1]?.aborted).toBe(true)
  })

  it('membuang preview server saat draf plan berubah', async () => {
    vi.spyOn(provisioningApi, 'previewProvisioning').mockResolvedValue({
      plan: { id: 'plan-1', tenantId: 'tenant-1', intentId: 'intent-1', revision: 2, status: 'VALIDATED', contentHash: 'hash', preconditionHash: 'before', steps: [] },
      decision: { allowed: true, code: 'ALLOWED', warnings: [], evidenceIds: [] },
    })
    const { result } = renderHook(() => useProvisioningDraft({ planId: 'plan-1' }))
    await act(() => result.current.previewPlan('plan-1', 'DRY_RUN'))

    act(() => result.current.setDraft({ planId: 'plan-2' }))

    expect(result.current.preview).toBeNull()
    expect(result.current.serverPlanRevision).toBeNull()
  })

  it('membatalkan preview aktif saat intent berubah', () => {
    let signal: AbortSignal | undefined
    vi.spyOn(provisioningApi, 'previewProvisioning').mockImplementation((_id, _mode, requestSignal) => {
      signal = requestSignal
      return new Promise(() => {})
    })
    const { result } = renderHook(() => useProvisioningDraft({ planId: 'plan-1' }))

    act(() => { void result.current.previewPlan('plan-1', 'DRY_RUN') })
    act(() => result.current.setDraft({ planId: '' }))

    expect(signal?.aborted).toBe(true)
    expect(result.current.previewing).toBe(false)
  })

  it('abort preview lama tidak mematikan loading request pengganti', async () => {
    let resolveSecond: (() => void) | undefined
    vi.spyOn(provisioningApi, 'previewProvisioning')
      .mockImplementationOnce(() => new Promise(() => {}))
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveSecond = () => resolve({
          plan: { id: 'plan-2', tenantId: 'tenant-1', intentId: 'intent-1', revision: 2, status: 'VALIDATED', contentHash: 'hash', preconditionHash: 'before', steps: [] },
          decision: { allowed: true, code: 'ALLOWED', warnings: [], evidenceIds: [] },
        })
      }))
    const { result } = renderHook(() => useProvisioningDraft({ planId: 'plan-1' }))

    act(() => { void result.current.previewPlan('plan-1', 'DRY_RUN') })
    act(() => { void result.current.previewPlan('plan-2', 'DRY_RUN') })
    expect(result.current.previewing).toBe(true)
    await act(async () => resolveSecond?.())

    expect(result.current.previewing).toBe(false)
  })
})

describe('useProvisioningApply', () => {
  it('memakai ulang satu kunci untuk retry eksplisit dari attempt yang sama', async () => {
    const apply = vi.spyOn(provisioningApi, 'applyProvisioningPlan')
      .mockRejectedValueOnce(new TypeError('network'))
      .mockResolvedValueOnce({ id: 'exec-1', planId: 'plan-1', revision: 1, status: 'QUEUED' })
    const { result } = renderHook(() => useProvisioningApply())

    await act(() => result.current.apply('plan-1', 7))
    await act(() => result.current.retry())

    expect(apply).toHaveBeenCalledTimes(2)
    expect(apply.mock.calls[0]?.[2]).toBe(apply.mock.calls[1]?.[2])
  })

  it('tidak menawarkan retry untuk konflik plan stale', async () => {
    const stale = new ApiError(409, 'Plan berubah', undefined, 'STALE_PLAN')
    const apply = vi.spyOn(provisioningApi, 'applyProvisioningPlan').mockRejectedValue(stale)
    const { result } = renderHook(() => useProvisioningApply())

    await act(() => result.current.apply('plan-1', 7))

    await waitFor(() => expect(result.current.error).toBe(stale))
    expect(result.current.canRetry).toBe(false)
    await act(() => result.current.retry())
    expect(apply).toHaveBeenCalledOnce()
  })
})

describe('useProvisioningIntents', () => {
  it('membatalkan daftar lama saat muat ulang dan saat unmount', () => {
    const signals: AbortSignal[] = []
    vi.spyOn(provisioningApi, 'listServiceIntents').mockImplementation((signal) => {
      if (signal) signals.push(signal)
      return new Promise(() => {})
    })
    const { result, unmount } = renderHook(() => useProvisioningIntents())

    act(() => { void result.current.reload() })
    act(() => { void result.current.reload() })

    expect(signals[0]?.aborted).toBe(true)
    expect(signals[1]?.aborted).toBe(false)
    unmount()
    expect(signals[1]?.aborted).toBe(true)
  })
})
