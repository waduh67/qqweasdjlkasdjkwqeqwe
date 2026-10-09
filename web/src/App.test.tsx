import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useCan } from './auth/useCan'
import { RequirePermission } from './App'

vi.mock('./auth/useCan', () => ({ useCan: vi.fn() }))

const mockedUseCan = vi.mocked(useCan)

beforeEach(() => {
  mockedUseCan.mockReturnValue({
    can: () => false,
    canAny: () => false,
    hasPermission: () => false,
    isPlatformAdmin: false,
  })
})

describe('RequirePermission', () => {
  it('menolak route provisioning tanpa izin view', () => {
    render(
      <RequirePermission permission="provisioning.segment.view">
        <p>Workspace provisioning</p>
      </RequirePermission>,
    )

    expect(screen.queryByText('Workspace provisioning')).toBeNull()
    expect(screen.getByText('Akses ditolak').textContent).toBe('Akses ditolak')
  })

  it('membuka route provisioning dengan izin view', () => {
    mockedUseCan.mockReturnValue({
      can: (permission) => permission === 'provisioning.segment.view',
      canAny: () => false,
      hasPermission: (permission) => permission === 'provisioning.segment.view',
      isPlatformAdmin: false,
    })

    render(
      <RequirePermission permission="provisioning.segment.view">
        <p>Workspace provisioning</p>
      </RequirePermission>,
    )

    expect(screen.getByText('Workspace provisioning').textContent).toBe('Workspace provisioning')
  })

  it('keeps granted field reads accessible while subscription writes are locked', () => {
    mockedUseCan.mockReturnValue({
      can: () => false,
      canAny: () => false,
      hasPermission: permission => permission === 'workorder.order.field',
      isPlatformAdmin: false,
    })
    render(<RequirePermission access="read" permission="workorder.order.field"><p>Tugas tersimpan</p></RequirePermission>)
    expect(screen.getByText('Tugas tersimpan').textContent).toBe('Tugas tersimpan')
  })
})
