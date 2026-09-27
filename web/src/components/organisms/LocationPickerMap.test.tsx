import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import LocationPickerMap from './LocationPickerMap'

const mocks = vi.hoisted(() => ({ construct: vi.fn(), loseContext: vi.fn() }))
vi.mock('maplibre-gl', () => ({
  default: { Map: class { constructor() { mocks.construct(); throw new Error('Failed to initialize WebGL') } } },
}))

beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
    getExtension: () => ({ loseContext: mocks.loseContext }),
  } as unknown as WebGLRenderingContext)
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

function CustomerForm({ onSave }: { onSave: (value: string[]) => void }) {
  const [coordinates, setCoordinates] = useState(['', ''])
  const [address, setAddress] = useState('')
  return <form onSubmit={event => { event.preventDefault(); onSave([...coordinates, address]) }}>
    <label>Alamat<input value={address} onChange={event => setAddress(event.target.value)} /></label>
    <LocationPickerMap longitude={coordinates[0]} latitude={coordinates[1]}
      onChange={(lng, lat) => setCoordinates([lng, lat])} onAddress={setAddress} />
    <button type="submit">Simpan</button>
  </form>
}

it('keeps the parent form and manual coordinates usable when WebGL is unavailable', async () => {
  const user = userEvent.setup()
  const save = vi.fn()
  render(<CustomerForm onSave={save} />)
  expect(mocks.loseContext).toHaveBeenCalledOnce()
  expect(mocks.construct).toHaveBeenCalledOnce()
  expect(screen.getByRole('status').textContent).toContain('Peta tidak tersedia')
  await user.type(screen.getByLabelText('Alamat'), 'Bandung')
  await user.type(screen.getByLabelText('Longitude'), '107.61')
  await user.type(screen.getByLabelText('Latitude'), '-6.91')
  await user.click(screen.getByRole('button', { name: 'Simpan' }))
  expect(save).toHaveBeenCalledWith(['107.61', '-6.91', 'Bandung'])
})

it('does not construct a partial MapLibre instance when neither WebGL context is available', () => {
  vi.mocked(HTMLCanvasElement.prototype.getContext).mockReturnValue(null)
  render(<CustomerForm onSave={vi.fn()} />)
  expect(screen.getByRole('status').textContent).toContain('Peta tidak tersedia')
  expect(mocks.construct).not.toHaveBeenCalled()
  expect(mocks.loseContext).not.toHaveBeenCalled()
  expect(HTMLCanvasElement.prototype.getContext).toHaveBeenCalledTimes(2)
})

it('still fills coordinates and address from search when the map cannot initialize', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    json: async () => [{ lon: '107.61', lat: '-6.91', display_name: 'Bandung, Jawa Barat' }],
  }))
  const user = userEvent.setup()
  const save = vi.fn()
  render(<CustomerForm onSave={save} />)
  await user.type(screen.getByRole('searchbox'), 'Bandung')
  await user.click(await screen.findByRole('button', { name: 'Bandung, Jawa Barat' }))
  await user.click(screen.getByRole('button', { name: 'Simpan' }))
  expect(save).toHaveBeenCalledWith(['107.610000', '-6.910000', 'Bandung, Jawa Barat'])
})
