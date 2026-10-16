import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { CableView } from '@/api/network'
import { spliceCable } from '@/test/spliceFixture'
import { CablePanel } from './CablePanel'
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: () => false }) }))
vi.mock('@/system', () => ({ useToast: () => toast, useConfirm: () => vi.fn() }))
it('retains the core selection and note when visiting another cable section without saving', async () => {
  const data = spliceCable()
  vi.spyOn(api, 'get').mockResolvedValue({ cableId: 'cable-1', cableCode: 'DIST-01', cableName: 'Distribusi 01', coreCount: 12, coresPerTube: 12, free: 11, used: 1, reserved: 0, damaged: 0, cores: data.cores.map(core => core.core) })
  const put = vi.spyOn(api, 'put')
  const cable: CableView = { id: 'cable-1', code: 'DIST-01', name: 'Distribusi 01', cableType: 'DISTRIBUTION', coreCount: 12, route: { points: [] }, lengthMeters: 154,
    attachments: [], fromKind: 'JOINT_BOX', fromId: 'jb-1', toKind: 'ODP', toId: 'odp-1', fromPonPortId: null, fromPortNumber: null, toPortNumber: null,
    fromPortLabel: null, status: 'ACTIVE', installation: null, installationLabel: null, ownership: 'OWNED', ownershipLabel: 'Milik sendiri' }
  const noop = vi.fn(), user = userEvent.setup()
  render(<CablePanel cable={cable} causes={[]} canEdit canDelete={false} canSimulate={false} canReleaseDrop={false} canViewOtdr={false} canRecordOtdr={false}
    otdrTests={[]} onRecordOtdr={noop} onDeleteOtdr={noop} onFocusOtdr={noop} onEdit={noop} onDelete={noop} onSimulate={noop} onReleased={noop} onReuse={noop}
    onPhysicalChange={noop} onRename={noop} onClose={noop} />)
  expect(api.get).not.toHaveBeenCalled()
  await user.click(screen.getByRole('tab', { name: 'Core kabel' }))
  const core = await screen.findByRole('button', { name: 'Core 2 · Oranye · Bebas' })
  await user.click(core)
  await user.type(screen.getByRole('textbox', { name: 'Catatan' }), 'Untuk pelanggan berikutnya')
  await user.click(screen.getByRole('tab', { name: 'Jalur kabel' }))
  expect(screen.queryByRole('textbox', { name: 'Catatan' })).toBeNull()
  await user.click(screen.getByRole('tab', { name: 'Core kabel' }))
  expect(core.getAttribute('aria-pressed')).toBe('true')
  expect(screen.getByRole('textbox', { name: 'Catatan' })).toHaveProperty('value', 'Untuk pelanggan berikutnya')
  expect(put).not.toHaveBeenCalled(); expect(noop).not.toHaveBeenCalled()
  vi.restoreAllMocks()
})
