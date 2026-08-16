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

/**
 * Port penjaga walled-garden pada deployment kita (lihat `deploy/Caddyfile`). Ini SATU-SATUNYA
 * pintu yang boleh jadi sasaran `dst-nat`, dan sengaja bukan port 80/443 halaman tagihan.
 *
 * Sebabnya permintaan yang dilempar router tiba membawa `Host` situs yang ASLI dibuka pelanggan
 * ("neverssl.com", "connectivitycheck.gstatic.com"), bukan alamat kita. Reverse-proxy kita di
 * port 80 hanya mengenali Host miliknya sendiri: permintaan berhost asing berakhir 404 atau
 * dilempar balik ke `https://neverssl.com` — pelanggan melihat error, bukan tagihannya. Port
 * 8880 tak peduli Host maupun path: apa pun yang masuk dijawab lemparan ke halaman tagihan.
 */
export const WALLED_GARDEN_PORT = 8880

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
  /**
   * `to-ports` untuk baris dst-nat — port yang menjawab HTTP POLOS di sisi sana.
   *
   * BUKAN port dari skema alamat halaman tagihan. Melempar permintaan HTTP polos ke port 443
   * membuatnya masuk ke telinga TLS: server menjawab `400 Client sent an HTTP request to an
   * HTTPS server` dan pelanggan melihat halaman error, bukan tagihannya. Karena itu alamat
   * `https://…` pun tetap dilempar ke [WALLED_GARDEN_PORT]; hanya port yang DIKETIK sendiri
   * oleh operator (mis. portal sendiri di `:8080`) yang dihormati apa adanya.
   */
  natPort: number
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
  // Port yang diketik operator dihormati; yang TIDAK diketik jatuh ke penjaga walled-garden,
  // bukan ke port skema — lihat [IsolirTarget.natPort].
  const natPort = parsed.port ? Number(parsed.port) : WALLED_GARDEN_PORT
  return { url: trimmed, host, ip: isIpv4(host) ? host : null, natPort }
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
 * Lima keputusan yang sengaja diambil, semuanya karena perilaku nyata di lapangan:
 *
 * 1. DNS dibuka lebih dulu. Tanpa itu browser tak pernah sampai ke tahap membuka koneksi,
 *    jadi tak ada apa pun untuk dilempar dan pelanggan cuma melihat "server tak ditemukan".
 * 2. Halaman tagihan diizinkan lewat address-list terpisah, bukan lewat IP di aturan filter,
 *    supaya operator yang portalnya pindah cukup menyunting satu baris.
 * 3. Apa pun yang SUDAH dilempar router sendiri ikut diizinkan (`connection-nat-state=dstnat`),
 *    dan barisnya wajib berada SEBELUM dua baris reject. Tanpa itu, walled-garden mati diam
 *    justru saat halaman tagihannya ada di belakang CDN: address-list terisi IP hasil resolusi
 *    nama (mis. milik Cloudflare) sedangkan `to-addresses` menyebut IP origin — dua himpunan
 *    yang berbeda, jadi paket yang barusan dilempar router tak cocok baris "halaman tagihan
 *    boleh" lalu dibunuh baris penutup buatan skrip ini sendiri. Yang terlihat operator cuma
 *    halaman yang tak pernah termuat, tanpa satu pun log yang menyalahkan siapa pun.
 * 4. HTTPS TIDAK dilempar, melainkan ditolak dengan tcp-reset. Melempar port 443 ke server
 *    lain menghasilkan peringatan sertifikat — pelanggan malah yakin jaringannya dibajak,
 *    bukan bahwa ia menunggak. Ditolak cepat, browser gagal seketika dan deteksi captive
 *    portal ponsel (yang memakai HTTP polos) memunculkan notifikasi "Masuk ke jaringan" yang
 *    membuka halaman tagihan sendiri.
 * 5. Sisanya di-reject, bukan drop: reject membuat aplikasi gagal seketika, sedangkan drop
 *    membuatnya menggantung sampai timeout — pelanggan menyimpulkan "internet mati" lalu
 *    menelepon CS, persis yang mau kita hindari.
 */
export function isolirScript(addressList: string, target: IsolirTarget | null): string {
  const list = addressList || 'isolir'
  const allowList = `${list}-tujuan`
  const host = target?.host ?? '<ALAMAT-HALAMAN-TAGIHAN>'
  const natTo = target?.ip ?? '<IP-HALAMAN-TAGIHAN>'
  const natPort = target?.natPort ?? WALLED_GARDEN_PORT
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
    `/ip firewall filter add chain=forward src-address-list=${list} connection-nat-state=dstnat \\`,
    `    action=accept comment="isolir: yang sudah dilempar router boleh lewat"`,
    `/ip firewall filter add chain=forward src-address-list=${list} protocol=tcp dst-port=443 \\`,
    `    action=reject reject-with=tcp-reset comment="isolir: https ditolak cepat (tak bisa dilempar)"`,
    `/ip firewall filter add chain=forward src-address-list=${list} \\`,
    `    action=reject reject-with=icmp-network-unreachable comment="isolir: sisanya ditutup"`,
  ].join('\n')
}
