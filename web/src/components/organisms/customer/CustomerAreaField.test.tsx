import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { CustomerAreaField } from './CustomerAreaField'

const access = vi.hoisted(() => ({ allowed: true, areaIds: [] as string[], platformAdmin: false }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: () => access.allowed }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { areaIds: access.areaIds, platformAdmin: access.platformAdmin } }) }))
const areas = [
  { id: 'north', code: 'N', name: 'Utara', parentId: null },
  { id: 'north-child', code: 'N1', name: 'Utara satu', parentId: 'north' },
  { id: 'south', code: 'S', name: 'Selatan', parentId: null },
]
beforeEach(() => { access.allowed = true; access.areaIds = []; access.platformAdmin = false })
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
function transport() {
  const fetch = vi.fn(async () => new Response(JSON.stringify(areas)))
  vi.stubGlobal('fetch', fetch)
  return fetch
}

it('lets an unrestricted customer administrator assign a legacy customer to its first area', async () => {
  transport()
  const changed = vi.fn()
  render(<CustomerAreaField value={null} onChange={changed} />)
  await screen.findByRole('option', { name: 'Utara · N' })
  expect(screen.getByRole('option', { name: 'Selatan · S' })).toBeDefined()
  fireEvent.change(screen.getByRole('combobox', { name: 'Area pelanggan' }), { target: { value: 'north' } })
  expect(changed).toHaveBeenCalledWith('north')
})

it('limits a scoped operator to its area descendants and preserves an inaccessible existing assignment', async () => {
  access.areaIds = ['north']; transport()
  const changed = vi.fn()
  render(<CustomerAreaField value="south" onChange={changed} />)
  await screen.findByRole('option', { name: 'Utara satu · N1' })
  expect(screen.queryByRole('option', { name: 'Selatan · S' })).toBeNull()
  const saved = screen.getByRole('option', { name: 'Area tersimpan (nama tidak dapat diakses)' })
  expect(saved).toHaveProperty('disabled', true)
  expect(screen.getByRole('combobox', { name: 'Area pelanggan' })).toHaveProperty('value', 'south')
  expect(changed).not.toHaveBeenCalled()
})

it('does not load or offer areas without the area view permission', async () => {
  access.allowed = false; const fetch = transport()
  render(<CustomerAreaField value={null} onChange={vi.fn()} />)
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Area pelanggan' })).toHaveProperty('disabled', true))
  expect(fetch).not.toHaveBeenCalled()
  expect(screen.queryByRole('option', { name: 'Utara · N' })).toBeNull()
})
