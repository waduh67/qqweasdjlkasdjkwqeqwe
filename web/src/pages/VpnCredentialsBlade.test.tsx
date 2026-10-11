import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { VpnAccountView } from '@/api/vpn'
import { copyText } from '@/utils/clipboard'
import { VpnCredentialsBlade } from './VpnCredentialsBlade'

const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/system', () => ({ useToast: () => toast, useConfirm: () => vi.fn() }))
vi.mock('@/utils/clipboard', () => ({ copyText: vi.fn() }))
const account: VpnAccountView = {
  id: 'vpn-test', label: 'MikroTik Lab', serverName: 'VPN Jakarta', host: 'vpn.example.test', port: 1194,
  protocol: 'TCP', cipher: 'AES-256-CBC', securityType: 'OpenVPN', username: 'mikrotik-lab',
  overlayIp: '10.8.0.2', remotePort: 20001, winboxAddress: 'vpn.example.test:20001', forwards: [], routes: [],
  status: 'ENABLED', online: false, lastHandshakeAt: null, supportsV6: true, password: 'demo-test-password',
  routerOsCommand: '/interface/ovpn-client/add cipher=aes256-cbc', routerOsCommandV6: '/interface ovpn-client add cipher=aes256',
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(copyText).mockResolvedValue(true)
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => vi.unstubAllGlobals())

it('masks credentials initially, lets the user reveal and copy them, and reports clipboard failures', async () => {
  const user = userEvent.setup(), close = vi.fn()
  render(<VpnCredentialsBlade account={account} canDownload onClose={close} onDownload={vi.fn()} />)
  const password = screen.getByLabelText('Password')
  expect(password).toHaveProperty('type', 'password')
  expect(password).toHaveProperty('readOnly', true)
  expect(screen.queryByText(account.routerOsCommand!)).toBeNull()
  await user.click(screen.getByRole('button', { name: 'Tampilkan password' }))
  expect(password).toHaveProperty('type', 'text')
  await user.click(screen.getByRole('button', { name: 'Salin password' }))
  expect(copyText).toHaveBeenCalledWith(account.password)
  expect(toast.success).toHaveBeenCalledWith('Password disalin')
  vi.mocked(copyText).mockResolvedValue(false)
  await user.click(screen.getByRole('button', { name: 'Salin username' }))
  expect(toast.error).toHaveBeenCalledWith('Gagal menyalin username')
  expect(toast.success).toHaveBeenCalledTimes(1)
  await user.click(screen.getByRole('button', { name: 'Sembunyikan password' }))
  expect(password).toHaveProperty('type', 'password')
  await user.click(screen.getByRole('button', { name: 'Tutup' }))
  expect(close).toHaveBeenCalledTimes(1)
})

it('uses the selected RouterOS version for both the command and downloads', async () => {
  const user = userEvent.setup(), download = vi.fn()
  render(<VpnCredentialsBlade account={account} canDownload onClose={vi.fn()} onDownload={download} />)
  await user.click(screen.getByRole('button', { name: 'Lihat konfigurasi' }))
  await user.click(screen.getByRole('button', { name: 'Salin perintah' }))
  expect(copyText).toHaveBeenLastCalledWith(account.routerOsCommand)
  await user.click(screen.getByRole('button', { name: 'Unduh .rsc' }))
  expect(download).toHaveBeenLastCalledWith('routeros', 'V7')
  await user.selectOptions(screen.getByRole('combobox', { name: 'Versi RouterOS' }), 'V6')
  await user.click(screen.getByRole('button', { name: 'Salin perintah' }))
  expect(copyText).toHaveBeenLastCalledWith(account.routerOsCommandV6)
  await user.click(screen.getByRole('button', { name: 'Unduh .ovpn' }))
  expect(download).toHaveBeenLastCalledWith('ovpn', 'V6')
  await user.click(screen.getByRole('tab', { name: 'Koneksi' }))
  await user.click(screen.getByRole('tab', { name: 'Konfigurasi' }))
  expect(screen.getByRole('combobox')).toHaveProperty('value', 'V6')
})

it('omits unsupported v6 and downloads without configuration permission', async () => {
  const user = userEvent.setup()
  render(<VpnCredentialsBlade account={{ ...account, supportsV6: false, routerOsCommandV6: null }} canDownload={false} onClose={vi.fn()} onDownload={vi.fn()} />)
  await user.click(screen.getByRole('tab', { name: 'Konfigurasi' }))
  expect(screen.queryByRole('option', { name: 'RouterOS v6' })).toBeNull()
  expect(screen.queryByRole('button', { name: /Unduh/ })).toBeNull()
})
