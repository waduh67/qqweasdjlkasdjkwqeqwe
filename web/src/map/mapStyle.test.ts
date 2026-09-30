import { afterEach, describe, expect, it } from 'vitest'
import { createMapStyle, PREF_BASEMAP, savedBasemap } from './mapStyle'

afterEach(() => localStorage.removeItem(PREF_BASEMAP))

describe('saved basemap', () => {
  it.each([
    ['default', 'default'],
    ['google-maps', 'google-maps'],
    ['google-earth', 'google-earth'],
    ['streets', 'default'],
    ['dark', 'default'],
    ['satellite', 'google-earth'],
    ['unrecognized', 'default'],
  ])('restores or migrates %s to %s', (saved, expected) => {
    localStorage.setItem(PREF_BASEMAP, saved)
    expect(savedBasemap()).toBe(expected)
  })

  it('opens Default on a new device', () => {
    expect(savedBasemap()).toBe('default')
  })

  it('starts with the migrated provider visible and network overlays above it', () => {
    localStorage.setItem(PREF_BASEMAP, 'satellite')
    const style = createMapStyle(savedBasemap())
    const visible = style.layers.filter((layer) =>
      layer.type === 'raster' && layer.layout?.visibility === 'visible',
    )
    expect(visible.map((layer) => layer.id)).toEqual(['basemap-google-earth'])
    expect(style.layers.findIndex((layer) => layer.id === 'cable')).toBeGreaterThan(
      style.layers.findIndex((layer) => layer.id === 'basemap-google-earth'),
    )
    expect(style.sources.ftth.type).toBe('vector')
  })
})
