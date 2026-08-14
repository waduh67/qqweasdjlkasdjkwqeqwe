import { describe, expect, it } from 'vitest'
import type { RadiusEndpointView } from '@/api/bng'
import { ipInCidr, radiusTargetFor, selfAddressWarning } from './radiusTarget'

const ENDPOINT: RadiusEndpointView = {
  host: '20.6.72.13',
  authPort: 1812,
  acctPort: 1813,
  coaPort: 3799,
  configured: true,
  vpnHosts: [{ tunnelCidr: '10.8.0.0/24', host: '10.8.0.1' }],
}

describe('alamat di dalam blok tunnel', () => {
  it('mengenali alamat peer sebagai penghuni bloknya', () => {
    expect(ipInCidr('10.8.0.3', '10.8.0.0/24')).toBe(true)
    expect(ipInCidr('10.8.0.1', '10.8.0.0/24')).toBe(true)
    expect(ipInCidr('10.8.0.255', '10.8.0.0/24')).toBe(true)
  })

  it('menolak alamat sebelah yang cuma mirip', () => {
    expect(ipInCidr('10.8.1.3', '10.8.0.0/24')).toBe(false)
    expect(ipInCidr('110.8.0.3', '10.8.0.0/24')).toBe(false)
  })

  // Blok tunnel tak selalu /24; hub /16 dan /30 point-to-point sama sahnya.
  it('menghormati panjang prefix apa pun', () => {
    expect(ipInCidr('10.8.5.9', '10.8.0.0/16')).toBe(true)
    expect(ipInCidr('10.9.5.9', '10.8.0.0/16')).toBe(false)
    expect(ipInCidr('172.16.0.2', '172.16.0.0/30')).toBe(true)
    expect(ipInCidr('172.16.0.5', '172.16.0.0/30')).toBe(false)
    expect(ipInCidr('8.8.8.8', '0.0.0.0/0')).toBe(true)
  })

  it('diam saja pada isian yang belum berbentuk alamat', () => {
    expect(ipInCidr('', '10.8.0.0/24')).toBe(false)
    expect(ipInCidr('bukan-ip', '10.8.0.0/24')).toBe(false)
    expect(ipInCidr('10.8.0.300', '10.8.0.0/24')).toBe(false)
    expect(ipInCidr('10.8.0.3', '10.8.0.0')).toBe(false)
    expect(ipInCidr('10.8.0.3', '10.8.0.0/33')).toBe(false)
    // Nama host BRAS (bukan IP) tak bisa dicocokkan ke blok — jatuh ke host publik.
    expect(ipInCidr('bras.pelanggan.id', '10.8.0.0/24')).toBe(false)
  })
})

describe('alamat RADIUS yang disodorkan ke sebuah BRAS', () => {
  // Inti perkaranya: router ber-tunnel yang diarahkan ke IP publik keluar lewat
  // internet biasa, jadi paketnya tiba dari IP publik lokasi pelanggan dan diabaikan.
  it('memakai alamat hub untuk BRAS yang masuk lewat tunnel', () => {
    expect(radiusTargetFor(ENDPOINT, '10.8.0.3')).toEqual({ host: '10.8.0.1', viaVpn: true })
  })

  it('memakai host publik untuk BRAS ber-IP publik', () => {
    expect(radiusTargetFor(ENDPOINT, '103.10.20.30')).toEqual({ host: '20.6.72.13', viaVpn: false })
  })

  it('memakai host publik selama alamat BRAS belum diketik', () => {
    expect(radiusTargetFor(ENDPOINT, '  ')).toEqual({ host: '20.6.72.13', viaVpn: false })
  })

  it('tetap menjawab walau platform belum menyetel host publik', () => {
    const belum: RadiusEndpointView = { ...ENDPOINT, host: null, configured: false }
    expect(radiusTargetFor(belum, '103.10.20.30')).toEqual({ host: null, viaVpn: false })
    // VPN tetap punya jawaban pasti meski host publik belum diisi.
    expect(radiusTargetFor(belum, '10.8.0.3')).toEqual({ host: '10.8.0.1', viaVpn: true })
  })

  it('tak tersandung platform tanpa VPN sama sekali', () => {
    const tanpaVpn: RadiusEndpointView = { ...ENDPOINT, vpnHosts: [] }
    expect(radiusTargetFor(tanpaVpn, '10.8.0.3')).toEqual({ host: '20.6.72.13', viaVpn: false })
  })
})

describe('peringatan alamat server kami sendiri', () => {
  it('menegur saat kolom alamat BRAS diisi host publik RADIUS', () => {
    const pesan = selfAddressWarning(ENDPOINT, '20.6.72.13')
    expect(pesan).toContain('20.6.72.13')
    expect(pesan).toContain('bukan alamat router kamu')
    expect(pesan).toContain('timeout')
  })

  // Kekeliruan yang sama persis, cuma angkanya versi overlay.
  it('menegur juga saat diisi alamat hub VPN', () => {
    expect(selfAddressWarning(ENDPOINT, '10.8.0.1')).toContain('10.8.0.1')
  })

  it('diam pada alamat router yang benar', () => {
    expect(selfAddressWarning(ENDPOINT, '10.8.0.3')).toBeNull()
    expect(selfAddressWarning(ENDPOINT, '103.10.20.30')).toBeNull()
    expect(selfAddressWarning(ENDPOINT, '')).toBeNull()
    expect(selfAddressWarning(null, '20.6.72.13')).toBeNull()
  })
})
