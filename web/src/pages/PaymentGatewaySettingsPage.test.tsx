import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { ToastProvider } from '@/system'
import { ApiError } from '../api/client'
import { afterEach, describe, expect, it, vi } from 'vitest'

const { getSettings, updateSettings } = vi.hoisted(() => ({
  getSettings: vi.fn(),
  updateSettings: vi.fn(),
}))

vi.mock('../api/payment', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/payment')>()
  return {
    ...actual,
    getPaymentGatewaySettings: getSettings,
    updatePaymentGatewaySettings: updateSettings,
  }
})

vi.mock('../auth/useCan', () => ({
  useCan: () => ({ can: () => true }),
}))

vi.mock('@/components/molecules', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/molecules')>()
  return {
    ...actual,
    Modal: ({ children, footer }: { children: ReactNode; footer: ReactNode }) => (
      <section aria-label="Konfirmasi perubahan gateway" role="dialog">
        {children}
        {footer}
      </section>
    ),
  }
})

import { PaymentGatewaySettingsPage } from './PaymentGatewaySettingsPage'

const tripaySettings = {
  provider: 'TRIPAY',
  enabled: true,
  manualTransferEnabled: false,
  bankName: null,
  accountNumber: null,
  accountHolder: null,
  manualQrisEnabled: false,
  qrisImageSet: false,
  tripayMerchantCode: 'MERCHANT-OLD',
  tripayApiKeySet: true,
  tripayPrivateKeySet: true,
  tripaySandbox: true,
}

const pivotSettings = {
  ...tripaySettings,
  provider: 'PIVOT',
  tripayMerchantCode: null,
  tripayApiKeySet: false,
  tripayPrivateKeySet: false,
}

function renderPage() {
  const user = userEvent.setup()
  render(
    <ToastProvider>
      <PaymentGatewaySettingsPage />
    </ToastProvider>,
  )
  return user
}

async function openReview(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Tinjau & simpan…' }))
  return screen.findByRole('dialog', { name: 'Konfirmasi perubahan gateway' })
}

afterEach(() => {
  getSettings.mockReset()
  updateSettings.mockReset()
})

describe('Tripay BYOK settings', () => {
  it('renders the tenant-owned Tripay provider and its safe configuration fields', async () => {
    getSettings.mockResolvedValue(tripaySettings)

    renderPage()

    expect(await screen.findByRole('button', { name: 'Tripay (akun sendiri)' })).toBeDefined()
    expect(screen.getByLabelText('Merchant code')).toBeDefined()
    expect(screen.getByLabelText('API Key').getAttribute('type')).toBe('password')
    expect(screen.getByLabelText('API Key').getAttribute('autocomplete')).toBe('new-password')
    expect(screen.getByLabelText('Private Key').getAttribute('type')).toBe('password')
    expect(screen.getByLabelText('Private Key').getAttribute('autocomplete')).toBe('new-password')
    expect(screen.getByText('/api/platform/tripay/callbacks/payment')).toBeDefined()
    expect(screen.getByText('API Key tersimpan.')).toBeDefined()
    expect(screen.getByText('Private Key tersimpan.')).toBeDefined()
  })

  it('submits null secret fields when their drafts are empty', async () => {
    getSettings.mockResolvedValue(tripaySettings)
    updateSettings.mockResolvedValue(tripaySettings)
    const user = renderPage()

    const merchantCode = await screen.findByLabelText('Merchant code')
    await user.clear(merchantCode)
    await user.type(merchantCode, 'MERCHANT-NEW')
    await openReview(user)
    await user.click(screen.getByRole('button', { name: 'Ya, simpan' }))

    await waitFor(() => {
      expect(updateSettings).toHaveBeenCalledWith(expect.objectContaining({
        tripayMerchantCode: 'MERCHANT-NEW',
        tripayApiKey: null,
        tripayPrivateKey: null,
      }))
    })
  })

  it('requires complete Tripay credentials before enabling it while retaining Pivot and Manual choices', async () => {
    getSettings.mockResolvedValue(pivotSettings)
    updateSettings.mockResolvedValue(tripaySettings)
    const user = renderPage()

    await screen.findByRole('button', { name: 'Pivot' })
    expect(screen.getByRole('button', { name: 'Manual (tunai/transfer)' })).toBeDefined()
    await user.click(screen.getByRole('button', { name: 'Tripay (akun sendiri)' }))

    expect(screen.getByText('Merchant code wajib diisi untuk mengaktifkan Tripay.')).toBeDefined()
    expect(screen.getByText('Masukkan API Key untuk mengaktifkan Tripay.')).toBeDefined()
    expect(screen.getByText('Masukkan Private Key untuk mengaktifkan Tripay.')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Tinjau & simpan…' }).hasAttribute('disabled')).toBe(true)

    await user.type(screen.getByLabelText('Merchant code'), 'MERCHANT-NEW')
    await user.type(screen.getByLabelText('API Key'), 'api-key-new')
    await user.type(screen.getByLabelText('Private Key'), 'private-key-new')
    await openReview(user)
    await user.click(screen.getByRole('button', { name: 'Ya, simpan' }))

    await waitFor(() => {
      expect(updateSettings).toHaveBeenCalledWith(expect.objectContaining({
        provider: 'TRIPAY',
        enabled: true,
        tripayMerchantCode: 'MERCHANT-NEW',
        tripayApiKey: 'api-key-new',
        tripayPrivateKey: 'private-key-new',
      }))
    })
  })

  it('submits entered secrets without displaying them in the review or error UI', async () => {
    const apiKey = 'test-api-key-123'
    const privateKey = 'test-private-key-456'
    getSettings.mockResolvedValue(tripaySettings)
    updateSettings.mockRejectedValue(new ApiError(422, `Konfigurasi ${apiKey} ditolak`))
    const user = renderPage()

    await user.type(await screen.findByLabelText('API Key'), apiKey)
    await user.type(screen.getByLabelText('Private Key'), privateKey)
    const review = await openReview(user)

    expect(review.textContent).not.toContain(apiKey)
    expect(review.textContent).not.toContain(privateKey)
    await user.click(screen.getByRole('button', { name: 'Ya, simpan' }))

    await waitFor(() => {
      expect(updateSettings).toHaveBeenCalledWith(expect.objectContaining({
        tripayApiKey: apiKey,
        tripayPrivateKey: privateKey,
      }))
    })
    expect(await screen.findByText('Gagal menyimpan setelan gateway. Periksa konfigurasi lalu coba lagi.')).toBeDefined()
    expect(screen.queryByText(apiKey)).toBeNull()
    expect(screen.queryByText(privateKey)).toBeNull()
  })
})
