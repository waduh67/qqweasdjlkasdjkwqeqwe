import { useState } from 'react'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MapOptions } from 'maplibre-gl'
import { PREF_BASEMAP } from '@/map/basemaps'
import LocationPickerMap from './LocationPickerMap'

const mocks = vi.hoisted(() => ({
  construct: vi.fn<(options: MapOptions) => void>(), loseContext: vi.fn(),
  addControl: vi.fn(), on: vi.fn<(name: string, listener: (event: unknown) => void) => void>(),
  once: vi.fn<(name: string, listener: () => void) => void>(),
  off: vi.fn<(name: string, listener: () => void) => void>(),
  getLayer: vi.fn<(id: string) => { id: string } | undefined>(),
  setLayoutProperty: vi.fn(), resize: vi.fn(), remove: vi.fn(), flyTo: vi.fn(),
  markerConstruct: vi.fn(), markerSet: vi.fn(), markerRemove: vi.fn(),
  markerOn: vi.fn<(name: string, listener: () => void) => void>(),
  markerPosition: vi.fn(() => ({ lng: 107.62, lat: -6.92 })),
}))
vi.mock('maplibre-gl', () => ({
  default: {
    Map: class {
      constructor(options: MapOptions) { mocks.construct(options) }
      addControl = mocks.addControl
      on = mocks.on
      once = mocks.once
      off = mocks.off
      getLayer = mocks.getLayer
      setLayoutProperty = mocks.setLayoutProperty
      resize = mocks.resize
      remove = mocks.remove
      flyTo = mocks.flyTo
    },
    NavigationControl: class {},
    Marker: class {
      constructor() { mocks.markerConstruct() }
      setLngLat(coordinates: [number, number]) { mocks.markerSet(coordinates); return this }
      addTo() { return this }
      on = mocks.markerOn
      getLngLat = mocks.markerPosition
      remove = mocks.markerRemove
    },
  },
}))

beforeEach(() => {
  vi.clearAllMocks()
  mocks.construct.mockImplementation(() => { throw new Error('Failed to initialize WebGL') })
  mocks.getLayer.mockImplementation(id => ({ id }))
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
    getExtension: () => ({ loseContext: mocks.loseContext }),
  } as unknown as WebGLRenderingContext)
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

function CustomerForm({ onSave, initialCoordinates = ['', ''] }: {
  onSave: (value: string[]) => void
  initialCoordinates?: string[]
}) {
  const [coordinates, setCoordinates] = useState(initialCoordinates)
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

describe('Google location maps', () => {
  beforeEach(() => {
    mocks.construct.mockImplementation(() => {})
    vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  })

  it('switches and restores the shared preference without changing the draft, pin, camera or submitting', async () => {
    const user = userEvent.setup()
    const save = vi.fn()
    const form = render(<CustomerForm onSave={save} initialCoordinates={['107.61', '-6.91']} />)
    expect(screen.getByRole('button', { name: 'Google Maps' }).getAttribute('aria-pressed')).toBe('true')
    expect(mocks.construct.mock.calls[0][0]).toMatchObject({ center: [107.61, -6.91], zoom: 16 })
    await user.type(screen.getByLabelText('Alamat'), 'Draft alamat')
    await user.click(screen.getByRole('button', { name: 'Google Earth' }))
    await user.click(screen.getByRole('button', { name: 'Google Maps' }))
    await user.click(screen.getByRole('button', { name: 'Google Earth' }))
    expect(save).not.toHaveBeenCalled()
    expect(mocks.construct).toHaveBeenCalledOnce()
    expect(mocks.markerConstruct).toHaveBeenCalledOnce()
    expect(mocks.markerSet).toHaveBeenCalledExactlyOnceWith([107.61, -6.91])
    expect(mocks.flyTo).not.toHaveBeenCalled()
    expect(mocks.setLayoutProperty).toHaveBeenLastCalledWith('basemap-google-earth', 'visibility', 'visible')
    expect(localStorage.getItem(PREF_BASEMAP)).toBe('google-earth')
    await user.click(screen.getByRole('button', { name: 'Simpan' }))
    expect(save).toHaveBeenCalledExactlyOnceWith(['107.61', '-6.91', 'Draft alamat'])
    form.unmount()
    render(<CustomerForm onSave={save} />)
    expect(screen.getByRole('button', { name: 'Google Earth' }).getAttribute('aria-pressed')).toBe('true')
    expect(mocks.construct.mock.calls[1][0].style).toMatchObject({
      layers: [
        { id: 'basemap-google-maps', layout: { visibility: 'none' } },
        { id: 'basemap-google-earth', layout: { visibility: 'visible' } },
      ],
    })
  })

  it('retains map click and pin drag after switching', async () => {
    const user = userEvent.setup()
    const save = vi.fn()
    render(<CustomerForm onSave={save} />)
    await user.click(screen.getByRole('button', { name: 'Google Earth' }))
    const click = mocks.on.mock.calls.find(([name]) => name === 'click')?.[1]
    expect(click).toBeDefined()
    act(() => click?.({ lngLat: { lng: 107.61, lat: -6.91 } }))
    expect(mocks.markerSet).toHaveBeenLastCalledWith([107.61, -6.91])
    const drag = mocks.markerOn.mock.calls.find(([name]) => name === 'dragend')?.[1]
    expect(drag).toBeDefined()
    act(() => drag?.())
    await user.click(screen.getByRole('button', { name: 'Simpan' }))
    expect(save).toHaveBeenCalledWith(['107.620000', '-6.920000', ''])
  })

  it('only reports the active source failure and keeps another view selectable', async () => {
    const user = userEvent.setup()
    render(<CustomerForm onSave={vi.fn()} />)
    const error = mocks.on.mock.calls.find(([name]) => name === 'error')?.[1]
    expect(error).toBeDefined()
    act(() => error?.({ sourceId: 'basemap-google-earth' }))
    expect(screen.queryByRole('alert')).toBeNull()
    act(() => error?.({ sourceId: 'basemap-google-maps' }))
    expect(screen.getByRole('alert').textContent).toContain('Google Maps gagal dimuat')
    await user.click(screen.getByRole('button', { name: 'Google Earth' }))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByRole('button', { name: 'Google Earth' }).getAttribute('aria-pressed')).toBe('true')
    act(() => error?.({ sourceId: 'basemap-google-maps' }))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('applies the final selection when switching before the style loads', async () => {
    const user = userEvent.setup()
    const waiting = new Set<() => void>()
    mocks.getLayer.mockReturnValue(undefined)
    mocks.once.mockImplementation((_, listener) => { waiting.add(listener) })
    mocks.off.mockImplementation((_, listener) => { waiting.delete(listener) })
    const form = render(<CustomerForm onSave={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Google Earth' }))
    await user.click(screen.getByRole('button', { name: 'Google Maps' }))
    await user.click(screen.getByRole('button', { name: 'Google Earth' }))
    expect(waiting.size).toBe(1)
    expect(mocks.setLayoutProperty).not.toHaveBeenCalled()
    mocks.getLayer.mockImplementation(id => ({ id }))
    act(() => { for (const listener of waiting) listener() })
    expect(mocks.setLayoutProperty.mock.calls).toEqual([
      ['basemap-google-maps', 'visibility', 'none'],
      ['basemap-google-earth', 'visibility', 'visible'],
    ])
    form.unmount()
    expect(waiting.size).toBe(0)
    expect(mocks.remove).toHaveBeenCalledOnce()
  })
})
