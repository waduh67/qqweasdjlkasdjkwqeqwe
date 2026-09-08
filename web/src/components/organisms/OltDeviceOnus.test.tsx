import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { deviceOnu, oltOnusSnapshot, unsupportedOnu } from '@/api/oltOnus.test-support'
import { OltDeviceOnus } from './OltDeviceOnus'

afterEach(() => vi.unstubAllGlobals())

function serveSnapshot(snapshot = oltOnusSnapshot()) {
  const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json(snapshot))
  vi.stubGlobal('fetch', fetch)
  return fetch
}

const headers = [
  'ONT ID', 'Name', 'Serial number', 'State', 'Running state', 'Config state',
  'Device type', 'Receive power', 'Last up time', 'Last down time', 'Last down cause',
]

describe('OltDeviceOnus', () => {
  it('populates all eleven columns directly from a device snapshot without customers', async () => {
    const fetch = serveSnapshot()
    render(<OltDeviceOnus oltId="olt-a" />)
    await screen.findByText('HWTC00112233')
    const grid = screen.getByRole('grid')
    expect(within(grid).getAllByRole('columnheader').map((header) => header.textContent)).toEqual(headers)
    const cells = within(grid).getAllByRole('gridcell')
    expect(cells.map((cell) => cell.querySelector('.olt-device-onus__value')?.textContent)).toEqual([
      '1/1:7', 'Cabinet Utara', 'HWTC00112233', 'Enabled', 'ONLINE', 'Success',
      'HG8245H', '-21.75 dBm', deviceOnu.lastUpTime, deviceOnu.lastDownTime, 'Dying gasp',
    ])
    expect(cells.map((cell) => cell.querySelector('.olt-device-onus__label')?.textContent)).toEqual(headers)
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch.mock.calls.map(([path]) => path)).toEqual(['/api/monitoring/olts/olt-a/onus'])
    expect(screen.queryByRole('button', { name: /provision|pelanggan/i })).toBeNull()
  })

  it.each(['hwtc0011', 'cABINET uTARA', '1/1:7'])('searches serial, name, and ONT ID case-insensitively: %s', async (query) => {
    const fetch = serveSnapshot(oltOnusSnapshot({ onus: [deviceOnu, unsupportedOnu] }))
    const user = userEvent.setup()
    render(<OltDeviceOnus oltId="olt-a" />)
    await screen.findByText('ZTEG44556677')
    await user.type(screen.getByRole('searchbox', { name: 'Cari serial, nama, atau ONT ID' }), query)
    expect(screen.getByText('HWTC00112233')).toBeDefined()
    expect(screen.queryByText('ZTEG44556677')).toBeNull()
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('limits absence claims to the current read and restores results when search clears', async () => {
    serveSnapshot()
    const user = userEvent.setup()
    render(<OltDeviceOnus oltId="olt-a" />)
    await screen.findByText('HWTC00112233')
    const search = screen.getByRole('searchbox', { name: 'Cari serial, nama, atau ONT ID' })
    await user.type(search, 'serial-not-in-snapshot')
    expect(screen.getByText('Tidak ditemukan pada hasil baca ini')).toBeDefined()
    expect(screen.queryByText(/tidak terdaftar|tidak ada di OLT/i)).toBeNull()
    await user.clear(search)
    expect(screen.getByText('HWTC00112233')).toBeDefined()
  })

  it('renders unavailable values as dashes without inferring IDs, vendor, or state', async () => {
    serveSnapshot(oltOnusSnapshot({ onus: [unsupportedOnu], systemDescription: null }))
    render(<OltDeviceOnus oltId="olt-a" />)
    await screen.findByText('ZTEG44556677')
    expect(within(screen.getByRole('grid')).getAllByText('—')).toHaveLength(10)
    expect(screen.queryByText('4194562')).toBeNull()
    expect(screen.queryByText('UNKNOWN')).toBeNull()
    expect(screen.getByText(/Field yang tidak tersedia atau belum didukung/)).toBeDefined()
  })

  it('keeps read-only source, actual read timestamp, device clock notice and partial-read warnings visible', async () => {
    serveSnapshot(oltOnusSnapshot({ warnings: ['Sebagian port gagal dibaca', 'Receive power belum didukung'] }))
    render(<OltDeviceOnus oltId="olt-a" />)
    await screen.findByText('HWTC00112233')
    expect(screen.getByText(/Sumber: OLT-A · HUAWEI/)).toBeDefined()
    expect(screen.getByText('Hanya baca')).toBeDefined()
    expect(screen.getByText('Test OLT firmware')).toBeDefined()
    expect(screen.getByText('2026-09-08T04:05:06Z').getAttribute('datetime')).toBe('2026-09-08T04:05:06Z')
    expect(screen.getByText(/time mengikuti jam OLT/)).toBeDefined()
    expect(screen.getByText('Sebagian port gagal dibaca')).toBeDefined()
    expect(screen.getByText('Receive power belum didukung')).toBeDefined()
  })

  it('renders zero receive power and arbitrary readable state/time/cause without normalization', async () => {
    serveSnapshot(oltOnusSnapshot({ onus: [{ ...deviceOnu, rxPowerDbm: 0, state: 'Vendor state 42', lastUpTime: '27 days uptime', lastDownCause: 'Vendor cause 9' }] }))
    render(<OltDeviceOnus oltId="olt-a" />)
    expect(await screen.findByText('0 dBm')).toBeDefined()
    expect(screen.getByText('Vendor state 42')).toBeDefined()
    expect(screen.getByText('27 days uptime')).toBeDefined()
    expect(screen.getByText('Vendor cause 9')).toBeDefined()
  })

  it('distinguishes a successful empty snapshot from errors', async () => {
    serveSnapshot(oltOnusSnapshot({ onus: [] }))
    render(<OltDeviceOnus oltId="olt-a" />)
    expect(await screen.findByText('Tidak ditemukan pada hasil baca ini')).toBeDefined()
    expect(screen.getByText('2026-09-08T04:05:06Z')).toBeDefined()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByRole('grid')).toBeNull()
  })
})
