import type { RasterLayerSpecification, SourceSpecification, StyleSpecification } from 'maplibre-gl'

export const INITIAL_CENTER: [number, number] = [106.995, -6.243]
export type BasemapMode = 'google-maps' | 'google-earth'

function googleTiles(layer: 'm' | 'y'): string[] {
  const style = layer === 'm'
    ? '&apistyle=' + encodeURIComponent('s.t:2|s.e:all|p.v:off,s.t:4|s.e:all|p.v:off')
    : ''
  return [0, 1, 2, 3].map((host) =>
    `https://mt${host}.google.com/vt?lyrs=${layer}&x={x}&y={y}&z={z}${style}`,
  )
}

export const BASEMAPS = {
  'google-maps': {
    label: 'Google Maps',
    tiles: googleTiles('m'),
    attribution: '&copy; <a href="https://maps.google.com">Google Maps</a>',
    maxzoom: 20,
  },
  'google-earth': {
    label: 'Google Earth',
    tiles: googleTiles('y'),
    attribution: '&copy; <a href="https://maps.google.com">Google Maps</a>',
    maxzoom: 20,
  },
} as const

export const BASEMAP_ORDER: readonly BasemapMode[] = ['google-maps', 'google-earth']
export const BASEMAP_HINTS = {
  'google-maps': 'Peta jalan Google Maps tanpa penanda tempat umum.',
  'google-earth': 'Citra satelit Google dengan label jalan; bukan aplikasi Google Earth 3D.',
} as const
export const PREF_BASEMAP = 'ftth.map.basemap'

export function basemapId(mode: BasemapMode): string {
  return `basemap-${mode}`
}

export function savedBasemap(): BasemapMode {
  const saved = localStorage.getItem(PREF_BASEMAP)
  switch (saved) {
    case 'google-maps':
    case 'google-earth':
      return saved
    case 'satellite':
      return 'google-earth'
    default:
      return 'google-maps'
  }
}

export function createBasemapStyle(mode: BasemapMode): StyleSpecification {
  const sources: Record<string, SourceSpecification> = {}
  const layers: RasterLayerSpecification[] = BASEMAP_ORDER.map((option) => {
    const preset = BASEMAPS[option]
    const id = basemapId(option)
    sources[id] = {
      type: 'raster', tiles: preset.tiles, tileSize: 256,
      maxzoom: preset.maxzoom, attribution: preset.attribution,
    }
    return {
      id, type: 'raster', source: id,
      layout: { visibility: option === mode ? 'visible' : 'none' },
      paint: { 'raster-opacity': 1, 'raster-fade-duration': 0 },
    }
  })
  return { version: 8, sources, layers }
}
