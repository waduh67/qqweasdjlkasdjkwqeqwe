import type { RadiusEndpointView } from '@/api/bng'

/**
 * Alamat mana yang harus diketik di `/radius address=` sebuah BRAS.
 *
 * Bukan satu jawaban untuk semua: FreeRADIUS mengenali klien dari alamat ASAL paketnya,
 * jadi jalur yang dipakai router menentukan alamat tujuannya. Router ber-IP publik
 * menembak IP publik kita. Router yang masuk lewat overlay VPN harus menembak alamat
 * HUB di dalam overlay — kalau ia diarahkan ke IP publik, paketnya keluar lewat internet
 * biasa dan tiba dengan alamat asal IP publik lokasi pelanggan, yang tak terdaftar
 * sebagai klien. Permintaan dari klien tak dikenal tidak dibalas sama sekali, jadi yang
 * terlihat di router cuma "radius timeout" tanpa sebab yang bisa ditebak.
 *
 * Alamat BRAS yang sudah diketik operator sendirilah petunjuknya: kalau ia jatuh di
 * dalam blok tunnel, BRAS itu masuk lewat tunnel.
 */
export interface RadiusTarget {
  /** Alamat untuk `address=`; null bila platform belum menyetel host publik. */
  host: string | null
  /** Alamat itu datang dari blok overlay VPN, bukan dari host publik. */
  viaVpn: boolean
}

/** Ubah IPv4 bertitik jadi angka 32-bit; null bila bukan IPv4 yang sah. */
function ipToInt(ip: string): number | null {
  const parts = ip.trim().split('.')
  if (parts.length !== 4) return null
  let value = 0
  for (const part of parts) {
    if (!/^\d{1,3}$/.test(part)) return null
    const octet = Number(part)
    if (octet > 255) return null
    value = value * 256 + octet
  }
  return value
}

/** Apakah [ip] berada di dalam blok [cidr] (mis. `10.8.0.3` di `10.8.0.0/24`). */
export function ipInCidr(ip: string, cidr: string): boolean {
  const [network, prefixText] = cidr.trim().split('/')
  const prefix = Number(prefixText)
  if (!Number.isInteger(prefix) || prefix < 0 || prefix > 32) return false
  const networkValue = ipToInt(network ?? '')
  const ipValue = ipToInt(ip)
  if (networkValue == null || ipValue == null) return false
  // Pembagian, bukan geser bit: `>>>` di JS bekerja pada 32-bit BERTANDA, dan prefix 0
  // membuat `-1 << 32` kembali jadi -1 alih-alih 0.
  const size = 2 ** (32 - prefix)
  return Math.floor(networkValue / size) === Math.floor(ipValue / size)
}

/**
 * Alamat RADIUS yang benar untuk BRAS ber-alamat [brasAddress]. Alamat kosong (belum
 * diketik) → jawab dengan host publik: itu jalur yang paling lazim.
 */
export function radiusTargetFor(endpoint: RadiusEndpointView, brasAddress: string): RadiusTarget {
  const address = brasAddress.trim()
  if (address) {
    const tunnel = endpoint.vpnHosts?.find((it) => ipInCidr(address, it.tunnelCidr))
    if (tunnel) return { host: tunnel.host, viaVpn: true }
  }
  return { host: endpoint.host, viaVpn: false }
}

/**
 * Peringatan saat kolom alamat BRAS diisi alamat SERVER RADIUS kita sendiri.
 *
 * Kekeliruan yang paling gampang terjadi dan paling sulit dilihat akibatnya: layar
 * sebelah memajang "arahkan router ke sini: <IP>", angka itu tersangkut di kepala, lalu
 * ia diketik pula ke kolom alamat BRAS. Tersimpan tanpa protes — baris klien RADIUS
 * berisi alamat server itu sendiri — dan tiap permintaan dari router asli ditolak diam
 * sebagai klien tak dikenal.
 *
 * @returns kalimat peringatan, atau null bila alamatnya memang alamat router
 */
export function selfAddressWarning(endpoint: RadiusEndpointView | null, brasAddress: string): string | null {
  const address = brasAddress.trim()
  if (!address || !endpoint) return null
  const ours = [endpoint.host, ...(endpoint.vpnHosts ?? []).map((it) => it.host)].filter(Boolean)
  if (!ours.includes(address)) return null
  return (
    `${address} adalah alamat server RADIUS kami, bukan alamat router kamu. Kolom ini diisi alamat ` +
    'BRAS-nya sendiri — alamat asal yang terlihat oleh RADIUS saat router mengirim permintaan, dan ' +
    'sasaran balik untuk CoA/Disconnect. Diisi alamat kami, tiap permintaan router ditolak diam-diam ' +
    'sebagai klien tak dikenal dan di router cuma terlihat sebagai timeout.'
  )
}
