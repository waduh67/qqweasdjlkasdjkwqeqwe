import type { Coordinate } from '@/api/network'

/** An empty pair is unlocated; a partial or invalid pair must never become (0, 0). */
export function optionalCustomerLocation(longitude: string, latitude: string): { location: Coordinate | null; error: string | null } {
  const lng = longitude.trim(), lat = latitude.trim()
  if (!lng && !lat) return { location: null, error: null }
  if (!lng || !lat) return { location: null, error: 'Isi longitude dan latitude, atau kosongkan keduanya.' }
  const location = { longitude: Number(lng), latitude: Number(lat) }
  if (!Number.isFinite(location.longitude) || !Number.isFinite(location.latitude)
    || Math.abs(location.longitude) > 180 || Math.abs(location.latitude) > 90
    || (location.longitude === 0 && location.latitude === 0)) {
    return { location: null, error: 'Tentukan koordinat lokasi yang valid.' }
  }
  return { location, error: null }
}
