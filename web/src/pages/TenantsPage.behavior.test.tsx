import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TenantsPage } from './TenantsPage'

const fixtures = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), can: (permission: string) => permission !== 'platform.billing.view' }))
vi.mock('../api/client', async importOriginal => ({ ...await importOriginal<object>(), api: { get: fixtures.get, post: fixtures.post } }))
vi.mock('../auth/useCan', () => ({ useCan: () => ({ can: fixtures.can }) }))
vi.mock('@/system', () => ({ useConfirm: () => vi.fn(), useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))

beforeEach(() => { vi.clearAllMocks() })
describe('tenant onboarding submission', () => {
  it('accepts a valid custom fee and shows a failed refresh outside the closed form', async () => {
    fixtures.get.mockResolvedValueOnce({ content: [], totalElements: 0 }).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ content: [{ id: 'isp', name: 'ISP Bandung', slug: 'isp-bandung', status: 'ACTIVE' }], totalElements: 1 })
    fixtures.post.mockResolvedValue({ id: 'isp' })
    render(<MemoryRouter><TenantsPage /></MemoryRouter>)
    await screen.findByText('Belum ada tenant')
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Tambah tenant' }))
    for (const [label, value] of [[/^Nama\s*\*?$/, 'ISP Bandung'], [/^Slug/, 'isp-bandung'], [/^Nama admin/, 'Admin ISP'], [/^Email admin/, 'admin@example.test'], [/^Password admin/, 'Random-password-123!']] as const) {
      await user.type(screen.getByLabelText(label), value)
    }
    const fee = screen.getByLabelText('Harga bulanan khusus (Rp)') as HTMLInputElement
    await user.type(fee, '149500')
    expect(fee.checkValidity()).toBe(true)
    await user.click(screen.getByRole('button', { name: 'Simpan' }))
    expect((await screen.findByRole('alert')).textContent).toContain('Gagal memuat daftar tenant')
    expect(screen.getByText(/Admin bisa langsung masuk/)).toBeTruthy()
    expect(screen.queryByLabelText(/^Nama admin/)).toBeNull()
    expect(fixtures.post).toHaveBeenCalledTimes(1)
    expect(fixtures.post.mock.calls[0][1].monthlyFee).toBe(149500)
    await user.click(screen.getByRole('button', { name: 'Coba lagi' }))
    expect(await screen.findByText('ISP Bandung')).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
