import { lazy, Suspense } from 'react'
import { Spinner } from './ui'

/**
 * Pemilih lokasi: peta klik-taruh-pin + cari alamat, menggantikan dua kotak
 * angka lintang/bujur yang bikin operator harus menyalin koordinat dari tempat
 * lain. Klik peta atau seret pin untuk menaruh titik; ketik alamat untuk
 * melompat ke sana. Kolom angka tetap ada untuk penyetelan presisi/tempel.
 *
 * Ini pembungkus ringan: implementasi peta (maplibre-gl + geocoder) dimuat malas
 * lewat [LocationPickerMap], jadi bundel peta tak ikut di muat awal halaman yang
 * cuma kebetulan mengimpor form berisi pemilih ini.
 */
export interface LocationPickerProps {
  longitude: string
  latitude: string
  onChange: (longitude: string, latitude: string) => void
  onAddress?: (address: string) => void
  height?: number
}

const LocationPickerMap = lazy(() => import('./LocationPickerMap'))

export function LocationPicker(props: LocationPickerProps) {
  return (
    <Suspense
      fallback={
        <div className="lp-map lp-loading" style={{ height: props.height ?? 280 }}>
          <Spinner />
        </div>
      }
    >
      <LocationPickerMap {...props} />
    </Suspense>
  )
}
