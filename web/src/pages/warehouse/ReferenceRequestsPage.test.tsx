import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { selectControl } from '@/test/selectControl'
import { ReferenceRequestsPage } from './ReferenceRequestsPage'

vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { id: 'admin', tenantId: 'tenant' } }) }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: () => true, hasPermission: () => true }) }))
function Location() { return <output aria-label="Current filter">{useLocation().search}</output> }
afterEach(() => vi.unstubAllGlobals())

it('keeps state when changing actionable tabs and keeps action scope when changing state', async () => {
  const fetch = vi.fn(async () => new Response(JSON.stringify({ items: [], page: 0, size: 25, totalElements: 0 }), { headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter initialEntries={['/warehouse/requests?state=SUBMITTED&actions=true']}><ReferenceRequestsPage /><Location /></MemoryRouter>)
  await screen.findByText('Belum ada permintaan')
  await selectControl(screen.getByRole('combobox', { name: 'Status permintaan' }), { target: { value: 'MANAGER_REVIEW' } })
  await waitFor(() => expect(screen.getByLabelText('Current filter').textContent).toBe('?state=MANAGER_REVIEW&actions=true'))

  fireEvent.click(screen.getByRole('tab', { name: 'Semua Request' }))

  await waitFor(() => expect(screen.getByLabelText('Current filter').textContent).toBe('?state=MANAGER_REVIEW'))
  await waitFor(() => expect(fetch.mock.calls.length).toBe(3))
})
