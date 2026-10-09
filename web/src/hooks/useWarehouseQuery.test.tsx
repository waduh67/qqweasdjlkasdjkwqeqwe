import { act, renderHook, waitFor } from '@testing-library/react'
import { expect, it } from 'vitest'
import { useWarehouseQuery } from './useWarehouseQuery'

it('discards an old account response and reloads when account scope changes', async () => {
  const pending: ((value: string) => void)[] = []
  const loader = () => new Promise<string>(resolve => pending.push(resolve))
  const { result, rerender } = renderHook(({ scope }) => useWarehouseQuery(loader, scope), { initialProps: { scope: 'tenant:old' } })
  rerender({ scope: 'tenant:new' })
  expect(result.current.state.status).toBe('loading')
  await act(async () => { pending[0]?.('old account data') })
  expect(result.current.state.status).toBe('loading')
  await act(async () => { pending[1]?.('new account data') })
  await waitFor(() => expect(result.current.state).toEqual({ status: 'ready', data: 'new account data' }))
})
