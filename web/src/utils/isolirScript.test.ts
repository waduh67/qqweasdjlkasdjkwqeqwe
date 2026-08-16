import { describe, expect, it } from 'vitest'
import { WALLED_GARDEN_PORT, defaultIsolirUrl, isolirScript, parseIsolirTarget } from './isolirScript'

describe('mengurai alamat halaman tagihan', () => {
  it('menerima alamat tanpa skema — operator mengetik alamat, bukan URL', () => {
    expect(parseIsolirTarget('portal.isp.id')).toMatchObject({ host: 'portal.isp.id', ip: null })
  })

  it('mengambil host dari URL berskema dan berjalur', () => {
    expect(parseIsolirTarget('https://portal.isp.id/portal/tagihan')).toMatchObject({
      host: 'portal.isp.id',
    })
  })

  // Melempar HTTP polos ke port 443 membuat server TLS menjawab "400 Client sent an HTTP
  // request to an HTTPS server" — pelanggan melihat halaman error, bukan tagihannya.
  it('tak pernah melempar ke port skema, sekalipun alamatnya https', () => {
    expect(parseIsolirTarget('https://portal.isp.id')?.natPort).toBe(WALLED_GARDEN_PORT)
    expect(parseIsolirTarget('portal.isp.id')?.natPort).toBe(WALLED_GARDEN_PORT)
  })

  it('mengenali IP sebagai sasaran dst-nat yang sah', () => {
    expect(parseIsolirTarget('http://20.6.72.13/portal')).toMatchObject({ host: '20.6.72.13', ip: '20.6.72.13' })
  })

  // dst-nat RouterOS hanya menerima alamat IP; nama host cuma boleh di address-list.
  it('tak menganggap nama host sebagai IP', () => {
    expect(parseIsolirTarget('portal.isp.id')?.ip).toBeNull()
  })

  // Port yang DIKETIK sendiri operator (portal di server sendiri) dihormati apa adanya.
  it('menghormati port tak baku', () => {
    expect(parseIsolirTarget('http://10.0.0.5:8080')).toMatchObject({ ip: '10.0.0.5', natPort: 8080 })
  })

  it('mengembalikan null untuk isian kosong atau tak terurai', () => {
    expect(parseIsolirTarget('')).toBeNull()
    expect(parseIsolirTarget('   ')).toBeNull()
    expect(parseIsolirTarget('http://')).toBeNull()
  })
})

describe('alamat bawaan', () => {
  it('menunjuk portal pelanggan pada deployment yang sedang dibuka', () => {
    expect(defaultIsolirUrl('https://noc.isp.id')).toBe('https://noc.isp.id/portal')
  })

  it('tak menggandakan garis miring', () => {
    expect(defaultIsolirUrl('https://noc.isp.id/')).toBe('https://noc.isp.id/portal')
  })
})

describe('aturan walled-garden RouterOS', () => {
  const target = parseIsolirTarget('http://20.6.72.13/portal')

  it('memakai nama address-list dari platform, bukan tebakan', () => {
    const script = isolirScript('nunggak', target)
    expect(script).toContain('src-address-list=nunggak')
    expect(script).toContain('list=nunggak-tujuan')
    expect(script).not.toContain('isolir-tujuan')
  })

  it('membuka DNS lebih dulu — tanpa itu tak ada koneksi untuk dilempar', () => {
    const script = isolirScript('isolir', target)
    expect(script).toContain('protocol=udp dst-port=53')
    expect(script).toContain('protocol=tcp dst-port=53')
  })

  it('melempar HTTP ke penjaga walled-garden, bukan ke port halaman tagihan', () => {
    expect(isolirScript('isolir', target)).toContain(
      `action=dst-nat to-addresses=20.6.72.13 to-ports=${WALLED_GARDEN_PORT}`,
    )
  })

  // Halaman tagihan di belakang CDN: address-list terisi IP hasil resolusi nama sedangkan
  // to-addresses menyebut IP origin. Tanpa baris ini, paket yang barusan dilempar router
  // dibunuh baris penutup skrip ini sendiri dan halaman tagihan tak pernah termuat.
  it('mengizinkan apa yang sudah dilempar router, sebelum baris-baris reject', () => {
    const lines = isolirScript('isolir', target).split('\n')
    const dstnat = lines.findIndex((l) => l.includes('connection-nat-state=dstnat'))
    const rejects = lines.findIndex((l) => l.includes('action=reject'))
    expect(dstnat).toBeGreaterThanOrEqual(0)
    expect(dstnat).toBeLessThan(rejects)
  })

  // Melempar 443 menghasilkan peringatan sertifikat: pelanggan mengira jaringannya dibajak,
  // bukan mengira dirinya menunggak.
  it('menolak HTTPS cepat alih-alih melemparnya', () => {
    const script = isolirScript('isolir', target)
    expect(script).toContain('dst-port=443 \\')
    expect(script).toContain('action=reject reject-with=tcp-reset')
    expect(script).not.toContain('dst-port=443 action=dst-nat')
  })

  // Drop membuat aplikasi menggantung sampai timeout; pelanggan menyimpulkan "internet mati".
  it('menutup sisanya dengan reject, bukan drop', () => {
    const script = isolirScript('isolir', target)
    expect(script).toContain('reject-with=icmp-network-unreachable')
    expect(script).not.toContain('action=drop')
  })

  it('memberi placeholder saat alamatnya nama host (dst-nat butuh IP)', () => {
    const script = isolirScript('isolir', parseIsolirTarget('portal.isp.id'))
    expect(script).toContain('address=portal.isp.id')
    expect(script).toContain('to-addresses=<IP-HALAMAN-TAGIHAN>')
  })

  it('memberi placeholder saat alamatnya belum diisi', () => {
    const script = isolirScript('isolir', null)
    expect(script).toContain('address=<ALAMAT-HALAMAN-TAGIHAN>')
    expect(script).toContain('to-addresses=<IP-HALAMAN-TAGIHAN>')
  })
})
