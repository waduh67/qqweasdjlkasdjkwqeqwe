import { afterEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MaterialScanner } from './MaterialScanner'

const mediaDevices = navigator.mediaDevices
afterEach(() => { vi.unstubAllGlobals(); Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: mediaDevices }) })
it('keeps manual keyboard selection available when camera capability or permission fails and never submits the form', async () => {
  const selected = vi.fn(), submit = vi.fn(event => event.preventDefault())
  vi.stubGlobal('BarcodeDetector', undefined)
  render(<form onSubmit={submit}><MaterialScanner onScan={selected} /></form>)
  fireEvent.click(screen.getByRole('button', { name: 'Gunakan kamera' }))
  await screen.findByText(/Pemindaian kamera belum tersedia/)
  const field = screen.getByRole('textbox', { name: 'Serial perangkat' })
  fireEvent.change(field, { target: { value: 'ONU-01' } }); fireEvent.keyDown(field, { key: 'Enter' })
  expect(selected).toHaveBeenCalledWith('ONU-01'); expect(submit).not.toHaveBeenCalled()
  vi.stubGlobal('BarcodeDetector', class { detect() { return Promise.resolve([]) } })
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: vi.fn().mockRejectedValue(new Error('denied')) } })
  fireEvent.click(screen.getByRole('button', { name: 'Gunakan kamera' }))
  await screen.findByText(/Kamera tidak dapat dibuka/); expect(screen.getByRole('textbox', { name: 'Serial perangkat' })).not.toHaveProperty('disabled', true)
})
it('reads one camera code only on user action and releases the camera after selection and unmount', async () => {
  const selected = vi.fn(), stop = vi.fn(), detect = vi.fn().mockResolvedValue([{ rawValue: 'ONU-02' }])
  vi.stubGlobal('BarcodeDetector', class { detect = detect })
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [{ stop }] }) } })
  const view = render(<MaterialScanner onScan={selected} />)
  fireEvent.click(screen.getByRole('button', { name: 'Gunakan kamera' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Baca kode di kamera' }))
  await screen.findByRole('button', { name: 'Gunakan kamera' }); expect(selected).toHaveBeenCalledWith('ONU-02'); expect(stop).toHaveBeenCalledTimes(1)
  fireEvent.click(screen.getByRole('button', { name: 'Gunakan kamera' })); await screen.findByRole('button', { name: 'Tutup kamera' })
  view.unmount(); expect(stop).toHaveBeenCalledTimes(2)
})
