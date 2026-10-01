import { afterEach, describe, expect, it } from 'vitest'
import { BASEMAPS, createMapStyle, PREF_BASEMAP, savedBasemap } from './mapStyle'
import { createBasemapStyle } from './basemaps'

afterEach(() => localStorage.removeItem(PREF_BASEMAP))

it.each(['google-maps', 'google-earth'] as const)('uses the same %s tiles in location forms without loading network data or an API key', mode => {
  const picker = createBasemapStyle(mode)
  const network = createMapStyle(mode)
  expect(Object.keys(picker.sources).sort()).toEqual(['basemap-google-earth', 'basemap-google-maps'])
  expect(picker.layers.every(layer => layer.type === 'raster')).toBe(true)
  expect(picker.layers).toEqual(network.layers.filter(layer => layer.type === 'raster'))
  for (const [id, source] of Object.entries(picker.sources)) {
    expect(source).toEqual(network.sources[id])
    expect(source.type).toBe('raster')
    if (source.type !== 'raster') throw new Error('Unexpected non-raster source')
    for (const tile of source.tiles ?? []) {
      const url = new URL(tile)
      expect(url.protocol).toBe('https:')
      expect(url.hostname).toMatch(/^mt[0-3]\.google\.com$/)
      expect(url.searchParams.has('key')).toBe(false)
    }
  }
})

describe('saved basemap', () => {
  it.each([
    ['default', 'google-maps'],
    ['google-maps', 'google-maps'],
    ['google-earth', 'google-earth'],
    ['streets', 'google-maps'],
    ['dark', 'google-maps'],
    ['satellite', 'google-earth'],
    ['unrecognized', 'google-maps'],
  ])('restores or migrates %s to %s', (saved, expected) => {
    localStorage.setItem(PREF_BASEMAP, saved)
    expect(savedBasemap()).toBe(expected)
  })

  it('opens Google Maps on a new device without the retired basemap', () => {
    expect(savedBasemap()).toBe('google-maps')
    const style = createMapStyle(savedBasemap())
    expect(style.layers.filter(layer => layer.type === 'raster' && layer.layout?.visibility === 'visible').map(layer => layer.id)).toEqual(['basemap-google-maps'])
    expect(style.sources).not.toHaveProperty('basemap-default')
  })

  it('hides road points of interest and transit markers without changing satellite imagery', () => {
    for (const tile of BASEMAPS['google-maps'].tiles) {
      const url = new URL(tile)
      expect(url.searchParams.get('lyrs')).toBe('m')
      expect(url.searchParams.get('apistyle')).toBe('s.t:2|s.e:all|p.v:off,s.t:4|s.e:all|p.v:off')
    }
    for (const tile of BASEMAPS['google-earth'].tiles) {
      const url = new URL(tile)
      expect(url.searchParams.get('lyrs')).toBe('y')
      expect(url.searchParams.has('apistyle')).toBe(false)
    }
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
