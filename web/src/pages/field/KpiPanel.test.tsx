import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TechnicianKpi } from '@/api/field'

const state = vi.hoisted(() => ({
  user: { tenantId: 'tenant-a', id: 'manager', permissions: ['kpi.team.view'] },
  session: 1,
  kpi: vi.fn(),
}))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: state.user }) }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ hasPermission: (permission: string) => state.user.permissions.includes(permission) }) }))
vi.mock('@/api/client', async importOriginal => ({ ...await importOriginal<typeof import('@/api/client')>(), tokenStore: { getSessionVersion: () => state.session } }))
vi.mock('@/api/field', () => ({ field: { kpi: state.kpi } }))

import { KpiPanel } from './KpiPanel'

const data: TechnicianKpi = {
  month: '2026-10', workingDays: 22, saturdayOff: true, average: 50, calendarError: null,
  technicians: [{ id: 'tech-ne', name: 'Teknisi Lama', team: 'NE' }],
  rows: [{ technician: { id: 'tech-ne', name: 'Teknisi Lama', team: 'NE' }, percentage: 50,
    metrics: Array.from({ length: 4 }, (_, index) => ({ key: String(index), label: 'Indikator ' + index, actual: 1, target: 2, percentage: 50 })),
    overdue: [], completed: [], clients: [],
  }],
}

describe('KpiPanel access changes', () => {
  beforeEach(() => {
    state.user = { tenantId: 'tenant-a', id: 'manager', permissions: ['kpi.team.view'] }
    state.session = 1
    state.kpi.mockReset().mockResolvedValue(data)
  })

  it('clears team filters and open details when team permission is removed', async () => {
    const view = render(<KpiPanel />), user = userEvent.setup()
    await screen.findByRole('button', { name: 'Teknisi Lama · NE' })
    await user.selectOptions(screen.getByLabelText('Tim teknisi'), 'NE')
    await waitFor(() => expect(state.kpi).toHaveBeenLastCalledWith(expect.any(String), 'NE', ''))
    await user.click(await screen.findByRole('button', { name: 'Teknisi Lama · NE' }))
    expect(screen.getByRole('dialog')).toBeTruthy()
    state.user = { ...state.user, permissions: ['kpi.own.view'] }
    view.rerender(<KpiPanel />)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.queryByLabelText('Tim teknisi')).toBeNull()
    await waitFor(() => expect(state.kpi).toHaveBeenLastCalledWith(expect.any(String), '', ''))
  })

  it('hides previous tenant data immediately while the new session is loading', async () => {
    const view = render(<KpiPanel />)
    await screen.findByRole('button', { name: 'Teknisi Lama · NE' })
    let complete: (result: TechnicianKpi) => void = () => { throw new Error('Query was not started') }
    state.kpi.mockImplementationOnce(() => new Promise<TechnicianKpi>(resolve => { complete = resolve }))
    state.user = { ...state.user, tenantId: 'tenant-b', id: 'manager-b' }
    state.session += 1
    view.rerender(<KpiPanel />)
    expect(screen.queryByText('Teknisi Lama')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Teknisi Lama · NE' })).toBeNull()
    await waitFor(() => expect(state.kpi).toHaveBeenCalledTimes(2))
    complete({ ...data, technicians: [], rows: [] })
    await screen.findByText('Tidak ada teknisi pada filter ini')
  })
})
