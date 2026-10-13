import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { mapSiteKey, useMapSites } from './useMapSites'
import type { SiteView } from '@/api/network'

vi.mock('@/api/client', () => ({ api: { get: vi.fn() } }))
const site: SiteView = { id: 'site-a', code: 'POP-A', name: 'Bandung', location: { longitude: 107.6, latitude: -6.9 }, address: null, areaId: null, oltCount: 2 }
const page = (sites = [site], totalPages = 1) => ({ content: sites, totalPages })
beforeEach(() => { vi.restoreAllMocks(); vi.resetAllMocks(); localStorage.clear(); vi.mocked(api.get).mockResolvedValue(page()) })

it('restores from every page using current site coordinates and only stores the selected ID', async () => {
  localStorage.setItem(mapSiteKey('tenant:user'), site.id)
  vi.mocked(api.get).mockResolvedValueOnce(page([], 2)).mockResolvedValueOnce(page([site], 2))
  const focus = vi.fn()
  const { result } = renderHook(() => useMapSites('tenant:user', true, focus))
  expect(result.current.hasSavedSite).toBe(true)
  await waitFor(() => expect(result.current.selected).toEqual(site))
  expect(api.get).toHaveBeenLastCalledWith('/api/sites?page=1&size=200')
  expect(focus).toHaveBeenCalledWith(site, true)
  expect(localStorage.getItem(mapSiteKey('tenant:user'))).toBe('site-a')
})

it('persists selection when leaving and restores it on returning', async () => {
  const focus = vi.fn()
  const first = renderHook(() => useMapSites('tenant:user', true, focus))
  await waitFor(() => expect(first.result.current.loading).toBe(false))
  act(() => first.result.current.select(site))
  expect(focus).toHaveBeenLastCalledWith(site, false)
  first.unmount()
  const next = renderHook(() => useMapSites('tenant:user', true, focus))
  await waitFor(() => expect(next.result.current.selected?.id).toBe(site.id))
  expect(focus).toHaveBeenLastCalledWith(site, true)
  act(() => next.result.current.select(null))
  expect(localStorage.getItem(mapSiteKey('tenant:user'))).toBeNull()
})

it('isolates accounts and ignores a response belonging to the previous tenant', async () => {
  let resolve!: (value: unknown) => void
  localStorage.setItem(mapSiteKey('tenant-a:user'), site.id)
  vi.mocked(api.get).mockImplementationOnce(() => new Promise(done => { resolve = done })).mockResolvedValue(page([]))
  const focus = vi.fn()
  const { result, rerender } = renderHook(({ scope }) => useMapSites(scope, true, focus), { initialProps: { scope: 'tenant-a:user' } })
  rerender({ scope: 'tenant-b:user' })
  await waitFor(() => expect(result.current.loading).toBe(false))
  await act(async () => resolve(page()))
  expect(result.current.sites).toEqual([])
  expect(result.current.selected).toBeNull()
  expect(focus).not.toHaveBeenCalled()
})

it('keeps a saved selection after a failed read and allows retry', async () => {
  localStorage.setItem(mapSiteKey('scope'), site.id)
  vi.mocked(api.get).mockRejectedValueOnce(new Error('offline'))
  const focus = vi.fn()
  const { result } = renderHook(() => useMapSites('scope', true, focus))
  await waitFor(() => expect(result.current.error).toBe('Daftar site gagal dimuat.'))
  expect(localStorage.getItem(mapSiteKey('scope'))).toBe(site.id)
  act(() => result.current.retry())
  await waitFor(() => expect(result.current.selected).toEqual(site))
  expect(result.current.error).toBe('')
})

it('clears unavailable saved sites and does not fetch without permission', async () => {
  localStorage.setItem(mapSiteKey('scope'), 'deleted-site')
  const focus = vi.fn()
  const { result, rerender } = renderHook(({ enabled }) => useMapSites('scope', enabled, focus), { initialProps: { enabled: true } })
  await waitFor(() => expect(result.current.loading).toBe(false))
  expect(localStorage.getItem(mapSiteKey('scope'))).toBeNull()
  expect(focus).not.toHaveBeenCalled()
  vi.mocked(api.get).mockClear()
  rerender({ enabled: false })
  expect(result.current.sites).toEqual([])
  expect(api.get).not.toHaveBeenCalled()
})

it('does not restore a selection cleared while loading and works when storage is blocked', async () => {
  let resolve!: (value: unknown) => void
  localStorage.setItem(mapSiteKey('scope'), site.id)
  vi.mocked(api.get).mockImplementationOnce(() => new Promise(done => { resolve = done }))
  const focus = vi.fn()
  const { result } = renderHook(() => useMapSites('scope', true, focus))
  act(() => result.current.select(null))
  await act(async () => resolve(page()))
  expect(result.current.selected).toBeNull()
  expect(focus).not.toHaveBeenCalled()
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
  act(() => result.current.select(site))
  expect(focus).toHaveBeenCalledWith(site, false)
})
