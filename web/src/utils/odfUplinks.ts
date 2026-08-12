import type { OdfUplinkView } from '@/api/network'

/**
 * Cara menyebut "rak ini nyantol ke OLT mana" — dipakai bareng panel peta dan
 * daftar inventaris supaya jawabannya berbunyi sama di kedua tempat.
 *
 * Angka portnya ikut disebut karena itu yang membedakan uplink sungguhan dari
 * satu patchcord nyasar: rak yang 12 portnya ke OLT-A dan 1 port ke OLT-B bukan
 * "rak dua OLT", melainkan rak milik OLT-A yang kebetulan menumpangi satu port.
 */
export function uplinkLabel(uplink: OdfUplinkView): string {
  return `${uplink.oltCode} · ${uplink.portCount} port`
}

/**
 * Ringkasan sebaris untuk sel tabel, yang lebarnya tak cukup buat semuanya.
 * Yang disebut adalah OLT dengan port terbanyak — server sudah mengurutkannya
 * begitu — lalu sisanya cuma dihitung, sebab yang dicari orang saat menyapu
 * daftar rak adalah "punya siapa", bukan daftar lengkapnya.
 */
export function summarizeOdfUplinks(olts: OdfUplinkView[]): string {
  if (olts.length === 0) return '—'
  const [first, ...rest] = olts
  return rest.length > 0 ? `${first.oltCode} +${rest.length}` : first.oltCode
}
