import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearOltOnusCache } from '@/api/oltOnus'
import { deviceOnu, oltOnusSnapshot, unsupportedOnu } from '@/api/oltOnus.test-support'
import { OltDeviceOnus } from './OltDeviceOnus'

afterEach(() => {
  clearOltOnusCache()
  vi.unstubAllGlobals()
})

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

  it('hides entirely unavailable columns without inferring IDs, vendor, or state', async () => {
    serveSnapshot(oltOnusSnapshot({ onus: [unsupportedOnu], systemDescription: null }))
    render(<OltDeviceOnus oltId="olt-a" />)
    await screen.findByText('ZTEG44556677')
    const grid = screen.getByRole('grid')
    expect(within(grid).getAllByRole('columnheader').map((header) => header.textContent)).toEqual(['Serial number'])
    expect(within(grid).queryAllByText('—')).toHaveLength(0)
    expect(grid.closest('.olt-device-onus__table')?.getAttribute('style')).toContain('--olt-device-onus-columns: 10rem')
    expect(screen.queryByText('4194562')).toBeNull()
    expect(screen.queryByText('UNKNOWN')).toBeNull()
    expect(screen.queryByText(/Field yang tidak tersedia atau belum didukung/)).toBeNull()
  })

  it('keeps source and cache age visible without displaying partial-read alerts', async () => {
    serveSnapshot(oltOnusSnapshot({ warnings: ['Sebagian port gagal dibaca', 'Receive power belum didukung'] }))
    render(<OltDeviceOnus oltId="olt-a" />)
    await screen.findByText('HWTC00112233')
    expect(screen.getByText(/Sumber: OLT-A · HUAWEI/)).toBeDefined()
    expect(screen.getByText('Hanya baca')).toBeDefined()
    expect(screen.getByText('Test OLT firmware')).toBeDefined()
    expect(screen.getByText('2026-09-08T04:05:06Z').getAttribute('datetime')).toBe('2026-09-08T04:05:06Z')
    expect(screen.getByText(/time mengikuti jam OLT/)).toBeDefined()
    expect(screen.getByText('Cache 15 menit. Refresh membaca ulang dari OLT.')).toBeDefined()
    expect(screen.queryByText('Sebagian port gagal dibaca')).toBeNull()
    expect(screen.queryByText('Receive power belum didukung')).toBeNull()
    expect(screen.queryByRole('note')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('keeps partially populated columns and does not change them when search filters to a missing value', async () => {
    serveSnapshot(oltOnusSnapshot({ onus: [
      { ...deviceOnu, deviceType: null, lastDownTime: null, lastDownCause: null },
      unsupportedOnu,
    ] }))
    const user = userEvent.setup()
    render(<OltDeviceOnus oltId="olt-a" />)
    await screen.findByText('HWTC00112233')
    const expectedHeaders = headers.filter((header) => !['Device type', 'Last down time', 'Last down cause'].includes(header))
    const grid = screen.getByRole('grid')
    expect(within(grid).getAllByRole('columnheader').map((header) => header.textContent)).toEqual(expectedHeaders)
    expect(within(grid).getByText('-21.75 dBm')).toBeDefined()
    await user.type(screen.getByRole('searchbox'), 'zteg')
    expect(within(grid).getAllByRole('columnheader').map((header) => header.textContent)).toEqual(expectedHeaders)
    expect(within(grid).getByText('ZTEG44556677')).toBeDefined()
    expect(within(grid).queryByText('-21.75 dBm')).toBeNull()
    expect(within(grid).getAllByText('—')).toHaveLength(expectedHeaders.length - 1)
  })

  it('hides blank optional fields but includes zero power', async () => {
    serveSnapshot(oltOnusSnapshot({ onus: [{ ...unsupportedOnu, name: '  ', state: '', rxPowerDbm: 0 }] }))
    render(<OltDeviceOnus oltId="olt-a" />)
    await screen.findByText('0 dBm')
    expect(within(screen.getByRole('grid')).getAllByRole('columnheader').map((header) => header.textContent))
      .toEqual(['Serial number', 'Receive power'])
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
