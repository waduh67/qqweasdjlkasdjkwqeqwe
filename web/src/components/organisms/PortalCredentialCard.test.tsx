import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { FluentProvider, webLightTheme } from '@fluentui/react-components'
import { PortalCredentialCard } from './PortalCredentialCard'
import { ApiError } from '@/api/client'
import { ToastProvider, DialogProvider } from '@/system'

const mocks = vi.hoisted(() => ({ provision: vi.fn() }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: () => true }) }))
vi.mock('@/api/portalAdmin', () => ({
  getPortalCredential: () => Promise.resolve({ provisioned: false }),
  provisionPortalCredential: mocks.provision,
  resetPortalPassword: vi.fn(), disablePortalCredential: vi.fn(), enablePortalCredential: vi.fn(),
}))

it('keeps rejected credentials editable and exposes the error inside the open review', async () => {
  mocks.provision.mockRejectedValue(new ApiError(409, 'Login sudah digunakan'))
  const user = userEvent.setup()
  render(<FluentProvider theme={webLightTheme}><ToastProvider><DialogProvider><PortalCredentialCard customerId="customer-1" /></DialogProvider></ToastProvider></FluentProvider>)
  await user.click(await screen.findByRole('button', { name: 'Buat login portal' }))
  await user.type(screen.getByRole('textbox', { name: 'Login' }), 'pelanggan-1')
  await user.click(screen.getByRole('button', { name: 'Tinjau + buat' }))
  expect(mocks.provision).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'Simpan' }))
  await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('Login sudah digunakan'))
  expect(screen.getByRole('tab', { name: 'Tinjau + buat' }).getAttribute('aria-selected')).toBe('true')
  expect(mocks.provision).toHaveBeenCalledExactlyOnceWith('customer-1', { login: 'pelanggan-1', password: null })
  await user.click(screen.getByRole('button', { name: 'Sebelumnya' }))
  expect(screen.getByRole('textbox', { name: 'Login' })).toHaveProperty('value', 'pelanggan-1')
})
