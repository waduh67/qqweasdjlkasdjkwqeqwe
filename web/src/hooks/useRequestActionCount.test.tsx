import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { tokenStore } from '@/api/client'
import { useRequestActionCount } from './useRequestActionCount'

const identity = vi.hoisted(() => ({ tenantId: 'tenant-a', id: 'admin', permissions: ['warehouse.request.review'], writable: true }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: identity }) }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: (permission: string) => identity.writable && identity.permissions.includes(permission) }) }))
const response = (count: number) => new Response(JSON.stringify({ count }), { headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  identity.tenantId = 'tenant-a'; identity.id = 'admin'; identity.permissions = ['warehouse.request.review']; identity.writable = true
  tokenStore.clear()
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('refreshes the actionable badge after a successful request command', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(response(3)).mockResolvedValueOnce(response(1))
  vi.stubGlobal('fetch', fetch)
  const { result } = renderHook(() => useRequestActionCount(true))
  await waitFor(() => expect(result.current).toEqual({ status: 'ready', count: 3 }))

  act(() => window.dispatchEvent(new Event('warehouse:requests-changed')))

  await waitFor(() => expect(result.current).toEqual({ status: 'ready', count: 1 }))
  expect(fetch).toHaveBeenCalledTimes(2)
})

it('discards an outstanding count when the same account starts a new session', async () => {
  let resolveOld: (value: Response) => void = () => { throw new Error('Old request has not started') }
  const old = new Promise<Response>(resolve => { resolveOld = resolve })
  const fetch = vi.fn().mockReturnValueOnce(old).mockResolvedValueOnce(response(2))
  vi.stubGlobal('fetch', fetch)
  const { result } = renderHook(() => useRequestActionCount(true))

  await act(async () => { tokenStore.setAccessToken('new-test-session'); resolveOld(response(99)) })

  await waitFor(() => expect(result.current).toEqual({ status: 'ready', count: 2 }))
  expect(fetch).toHaveBeenCalledTimes(2)
})

it('hides a previous tenant count immediately while loading the new tenant', async () => {
  let resolveNew: (value: Response) => void = () => { throw new Error('New request has not started') }
  const next = new Promise<Response>(resolve => { resolveNew = resolve })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(7)).mockReturnValueOnce(next))
  const { result, rerender } = renderHook(() => useRequestActionCount(true))
  await waitFor(() => expect(result.current).toEqual({ status: 'ready', count: 7 }))

  identity.tenantId = 'tenant-b'; rerender()

  expect(result.current).toEqual({ status: 'loading' })
  await act(async () => resolveNew(response(0)))
  expect(result.current).toEqual({ status: 'ready', count: 0 })
})

it('does not request a badge for technicians or a subscription locked role', () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  identity.permissions = ['warehouse.request.own']
  const { result, rerender } = renderHook(() => useRequestActionCount(true))
  expect(result.current).toBeNull()

  identity.permissions = ['warehouse.request.review']; identity.writable = false; rerender()

  expect(result.current).toBeNull()
  expect(fetch).not.toHaveBeenCalled()
})

it('shows a failed count as an error and retries on the next request change', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response('{}', { status: 503 })).mockResolvedValueOnce(response(4)))
  const { result } = renderHook(() => useRequestActionCount(true))
  await waitFor(() => expect(result.current).toEqual({ status: 'error' }))

  act(() => window.dispatchEvent(new Event('warehouse:requests-changed')))

  await waitFor(() => expect(result.current).toEqual({ status: 'ready', count: 4 }))
})
