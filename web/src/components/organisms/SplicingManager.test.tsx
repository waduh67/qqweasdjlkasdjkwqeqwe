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
  await screen.findByRole(can('network.splice.manage') ? 'region' : 'table', { name: can('network.splice.manage') ? 'Meja sambung' : 'Daftar sambungan' })
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
  it('connects directly from colored points, offers only inputs and prevents duplicate saves', async () => {
    const { user, changed } = await setup()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.queryByRole('combobox')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Pilih sumber DIST-JB-ODP · Core 1 · Biru' }))
    const targets = within(screen.getByRole('region', { name: 'Titik tujuan' }))
    expect(targets.getAllByRole('button')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Sambungkan' })).toHaveProperty('disabled', true)
    await user.click(targets.getByRole('button', { name: 'Pilih tujuan SPL-1 · 1:16 · Input' }))
    expect(apiPost).not.toHaveBeenCalled()
    expect(screen.getByRole('status', { name: 'Pasangan sambungan' }).textContent).toContain('DIST-JB-ODP · Core 1 · Biru')
    await user.dblClick(screen.getByRole('button', { name: 'Sambungkan' }))
    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1))
    expect(apiPost).toHaveBeenCalledWith('/api/fiber-connections', expect.objectContaining({ closureKind: 'ODP', closureId: 'odp-qa',
      a: { kind: 'CORE', coreId: 'in-1' }, b: { kind: 'SPLITTER_IN', nodeId: 'spl-1', portNumber: null, portSide: null }, lossDb: null }))
    expect(changed).toHaveBeenCalledOnce()
    expect(screen.getByRole('button', { name: 'Sambungkan' })).toHaveProperty('disabled', true)
  })
  it('excludes the source cable from the destination board and clears changed purposes', async () => {
    const data = spliceFixture(); data.cables.push(spliceCable('out'))
    const { user } = await setup(data)
    await user.click(screen.getByRole('button', { name: 'Antar kabel' }))
    await user.click(screen.getByRole('button', { name: 'Pilih sumber DIST-JB-ODP · Core 7 · Merah' }))
    expect(within(screen.getByRole('region', { name: 'Titik tujuan' })).queryByRole('region', { name: 'DIST-JB-ODP' })).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Pilih tujuan DIST-ODP-LANJUTAN · Core 7 · Merah' }))
    await user.click(screen.getByRole('button', { name: 'Input splitter' }))
    expect(screen.getByRole('button', { name: 'Sambungkan' })).toHaveProperty('disabled', true)
    expect(screen.queryByRole('button', { name: /Pilih tujuan/ })).toBeNull()
    expect(apiPost).not.toHaveBeenCalled()
  })
  it('toggles selected points off and retains the pair while visiting the list', async () => {
    const { user } = await setup()
    await user.click(screen.getByRole('button', { name: 'Pilih sumber DIST-JB-ODP · Core 1 · Biru' }))
    await user.click(screen.getByRole('button', { name: 'Pilih tujuan SPL-1 · 1:16 · Input' }))
    await user.click(screen.getByRole('tab', { name: 'Daftar sambungan' }))
    await user.click(screen.getByRole('tab', { name: 'Penyambungan' }))
    const target = screen.getByRole('button', { name: 'Pilih tujuan SPL-1 · 1:16 · Input' })
    expect(target.getAttribute('aria-pressed')).toBe('true')
    await user.click(target)
    expect(screen.getByRole('button', { name: 'Sambungkan' })).toHaveProperty('disabled', true)
    await user.click(screen.getByRole('button', { name: 'Batalkan pilihan' }))
    expect(screen.queryByRole('button', { name: /Pilih tujuan/ })).toBeNull()
    expect(apiPost).not.toHaveBeenCalled()
  })
  it('explains a missing destination and never offers occupied or damaged points', async () => {
    const data = spliceFixture(); data.closureKind = 'JOINT_BOX'; data.points = []
    data.cables[0].cores[1].connectionId = 'used'; data.cables[0].cores[2].core.status = 'DAMAGED'
    const { user } = await setup(data)
    expect(screen.queryByRole('button', { name: /Pilih sumber.*Core 2 ·/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Pilih sumber.*Core 3 ·/ })).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Pilih sumber DIST-JB-ODP · Core 1 · Biru' }))
    expect(screen.getByText('Tidak ada core tersedia pada kabel lain.')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Sambungkan' })).toHaveProperty('disabled', true)
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
  it('validates optional loss, preserves failed pairs and saves zero loss', async () => {
    const { user } = await setup()
    await user.click(screen.getByRole('button', { name: 'Pilih sumber DIST-JB-ODP · Core 7 · Merah' }))
    await user.click(screen.getByRole('button', { name: 'Pilih tujuan SPL-1 · 1:16 · Input' }))
    await user.click(screen.getByRole('button', { name: 'Detail sambungan' }))
    await user.type(screen.getByRole('textbox', { name: 'Redaman (dB)' }), '-1')
    await user.click(screen.getByRole('button', { name: 'Sambungkan' }))
    expect(screen.getByRole('alert').textContent).toBe('Redaman harus antara 0 dan 999 dB.'); expect(apiPost).not.toHaveBeenCalled()
    await user.clear(screen.getByRole('textbox', { name: 'Redaman (dB)' })); await user.type(screen.getByRole('textbox', { name: 'Redaman (dB)' }), '0')
    apiPost.mockRejectedValueOnce(new Error('offline'))
    await user.click(screen.getByRole('button', { name: 'Sambungkan' }))
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Sambungan gagal disimpan. Coba lagi.')
    expect(screen.getByRole('status', { name: 'Pasangan sambungan' }).textContent).toContain('DIST-JB-ODP · Core 7 · Merah')
    await user.click(screen.getByRole('button', { name: 'Sambungkan' }))
    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(2))
    expect(apiPost.mock.calls[1][1].lossDb).toBe(0)
  })
  it('invalidates a selected target if the refreshed workbench reports it occupied', async () => {
    const data = spliceFixture(), { user } = await setup(data)
    await user.click(screen.getByRole('button', { name: 'Pilih sumber DIST-JB-ODP · Core 1 · Biru' }))
    await user.click(screen.getByRole('button', { name: 'Pilih tujuan SPL-1 · 1:16 · Input' }))
    const next = structuredClone(data); next.points[0].connectionId = 'someone-else'
    apiGet.mockResolvedValue(next)
    await user.click(screen.getByRole('button', { name: 'Muat ulang' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: /Pilih tujuan/ })).toBeNull())
    expect(screen.getByRole('button', { name: 'Sambungkan' })).toHaveProperty('disabled', true)
    expect(apiPost).not.toHaveBeenCalled()
  })
  it('does not create beyond closure capacity', async () => {
    const data = spliceFixture(); data.spliceCapacity = 1; data.spliceCount = 1
    await setup(data)
    expect(screen.getByText('Kapasitas sambungan penuh.')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Pilih sumber DIST-JB-ODP · Core 1 · Biru' })).toHaveProperty('disabled', true)
    expect(screen.getByRole('button', { name: 'Sambungkan' })).toHaveProperty('disabled', true)
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
    expect(screen.queryByRole('button', { name: 'Sambungkan' })).toBeNull(); expect(screen.queryByRole('region', { name: 'Meja sambung' })).toBeNull(); expect(screen.queryByRole('checkbox')).toBeNull()
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
    await screen.findByRole('region', { name: 'Meja sambung' }); resolve(spliceFixture())
    await user.click(screen.getByRole('tab', { name: 'Kabel di lokasi' }))
    expect(screen.getByRole('cell', { name: 'DIST-ODP-LANJUTAN' })).toBeTruthy(); expect(screen.queryByRole('cell', { name: 'DIST-JB-ODP' })).toBeNull()
  })
})
