import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { spliceCable, spliceFixture } from '@/test/spliceFixture'
import type { SpliceWorkbenchView } from '@/api/network'
const { apiGet, apiPost, apiPut, apiDel, can, confirm, toast } = vi.hoisted(() => ({
  apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiDel: vi.fn(), can: vi.fn((_permission: string) => true),
  confirm: vi.fn(() => Promise.resolve(true)), toast: { error: vi.fn(), success: vi.fn() },
}))
vi.mock('@/api/client', () => ({ api: { get: apiGet, post: apiPost, put: apiPut, del: apiDel }, ApiError: class ApiError extends Error {} }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can }) }))
vi.mock('@/hooks/useOpenWorkOrders', () => ({ useOpenWorkOrders: () => ({ canPick: false, fetchWorkOrders: vi.fn() }) }))
vi.mock('@/system', () => ({ useConfirm: () => confirm, useToast: () => toast }))
import { SplicingManager } from './SplicingManager'
async function setup(data = spliceFixture()) {
  apiGet.mockResolvedValue(data)
  const user = userEvent.setup(), changed = vi.fn()
  const view = render(<SplicingManager closureKind={data.closureKind} closureId={data.closureId} onChanged={changed} />)
  await screen.findByRole('table', { name: 'Daftar sambungan' })
  return { user, changed, ...view }
}
async function pick(user: ReturnType<typeof userEvent.setup>, label: string, option: string) {
  await user.click(screen.getByRole('combobox', { name: label }))
  await user.click(await screen.findByRole('option', { name: option }))
}
beforeEach(() => {
  vi.clearAllMocks(); apiGet.mockReset(); apiPost.mockResolvedValue({}); apiPut.mockResolvedValue({}); apiDel.mockResolvedValue({}); can.mockReturnValue(true)
})
describe('SplicingManager', () => {
  it('offers only splitter inputs and saves the reviewed remote-core pair once', async () => {
    const { user, changed } = await setup()
    expect(screen.queryByRole('combobox', { name: 'Kabel sumber' })).toBeNull()
    expect(screen.queryByText('Ada kabel lain di dalam kotak?')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Tambah sambungan' }))
    expect(screen.getByRole('combobox', { name: 'Jenis sambungan' })).toHaveProperty('value', 'INPUT')
    await pick(user, 'Core sumber', 'Core 1 · Biru')
    expect(screen.getByText('Core ini memiliki sambungan di lokasi lain.')).toBeTruthy()
    await user.click(screen.getByRole('combobox', { name: 'Port tujuan' }))
    expect(within(screen.getByRole('listbox')).getAllByRole('option').map(option => option.textContent)).toEqual(['Input'])
    await user.click(screen.getByRole('option', { name: 'Input' }))
    await user.click(screen.getByRole('tab', { name: 'Tinjau + buat' }))
    expect(screen.getByText('DIST-JB-ODP · Core 1 · Biru')).toBeTruthy()
    expect(screen.getByText('SPL-1 · 1:16 · Input')).toBeTruthy()
    expect(apiPost).not.toHaveBeenCalled()
    await user.dblClick(screen.getByRole('button', { name: 'Buat sambungan' }))
    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1))
    expect(apiPost).toHaveBeenCalledWith('/api/fiber-connections', expect.objectContaining({ closureKind: 'ODP', closureId: 'odp-qa',
      a: { kind: 'CORE', coreId: 'in-1' }, b: { kind: 'SPLITTER_IN', nodeId: 'spl-1', portNumber: null, portSide: null }, lossDb: null }))
    expect(changed).toHaveBeenCalledOnce()
  })
  it('excludes the source cable from destinations and clears the pair when purpose changes', async () => {
    const data = spliceFixture(); data.cables.push(spliceCable('out'))
    const { user } = await setup(data)
    await user.click(screen.getByRole('button', { name: 'Tambah sambungan' }))
    await user.selectOptions(screen.getByRole('combobox', { name: 'Jenis sambungan' }), 'CABLE')
    await pick(user, 'Kabel sumber', 'DIST-JB-ODP'); await pick(user, 'Core sumber', 'Core 7 · Merah')
    await user.click(screen.getByRole('combobox', { name: 'Kabel tujuan' }))
    expect(screen.queryByRole('option', { name: 'DIST-JB-ODP' })).toBeNull()
    await user.click(screen.getByRole('option', { name: 'DIST-ODP-LANJUTAN' }))
    await pick(user, 'Core tujuan', 'Core 7 · Merah')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Jenis sambungan' }), 'INPUT')
    expect(screen.getByRole('combobox', { name: 'Core sumber' })).toHaveProperty('value', '')
    expect(screen.queryByRole('combobox', { name: 'Core tujuan' })).toBeNull()
    await user.click(screen.getByRole('tab', { name: 'Tinjau + buat' }))
    expect(apiPost).not.toHaveBeenCalled(); expect(screen.queryByRole('button', { name: 'Buat sambungan' })).toBeNull()
  })
  it('explains the missing destination for a joint box with only one cable', async () => {
    const data = spliceFixture(); data.closureKind = 'JOINT_BOX'; data.points = []
    const { user } = await setup(data)
    await user.click(screen.getByRole('button', { name: 'Tambah sambungan' }))
    await pick(user, 'Core sumber', 'Core 1 · Biru')
    expect(screen.getByText('Belum ada kabel lain dengan core tersedia di lokasi ini.')).toBeTruthy()
    expect(screen.getByRole('combobox', { name: 'Core tujuan' })).toHaveProperty('disabled', true)
    expect(apiPost).not.toHaveBeenCalled()
  })
  it('reviews exact numbered bulk pairs without shifting occupied cores', async () => {
    const data = spliceFixture(); data.closureKind = 'JOINT_BOX'; data.points = []
    data.cables = [spliceCable('in', 5), spliceCable('out', 5)]
    data.cables[0].cores[1].connectionId = 'used'; data.cables[1].cores[3].connectionId = 'used'
    const { user } = await setup(data)
    await user.click(screen.getByRole('button', { name: 'Sambung core 1:1' }))
    await pick(user, 'Kabel sumber', 'DIST-JB-ODP'); await pick(user, 'Kabel tujuan', 'DIST-ODP-LANJUTAN')
    await user.click(screen.getByRole('tab', { name: 'Tinjau + buat' }))
    expect(within(screen.getByRole('table', { name: 'Pasangan core' })).getAllByRole('row')).toHaveLength(4)
    expect(apiPost).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Buat 3 sambungan' }))
    await waitFor(() => expect(apiPost).toHaveBeenCalledOnce())
    expect(apiPost.mock.calls[0][1].pairs.map((pair: { a: { coreId: string }; b: { coreId: string } }) => [pair.a.coreId, pair.b.coreId])).toEqual([
      ['in-1', 'out-1'], ['in-3', 'out-3'], ['in-5', 'out-5'],
    ])
  })
  it('validates loss and preserves the draft when saving fails', async () => {
    const { user } = await setup()
    await user.click(screen.getByRole('button', { name: 'Tambah sambungan' }))
    await pick(user, 'Core sumber', 'Core 7 · Merah')
    await user.click(screen.getByRole('tab', { name: 'Detail' }))
    await user.type(screen.getByRole('textbox', { name: 'Redaman (dB)' }), '-1')
    await user.click(screen.getByRole('tab', { name: 'Tinjau + buat' }))
    expect(screen.getByText('Redaman harus antara 0 dan 999 dB.')).toBeTruthy(); expect(apiPost).not.toHaveBeenCalled()
    await user.clear(screen.getByRole('textbox', { name: 'Redaman (dB)' })); await user.type(screen.getByRole('textbox', { name: 'Redaman (dB)' }), '0')
    await user.click(screen.getByRole('tab', { name: 'Tinjau + buat' }))
    apiPost.mockRejectedValueOnce(new Error('offline'))
    await user.click(screen.getByRole('button', { name: 'Buat sambungan' }))
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Sambungan gagal disimpan.'))
    expect(screen.getByText('DIST-JB-ODP · Core 7 · Merah')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Buat sambungan' }))
    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(2))
    expect(apiPost.mock.calls[1][1].lossDb).toBe(0)
  })
  it('separates cable attachment management and protects a physical endpoint', async () => {
    const { user } = await setup()
    await user.click(screen.getByRole('tab', { name: 'Kabel di lokasi' }))
    await user.click(screen.getByRole('checkbox', { name: 'Pilih DIST-JB-ODP' }))
    expect(screen.getByRole('button', { name: 'Ubah catatan' })).toHaveProperty('disabled', true)
    expect(screen.getByRole('button', { name: 'Hapus catatan' })).toHaveProperty('disabled', true)
    expect(screen.queryByRole('combobox', { name: 'Work order' })).toBeNull()
  })
  it('honors both read and manage permissions', async () => {
    can.mockImplementation(permission => permission !== 'network.splice.manage')
    const view = await setup()
    expect(screen.queryByRole('button', { name: 'Tambah sambungan' })).toBeNull(); expect(screen.queryByRole('checkbox')).toBeNull()
    view.unmount(); apiGet.mockClear(); can.mockReturnValue(false)
    render(<SplicingManager closureKind="ODP" closureId="other" />)
    expect(apiGet).not.toHaveBeenCalled()
  })
  it('retries a read failure and ignores the old location response', async () => {
    apiGet.mockRejectedValueOnce(new Error('offline'))
    const user = userEvent.setup(), view = render(<SplicingManager closureKind="ODP" closureId="first" />)
    await screen.findByText('Sambungan gagal dimuat.')
    let resolve!: (data: SpliceWorkbenchView) => void
    apiGet.mockImplementationOnce(() => new Promise<SpliceWorkbenchView>(done => { resolve = done }))
    await user.click(screen.getByRole('button', { name: 'Coba lagi' }))
    const next = spliceFixture(); next.cables = [spliceCable('out')]; apiGet.mockResolvedValue(next)
    view.rerender(<SplicingManager closureKind="ODP" closureId="second" />)
    await screen.findByRole('table', { name: 'Daftar sambungan' }); resolve(spliceFixture())
    await user.click(screen.getByRole('tab', { name: 'Kabel di lokasi' }))
    expect(screen.getByText('DIST-ODP-LANJUTAN')).toBeTruthy(); expect(screen.queryByText('DIST-JB-ODP')).toBeNull()
  })
})
