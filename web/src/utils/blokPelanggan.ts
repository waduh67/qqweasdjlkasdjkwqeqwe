/**
 * Sisi Mikrotik dari "blok di belakang peer": tunnel-nya sudah berdiri dan hub sudah tahu
 * blok pelanggan ada di balik router itu — tapi routernya sendiri belum tentu MENGIZINKAN
 * paketnya lewat. Hampir semua BRAS di lapangan menutup rapat chain `forward`, jadi tanpa dua
 * baris di bawah connection request TR-069 dari server berhenti diam-diam di firewall router:
 * tak ada log di ACS, tak ada log di ONT, dan perangkat tetap "Not Connect".
 *
 * Karena itu skripnya dirakit di sini alih-alih ditulis manual di dokumentasi — operator
 * tinggal salin-tempel, sama seperti perintah ovpn-client saat akun dibuat.
 */

/** Nama interface ovpn-client di RouterOS — sama dengan yang dipakai perintah pasang akun. */
export function ovpnInterfaceName(username: string): string {
  return `ovpn-${username}`
}

/**
 * Berbentuk `a.b.c.d/prefix` yang masuk akal. SENGAJA cuma pemeriksaan bentuk: aturan
 * sesungguhnya (blok terlalu lebar, loopback/multicast, beririsan dengan akun lain di hub yang
 * sama) hanya bisa dijawab server, dan menyalinnya ke sini cuma melahirkan dua kebenaran.
 * Gunanya sekadar menahan tombol Tambah dari kiriman yang jelas-jelas salah ketik.
 */
export function isCidrLike(value: string): boolean {
  const match = value.trim().match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\/(\d{1,2})$/)
  if (!match) return false
  const octets = match.slice(1, 5).map(Number)
  const prefix = Number(match[5])
  return octets.every((o) => o <= 255) && prefix >= 8 && prefix <= 32
}

/** Penanda aturan buatan kami di RouterOS — dipakai `remove`/`move` agar tempelan bisa diulang. */
const RULE_MARK = 'ftth-blok'

/**
 * Aturan firewall RouterOS yang membuka jalan dari hub ke blok pelanggan, dua arah:
 * dari tunnel ke pelanggan (permintaan), dan dari pelanggan kembali ke tunnel (balasan).
 *
 * Balasannya diizinkan eksplisit, tak menggantung pada aturan `established,related` yang
 * biasanya sudah ada — banyak konfigurasi lapangan menaruh aturan itu SESUDAH drop-nya,
 * atau memakai fasttrack yang melewatkan koneksi dari interface tunnel.
 *
 * Urutannya diatur lewat `move …destination=0` di akhir, BUKAN `place-before=0` di tiap
 * `add`: `place-before` menunjuk aturan yang harus sudah ada, jadi di router yang chain
 * `forward`-nya masih kosong perintahnya gagal dengan "no such item" — persis di router
 * yang paling mungkin dipakai orang saat pertama menyiapkan BRAS. `move` atas daftar
 * kosong tak melakukan apa-apa, jadi aman di kedua keadaan.
 *
 * `move`-nya dibungkus `:do … on-error={}` karena ia justru menolak ("destination item in
 * source list") ketika aturannya SUDAH di urutan teratas — hasil yang benar, tapi tampil
 * sebagai baris merah di akhir tempelan dan bikin operator mengira semuanya gagal.
 *
 * Diawali `remove` agar tempelan yang sama boleh dijalankan berulang: operator yang
 * menambah blok kedua tinggal menyalin ulang seluruhnya tanpa menumpuk aturan kembar.
 *
 * @returns string kosong bila belum ada blok terdaftar (tak ada yang perlu ditempel).
 */
export function blokFirewallScript(interfaceName: string, cidrs: string[]): string {
  if (cidrs.length === 0) return ''
  const lines = [`/ip firewall filter remove [find comment~"^${RULE_MARK}"]`]
  cidrs.forEach((cidr) => {
    lines.push(
      `/ip firewall filter add chain=forward action=accept in-interface=${interfaceName} \\`,
      `    dst-address=${cidr} comment="${RULE_MARK}: server boleh menghubungi ${cidr}"`,
      `/ip firewall filter add chain=forward action=accept out-interface=${interfaceName} \\`,
      `    src-address=${cidr} comment="${RULE_MARK}: balasan ${cidr} boleh kembali"`,
    )
  })
  lines.push(`:do { /ip firewall filter move [find comment~"^${RULE_MARK}"] destination=0 } on-error={}`)
  return lines.join('\n')
}
