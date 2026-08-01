import { useEffect, useRef, useState } from 'react'
import maplibregl, { type Map as MapLibreMap, type Marker } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import type { LocationPickerProps } from './LocationPicker'
import { IconClose, IconSearch } from './icons'
import { Spinner } from './ui'

/**
 * Isi berat pemilih lokasi: peta MapLibre + geocoder Nominatim. Dipisah dari
 * [LocationPicker] agar bisa dimuat malas — bundel maplibre-gl (~800 KB) hanya
 * diunduh saat form yang memakai pemilih benar-benar dibuka, bukan di muat awal
 * tiap halaman aplikasi.
 *
 * Komponen terkendali: pemanggil memegang `longitude`/`latitude` sebagai string
 * (mengikuti bentuk draft form yang ada) dan menerima perubahan lewat `onChange`.
 * `onAddress` opsional diisi saat sebuah hasil pencarian dipilih, agar kolom
 * alamat form ikut terisi.
 */

/** Basemap terang (Carto Voyager) — jalan & label alamat terbaca jelas saat menaruh pin. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const PICKER_STYLE: any = {
  version: 8,
  sources: {
    basemap: {
      type: 'raster',
      tiles: [
        'https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
        'https://b.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
        'https://c.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
      ],
      tileSize: 256,
      attribution: '&copy; Kontributor OpenStreetMap &copy; CARTO',
    },
  },
  layers: [{ id: 'basemap', type: 'raster', source: 'basemap' }],
}

/** Pusat awal bila belum ada koordinat: Bekasi — sekadar titik berangkat. */
const INITIAL_CENTER: [number, number] = [106.995, -6.243]

/** Bentuk hasil Nominatim yang kita pakai (subset). */
interface NominatimResult {
  lat: string
  lon: string
  display_name: string
}

/** Enam desimal ≈ presisi 0,1 m — cukup untuk titik rumah pelanggan. */
const fmt = (n: number): string => n.toFixed(6)

export default function LocationPickerMap({
  longitude,
  latitude,
  onChange,
  onAddress,
  height = 280,
}: LocationPickerProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<MapLibreMap | null>(null)
  const markerRef = useRef<Marker | null>(null)
  // Handler disimpan di ref agar penangan klik peta / seret pin selalu membaca
  // callback terbaru tanpa perlu membangun ulang peta atau pin tiap render.
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange
  const onAddressRef = useRef(onAddress)
  onAddressRef.current = onAddress

  const lng = Number(longitude)
  const lat = Number(latitude)
  const hasCoords =
    longitude.trim() !== '' && latitude.trim() !== '' && !Number.isNaN(lng) && !Number.isNaN(lat)

  const [query, setQuery] = useState('')
  const [results, setResults] = useState<NominatimResult[]>([])
  const [searching, setSearching] = useState(false)
  const [open, setOpen] = useState(false)

  // Inisialisasi peta sekali. Klik di mana pun menaruh/menggeser titik.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: PICKER_STYLE,
      center: hasCoords ? [lng, lat] : INITIAL_CENTER,
      zoom: hasCoords ? 16 : 12,
      attributionControl: { compact: true },
    })
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right')
    map.on('click', (e) => onChangeRef.current(fmt(e.lngLat.lng), fmt(e.lngLat.lat)))
    mapRef.current = map

    // Peta sering lahir di dalam modal yang berukuran nol lalu membesar — beri
    // tahu MapLibre agar kanvas mengisi ulang penuh begitu wadahnya punya ukuran.
    const ro = new ResizeObserver(() => map.resize())
    ro.observe(containerRef.current)

    return () => {
      ro.disconnect()
      map.remove()
      mapRef.current = null
      markerRef.current = null
    }
    // Sengaja hanya sekali: pusat/zoom awal dibaca dari koordinat saat mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Sinkronkan pin dengan koordinat terkendali. Pin muncul saat koordinat valid,
  // bisa diseret, dan hilang saat koordinat dikosongkan. Tidak me-recenter di
  // sini agar tak melawan pengguna saat menyeret; lompatan hanya dari pencarian.
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    if (!hasCoords) {
      markerRef.current?.remove()
      markerRef.current = null
      return
    }
    if (!markerRef.current) {
      const marker = new maplibregl.Marker({ draggable: true, color: '#5b8cff' })
        .setLngLat([lng, lat])
        .addTo(map)
      marker.on('dragend', () => {
        const p = marker.getLngLat()
        onChangeRef.current(fmt(p.lng), fmt(p.lat))
      })
      markerRef.current = marker
    } else {
      markerRef.current.setLngLat([lng, lat])
    }
  }, [hasCoords, lng, lat])

  // Geocode alamat lewat Nominatim (OSM), dibatasi ke Indonesia. Didebounce agar
  // tak membombardir layanan gratis; permintaan lama dibatalkan bila kata berganti.
  useEffect(() => {
    const term = query.trim()
    if (term.length < 3) {
      setResults([])
      setSearching(false)
      return
    }
    let cancelled = false
    setSearching(true)
    const timer = window.setTimeout(async () => {
      try {
        const url =
          'https://nominatim.openstreetmap.org/search?format=json&limit=5&countrycodes=id&q=' +
          encodeURIComponent(term)
        const res = await fetch(url, { headers: { 'Accept-Language': 'id' } })
        const data = (await res.json()) as NominatimResult[]
        if (!cancelled) {
          setResults(Array.isArray(data) ? data : [])
          setOpen(true)
        }
      } catch {
        if (!cancelled) setResults([])
      } finally {
        if (!cancelled) setSearching(false)
      }
    }, 450)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [query])

  const pick = (r: NominatimResult) => {
    const plng = Number(r.lon)
    const plat = Number(r.lat)
    onChangeRef.current(fmt(plng), fmt(plat))
    onAddressRef.current?.(r.display_name)
    mapRef.current?.flyTo({ center: [plng, plat], zoom: 16 })
    setOpen(false)
    setResults([])
    setQuery('')
  }

  return (
    <div className="location-picker">
      <div className="lp-search">
        <IconSearch size={16} />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onFocus={() => results.length > 0 && setOpen(true)}
          placeholder="Cari alamat lalu pilih untuk taruh pin…"
        />
        {searching && <Spinner />}
        {query && (
          <button type="button" className="ghost icon-btn" onClick={() => setQuery('')} aria-label="Bersihkan pencarian">
            <IconClose size={15} />
          </button>
        )}
        {open && results.length > 0 && (
          <ul className="lp-results">
            {results.map((r, i) => (
              <li key={`${r.lat},${r.lon},${i}`}>
                <button type="button" onClick={() => pick(r)}>
                  {r.display_name}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div ref={containerRef} className="lp-map" style={{ height }} />

      <div className="row lp-coords">
        <label style={{ flex: 1 }}>
          <span>Longitude</span>
          <input value={longitude} onChange={(e) => onChange(e.target.value, latitude)} placeholder="106.8" />
        </label>
        <label style={{ flex: 1 }}>
          <span>Latitude</span>
          <input value={latitude} onChange={(e) => onChange(longitude, e.target.value)} placeholder="-6.2" />
        </label>
      </div>

      <p className="muted lp-hint">Klik peta atau seret pin untuk menyetel titik — atau cari alamat di atas.</p>
    </div>
  )
}
