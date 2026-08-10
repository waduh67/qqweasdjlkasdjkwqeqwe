import type { Coordinate } from '@/api/network'

/**
 * Kontrak "buka aset ini di peta" antar-halaman.
 *
 * Halaman lain (Inventory, detail pelanggan) menitipkan pesan ini lewat router state
 * saat pindah ke `/map`; halaman Peta membacanya sekali, terbang ke titiknya, membuka
 * panel infonya, lalu MEMBERSIHKAN pesannya.
 *
 * Koordinatnya ikut dititipkan, bukan dicari ulang di peta: daftar inventory dan detail
 * pelanggan sudah memegang titiknya, jadi peta bisa langsung bergerak sementara tarikan
 * isi panel menyusul.
 */

/** Lapisan yang bisa dituju. Namanya sengaja sama dengan nama layer MapLibre-nya. */
export type MapFocusLayer = 'site' | 'olt' | 'odc' | 'odp' | 'joint_box' | 'customer'

export interface MapFocus {
  layer: MapFocusLayer
  id: string
  lng: number
  lat: number
}

/**
 * Bentuk argumen kedua `navigate('/map', …)`. Diberi nama supaya komponen yang cuma
 * MENERUSKAN pesan sorot (tanpa menyusunnya sendiri) bisa mengetiknya tanpa menyalin
 * strukturnya.
 */
export interface MapFocusState {
  state: { focus: MapFocus }
}

/**
 * Argumen kedua `navigate('/map', …)` untuk menyorot satu aset. Dibungkus fungsi supaya
 * bentuk pesannya ditulis di SATU tempat — pemanggil cukup menyebut lapisan, id, dan
 * titiknya, bukan menyalin struktur state yang gampang meleset satu huruf.
 */
export function mapFocusState(layer: MapFocusLayer, id: string, at: Coordinate): MapFocusState {
  return { state: { focus: { layer, id, lng: at.longitude, lat: at.latitude } } }
}
