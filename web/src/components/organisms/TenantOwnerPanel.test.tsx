import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TenantOwnerPanel } from './TenantOwnerPanel'
import { ApiError } from '@/api/client'
import type { PlatformTenant, TenantOwner } from '@/api/tenant'

const wire = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('@/api/client', async original => ({ ...await original<object>(), api: wire }))
vi.mock('@/system', () => ({ useConfirm: () => vi.fn() }))
const owner: TenantOwner = { id: 'owner', name: 'Owner ISP', email: 'owner@isp.test', status: 'ACTIVE' }
const candidate: TenantOwner = { id: 'candidate', name: 'Operator Baru', email: 'operator@isp.test', status: 'ACTIVE' }
const tenant: PlatformTenant = { id: 'isp', slug: 'isp', name: 'ISP Bandung', status: 'ACTIVE', owner }
const candidates = (content: TenantOwner[], page = 0, totalPages = 1) => ({ content, page, size: 20, totalPages, totalElements: totalPages === 1 ? content.length : 21 })
beforeEach(() => {
  vi.resetAllMocks()
  wire.get.mockImplementation((path: string) => Promise.resolve(path.includes('candidates') ? candidates([owner, candidate]) : tenant))
})

describe('tenant owner panel', () => {
  it('blocks reset and dismissal while an owner change is pending', async () => {
    let finish = () => {}
    wire.put.mockReturnValue(new Promise<void>(resolve => { finish = resolve }))
    const onClose = vi.fn()
    const onSaved = vi.fn()
    render(<TenantOwnerPanel tenant={tenant} onClose={onClose} onSaved={onSaved} />)
    const user = userEvent.setup()
    await user.selectOptions(await screen.findByLabelText('Calon owner'), candidate.id)
    await user.click(screen.getByRole('button', { name: 'Simpan owner' }))
    expect(screen.getByRole('button', { name: 'Ganti password owner' })).toHaveProperty('disabled', true)
    expect(screen.getByRole('button', { name: 'Batal' })).toHaveProperty('disabled', true)
    expect(screen.getByRole('button', { name: /^Tutup$/ })).toHaveProperty('disabled', true)
    await user.click(screen.getByRole('button', { name: /^Tutup$/ }))
    await user.keyboard('{Escape}')
    expect(onClose).not.toHaveBeenCalled()
    expect(wire.post).not.toHaveBeenCalled()
    await act(async () => { finish() })
    expect(onSaved).toHaveBeenCalledTimes(1)
  })

  it('blocks owner changes during reset and ignores a response after the panel unmounts', async () => {
    let finish = () => {}
    wire.post.mockReturnValue(new Promise<void>(resolve => { finish = resolve }))
    const onSaved = vi.fn()
    const view = render(<TenantOwnerPanel tenant={tenant} onClose={vi.fn()} onSaved={onSaved} />)
    const user = userEvent.setup()
    await user.selectOptions(await screen.findByLabelText('Calon owner'), candidate.id)
    await user.type(screen.getByLabelText(/^Password baru/), 'new-password-123')
    await user.type(screen.getByLabelText(/^Konfirmasi password baru/), 'new-password-123')
    await user.click(screen.getByRole('button', { name: 'Ganti password owner' }))
    expect(screen.getByRole('button', { name: 'Simpan owner' })).toHaveProperty('disabled', true)
    expect(screen.getByLabelText('Calon owner')).toHaveProperty('disabled', true)
    view.unmount()
    await act(async () => { finish() })
    expect(onSaved).not.toHaveBeenCalled()
    expect(wire.put).not.toHaveBeenCalled()
  })

  it('keeps password input on validation and server errors, then sends the displayed owner identity', async () => {
    const onSaved = vi.fn()
    render(<TenantOwnerPanel tenant={tenant} onClose={vi.fn()} onSaved={onSaved} />)
    const user = userEvent.setup()
    await screen.findByText('Owner saat ini')
    await user.type(screen.getByLabelText(/^Password baru/), 'new-password-123')
    await user.type(screen.getByLabelText(/^Konfirmasi password baru/), 'different')
    await user.click(screen.getByRole('button', { name: 'Ganti password owner' }))
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Konfirmasi password belum cocok')
    expect(wire.post).not.toHaveBeenCalled()
    await user.clear(screen.getByLabelText(/^Konfirmasi password baru/))
    await user.type(screen.getByLabelText(/^Konfirmasi password baru/), 'new-password-123')
    wire.post.mockRejectedValueOnce(new ApiError(400, 'Password ditolak')).mockResolvedValueOnce(undefined)
    await user.click(screen.getByRole('button', { name: 'Ganti password owner' }))
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Password ditolak')
    expect(screen.getByLabelText(/^Password baru/)).toHaveProperty('value', 'new-password-123')
    await user.click(screen.getByRole('button', { name: 'Ganti password owner' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
    expect(wire.post).toHaveBeenLastCalledWith('/api/platform/tenants/isp/owner/password', { expectedOwnerUserId: 'owner', newPassword: 'new-password-123' })
    expect(screen.getByLabelText(/^Password baru/)).toHaveProperty('value', '')
  })

  it('requires refreshed identity after a conflicting reset', async () => {
    wire.post.mockRejectedValue(new ApiError(409, 'Owner telah berubah'))
    render(<TenantOwnerPanel tenant={tenant} onClose={vi.fn()} onSaved={vi.fn()} />)
    const user = userEvent.setup()
    await screen.findByText('Owner saat ini')
    await user.type(screen.getByLabelText(/^Password baru/), 'new-password-123')
    await user.type(screen.getByLabelText(/^Konfirmasi password baru/), 'new-password-123')
    await user.click(screen.getByRole('button', { name: 'Ganti password owner' }))
    await screen.findByText('Owner telah berubah')
    expect(screen.getByRole('button', { name: 'Ganti password owner' })).toHaveProperty('disabled', true)
    expect(wire.post).toHaveBeenCalledTimes(1)
  })

  it('keeps selection through pagination and search, excludes inactive candidates, and binds explicitly', async () => {
    const inactive = { ...candidate, id: 'disabled', name: 'Nonaktif', status: 'DISABLED' }
    wire.get.mockImplementation((path: string) => Promise.resolve(path.includes('candidates') ?
      path.includes('page=1') || path.includes('query=missing') ? candidates([], 1, 2) : candidates([owner, candidate, inactive], 0, 2) : tenant))
    const onSaved = vi.fn()
    render(<TenantOwnerPanel tenant={tenant} onClose={vi.fn()} onSaved={onSaved} />)
    const user = userEvent.setup()
    const select = await screen.findByLabelText('Calon owner')
    await user.selectOptions(select, candidate.id)
    expect(screen.getByRole('option', { name: /Nonaktif/ })).toHaveProperty('disabled', true)
    await user.click(screen.getByRole('button', { name: 'Berikutnya' }))
    await screen.findByText('Halaman 2 dari 2 · 21 pengguna')
    expect(select).toHaveProperty('value', candidate.id)
    await user.type(screen.getByLabelText('Cari calon owner'), 'missing')
    await waitFor(() => expect(wire.get).toHaveBeenLastCalledWith('/api/platform/tenants/isp/owner/candidates?query=missing&page=0&size=20'))
    expect(wire.put).not.toHaveBeenCalled()
    wire.put.mockResolvedValue(candidate)
    await user.click(screen.getByRole('button', { name: 'Simpan owner' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
    expect(wire.put).toHaveBeenCalledWith('/api/platform/tenants/isp/owner', { userId: candidate.id })
  })

  it('an unbound owner has selection without a password reset and closing drops the draft', async () => {
    wire.get.mockImplementation((path: string) => Promise.resolve(path.includes('candidates') ? candidates([candidate]) : { ...tenant, owner: null }))
    const onClose = vi.fn()
    const view = render(<TenantOwnerPanel tenant={tenant} onClose={onClose} onSaved={vi.fn()} />)
    const user = userEvent.setup()
    await screen.findByText(/Belum ditentukan/)
    expect(screen.queryByLabelText(/^Password baru/)).toBeNull()
    await user.selectOptions(screen.getByLabelText('Calon owner'), candidate.id)
    await user.click(screen.getByRole('button', { name: 'Batal' }))
    expect(onClose).toHaveBeenCalledTimes(1)
    view.unmount()
    render(<TenantOwnerPanel tenant={tenant} onClose={onClose} onSaved={vi.fn()} />)
    expect(await screen.findByLabelText('Calon owner')).toHaveProperty('value', '')
    expect(wire.put).not.toHaveBeenCalled()
  })
})
