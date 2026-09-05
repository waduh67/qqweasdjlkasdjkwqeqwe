import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { ToastProvider } from '@/system'
import { ApiError } from '../api/client'
import type {
  PaymentGatewaySettingsView,
  TripaySandboxTestRequest,
  TripaySandboxTestView,
  UpdatePaymentGatewaySettingsRequest,
} from '../api/payment'
import { beforeEach, describe, expect, it, vi } from 'vitest'

function deferred<T>() {
  let resolve: (value: T) => void = (_value: T) => {
    throw new Error('Deferred promise was not initialized')
  }
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve
  })
  return { promise, resolve }
}

const { can, getSettings, testTripaySandbox, updateSettings } = vi.hoisted(() => ({
  can: vi.fn<(permission: string) => boolean>(() => true),
  getSettings: vi.fn<() => Promise<PaymentGatewaySettingsView>>(),
  testTripaySandbox: vi.fn<(request: TripaySandboxTestRequest) => Promise<TripaySandboxTestView>>(),
  updateSettings: vi.fn<(request: UpdatePaymentGatewaySettingsRequest) => Promise<PaymentGatewaySettingsView>>(),
}))

vi.mock('../api/payment', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/payment')>()
  return {
    ...actual,
    getPaymentGatewaySettings: getSettings,
    testTripaySandboxPayment: testTripaySandbox,
    updatePaymentGatewaySettings: updateSettings,
  }
})

