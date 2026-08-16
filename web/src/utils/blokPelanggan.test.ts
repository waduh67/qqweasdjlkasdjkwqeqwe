import { describe, expect, it } from 'vitest'
import { blokFirewallScript, isCidrLike, ovpnInterfaceName } from './blokPelanggan'

describe('isCidrLike', () => {
  it('menerima blok yang lazim dipakai kolam PPPoE', () => {
    expect(isCidrLike('10.20.0.0/16')).toBe(true)
    expect(isCidrLike('172.16.8.0/24')).toBe(true)
    expect(isCidrLike(' 10.20.30.40/32 ')).toBe(true)
  })

  it('menolak salah ketik yang paling sering terjadi', () => {
    expect(isCidrLike('10.20.0.0')).toBe(false) // lupa prefix
    expect(isCidrLike('10.20.0.0/')).toBe(false)
    expect(isCidrLike('10.300.0.0/16')).toBe(false)
    expect(isCidrLike('10.20.0.0/33')).toBe(false)
    expect(isCidrLike('')).toBe(false)
  })

  it('menolak prefix di bawah /8 — blok selebar itu pasti bukan kolam pelanggan', () => {
    expect(isCidrLike('10.0.0.0/4')).toBe(false)
  })
})

describe('blokFirewallScript', () => {
  it('membuka dua arah untuk tiap blok', () => {
    const script = blokFirewallScript(ovpnInterfaceName('bras-rumah'), ['10.20.0.0/16'])

    expect(script).toContain('in-interface=ovpn-bras-rumah dst-address=10.20.0.0/16')
    expect(script).toContain('out-interface=ovpn-bras-rumah src-address=10.20.0.0/16')
    expect(script.split('\n')).toHaveLength(4)
  })

  it('menaruh aturan di paling atas, bukan di ekor chain', () => {
    // Aturan drop bawaan konfigurasi ISP duduk di atas; tanpa place-before aturan ini tak terbaca.
    const script = blokFirewallScript('ovpn-x', ['10.20.0.0/16'])

    expect(script.match(/place-before=0/g)).toHaveLength(2)
  })

  it('merangkai semua blok dalam satu tempelan', () => {
    const script = blokFirewallScript('ovpn-x', ['10.20.0.0/16', '10.30.0.0/16'])

    expect(script.split('\n')).toHaveLength(8)
    expect(script).toContain('10.30.0.0/16')
  })

  it('tak menghasilkan apa-apa bila belum ada blok terdaftar', () => {
    expect(blokFirewallScript('ovpn-x', [])).toBe('')
  })
})
