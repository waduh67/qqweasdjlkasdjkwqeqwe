import type { MountingType } from '@/api/network'

/**
 * Kosakata "data lapangan" sebuah kotak — dipakai bareng form taruh-perangkat di
 * peta dan panel sunting, supaya kata yang dibaca operator sama persis di
 * keduanya.
 */

/**
 * Daftar dudukan beserta akibat praktisnya. Keterangannya bukan hiasan: yang
 * dipilih di sini menentukan alat yang dibawa tim SEBELUM berangkat, dan salah
 * tebak berarti mereka pulang tanpa hasil sementara pelanggan menunggu sehari
 * lagi.
 */
export const MOUNTING_OPTIONS: { value: MountingType; label: string }[] = [
  { value: 'POLE', label: 'Tiang — perlu tangga/bucket truck' },
  { value: 'WALL', label: 'Dinding — dibaut ke bangunan' },
  { value: 'AERIAL', label: 'Gantung di kabel (aerial)' },
  { value: 'PEDESTAL', label: 'Pedestal — berdiri di tanah' },
  { value: 'UNDERGROUND', label: 'Bawah tanah — handhole, perlu kunci' },
  { value: 'INDOOR', label: 'Dalam ruangan' },
]

/** Nama pendek dudukan untuk panel baca; tanpa embel-embel alat. */
const SHORT_LABEL: Record<MountingType, string> = {
  POLE: 'Tiang',
  WALL: 'Dinding',
  AERIAL: 'Gantung di kabel',
  PEDESTAL: 'Pedestal',
  UNDERGROUND: 'Bawah tanah (handhole)',
  INDOOR: 'Dalam ruangan',
}

export function mountingLabel(value: MountingType | null): string {
  return value ? SHORT_LABEL[value] : 'belum dicatat'
}

/** Tanggal hari ini dalam zona waktu MESIN operator, bukan UTC — kotak dipasang di sini, bukan di Greenwich. */
export function todayIso(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des']

/**
 * "17 Mar 2021 · 5 thn" — tanggalnya untuk klaim garansi, umurnya untuk menebak
 * tersangka. Umur yang ditulis di sebelahnya justru bagian yang dipakai: yang
 * ditanya saat satu klaster pelan-pelan meredup bukan "tanggal berapa" melainkan
 * "kotak ini sudah setua apa".
 *
 * Tanggal di masa depan (salah ketik tahun) tetap ditampilkan apa adanya tanpa
 * umur — mengarang "0 bln" untuk kotak yang katanya dipasang tahun depan cuma
 * menyembunyikan salah ketiknya.
 */
export function describeInstalledOn(iso: string | null, now: Date = new Date()): string {
  if (!iso) return 'belum dicatat'
  const [y, m, d] = iso.split('-').map(Number)
  if (!y || !m || !d) return iso
  const date = `${d} ${MONTHS[m - 1]} ${y}`
  const months = (now.getFullYear() - y) * 12 + (now.getMonth() + 1 - m) - (now.getDate() < d ? 1 : 0)
  if (months < 0) return date
  if (months < 1) return `${date} · baru`
  if (months < 12) return `${date} · ${months} bln`
  return `${date} · ${Math.floor(months / 12)} thn`
}
