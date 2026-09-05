import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { ToastProvider } from '@/system'
import { afterEach, describe, expect, it, vi } from 'vitest'

const { getSettings, updateSettings, sendWhatsAppTest } = vi.hoisted(() => ({
  getSettings: vi.fn(),
  updateSettings: vi.fn(),
  sendWhatsAppTest: vi.fn(),
}))

vi.mock('../api/notification', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/notification')>()
  return {
    ...actual,
    getNotificationSettings: getSettings,
    updateNotificationSettings: updateSettings,
    sendWhatsAppTest,
  }
})

vi.mock('../auth/useCan', () => ({
  useCan: () => ({ can: () => true }),
}))

vi.mock('@/components/organisms', () => ({
  TenantEmailBrandingCard: () => <div>Branding email tenant</div>,
  WhatsAppTemplateCard: ({ templateReady }: { readonly templateReady: boolean }) => (
    <div>Template WhatsApp {templateReady ? 'siap' : 'belum siap'}</div>
  ),
}))

import { NotificationSettingsPage } from './NotificationSettingsPage'

const baseSettings = {
  provider: 'FONNTE',
  gatewayEnabled: false,
  emailEnabled: false,
  httpEndpointUrl: null,
  httpTokenSet: false,
  httpPhoneField: 'target',
  httpMessageField: 'message',
  metaPhoneNumberId: null,
  metaAccessTokenSet: false,
  metaWabaId: null,
  qontakAccessTokenSet: false,
  qontakChannelIntegrationId: null,
  templateReady: false,
  templateBlockedReason: 'Gateway nonaktif',
  notifyOnSubscriptionLifecycle: false,
  notifyOnInvoiceReminder: false,
  notifyOnWorkOrderSchedule: false,
  notifyOnIncidentOpen: false,
} as const

function renderPage(path = '/notifications/whatsapp') {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={[path]}>
      <ToastProvider>
        <Routes>
          <Route path="/notifications/*" element={<NotificationSettingsPage />} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>,
  )
  return user
}

afterEach(() => {
  getSettings.mockReset()
  updateSettings.mockReset()
  sendWhatsAppTest.mockReset()
})

describe('Notification settings routes', () => {
  it('memisahkan WhatsApp, email, dan pemicu tanpa membuang draft saat pindah tab', async () => {
    getSettings.mockResolvedValue(baseSettings)
    const user = renderPage()

    const token = await screen.findByLabelText(/Token Fonnte/)
    await user.type(token, 'draft-token')
    expect(screen.queryByText('Branding email tenant')).toBeNull()
    expect(screen.queryByText('Perubahan langganan')).toBeNull()

    await user.click(screen.getByRole('tab', { name: 'Email' }))
    expect(await screen.findByText('Branding email tenant')).toBeDefined()
    expect(screen.queryByLabelText(/Token Fonnte/)).toBeNull()

    await user.click(screen.getByRole('tab', { name: 'WhatsApp' }))
    expect((await screen.findByLabelText(/Token Fonnte/) as HTMLInputElement).value).toBe('draft-token')

    await user.click(screen.getByRole('tab', { name: 'Pemicu otomatis' }))
    expect(await screen.findByText('Perubahan langganan')).toBeDefined()
    expect(screen.queryByText('Branding email tenant')).toBeNull()
  })

  it('mengirim tes Fonnte memakai nomor pesan dan token draft walau gateway belum aktif', async () => {
    getSettings.mockResolvedValue(baseSettings)
    sendWhatsAppTest.mockResolvedValue({ delivered: true, detail: 'diterima' })
    const user = renderPage()

    await user.type(await screen.findByLabelText(/Token Fonnte/), 'draft-token')
    await user.type(screen.getByLabelText('Nomor tujuan uji'), '628123456789')
    const message = screen.getByLabelText('Pesan uji')
    await user.clear(message)
    await user.type(message, 'Halo dari pengujian')
    await user.click(screen.getByRole('button', { name: 'Kirim pesan test' }))

    await waitFor(() => {
      expect(sendWhatsAppTest).toHaveBeenCalledWith({
        provider: 'FONNTE',
        destination: '628123456789',
        message: 'Halo dari pengujian',
        httpToken: 'draft-token',
        httpEndpointUrl: null,
        httpPhoneField: 'target',
        httpMessageField: 'message',
      })
    })
  })

  it('menawarkan tes HTTP Generic dengan konfigurasi draft tetapi tidak untuk provider resmi', async () => {
    getSettings.mockResolvedValue({
      ...baseSettings,
      provider: 'HTTP_GENERIC',
      httpEndpointUrl: 'https://gateway.example/send',
    })
    const user = renderPage()

    expect(await screen.findByLabelText('Nomor tujuan uji')).toBeDefined()
    expect(screen.getByLabelText('Pesan uji')).toBeDefined()

    await user.selectOptions(screen.getByLabelText('Penyedia'), 'META_CLOUD')
    expect(screen.queryByLabelText('Nomor tujuan uji')).toBeNull()
    expect(screen.queryByLabelText('Pesan uji')).toBeNull()
  })

  it('mengalihkan route dasar dan route asing ke WhatsApp', async () => {
    getSettings.mockResolvedValue(baseSettings)
    renderPage('/notifications/route-yang-tidak-ada')

    expect(await screen.findByText('Gateway WhatsApp')).toBeDefined()
    expect(screen.getByRole('tab', { name: 'WhatsApp' }).getAttribute('aria-selected')).toBe('true')
  })
})