vi.mock('../auth/useCan', () => ({
  useCan: () => ({ can }),
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

const tripaySettings: PaymentGatewaySettingsView = {
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

const pivotSettings: PaymentGatewaySettingsView = {
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

beforeEach(() => {
  can.mockReset()
  can.mockReturnValue(true)
  getSettings.mockReset()
  testTripaySandbox.mockReset()
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
    expect(screen.getByRole('button', { name: 'Test sandbox' })).toBeDefined()
    expect(screen.queryByText('Kontrak server Tripay')).toBeNull()
    expect(screen.queryByText(/api-sandbox\/transaction\/create/)).toBeNull()
  })

  it('hides the sandbox action in production mode or without manage permission', async () => {
    getSettings.mockResolvedValue({ ...tripaySettings, tripaySandbox: false })

    renderPage()

    await screen.findByLabelText('Merchant code')
    expect(screen.queryByRole('button', { name: 'Test sandbox' })).toBeNull()
  })

  it('hides the sandbox action without manage permission', async () => {
    can.mockImplementation((permission) => permission !== 'billing.gateway.manage')
    getSettings.mockResolvedValue(tripaySettings)

    renderPage()

    await screen.findByLabelText('Merchant code')
    expect(screen.queryByRole('button', { name: 'Test sandbox' })).toBeNull()
  })

  it('shows the sandbox action when unsaved drafts complete missing stored credentials', async () => {
    getSettings.mockResolvedValue({
      ...tripaySettings,
      tripayApiKeySet: false,
      tripayPrivateKeySet: false,
    })
    const user = renderPage()

    await screen.findByLabelText('Merchant code')
    expect(screen.queryByRole('button', { name: 'Test sandbox' })).toBeNull()

    await user.type(screen.getByLabelText('API Key'), 'draft-api-key')
    expect(screen.queryByRole('button', { name: 'Test sandbox' })).toBeNull()
    await user.type(screen.getByLabelText('Private Key'), 'draft-private-key')

    expect(screen.getByRole('button', { name: 'Test sandbox' })).toBeDefined()
  })

  it('submits current Tripay drafts and exposes the returned sandbox payment URL', async () => {
    const pending = deferred<TripaySandboxTestView>()
    testTripaySandbox.mockReturnValue(pending.promise)
    getSettings.mockResolvedValue(tripaySettings)
    const user = renderPage()

    const merchantCode = await screen.findByLabelText('Merchant code')
    await user.clear(merchantCode)
    await user.type(merchantCode, 'MERCHANT-DRAFT')
    await user.type(screen.getByLabelText('API Key'), 'draft-api-key')
    await user.type(screen.getByLabelText('Private Key'), 'draft-private-key')
    await user.click(screen.getByRole('button', { name: 'Test sandbox' }))

    expect(testTripaySandbox).toHaveBeenCalledWith({
      merchantCode: 'MERCHANT-DRAFT',
      apiKey: 'draft-api-key',
      privateKey: 'draft-private-key',
    })
    expect(screen.getByRole('button', { name: 'Menguji…' }).hasAttribute('disabled')).toBe(true)

    pending.resolve({ paymentUrl: 'https://tripay.example/sandbox/pay/123' })
    const link = await screen.findByRole('link', { name: 'Buka pembayaran sandbox' })
    expect(link.getAttribute('href')).toBe('https://tripay.example/sandbox/pay/123')
    expect(link.getAttribute('target')).toBe('_blank')
    expect(link.getAttribute('rel')).toBe('noopener noreferrer')
  })

  it('submits blank drafts as stored-credential fallbacks', async () => {
    testTripaySandbox.mockResolvedValue({ paymentUrl: 'https://tripay.example/sandbox/pay/stored' })
    getSettings.mockResolvedValue(tripaySettings)
    const user = renderPage()

    await user.click(await screen.findByRole('button', { name: 'Test sandbox' }))

    expect(testTripaySandbox).toHaveBeenCalledWith({
      merchantCode: 'MERCHANT-OLD',
      apiKey: null,
      privateKey: null,
    })
  })

  it.each([
    ['merchant code', async (user: ReturnType<typeof userEvent.setup>) => {
      const field = screen.getByLabelText('Merchant code')
      await user.clear(field)
      await user.type(field, 'MERCHANT-CHANGED')
    }],
    ['API Key draft', async (user: ReturnType<typeof userEvent.setup>) => {
      await user.type(screen.getByLabelText('API Key'), 'changed-api-key')
    }],
    ['Private Key draft', async (user: ReturnType<typeof userEvent.setup>) => {
      await user.type(screen.getByLabelText('Private Key'), 'changed-private-key')
    }],
    ['mode', async (user: ReturnType<typeof userEvent.setup>) => {
      await user.click(screen.getByRole('button', { name: 'Produksi' }))
      await user.click(screen.getByRole('button', { name: 'Sandbox' }))
    }],
    ['provider', async (user: ReturnType<typeof userEvent.setup>) => {
      await user.click(screen.getByRole('button', { name: 'Pivot' }))
      await user.click(screen.getByRole('button', { name: 'Tripay (akun sendiri)' }))
    }],
  ])('clears a stale sandbox link when %s changes', async (_field, changeField) => {
    testTripaySandbox.mockResolvedValue({ paymentUrl: 'https://tripay.example/sandbox/pay/stale' })
    getSettings.mockResolvedValue(tripaySettings)
    const user = renderPage()

    await user.click(await screen.findByRole('button', { name: 'Test sandbox' }))
    await screen.findByRole('link', { name: 'Buka pembayaran sandbox' })
    await changeField(user)

    expect(screen.queryByRole('link', { name: 'Buka pembayaran sandbox' })).toBeNull()
  })

  it('ignores a sandbox response that finishes after credentials change', async () => {
    const pending = deferred<TripaySandboxTestView>()
    testTripaySandbox.mockReturnValue(pending.promise)
    getSettings.mockResolvedValue(tripaySettings)
    const user = renderPage()

    await user.click(await screen.findByRole('button', { name: 'Test sandbox' }))
    await user.type(screen.getByLabelText('API Key'), 'new-api-key')
    expect(screen.getByRole('button', { name: 'Test sandbox' }).hasAttribute('disabled')).toBe(false)

    await act(async () => pending.resolve({ paymentUrl: 'https://tripay.example/sandbox/pay/late' }))

    expect(screen.queryByRole('link', { name: 'Buka pembayaran sandbox' })).toBeNull()
  })

  it('uses a safe fallback when the sandbox test rejects with a malformed error', async () => {
    const secret = 'must-not-leak-private-key'
    testTripaySandbox.mockRejectedValue(new Error(`malformed response ${secret}`))
    getSettings.mockResolvedValue(tripaySettings)
    const user = renderPage()

    await user.type(await screen.findByLabelText('Private Key'), secret)
    await user.click(screen.getByRole('button', { name: 'Test sandbox' }))

    expect(await screen.findByText('Gagal menguji Tripay sandbox. Periksa konfigurasi lalu coba lagi.')).toBeDefined()
    expect(screen.queryByText(new RegExp(secret))).toBeNull()
    expect(screen.getByRole('button', { name: 'Test sandbox' }).hasAttribute('disabled')).toBe(false)
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
