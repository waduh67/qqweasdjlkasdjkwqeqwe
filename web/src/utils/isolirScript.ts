/**
 * Aturan RouterOS yang mengubah "pelanggan terisolir" dari sekadar label di layar menjadi
 * halaman tagihan yang benar-benar muncul di browser pelanggan.
 *
 * Pembagian kerjanya: RADIUS hanya menempelkan IP sesi pelanggan ke sebuah address-list
 * (VSA `Mikrotik-Address-List`) dan menurunkan kecepatannya. Router yang memutuskan apa
 * artinya daftar itu. Tanpa aturan di bawah, address-list-nya terisi rapi tapi tak ada yang
 * membacanya — pelanggan "terisolir" tetap browsing seperti biasa, dan tak ada satu pun log
 * yang menunjukkan ada yang salah.
 */

/** Alamat halaman tagihan yang mesti dituju pelanggan terisolir. */
export interface IsolirTarget {
  /** Yang diketik operator / bawaan dari alamat konsol ini. */
  url: string
  /** Host murni tanpa skema & path — yang dipakai address-list router. */
  host: string
  /**
   * IP untuk `dst-nat to-addresses`. RouterOS hanya menerima alamat IP di sana (address-list
   * boleh nama host, dst-nat tidak), jadi nama host → null dan barisnya diberi placeholder.
   */
  ip: string | null
  /** Port halaman tagihan (dipakai sebagai `to-ports` saat melempar HTTP). */
  port: number
}

/** Berbentuk IPv4 dotted-quad. */
function isIpv4(host: string): boolean {
  const parts = host.split('.')
  return parts.length === 4 && parts.every((p) => /^\d{1,3}$/.test(p) && Number(p) <= 255)
}

/**
 * Urai alamat halaman tagihan yang diketik operator. Skema boleh tak ditulis (`portal.isp.id`
 * sama sahnya dengan `https://portal.isp.id`) — operator mengetik alamat, bukan URL.
 *
 * @returns null bila kosong / tak terurai; pemanggil menyodorkan placeholder.
 */
export function parseIsolirTarget(url: string): IsolirTarget | null {
  const trimmed = url.trim()
  if (!trimmed) return null
  const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`
  let parsed: URL
  try {
    parsed = new URL(withScheme)
  } catch {
    return null
  }
  const host = parsed.hostname
  if (!host) return null
  const port = parsed.port ? Number(parsed.port) : parsed.protocol === 'https:' ? 443 : 80
  return { url: trimmed, host, ip: isIpv4(host) ? host : null, port }
}

/**
 * Alamat halaman tagihan bawaan: portal pelanggan pada deployment yang SEDANG dibuka operator.
 * Konsol dan portal dilayani aplikasi yang sama, jadi asal-usul halaman ini adalah tebakan
 * yang hampir selalu benar — dan tetap boleh diganti kalau portalnya dipasang di domain lain.
 */
export function defaultIsolirUrl(origin: string): string {
  return `${origin.replace(/\/+$/, '')}/portal`
}

/**
 * Rakit aturan walled-garden RouterOS v7 untuk address-list [addressList].
 *
 * Empat keputusan yang sengaja diambil, semuanya karena perilaku nyata di lapangan:
 *
 * 1. DNS dibuka lebih dulu. Tanpa itu browser tak pernah sampai ke tahap membuka koneksi,
 *    jadi tak ada apa pun untuk dilempar dan pelanggan cuma melihat "server tak ditemukan".
 * 2. Halaman tagihan diizinkan lewat address-list terpisah, bukan lewat IP di aturan filter,
 *    supaya operator yang portalnya pindah cukup menyunting satu baris.
 * 3. HTTPS TIDAK dilempar, melainkan ditolak dengan tcp-reset. Melempar port 443 ke server
 *    lain menghasilkan peringatan sertifikat — pelanggan malah yakin jaringannya dibajak,
 *    bukan bahwa ia menunggak. Ditolak cepat, browser gagal seketika dan deteksi captive
 *    portal ponsel (yang memakai HTTP polos) memunculkan notifikasi "Masuk ke jaringan" yang
 *    membuka halaman tagihan sendiri.
 * 4. Sisanya di-reject, bukan drop: reject membuat aplikasi gagal seketika, sedangkan drop
 *    membuatnya menggantung sampai timeout — pelanggan menyimpulkan "internet mati" lalu
 *    menelepon CS, persis yang mau kita hindari.
 */
export function isolirScript(addressList: string, target: IsolirTarget | null): string {
  const list = addressList || 'isolir'
  const allowList = `${list}-tujuan`
  const host = target?.host ?? '<ALAMAT-HALAMAN-TAGIHAN>'
  const natTo = target?.ip ?? '<IP-HALAMAN-TAGIHAN>'
  const natPort = target?.port ?? 80
  return [
    `/ip firewall address-list add list=${allowList} address=${host} comment="halaman tagihan"`,
    `/ip firewall filter add chain=forward src-address-list=${list} protocol=udp dst-port=53 \\`,
    `    action=accept comment="isolir: DNS boleh"`,
    `/ip firewall filter add chain=forward src-address-list=${list} protocol=tcp dst-port=53 \\`,
    `    action=accept comment="isolir: DNS boleh"`,
    `/ip firewall filter add chain=forward src-address-list=${list} dst-address-list=${allowList} \\`,
    `    action=accept comment="isolir: halaman tagihan boleh"`,
    `/ip firewall nat add chain=dstnat src-address-list=${list} protocol=tcp dst-port=80 \\`,
    `    action=dst-nat to-addresses=${natTo} to-ports=${natPort} comment="isolir: http dilempar ke halaman tagihan"`,
    `/ip firewall filter add chain=forward src-address-list=${list} protocol=tcp dst-port=443 \\`,
    `    action=reject reject-with=tcp-reset comment="isolir: https ditolak cepat (tak bisa dilempar)"`,
    `/ip firewall filter add chain=forward src-address-list=${list} \\`,
    `    action=reject reject-with=icmp-network-unreachable comment="isolir: sisanya ditutup"`,
  ].join('\n')
}
