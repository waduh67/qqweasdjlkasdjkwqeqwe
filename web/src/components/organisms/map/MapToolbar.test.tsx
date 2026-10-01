import { useState } from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import type { BasemapMode } from '@/map/mapStyle'
import { MapToolbar } from './MapToolbar'

afterEach(cleanup)

function Toolbar({ onLocate }: { onLocate: () => void }) {
  const [mode, setMode] = useState<BasemapMode>('google-maps')
  return <MapToolbar basemap={mode} onBasemap={setMode} onLocate={onLocate} basemapFailed={false} />
}

it('keeps exactly one selected view during quick changes and retains the location action', async () => {
  const user = userEvent.setup()
  const locate = vi.fn()
  render(<Toolbar onLocate={locate} />)
  const roads = screen.getByRole('button', { name: 'Google Maps' })
  const earth = screen.getByRole('button', { name: 'Google Earth' })
  expect(screen.queryByRole('button', { name: 'Default' })).toBeNull()
  await user.click(roads)
  await user.click(earth)
  await user.click(roads)
  expect(roads.getAttribute('aria-pressed')).toBe('true')
  expect(earth.getAttribute('aria-pressed')).toBe('false')
  await user.click(screen.getByRole('button', { name: 'Lokasi saya' }))
  expect(locate).toHaveBeenCalledOnce()
})

it('lets a keyboard user choose a view', async () => {
  const user = userEvent.setup()
  render(<Toolbar onLocate={vi.fn()} />)
  await user.tab()
  await user.tab()
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Google Earth' }))
  await user.keyboard('{Enter}')
  expect(screen.getByRole('button', { name: 'Google Earth' }).getAttribute('aria-pressed')).toBe('true')
})

it('keeps other views selectable when the current provider fails', async () => {
  const user = userEvent.setup()
  const choose = vi.fn()
  render(<MapToolbar basemap="google-earth" onBasemap={choose} onLocate={vi.fn()} basemapFailed />)
  expect(screen.getByRole('alert')).toBeTruthy()
  await user.click(screen.getByRole('button', { name: 'Google Maps' }))
  expect(choose).toHaveBeenCalledWith('google-maps')
})
