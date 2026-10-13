import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { SaveCablePanel } from './SaveCablePanel'
import type { NodeKind } from '@/api/network'

vi.mock('@/api/client', () => ({ api: { get: vi.fn() } }))
vi.mock('@/system', () => ({ useConfirm: () => vi.fn() }))
const ports = [{ ponPortId: 'pon-1', portNumber: null, label: 'PON1', occupied: true, occupiedByCable: 'FEEDER-01' },
  { ponPortId: 'pon-2', portNumber: null, label: 'PON2', occupied: false, occupiedByCable: null }]
beforeEach(() => { vi.resetAllMocks(); vi.mocked(api.get).mockResolvedValue(ports) })
function setup(fromKind: NodeKind = 'OLT') {
  const save = vi.fn()
  const toKind = fromKind === 'OLT' ? 'ODC' : fromKind === 'ODC' ? 'JOINT_BOX' : 'ODP'
  render(<SaveCablePanel from={`${fromKind}-01`} to={`${toKind}-02`} fromKind={fromKind} fromId="from-1" toKind={toKind} toId="to-1"
    cableType={fromKind === 'OLT' ? 'FEEDER' : 'DISTRIBUTION'} lengthMeters={150} waypoints={[]} onRemoveWaypoint={vi.fn()} canAssignPort onCancel={vi.fn()} onSave={save} />)
  return { user: userEvent.setup(), save }
}

it('uses one labeled PON select, prevents occupied selection and includes the chosen port in review', async () => {
  const { user, save } = setup()
  await user.click(screen.getByRole('tab', { name: 'Sambungan' }))
  const field = await screen.findByRole('combobox', { name: 'Port PON' })
  expect(screen.getByRole('option', { name: 'PON1 · Terpakai (FEEDER-01)' })).toHaveProperty('disabled', true)
  expect(screen.queryByRole('button', { name: 'PON2' })).toBeNull()
  await user.selectOptions(field, 'pon-2')
  await user.click(screen.getByRole('tab', { name: 'Tinjau + buat' }))
  expect(screen.getByText('Port sumber').parentElement?.textContent).toBe('Port sumberPON2')
  expect(save).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'Simpan kabel' }))
  expect(save).toHaveBeenCalledWith(expect.objectContaining({ fromPonPortId: 'pon-2', fromPortNumber: undefined }))
})

it.each(['ODC', 'JOINT_BOX'] as NodeKind[])('shows the core connection workflow for %s without asking for a nonexistent source port', async fromKind => {
  vi.mocked(api.get).mockResolvedValue([])
  const { user } = setup(fromKind)
  await user.click(screen.getByRole('tab', { name: 'Sambungan' }))
  await screen.findByText(/Setelah kabel disimpan,.*Sambungan serat/)
  expect(screen.queryByRole('combobox', { name: 'Port sumber' })).toBeNull()
  expect(screen.getByText(`${fromKind}-01`)).toBeTruthy()
  expect(screen.queryByText(/SERATNYA|Joint box tak punya port|otomatis menyetel/)).toBeNull()
})

it('shows a failed source read with retry and prevents saving until ports are loaded', async () => {
  vi.mocked(api.get).mockRejectedValueOnce(new Error('offline'))
  const { user, save } = setup()
  await user.click(screen.getByRole('tab', { name: 'Sambungan' }))
  await screen.findByRole('alert')
  expect(screen.queryByText(/Belum ada port PON/)).toBeNull()
  expect(save).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'Coba lagi' }))
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Port PON' })).toBeTruthy())
})
